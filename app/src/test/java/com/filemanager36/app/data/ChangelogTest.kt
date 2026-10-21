package com.filemanager36.app.data

import com.filemanager36.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce qui s'annonce au démarrage d'une version fraîchement installée.
 *
 * ## Le test qui compte vraiment est le dernier
 * `la version en cours a son entree` échoue dès qu'on incrémente `versionCode` sans écrire les
 * nouveautés correspondantes. Sans lui, l'oubli ne casserait rien de visible : l'application se
 * contenterait de n'afficher aucune fenêtre, et personne ne s'en apercevrait avant que
 * l'utilisateur ne signale que la mise à jour « n'annonce plus rien ».
 */
class ChangelogTest {

    // Les exemples utilisent les vrais numéros de l'historique (2.5.0 = 20, 2.4.0 = 19…) :
    // repris de 36Note avec ses propres numéros, ces tests échouaient sans défaut de logique,
    // `aAnnoncer` ne pouvant présenter que des versions qui existent dans l'historique.

    // ---------------------------------------------------------------- quand se taire

    @Test
    fun `rien n'est annonce a la premiere installation`() {
        // Tout est nouveau pour qui découvre l'application : lui parler de « nouveautés »
        // n'aurait aucun sens.
        assertTrue(
            Changelog.aAnnoncer(
                versionActuelle = 20,
                derniereVue = 0,
                premiereInstallation = true
            ).isEmpty()
        )
    }

    @Test
    fun `rien n'est annonce deux fois`() {
        assertTrue(
            Changelog.aAnnoncer(versionActuelle = 20, derniereVue = 20, premiereInstallation = false)
                .isEmpty()
        )
    }

    @Test
    fun `une version anterieure a celle deja vue n'annonce rien`() {
        // Cas d'une réinstallation d'un APK plus ancien par-dessus : on ne remonte pas le temps.
        assertTrue(
            Changelog.aAnnoncer(versionActuelle = 18, derniereVue = 20, premiereInstallation = false)
                .isEmpty()
        )
    }

    // ---------------------------------------------------------------- quand parler

    @Test
    fun `une mise a jour annonce la version installee`() {
        val annonce = Changelog.aAnnoncer(
            versionActuelle = 20,
            derniereVue = 19,
            premiereInstallation = false
        )
        assertEquals(1, annonce.size)
        assertEquals(20, annonce.single().code)
    }

    @Test
    fun `sauter plusieurs versions les annonce toutes, de la plus recente a la plus ancienne`() {
        // Sans cela, les nouveautés d'une version sautée ne seraient annoncées à personne.
        val annonce = Changelog.aAnnoncer(
            versionActuelle = 20,
            derniereVue = 17,
            premiereInstallation = false
        )
        assertEquals(listOf(20, 19, 18), annonce.map { it.code })
    }

    @Test
    fun `venir d'avant cette fenetre n'affiche que la version installee`() {
        /*
         * Une application déjà installée dont aucune version n'a été vue : quelqu'un qui vient
         * d'une version antérieure à cette fenêtre. On ignore d'où il vient, et dérouler tout
         * l'historique au premier lancement serait un mur de texte.
         */
        val annonce = Changelog.aAnnoncer(
            versionActuelle = 20,
            derniereVue = 0,
            premiereInstallation = false
        )
        assertEquals(1, annonce.size)
        assertEquals(20, annonce.single().code)
    }

    // ---------------------------------------------------------------- l'historique lui-même

    @Test
    fun `l'historique va de la plus recente a la plus ancienne, sans doublon`() {
        val codes = Changelog.versions.map { it.code }
        assertEquals("Ordre d'affichage : la plus récente d'abord", codes.sortedDescending(), codes)
        assertEquals("Deux entrées pour un même code s'afficheraient en double", codes.distinct(), codes)
    }

    @Test
    fun `chaque version dit quelque chose`() {
        Changelog.versions.forEach { version ->
            assertTrue("La version ${version.nom} n'a aucune ligne", version.lignes.isNotEmpty())
            assertTrue("Le nom de la version ${version.code} est vide", version.nom.isNotBlank())
            assertTrue("La date de la version ${version.nom} est vide", version.date.isNotBlank())
            version.lignes.forEach {
                assertTrue("Une ligne vide dans la version ${version.nom}", it.texte.isNotBlank())
            }
        }
    }

    /**
     * ⚠️ Ce test échoue quand on incrémente `versionCode` sans écrire les nouveautés.
     *
     * C'est volontaire, et c'est le seul garde-fou : l'oubli ne produit aucune erreur de
     * compilation, et l'application se contenterait de n'afficher aucune fenêtre au démarrage.
     * Le correctif est d'ajouter une entrée dans `Changelog.versions`, pas de changer ce test.
     */
    @Test
    fun `la version en cours a son entree dans l'historique`() {
        val entree = Changelog.pour(BuildConfig.VERSION_CODE)
        assertNotNull(
            "versionCode ${BuildConfig.VERSION_CODE} (${BuildConfig.VERSION_NAME}) n'a pas " +
                "d'entrée dans Changelog.versions : ajoutez-la, l'application n'annoncerait " +
                "sinon aucune nouveauté au démarrage",
            entree
        )
        assertEquals(
            "Le nom affiché doit être celui de la version construite",
            BuildConfig.VERSION_NAME,
            entree!!.nom
        )
    }
}
