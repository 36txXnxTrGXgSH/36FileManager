package com.filemanager36.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class TreeDeleterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Arborescence de [depth] niveaux, [width] sous-dossiers et [files] fichiers par dossier. */
    private fun tree(root: File, depth: Int, width: Int, files: Int): Int {
        var count = 0
        root.mkdirs()
        repeat(files) { File(root, "f$it.txt").writeText("contenu $it"); count++ }
        if (depth > 0) repeat(width) {
            val d = File(root, "d$it")
            count += 1 + tree(d, depth - 1, width, files)
        }
        return count
    }

    @Test
    fun `une arborescence entiere disparait, progression exacte`() {
        val root = File(tmp.root, "extrait")
        val inside = tree(root, depth = 4, width = 3, files = 5)
        val lastDone = AtomicInteger()
        val lastTotal = AtomicInteger()
        val out = TreeDeleter.delete(listOf(root)) { _, done, total ->
            if (total > 0) { lastDone.set(done); lastTotal.set(total) }
        }
        assertFalse(root.exists())
        assertEquals(inside + 1, out.total)
        assertEquals(out.total, out.deleted)
        assertTrue(out.remaining.isEmpty())
        assertFalse(out.cancelled)
        assertEquals(out.total, lastDone.get())
        assertEquals(out.total, lastTotal.get())
    }

    @Test
    fun `plusieurs racines, fichiers et dossiers melanges`() {
        val a = File(tmp.root, "a").also { tree(it, 2, 2, 3) }
        val b = File(tmp.root, "b.txt").apply { writeText("b") }
        val garde = File(tmp.root, "garde.txt").apply { writeText("reste") }
        val out = TreeDeleter.delete(listOf(a, b))
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertTrue(garde.exists())
        assertTrue(out.remaining.isEmpty())
    }

    @Test
    fun `annulation - s'arrete et le dit`() {
        val root = File(tmp.root, "gros").also { tree(it, 3, 3, 4) }
        val out = TreeDeleter.delete(listOf(root), isCancelled = { true })
        assertTrue(out.cancelled)
        assertTrue(root.exists())
        assertEquals(listOf(root), out.remaining)
    }

    @Test
    fun `un lien vers un dossier est efface, jamais sa cible`() {
        val cible = File(tmp.root, "precieux").also { tree(it, 1, 2, 3) }
        val avant = cible.walkTopDown().count()
        val root = File(tmp.root, "a-supprimer").apply { mkdirs() }
        val lien = File(root, "lien")
        val ok = runCatching { Files.createSymbolicLink(lien.toPath(), cible.toPath()) }.isSuccess
        assumeTrue("liens symboliques indisponibles sur cette machine", ok)

        assertFalse(TreeDeleter.isRealDirectory(lien))
        val out = TreeDeleter.delete(listOf(root))
        assertFalse(root.exists())
        assertTrue(out.remaining.isEmpty())
        assertTrue(cible.isDirectory)
        assertEquals(avant, cible.walkTopDown().count())
    }

    @Test
    fun `deleteRecursive - fichier seul et dossier`() {
        val f = File(tmp.root, "seul.txt").apply { writeText("x") }
        assertTrue(FileOperations.deleteRecursive(f))
        assertFalse(f.exists())
        val d = File(tmp.root, "dossier").also { tree(it, 2, 2, 2) }
        assertTrue(FileOperations.deleteRecursive(d))
        assertFalse(d.exists())
    }
}
