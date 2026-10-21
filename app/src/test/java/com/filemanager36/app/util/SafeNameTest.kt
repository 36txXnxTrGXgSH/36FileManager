package com.filemanager36.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SafeName` est la porte par laquelle passent tous les noms venus de l'extérieur :
 * navigateur du PC, autre téléphone, serveur SMB, fournisseur cloud. Une faille ici
 * fait écrire hors du dossier de destination, donc n'importe où sur le stockage.
 *
 * [SafeName.relative] est arrivée avec l'envoi d'un dossier entier depuis le navigateur
 * (2.4.0) : c'est le seul endroit du projet où un **chemin** — et non un simple nom —
 * vient du réseau.
 */
class SafeNameTest {

    // ---------- of() : un seul segment ----------

    @Test
    fun `retire le chemin et ne garde que le nom`() {
        assertEquals("img.jpg", SafeName.of("../../etc/img.jpg"))
        assertEquals("img.jpg", SafeName.of("C:\\WINDOWS\\img.jpg"))
    }

    @Test
    fun `refuse ce qui ne laisse rien d'exploitable`() {
        assertEquals("", SafeName.of(".."))
        assertEquals("", SafeName.of("."))
        assertEquals("", SafeName.of("   "))
        assertEquals("", SafeName.of("\u0001\u0002"))
    }

    @Test
    fun `tronque sur les octets sans couper un caractere en deux`() {
        val cut = SafeName.of("é".repeat(300))
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 200)
        // Aucun caractère de remplacement : la coupe est tombée sur une frontière.
        assertTrue(cut.none { it == '\uFFFD' })
    }

    // ---------- relative() : un chemin entier ----------

    @Test
    fun `garde les segments d un chemin normal`() {
        assertEquals(listOf("photos", "2026", "img.jpg"), SafeName.relative("photos/2026/img.jpg"))
    }

    @Test
    fun `accepte les antislashs de Windows`() {
        assertEquals(listOf("photos", "img.jpg"), SafeName.relative("photos\\img.jpg"))
    }

    @Test
    fun `ignore les segments vides et les points seuls`() {
        assertEquals(listOf("a", "b.txt"), SafeName.relative("./a//./b.txt"))
        assertEquals(listOf("a", "b.txt"), SafeName.relative("/a/b.txt"))
    }

    /**
     * Le point de tout l'exercice : une remontée fait échouer le chemin entier. La
     * retirer en douce fabriquerait un chemin que personne n'a demandé — c'est ainsi
     * que `a/../../b` devient `a/b` sur un assainisseur, puis `../b` sur le suivant.
     */
    @Test
    fun `refuse toute remontee`() {
        assertTrue(SafeName.relative("../secret").isEmpty())
        assertTrue(SafeName.relative("a/../../b").isEmpty())
        assertTrue(SafeName.relative("a\\..\\b").isEmpty())
        assertTrue(SafeName.relative("photos/../../../data/data/com.x/fichier").isEmpty())
    }

    @Test
    fun `refuse un chemin vide ou trop profond`() {
        assertTrue(SafeName.relative("").isEmpty())
        assertTrue(SafeName.relative("///").isEmpty())
        assertTrue(SafeName.relative((1..25).joinToString("/") { "d$it" }).isEmpty())
        assertEquals(24, SafeName.relative((1..24).joinToString("/") { "d$it" }).size)
    }

    @Test
    fun `refuse un segment reduit a rien par l assainissement`() {
        // Un segment fait uniquement de caractères de contrôle ne laisse rien.
        assertTrue(SafeName.relative("a/\u0001/b.txt").isEmpty())
    }
}
