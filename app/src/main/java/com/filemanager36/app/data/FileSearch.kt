package com.filemanager36.app.data

import java.io.File
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

/**
 * Recherche par nom dans une arborescence, partagée par l'application et par la page
 * web du partage réseau.
 *
 * Un seul moteur pour les deux : la page web et l'écran de recherche doivent trouver
 * **les mêmes fichiers** pour la même saisie. Deux implémentations auraient divergé au
 * premier accent ou au premier dossier caché, et l'utilisateur aurait cru à un fichier
 * disparu.
 *
 * Aucune dépendance Android : le moteur est couvert par des tests JVM.
 */
object FileSearch {

    /** Profondeur maximale, alignée sur celle des opérations de fichiers. */
    private const val MAX_DEPTH = 32

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    /** Ce que la recherche a parcouru, et pourquoi elle s'est arrêtée. */
    class Outcome(
        val scanned: Int,
        val found: Int,
        /** Le plafond de résultats a été atteint : il en existe peut-être d'autres. */
        val truncated: Boolean,
        /** Interrompue de l'extérieur (nouvelle saisie, délai écoulé). */
        val stopped: Boolean
    )

    /**
     * Forme comparable d'un nom : minuscules, **sans accents**. Taper « ete » doit
     * trouver « Été 2025 » : sur un clavier de téléphone, les accents sont la première
     * chose que l'on omet.
     */
    fun normalize(s: String): String =
        DIACRITICS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT)

    /** Les mots de la saisie, déjà normalisés. */
    fun terms(query: String): List<String> =
        normalize(query).trim().split(SPACES).filter { it.isNotEmpty() }

    /**
     * Tous les mots doivent figurer dans le nom, **dans n'importe quel ordre** :
     * « facture 2025 » trouve « 2025_Facture_EDF.pdf ». Une recherche de la phrase
     * exacte obligerait à deviner comment le fichier a été nommé.
     */
    fun matches(name: String, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val n = normalize(name)
        return terms.all { n.contains(it) }
    }

    /**
     * Parcourt [roots] **en largeur** : les résultats proches de la racine arrivent en
     * premier. Un parcours en profondeur rendrait d'abord le trois-centième fichier d'un
     * cache de miniatures, avant le dossier « Factures » qui est juste sous les yeux.
     *
     * Les liens symboliques sont listés s'ils correspondent, mais jamais traversés :
     * `/sdcard` pointe vers le stockage interne, et le suivre ferait parcourir le même
     * volume deux fois — ou à l'infini sur une boucle.
     *
     * @param includeHidden descendre aussi dans les dossiers dont le nom commence par
     * un point. Faux par défaut : c'est le réglage du navigateur de fichiers, et la page
     * web masque toujours ces entrées.
     * @param shouldStop consulté régulièrement ; `true` interrompt proprement.
     * @param onMatch reçoit chaque résultat au fil de l'eau, dans l'ordre du parcours.
     */
    fun search(
        roots: List<File>,
        query: String,
        includeHidden: Boolean,
        maxResults: Int,
        shouldStop: () -> Boolean = { false },
        onMatch: (File) -> Unit
    ): Outcome {
        val words = terms(query)
        if (words.isEmpty()) return Outcome(0, 0, truncated = false, stopped = false)

        // Racines canoniques : `isSymlink` compare chemin canonique et chemin absolu, et
        // une racine désignée par un raccourci (« /sdcard ») ferait prendre chacun de ses
        // enfants pour un lien.
        val queue = ArrayDeque<Pair<File, Int>>()
        val seen = HashSet<String>()
        for (root in roots) {
            val canonical = runCatching { root.canonicalFile }.getOrNull() ?: continue
            if (canonical.isDirectory && seen.add(canonical.path)) queue.add(canonical to 0)
        }

        var scanned = 0
        var found = 0
        while (queue.isNotEmpty()) {
            if (shouldStop()) return Outcome(scanned, found, truncated = false, stopped = true)
            val (dir, depth) = queue.poll()!!
            // listFiles rend null sur un dossier illisible (Android/data depuis
            // Android 11, par exemple) : on passe, sans interrompre la recherche.
            val children = dir.listFiles() ?: continue
            children.sortBy { it.name.lowercase(Locale.ROOT) }
            for (child in children) {
                scanned++
                val hidden = child.name.startsWith(".")
                if (hidden && !includeHidden) continue

                if (matches(child.name, words)) {
                    onMatch(child)
                    found++
                    if (found >= maxResults) {
                        return Outcome(scanned, found, truncated = true, stopped = false)
                    }
                }
                if (child.isDirectory && depth + 1 < MAX_DEPTH && !FileOperations.isSymlink(child)) {
                    queue.add(child to depth + 1)
                }
                if (scanned % 256 == 0 && shouldStop()) {
                    return Outcome(scanned, found, truncated = false, stopped = true)
                }
            }
        }
        return Outcome(scanned, found, truncated = false, stopped = false)
    }
}
