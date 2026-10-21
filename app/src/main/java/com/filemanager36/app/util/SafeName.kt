package com.filemanager36.app.util

/**
 * Assainissement des noms de fichiers **venus de l'extérieur** : navigateur du PC,
 * autre téléphone, serveur SMB, fournisseur cloud.
 *
 * Un nom reçu du réseau n'est pas une donnée de confiance. `File(dossier, nom)`
 * interprète les séparateurs : un serveur qui répond « ../../secret » ferait écrire
 * hors du dossier de destination. Cette fonction était déjà écrite — en privé — dans
 * `HttpShareServer` ; elle est remontée ici parce que trois autres chemins de code en
 * avaient besoin sans l'avoir (téléchargement SMB récursif, copie depuis le cloud).
 */
object SafeName {

    /** Longueur maximale d'un nom, **en octets** : la limite des systèmes de fichiers
     *  porte sur les octets et non sur les caractères — « é » en compte deux. */
    private const val MAX_BYTES = 200

    private val CONTROL = Regex("[\\x00-\\x1f\\x7f]")

    /**
     * @return un nom de fichier sûr, ou une chaîne vide si rien d'exploitable ne reste.
     * L'appelant **doit** traiter la chaîne vide comme un refus.
     */
    fun of(raw: String): String {
        // Les deux séparateurs : un serveur Windows renvoie des antislashs.
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base == "." || base == "..") return ""
        val clean = CONTROL.replace(base, "")
        if (clean.isBlank()) return ""
        val bytes = clean.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_BYTES) return clean
        // On tronque sur le tableau d'octets en reculant jusqu'au début d'un caractère
        // complet : une boucle de `dropLast` ré-encoderait toute la chaîne à chaque tour
        // et pourrait couper une paire de substitution en deux.
        var cut = MAX_BYTES
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        return String(bytes, 0, cut, Charsets.UTF_8).trim()
    }

    /** Variante repli : renvoie [fallback] plutôt qu'une chaîne vide. */
    fun orDefault(raw: String, fallback: String): String = of(raw).ifBlank { fallback }

    /** Profondeur maximale d'un chemin relatif reçu du réseau. */
    private const val MAX_DEPTH = 24

    /**
     * Assainit un **chemin relatif** venu de l'extérieur — l'envoi d'un dossier entier
     * depuis le navigateur, où chaque fichier arrive avec son chemin dans l'arborescence
     * (`photos/2026/été/img.jpg`).
     *
     * Chaque segment passe par [of] : le tout premier `..` ou segment vide fait échouer
     * l'ensemble plutôt que d'être discrètement retiré. Retirer un segment fautif d'un
     * chemin, c'est en fabriquer un autre que personne n'a demandé — et c'est ainsi que
     * `a/../../b` devient `a/b` sur certains assainisseurs, puis `../b` sur le suivant.
     *
     * @return les segments, du premier dossier au nom de fichier, ou une **liste vide**
     * si le chemin n'est pas exploitable. L'appelant doit traiter la liste vide comme un
     * refus.
     */
    fun relative(raw: String): List<String> {
        // Un serveur ou un système Windows renvoie des antislashs.
        val parts = raw.replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.size > MAX_DEPTH) return emptyList()
        val clean = parts.map { of(it) }
        return if (clean.any { it.isBlank() }) emptyList() else clean
    }
}
