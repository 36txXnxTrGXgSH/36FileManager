package com.filemanager36.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Le moteur de recherche sert l'application **et** la page web : ces tests fixent ce
 * que « trouver » veut dire pour les deux.
 */
class FileSearchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, content: String = "x"): File {
        val f = File(tmp.root, path)
        f.parentFile!!.mkdirs()
        f.writeText(content)
        return f
    }

    private fun run(
        query: String,
        includeHidden: Boolean = false,
        max: Int = 1000,
        roots: List<File> = listOf(tmp.root)
    ): Pair<List<String>, FileSearch.Outcome> {
        val names = mutableListOf<String>()
        val outcome = FileSearch.search(roots, query, includeHidden, max) { names += it.name }
        return names to outcome
    }

    // ---------- Normalisation et correspondance ----------

    @Test
    fun `ignore la casse et les accents`() {
        assertEquals("ete 2025", FileSearch.normalize("Été 2025"))
        assertEquals("francais", FileSearch.normalize("FRANÇAIS"))
        assertTrue(FileSearch.matches("Vacances d'été.jpg", FileSearch.terms("ETE")))
    }

    @Test
    fun `tous les mots dans n importe quel ordre`() {
        val terms = FileSearch.terms("  facture   2025 ")
        assertEquals(listOf("facture", "2025"), terms)
        assertTrue(FileSearch.matches("2025_Facture_EDF.pdf", terms))
        assertFalse(FileSearch.matches("Facture_2024.pdf", terms))
    }

    @Test
    fun `une saisie vide ne trouve rien et ne parcourt rien`() {
        file("a.txt")
        val (names, outcome) = run("   ")
        assertTrue(names.isEmpty())
        assertEquals(0, outcome.scanned)
    }

    // ---------- Parcours ----------

    @Test
    fun `trouve les fichiers et les dossiers en profondeur`() {
        file("Documents/Impots/Facture 2025.pdf")
        file("Photos/facture-scan.jpg")
        File(tmp.root, "Factures").mkdirs()
        val (names, _) = run("facture")
        assertEquals(setOf("Facture 2025.pdf", "facture-scan.jpg", "Factures"), names.toSet())
    }

    @Test
    fun `les resultats proches de la racine arrivent en premier`() {
        file("a/b/c/d/rapport-profond.txt")
        file("rapport-racine.txt")
        file("a/rapport-moyen.txt")
        val (names, _) = run("rapport")
        assertEquals(listOf("rapport-racine.txt", "rapport-moyen.txt", "rapport-profond.txt"), names)
    }

    @Test
    fun `les dossiers caches sont ignores sauf demande`() {
        file(".thumbnails/photo-cachee.jpg")
        file("photo-visible.jpg")
        assertEquals(listOf("photo-visible.jpg"), run("photo").first)
        assertEquals(
            setOf("photo-visible.jpg", "photo-cachee.jpg"),
            run("photo", includeHidden = true).first.toSet()
        )
    }

    @Test
    fun `le plafond de resultats arrete la recherche et le signale`() {
        repeat(10) { file("lot/image$it.jpg") }
        val (names, outcome) = run("image", max = 3)
        assertEquals(3, names.size)
        assertTrue(outcome.truncated)
        assertFalse(outcome.stopped)
    }

    @Test
    fun `une interruption exterieure arrete la recherche`() {
        repeat(5) { file("d$it/cible.txt") }
        val found = mutableListOf<File>()
        val outcome = FileSearch.search(listOf(tmp.root), "cible", false, 1000, shouldStop = { true }) {
            found += it
        }
        assertTrue(outcome.stopped)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `plusieurs racines, sans doublon quand elles se repetent`() {
        file("interne/musique.mp3")
        file("carte/musique-sd.mp3")
        val interne = File(tmp.root, "interne")
        val carte = File(tmp.root, "carte")
        val (names, _) = run("musique", roots = listOf(interne, carte, interne))
        assertEquals(listOf("musique.mp3", "musique-sd.mp3"), names)
    }

    @Test
    fun `un dossier illisible ou absent n interrompt pas la recherche`() {
        file("present/trouve-moi.txt")
        val absent = File(tmp.root, "n-existe-pas")
        val (names, _) = run("trouve", roots = listOf(absent, File(tmp.root, "present")))
        assertEquals(listOf("trouve-moi.txt"), names)
    }
}
