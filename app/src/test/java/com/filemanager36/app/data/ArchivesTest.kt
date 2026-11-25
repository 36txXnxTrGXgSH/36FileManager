package com.filemanager36.app.data

import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * L'extraction d'archives traite des fichiers venus d'ailleurs : ces tests vérifient
 * chaque format, mais surtout que rien ne sort jamais du dossier d'extraction et que
 * rien de partiel ne reste derrière une annulation.
 */
class ArchivesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val texte = "Bonjour, ceci est un fichier de test.\n".repeat(50).toByteArray()
    private val binaire = ByteArray(300_000) { (it * 31 + 7).toByte() }

    /** Espace libre fictif, pour ne dépendre ni du disque de la machine ni de sa marge. */
    private val beaucoup: (File) -> Long = { 1L shl 40 }

    private fun out() = tmp.newFolder()

    private fun resource(name: String): File {
        val url = javaClass.classLoader!!.getResource("archives/$name") ?: error("ressource absente : $name")
        return File(url.toURI())
    }

    // ---------- 7z ----------

    private fun make7z(file: File) {
        SevenZOutputFile(file).use { z ->
            val dir = z.createArchiveEntry(tmp.newFolder("dir7z"), "Été 2025")
            z.putArchiveEntry(dir); z.closeArchiveEntry()
            val a = File(tmp.root, "a.txt").apply { writeBytes(texte) }
            z.putArchiveEntry(z.createArchiveEntry(a, "Été 2025/lisez-moi.txt")); z.write(texte); z.closeArchiveEntry()
            val b = File(tmp.root, "b.bin").apply { writeBytes(binaire) }
            z.putArchiveEntry(z.createArchiveEntry(b, "Été 2025/photos/données.bin")); z.write(binaire); z.closeArchiveEntry()
        }
    }

    @Test
    fun `7z - inventaire puis extraction fidele`() {
        val archive = File(tmp.root, "vacances.7z").also { make7z(it) }
        val summary = Archives.summarize(archive)
        assertEquals(ArchiveFormat.SEVEN_Z, summary.format)
        assertEquals(2, summary.files)
        assertEquals((texte.size + binaire.size).toLong(), summary.totalBytes)
        assertFalse(summary.needsPassword)

        val parent = out()
        val result = Archives.extract(archive, parent, freeSpace = beaucoup)
        assertEquals(File(parent, "vacances"), result.output)
        assertEquals(2, result.files)
        assertArrayEquals(texte, File(result.output, "Été 2025/lisez-moi.txt").readBytes())
        assertArrayEquals(binaire, File(result.output, "Été 2025/photos/données.bin").readBytes())
    }

    /** Contenu reconnaissable et propre à chaque fichier : un mélange se verrait. */
    private fun contenu(i: Int, size: Int) = ByteArray(size) { ((it * 7 + i * 13) xor (it ushr 9)).toByte() }

    @Test
    fun `7z multi-blocs - decode en parallele, fidele octet pour octet`() {
        // SevenZOutputFile range chaque fichier dans son propre bloc : de quoi faire
        // tourner plusieurs décodeurs, et la file d'écriture (petits et gros fichiers).
        val archive = File(tmp.root, "blocs.7z")
        val attendus = LinkedHashMap<String, ByteArray>()
        SevenZOutputFile(archive).use { z ->
            val dir = z.createArchiveEntry(tmp.newFolder("vide7z"), "dossier vide")
            z.putArchiveEntry(dir); z.closeArchiveEntry()
            val source = File(tmp.root, "src.bin").apply { writeBytes(ByteArray(1)) }
            for (i in 0 until 60) {
                val size = when {
                    i == 5 -> 0                       // fichier vide
                    i % 20 == 0 -> 2 * 1024 * 1024 + i // plus gros que le seuil d'écriture en file
                    else -> 1_000 + i * 997
                }
                val name = "lot ${i % 4}/sous ${i % 3}/fichier-$i.bin"
                val data = contenu(i, size)
                attendus[name] = data
                z.putArchiveEntry(z.createArchiveEntry(source, name).apply { this.size = size.toLong() })
                if (size > 0) z.write(data)
                z.closeArchiveEntry()
            }
        }
        val parent = out()
        val result = Archives.extract(archive, parent, freeSpace = beaucoup)
        assertTrue("le chemin parallèle doit être utilisé", Archives.lastSevenZParallel)
        assertEquals(attendus.size, result.files)
        for ((name, data) in attendus) assertArrayEquals(name, data, File(result.output, name).readBytes())
        assertTrue(File(result.output, "dossier vide").isDirectory)
        assertEquals(attendus.values.sumOf { it.size }.toLong(), result.bytes)
    }

    @Test
    fun `7z - jamais deux blocs de petits fichiers a la fois tant qu'il reste des gros`() {
        fun bloc(index: Int, tailleMoyenne: Long) = SevenZPlan.Block(
            index, 0, 0, emptyList(),
            List(10) { SevenZArchiveEntryAvecTaille(tailleMoyenne) }, 0, null
        )
        val petits = (0..3).map { bloc(it, 40_000) }
        val gros = (4..7).map { bloc(it, 20L shl 20) }
        val s = Archives.BlockScheduler(petits + gros)
        // Trois décodeurs démarrent : un seul prend un bloc de petits fichiers.
        val d1 = s.next(null)!!; val d2 = s.next(null)!!; val d3 = s.next(null)!!
        assertEquals(listOf(0, 4, 5), listOf(d1.index, d2.index, d3.index))
        // Un décodeur de gros fichiers finit : il reprend un gros bloc, pas un petit.
        assertEquals(6, s.next(d2)!!.index)
        // Le bloc de petits fichiers finit : le suivant de petits fichiers prend sa place.
        assertEquals(1, s.next(d1)!!.index)
        // Plus de gros blocs : les décodeurs libres finissent les petits.
        assertEquals(7, s.next(d3)!!.index)
        val restants = listOfNotNull(s.next(null), s.next(null), s.next(null)).map { it.index }
        assertEquals(listOf(2, 3), restants)
    }

    private fun SevenZArchiveEntryAvecTaille(taille: Long) =
        org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry().apply { size = taille }

    @Test
    fun `7z - une donnee alteree est detectee par la somme de controle`() {
        // Sans compression, le contenu se retrouve tel quel dans l'archive : on peut en
        // modifier un octet et vérifier que l'extraction le remarque.
        val archive = File(tmp.root, "altere.7z")
        val marque = "MARQUEUR-UNIQUE-0123456789".toByteArray()
        val data = marque + ByteArray(5_000) { (it % 251).toByte() }
        SevenZOutputFile(archive).use { z ->
            z.setContentCompression(org.apache.commons.compress.archivers.sevenz.SevenZMethod.COPY)
            val src = File(tmp.root, "x.bin").apply { writeBytes(data) }
            z.putArchiveEntry(z.createArchiveEntry(src, "sain.bin")); z.write(texte); z.closeArchiveEntry()
            z.putArchiveEntry(z.createArchiveEntry(src, "abime.bin")); z.write(data); z.closeArchiveEntry()
        }
        val bytes = archive.readBytes()
        val at = (0..bytes.size - marque.size).first { i -> marque.indices.all { bytes[i + it] == marque[it] } }
        bytes[at + marque.size + 100] = (bytes[at + marque.size + 100] + 1).toByte()
        archive.writeBytes(bytes)

        val parent = out()
        try {
            Archives.extract(archive, parent, freeSpace = beaucoup)
            fail("une archive altérée ne doit pas s'extraire sans erreur")
        } catch (e: java.io.IOException) {
            assertTrue(Archives.describe(e), Archives.describe(e).contains("endommag", ignoreCase = true))
        }
        assertTrue(Archives.lastSevenZParallel)
        // Le fichier abîmé n'est jamais laissé derrière, même partiellement.
        assertFalse(File(parent, "altere/abime.bin").exists())
    }

    @Test
    fun `zip de nombreux petits fichiers - tous ecrits, aucun melange`() {
        val archive = File(tmp.root, "beaucoup.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            for (i in 0 until 400) {
                zip.putNextEntry(ZipEntry("d${i % 9}/f$i.dat"))
                zip.write(contenu(i, 50 + i * 3))
                zip.closeEntry()
            }
        }
        val result = Archives.extract(archive, out(), freeSpace = beaucoup)
        assertEquals(400, result.files)
        for (i in 0 until 400) assertArrayEquals(contenu(i, 50 + i * 3), File(result.output, "d${i % 9}/f$i.dat").readBytes())
    }

    @Test
    fun `7z decoupe en volumes, en partant du premier comme d'un autre`() {
        val whole = File(tmp.root, "src.7z").also { make7z(it) }.readBytes()
        val dir = tmp.newFolder("volumes")
        val cut = whole.size / 3
        listOf(0 until cut, cut until 2 * cut, 2 * cut until whole.size).forEachIndexed { i, r ->
            File(dir, "jeu.7z.00${i + 1}").writeBytes(whole.copyOfRange(r.first, r.last + 1))
        }
        for (start in listOf("jeu.7z.001", "jeu.7z.002")) {
            val summary = Archives.summarize(File(dir, start))
            assertEquals(3, summary.volumes)
            val result = Archives.extract(File(dir, start), out(), freeSpace = beaucoup)
            assertEquals("jeu", result.output.name)
            assertArrayEquals(binaire, File(result.output, "Été 2025/photos/données.bin").readBytes())
        }
    }

    @Test
    fun `un zip qui est en fait un 7z est reconnu par son contenu`() {
        val archive = File(tmp.root, "mal-nomme.zip").also { make7z(it) }
        assertEquals(ArchiveFormat.SEVEN_Z, Archives.detect(archive))
        val result = Archives.extract(archive, out(), freeSpace = beaucoup)
        assertEquals(2, result.files)
    }

    /**
     * Échantillon officiel de Commons Compress, chiffré en AES-256 (mot de passe « foo »).
     * La table des matières y est en clair : c'est le cas où la protection passait
     * inaperçue, la table de getEntries() ne disant rien des méthodes de chiffrement.
     */
    @Test
    fun `7z chiffre AES - le mot de passe est demande, verifie, puis utilise`() {
        val archive = resource("bla.encrypted.7z")
        assertTrue(Archives.summarize(archive).needsPassword)
        try { Archives.extract(archive, out(), freeSpace = beaucoup); fail() } catch (e: ArchivePasswordRequired) {}
        assertFalse(Archives.checkPassword(archive, "mauvais".toCharArray()))
        try {
            Archives.extract(archive, out(), "mauvais".toCharArray(), freeSpace = beaucoup); fail()
        } catch (e: ArchiveWrongPassword) {}
        assertTrue(Archives.checkPassword(archive, "foo".toCharArray()))
        val result = Archives.extract(archive, out(), "foo".toCharArray(), freeSpace = beaucoup)
        assertTrue(result.files > 0)
    }

    // ---------- ZIP ----------

    @Test
    fun `zip windows - noms en page de code 850, pas en UTF-8`() {
        val archive = File(tmp.root, "windows.zip")
        ZipOutputStream(FileOutputStream(archive), Charset.forName("IBM850")).use { z ->
            z.putNextEntry(ZipEntry("Réunion/Été.txt")); z.write(texte); z.closeEntry()
        }
        val result = Archives.extract(archive, out(), freeSpace = beaucoup)
        assertArrayEquals(texte, File(result.output, "Réunion/Été.txt").readBytes())
    }

    private fun encryptedZip(file: File, password: String) {
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            fileNameInZip = "secret/rapport.txt"
        }
        net.lingala.zip4j.ZipFile(file, password.toCharArray()).use { it.addStream(texte.inputStream(), params) }
    }

    @Test
    fun `zip AES - mot de passe demande, refuse s'il est faux, accepte s'il est juste`() {
        val archive = File(tmp.root, "protege.zip").also { encryptedZip(it, "secret36") }
        assertTrue(Archives.summarize(archive).needsPassword)

        try { Archives.extract(archive, out(), freeSpace = beaucoup); fail() } catch (e: ArchivePasswordRequired) {}
        assertFalse(Archives.checkPassword(archive, "mauvais".toCharArray()))
        try {
            Archives.extract(archive, out(), "mauvais".toCharArray(), freeSpace = beaucoup); fail()
        } catch (e: ArchiveWrongPassword) {}

        assertTrue(Archives.checkPassword(archive, "secret36".toCharArray()))
        val result = Archives.extract(archive, out(), "secret36".toCharArray(), freeSpace = beaucoup)
        assertArrayEquals(texte, File(result.output, "secret/rapport.txt").readBytes())
    }

    /** L'attaque « Zip Slip » : une entrée nommée « ../../… » écrirait hors du dossier. */
    @Test
    fun `zip slip - une remontee fait refuser l'archive entiere et ne laisse rien`() {
        val archive = File(tmp.root, "piege.zip")
        ZipOutputStream(FileOutputStream(archive)).use { z ->
            z.putNextEntry(ZipEntry("innocent.txt")); z.write(texte); z.closeEntry()
            z.putNextEntry(ZipEntry("../../evade.txt")); z.write(texte); z.closeEntry()
        }
        val parent = out()
        try { Archives.extract(archive, parent, freeSpace = beaucoup); fail() } catch (e: SecurityException) {}
        assertFalse(File(parent, "piege").exists())
        assertFalse(File(parent.parentFile, "evade.txt").exists())
        assertFalse(File(tmp.root.parentFile, "evade.txt").exists())
    }

    @Test
    fun `chemins absolus et lettres de lecteur ramenes dans le dossier`() {
        assertEquals("etc/passwd", Archives.safeRelativePath("/etc/passwd"))
        assertEquals("Windows/x.dll", Archives.safeRelativePath("C:\\Windows\\x.dll"))
        assertNull(Archives.safeRelativePath("./"))
        try { Archives.safeRelativePath("a/../../b"); fail() } catch (e: SecurityException) {}
    }

    // ---------- TAR et compressions ----------

    private fun makeTar(out: OutputStream, withLink: Boolean = true) {
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val e = TarArchiveEntry("dossier/fichier.bin").apply { size = binaire.size.toLong() }
            tar.putArchiveEntry(e); tar.write(binaire); tar.closeArchiveEntry()
            if (withLink) {
                // Un lien vers la racine : le suivre ferait écrire n'importe où.
                val link = TarArchiveEntry("dossier/lien", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/sdcard" }
                tar.putArchiveEntry(link); tar.closeArchiveEntry()
            }
        }
    }

    @Test
    fun `tar gz, tar bz2, tar xz - extraits, liens symboliques ignores`() {
        val variants = mapOf(
            "a.tar.gz" to { o: OutputStream -> GzipCompressorOutputStream(o) },
            "b.tbz2" to { o: OutputStream -> BZip2CompressorOutputStream(o) },
            "c.tar.xz" to { o: OutputStream -> XZCompressorOutputStream(o) },
            "d.tar" to { o: OutputStream -> o }
        )
        for ((name, wrap) in variants) {
            val archive = File(tmp.root, name)
            FileOutputStream(archive).use { makeTar(wrap(it)) }
            val result = Archives.extract(archive, out(), freeSpace = beaucoup)
            assertEquals(name, 1, result.files)
            assertEquals(name, 1, result.skipped)
            assertArrayEquals(name, binaire, File(result.output, "dossier/fichier.bin").readBytes())
            assertFalse(name, File(result.output, "dossier/lien").exists())
        }
    }

    @Test
    fun `gz isole - un fichier, pas de dossier`() {
        val archive = File(tmp.root, "journal.txt.gz")
        GzipCompressorOutputStream(FileOutputStream(archive)).use { it.write(texte) }
        val parent = out()
        val result = Archives.extract(archive, parent, freeSpace = beaucoup)
        assertEquals(File(parent, "journal.txt"), result.output)
        assertArrayEquals(texte, result.output.readBytes())
    }

    @Test
    fun `xz - la taille decompressee est connue sans decompresser`() {
        val archive = File(tmp.root, "donnees.bin.xz")
        XZCompressorOutputStream(FileOutputStream(archive)).use { it.write(binaire) }
        assertEquals(binaire.size.toLong(), Archives.summarize(archive).totalBytes)
    }

    // ---------- RAR ----------

    @Test
    fun `rar 5 - extraction, accents et sous-dossiers`() {
        val result = Archives.extract(resource("normal.rar"), out(), freeSpace = beaucoup)
        assertEquals("Bonjour depuis un RAR\n", File(result.output, "Été 2025/lisez-moi.txt").readText())
        assertEquals(256 * 400L, File(result.output, "Été 2025/photos/donnees.bin").length())
    }

    @Test
    fun `rar 5 - mot de passe`() {
        val archive = resource("mot-de-passe.rar")
        assertTrue(Archives.summarize(archive).needsPassword)
        try { Archives.extract(archive, out(), freeSpace = beaucoup); fail() } catch (e: ArchivePasswordRequired) {}
        assertFalse(Archives.checkPassword(archive, "mauvais".toCharArray()))
        assertTrue(Archives.checkPassword(archive, "secret36".toCharArray()))
        val result = Archives.extract(archive, out(), "secret36".toCharArray(), freeSpace = beaucoup)
        assertEquals("Bonjour depuis un RAR\n", File(result.output, "Été 2025/lisez-moi.txt").readText())
    }

    @Test
    fun `rar 5 - en-tetes chiffres, la liste elle-meme est secrete`() {
        val archive = resource("entetes-chiffres.rar")
        val summary = Archives.summarize(archive)
        assertTrue(summary.needsPassword)
        assertNull(summary.files)
        assertFalse(Archives.checkPassword(archive, "mauvais".toCharArray()))
        val result = Archives.extract(archive, out(), "secret36".toCharArray(), freeSpace = beaucoup)
        assertEquals(2, result.files)
    }

    // ---------- Espace, annulation ----------

    @Test
    fun `espace insuffisant - refus avant d'ecrire quoi que ce soit`() {
        val archive = File(tmp.root, "gros.7z").also { make7z(it) }
        val parent = out()
        try {
            Archives.extract(archive, parent, freeSpace = { 1000L })
            fail()
        } catch (e: ArchiveNoSpace) {}
        assertEquals(0, parent.listFiles()!!.size)
    }

    @Test
    fun `annulation - la sortie partielle est effacee`() {
        val archive = File(tmp.root, "long.tar.gz")
        FileOutputStream(archive).use { makeTar(GzipCompressorOutputStream(it), withLink = false) }
        val parent = out()
        var calls = 0
        try {
            Archives.extract(archive, parent, freeSpace = beaucoup, isCancelled = { calls++ > 2 })
            fail()
        } catch (e: ArchiveCancelled) {}
        assertEquals(0, parent.listFiles()!!.size)
    }

    @Test
    fun `noms de sortie`() {
        assertEquals("photos", Archives.outputName(File("photos.tar.gz")))
        assertEquals("jeu", Archives.outputName(File("jeu.7z.003")))
        assertEquals("film", Archives.outputName(File("film.part2.rar")))
        assertEquals("journal.txt", Archives.outputName(File("journal.txt.gz")))
        assertTrue(Archives.isArchiveName("Comique.cbz"))
        assertFalse(Archives.isArchiveName("photo.jpg"))
    }
}
