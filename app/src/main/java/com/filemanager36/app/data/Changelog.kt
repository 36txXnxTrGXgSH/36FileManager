package com.filemanager36.app.data

/** Nature d'une ligne de nouveautés : elle décide de la pastille affichée. */
enum class GenreNote { NOUVEAU, AMELIORE, CORRIGE }

/** Une ligne de la liste des nouveautés. */
data class LigneNote(val genre: GenreNote, val texte: String)

/** Ce qui a changé dans une version. */
data class VersionNote(
    val code: Int,
    val nom: String,
    val date: String,
    val lignes: List<LigneNote>
)

/**
 * Ce qui a changé, version par version — affiché dans une fenêtre au premier lancement
 * de chaque nouvelle version (repris de 36Note).
 *
 * ## ⚠️ À tenir à jour à CHAQUE livraison
 * Une entrée doit être ajoutée ici en même temps que l'on incrémente `versionCode` dans
 * `app/build.gradle.kts`. Oublier ne casse rien de visible à la compilation — l'application
 * se contenterait de n'afficher aucune nouveauté — ce qui est précisément ce qui rend
 * l'oubli facile. `ChangelogTest` échoue si la version construite n'a pas son entrée.
 *
 * ## Ce qu'on écrit ici
 * Ce que l'utilisateur constate, pas ce que le code fait. « La recherche ignore les
 * accents », pas « normalisation NFD dans FileSearch ». Une ligne par changement, sans
 * jargon.
 */
object Changelog {

    /** De la plus récente à la plus ancienne : c'est l'ordre d'affichage. */
    val versions: List<VersionNote> = listOf(
        VersionNote(
            code = 24,
            nom = "2.6.2",
            date = "25 novembre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Copier et déplacer affichent une vraie barre de progression : la quantité " +
                        "copiée, la vitesse, le nombre de fichiers restants et le temps restant. " +
                        "Bouton Annuler, et l'opération continue écran éteint."
                ),
                LigneNote(
                    GenreNote.AMELIORE,
                    "Copie et déplacement de nombreux petits fichiers plusieurs fois plus rapides " +
                        "(quatre fichiers à la fois), et la place libre est vérifiée avant de commencer."
                ),
                LigneNote(
                    GenreNote.AMELIORE,
                    "Un déplacement annulé ne laisse rien derrière lui : les copies déjà faites sont " +
                        "retirées et les originaux n'ont pas bougé."
                ),
                LigneNote(
                    GenreNote.CORRIGE,
                    "« Informations » sur un gros dossier ne restait plus muet : la fiche s'ouvre " +
                        "aussitôt et compte fichiers, dossiers et taille en direct."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "« Informations » décrit chaque type de fichier : dimensions et appareil d'une " +
                        "photo, durée et résolution d'une vidéo, titre et artiste d'une musique, version " +
                        "d'un APK, contenu d'une archive, pages d'un PDF, lignes d'un texte. Elle " +
                        "fonctionne aussi sur plusieurs éléments sélectionnés à la fois."
                )
            )
        ),
        VersionNote(
            code = 23,
            nom = "2.6.1",
            date = "25 novembre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.AMELIORE,
                    "Extraction bien plus rapide : plusieurs fichiers sont écrits à la fois, et " +
                        "les archives 7z sont décompressées sur plusieurs cœurs. Sur une archive de " +
                        "200 000 fichiers, plusieurs heures deviennent une demi-heure environ."
                ),
                LigneNote(
                    GenreNote.AMELIORE,
                    "Suppression des gros dossiers plusieurs fois plus rapide, avec le nombre " +
                        "d'éléments supprimés, un bouton Annuler, et qui continue écran éteint."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "« Extraire vers… » dans le menu de sélection, pour choisir où extraire une " +
                        "archive — une carte SD par exemple."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "La fenêtre d'extraction prévient quand la destination est une carte SD : " +
                        "Android y crée les fichiers très lentement, une archive de milliers de " +
                        "fichiers y prend des heures. Le stockage interne est bien plus rapide."
                ),
                LigneNote(
                    GenreNote.CORRIGE,
                    "L'application ne se lançait plus après l'installation de la 2.6.0."
                )
            )
        ),
        VersionNote(
            code = 22,
            nom = "2.6.0",
            date = "25 novembre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Extraction des archives 7z, RAR (jusqu'au RAR 5), TAR, TAR.GZ, TAR.BZ2, TAR.XZ, " +
                        "GZ, BZ2 et XZ, en plus du ZIP — y compris les archives découpées en " +
                        "plusieurs parties (.7z.001, .part1.rar)."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Archives protégées par mot de passe (ZIP, 7z, RAR) : le mot de passe est " +
                        "demandé et vérifié avant de commencer."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Avant d'extraire, la fenêtre indique le nombre de fichiers, la place " +
                        "nécessaire et la place disponible ; on peut choisir un autre emplacement, " +
                        "une carte SD par exemple."
                ),
                LigneNote(
                    GenreNote.AMELIORE,
                    "Une extraction se suit en mégaoctets, s'annule d'un bouton, et continue " +
                        "écran éteint, avec sa progression dans les notifications."
                ),
                LigneNote(
                    GenreNote.CORRIGE,
                    "Les archives ZIP créées sous Windows avec des accents dans les noms " +
                        "s'extraient correctement, et les archives de plus de 16 Go ne sont plus " +
                        "refusées."
                ),
                LigneNote(
                    GenreNote.CORRIGE,
                    "Visionneuse texte : les fichiers écrits sous Windows (sous-titres .srt, " +
                        "vieux .txt) affichent de nouveau leurs accents au lieu de « � »."
                )
            )
        ),
        VersionNote(
            code = 21,
            nom = "2.5.1",
            date = "21 octobre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.AMELIORE,
                    "Partage réseau : quand un morceau ou une vidéo envoyé depuis le PC existe déjà " +
                        "sur le téléphone, la fenêtre « Ce fichier existe déjà » affiche la durée des " +
                        "deux versions, pour savoir si c'est le même enregistrement."
                ),
                LigneNote(
                    GenreNote.AMELIORE,
                    "Dans cette même fenêtre, quand les deux tailles s'affichent pareil (« 28 Mo » " +
                        "et « 28 Mo »), la taille exacte en octets apparaît en dessous."
                ),
                LigneNote(
                    GenreNote.CORRIGE,
                    "Partage réseau : dans la page web, cocher des fichiers active de nouveau les " +
                        "boutons Télécharger, Déplacer, Renommer et Supprimer, et une recherche " +
                        "indique de nouveau le nombre de résultats."
                ),
                LigneNote(
                    GenreNote.AMELIORE,
                    "Les tailles affichées dans la page web s'écrivent avec une virgule, comme dans " +
                        "l'application (« 3,8 Mo »)."
                )
            )
        ),
        VersionNote(
            code = 20,
            nom = "2.5.0",
            date = "17 septembre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Recherche sur tout le téléphone : la loupe de l'accueil trouve un fichier ou " +
                        "un dossier où qu'il soit. Majuscules et accents sont ignorés, et les mots " +
                        "peuvent venir dans n'importe quel ordre."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Dans un dossier, quand vous tapez dans le filtre, un bandeau propose de " +
                        "chercher aussi dans tous les sous-dossiers. Un retour depuis un résultat " +
                        "ouvert ramène à la liste."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Tuile « Téléchargements » à l'accueil, sous les catégories."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Partage réseau : depuis le navigateur du PC, rechercher dans le téléphone et " +
                        "déplacer des fichiers ou des dossiers vers un autre dossier. Rien n'est " +
                        "jamais écrasé."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Cette fenêtre, qui présente les nouveautés à chaque mise à jour. Pour la " +
                        "revoir, touchez le numéro de version en haut de l'accueil."
                )
            )
        ),
        VersionNote(
            code = 19,
            nom = "2.4.0",
            date = "11 septembre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Partage réseau : envoyer un dossier entier depuis le PC, sous-dossiers compris."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Un fichier déjà présent à l'identique n'est pas renvoyé. S'il diffère, vous " +
                        "choisissez : garder celui du téléphone, garder les deux, ou remplacer."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Bouton « Relancer » sur les envois en échec."
                ),
                LigneNote(
                    GenreNote.CORRIGE,
                    "Les envois ne restent plus bloqués à « 0 % » : ils avancent deux par deux, " +
                        "les autres attendent leur tour en l'indiquant."
                ),
                LigneNote(
                    GenreNote.CORRIGE,
                    "Les transferts ne tombent plus en « Connexion perdue » quand l'écran du " +
                        "téléphone s'éteint."
                )
            )
        ),
        VersionNote(
            code = 18,
            nom = "2.3.1",
            date = "5 septembre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.CORRIGE,
                    "Partage réseau : les boutons Renommer et Supprimer apparaissent enfin dans " +
                        "la page web — sur la ligne pour un élément, en haut pour une sélection."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Renommer plusieurs éléments à la suite depuis le navigateur."
                )
            )
        ),
        VersionNote(
            code = 17,
            nom = "2.3.0",
            date = "5 septembre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Partage réseau : la carte SD et les clés USB sont accessibles depuis le PC, " +
                        "plus seulement le stockage interne."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "L'espace libre de chaque volume est affiché en permanence dans la page web."
                )
            )
        ),
        VersionNote(
            code = 16,
            nom = "2.2.0",
            date = "5 septembre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Partage réseau : renommer et supprimer depuis le navigateur du PC."
                ),
                LigneNote(
                    GenreNote.NOUVEAU,
                    "Bouton « Effacer » pour vider l'historique des transferts."
                )
            )
        ),
        VersionNote(
            code = 15,
            nom = "2.1.0",
            date = "2 septembre 2026",
            lignes = listOf(
                LigneNote(
                    GenreNote.AMELIORE,
                    "Sécurité du partage réseau renforcée : il ne s'ouvre jamais sur les données " +
                        "mobiles, et un site web consulté en même temps ne peut plus agir sur le " +
                        "téléphone."
                ),
                LigneNote(
                    GenreNote.AMELIORE,
                    "Les mots de passe des lecteurs réseau sont désormais chiffrés, et le contenu " +
                        "du coffre-fort est protégé contre toute altération."
                )
            )
        )
    )

    fun pour(code: Int): VersionNote? = versions.firstOrNull { it.code == code }

    /**
     * Les versions à présenter au lancement.
     *
     * - Première installation : rien. Tout est nouveau pour qui découvre l'application.
     * - Version déjà vue, ou plus ancienne que la dernière vue : rien.
     * - Aucune version jamais vue sur une application déjà installée : quelqu'un qui vient
     *   d'avant cette fenêtre. On ignore d'où, et dérouler tout l'historique serait un mur
     *   de texte : seule la version installée est présentée.
     * - Sinon : toutes les versions sautées, de la plus récente à la plus ancienne.
     */
    fun aAnnoncer(
        versionActuelle: Int,
        derniereVue: Int,
        premiereInstallation: Boolean
    ): List<VersionNote> = when {
        premiereInstallation -> emptyList()
        derniereVue >= versionActuelle -> emptyList()
        derniereVue <= 0 -> listOfNotNull(pour(versionActuelle))
        else -> versions.filter { it.code in (derniereVue + 1)..versionActuelle }
    }
}
