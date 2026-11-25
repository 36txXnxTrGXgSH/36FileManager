package com.filemanager36.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class TreeCopierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Arborescence de test : fichiers de tailles variées, un fichier vide, un dossier vide. */
    private fun sample(root: File, files: Int = 40): File {
        val rnd = Random(36)
        root.mkdirs()
        repeat(files) { i ->
            val dir = File(root, "d${i % 4}/s${i % 3}").apply { mkdirs() }
            val size = when (i % 5) { 0 -> 0; 1 -> 700_000; else -> rnd.nextInt(1, 9_000) }
            File(dir, "f$i.bin").writeBytes(rnd.nextBytes(size))
        }
        File(root, "vide").mkdirs()
        File(root, "racine.txt").writeText("à la racine")
        return root
    }

    private fun relativeFiles(root: File): Map<String, File> =
        root.walkTopDown().filter { it != root }.associateBy { it.relativeTo(root).path }

    private fun assertSameTree(expected: File, actual: File) {
        val a = relativeFiles(expected)
        val b = relativeFiles(actual)
        assertEquals(a.keys, b.keys)
        for ((rel, f) in a) {
            val g = b.getValue(rel)
            assertEquals(rel, f.isDirectory, g.isDirectory)
            if (f.isFile) assertArrayEquals(rel, f.readBytes(), g.readBytes())
        }
    }

    @Test
    fun `copie fidele, progression complete, original intact`() {
        val src = sample(File(tmp.root, "source"))
        val reference = File(tmp.root, "reference").also { src.copyRecursively(it) }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        val last = AtomicReference<TreeCopier.Progress?>()
        val out = TreeCopier.run(listOf(src), dest, move = false) { if (it.phase == TreeCopier.Phase.COPY) last.set(it) }

        assertEquals(1, out.succeeded)
        assertTrue(out.failed.isEmpty())
        assertFalse(out.cancelled)
        assertSameTree(reference, File(dest, "source"))
        assertSameTree(reference, src)
        val p = last.get()!!
        assertEquals(p.bytesTotal, p.bytesDone)
        assertEquals(p.filesTotal, p.filesDone)
        assertEquals(41L, p.filesTotal)
        assertEquals(out.filesTotal, out.filesCopied)
        // La date des fichiers suit la copie.
        val f = File(src, "racine.txt")
        assertEquals(f.lastModified() / 1000, File(dest, "source/racine.txt").lastModified() / 1000)
    }

    @Test
    fun `deplacement entre volumes - copie puis effacement de l'original`() {
        val src = sample(File(tmp.root, "source"))
        val reference = File(tmp.root, "reference").also { src.copyRecursively(it) }
        val dest = File(tmp.root, "carte").apply { mkdirs() }
        val out = TreeCopier.run(listOf(src), dest, move = true, allowRename = false)
        assertEquals(1, out.succeeded)
        assertTrue(out.failed.isEmpty())
        assertFalse(src.exists())
        assertSameTree(reference, File(dest, "source"))
    }

    @Test
    fun `deplacement sur le meme volume - un simple renommage`() {
        val src = sample(File(tmp.root, "source"), files = 5)
        val dest = File(tmp.root, "ailleurs").apply { mkdirs() }
        val out = TreeCopier.run(listOf(src), dest, move = true)
        assertEquals(1, out.succeeded)
        assertFalse(src.exists())
        assertTrue(File(dest, "source/racine.txt").isFile)
        assertEquals(0L, out.filesTotal)
    }

    @Test
    fun `rien n'est jamais ecrase - nom (1)`() {
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        File(dest, "photo.jpg").writeText("ancienne")
        val src = File(tmp.root, "photo.jpg").apply { writeText("nouvelle") }
        val out = TreeCopier.run(listOf(src), dest, move = false)
        assertEquals(1, out.succeeded)
        assertEquals("ancienne", File(dest, "photo.jpg").readText())
        assertEquals("nouvelle", File(dest, "photo (1).jpg").readText())
    }

    @Test
    fun `un dossier ne peut pas entrer dans lui-meme`() {
        val src = sample(File(tmp.root, "source"), files = 3)
        val inside = File(src, "d0").apply { mkdirs() }
        val out = TreeCopier.run(listOf(src), inside, move = true, allowRename = false)
        assertEquals(0, out.succeeded)
        assertEquals(1, out.failed.size)
        assertFalse(File(inside, "source").exists())
        assertTrue(File(src, "racine.txt").isFile)
    }

    @Test
    fun `deplacer dans son propre dossier ne fait rien`() {
        val f = File(tmp.root, "a.txt").apply { writeText("a") }
        val out = TreeCopier.run(listOf(f), tmp.root, move = true)
        assertEquals(1, out.succeeded)
        assertTrue(f.isFile)
        assertFalse(File(tmp.root, "a (1).txt").exists())
    }

    @Test
    fun `deplacement annule - copies retirees, originaux intacts`() {
        val src = sample(File(tmp.root, "source"))
        val reference = File(tmp.root, "reference").also { src.copyRecursively(it) }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        val target = File(dest, "source")
        // Annulé dès que quelques fichiers sont arrivés.
        val cancel = { target.exists() && target.walkTopDown().count { it.isFile } >= 5 }
        val out = TreeCopier.run(listOf(src), dest, move = true, cancelRequest = cancel, allowRename = false)
        assertTrue("annule=${out.cancelled} ${out.filesCopied}/${out.filesTotal} ${out.failed}", out.cancelled)
        assertFalse(out.cancelledDuringCleanup)
        assertFalse(target.exists())
        assertSameTree(reference, src)
    }

    @Test
    fun `copie annulee - les fichiers termines restent, aucun fichier tronque`() {
        val src = sample(File(tmp.root, "source"))
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        val target = File(dest, "source")
        val cancel = { target.exists() && target.walkTopDown().count { it.isFile } >= 8 }
        val out = TreeCopier.run(listOf(src), dest, move = false, cancelRequest = cancel)
        assertTrue("annule=${out.cancelled} ${out.filesCopied}/${out.filesTotal} ${out.failed}", out.cancelled)
        assertTrue(out.filesCopied < out.filesTotal)
        for ((rel, f) in relativeFiles(target)) {
            if (f.isFile) assertArrayEquals(rel, File(src, rel).readBytes(), f.readBytes())
        }
        assertTrue(File(src, "racine.txt").isFile)
    }

    @Test
    fun `plusieurs elements, dont un absent`() {
        val a = sample(File(tmp.root, "a"), files = 6)
        val b = File(tmp.root, "b.txt").apply { writeText("b") }
        val absent = File(tmp.root, "absent.txt")
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        val out = TreeCopier.run(listOf(a, b, absent), dest, move = true, allowRename = false)
        assertEquals(2, out.succeeded)
        assertEquals(listOf("absent.txt"), out.failed)
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertEquals("b", File(dest, "b.txt").readText())
        assertTrue(File(dest, "a/vide").isDirectory)
    }

    @Test
    fun `informations - fichiers, dossiers et octets comptes en parallele`() {
        val src = sample(File(tmp.root, "source"))
        val expectedBytes = src.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val expectedFolders = src.walkTopDown().count { it.isDirectory && it != src }.toLong()
        val t = TreeScanner.measure(listOf(src))
        assertEquals(41L, t.files)
        assertEquals(expectedFolders, t.folders)
        assertEquals(expectedBytes, t.bytes)
        assertEquals(0, t.unreadable)
        assertFalse(t.cancelled)

        // Sélection multiple : un dossier et un fichier.
        val extra = File(tmp.root, "seul.txt").apply { writeText("12345") }
        val both = TreeScanner.measure(listOf(src, extra))
        assertEquals(42L, both.files)
        assertEquals(expectedBytes + 5, both.bytes)
    }

    @Test
    fun `informations - un lien vers un dossier n'est pas suivi`() {
        val target = sample(File(tmp.root, "cible"), files = 10)
        val root = File(tmp.root, "racine").apply { mkdirs() }
        File(root, "a.txt").writeText("a")
        val link = File(root, "lien")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue("liens symboliques indisponibles ici", made)
        val t = TreeScanner.measure(listOf(root))
        assertEquals(2L, t.files) // a.txt et le lien lui-même
        assertEquals(0L, t.folders)
    }
}
