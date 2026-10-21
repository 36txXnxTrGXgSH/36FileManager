package com.filemanager36.app.data

import java.io.EOFException
import java.io.InputStream

/**
 * Lecteur `multipart/form-data` **en flux**.
 *
 * Les implémentations habituelles rassemblent chaque partie en mémoire ou dans un
 * fichier temporaire avant de la rendre disponible. Déposer un film de 2 Go depuis
 * le PC ferait alors exploser la mémoire du téléphone, ou doublerait l'écriture sur
 * la carte SD. Ici, chaque partie est exposée comme un `InputStream` qui s'arrête
 * exactement sur la frontière suivante : les octets vont du réseau au fichier
 * de destination sans étape intermédiaire.
 */
internal class MultipartReader(
    private val src: InputStream,
    boundary: String
) {

    /** Une partie se termine par CRLF suivi de la frontière. */
    private val delim = ("\r\n--$boundary").toByteArray(Charsets.US_ASCII)
    private val firstDelim = ("--$boundary").toByteArray(Charsets.US_ASCII)

    private val buf = ByteArray(128 * 1024)
    private var pos = 0
    private var end = 0
    private var eof = false

    /**
     * Parcourt les parties. Le bloc reçoit le nom de fichier annoncé et un flux à lire
     * jusqu'à son terme ; les parties sans nom de fichier (champs de formulaire
     * ordinaires) sont ignorées.
     */
    fun forEachFilePart(block: (String, InputStream) -> Unit) {
        if (!seekFirstBoundary()) return
        var guard = 0
        while (guard++ < 10_000) {
            // Juste après une frontière : « -- » clôt le message, CRLF introduit une partie.
            if (!ensure(2)) return
            if (buf[pos] == '-'.code.toByte() && buf[pos + 1] == '-'.code.toByte()) return
            // Consomme le CRLF de fin de ligne de frontière (tolère un LF seul).
            if (buf[pos] == '\r'.code.toByte()) pos++
            if (pending() > 0 && buf[pos] == '\n'.code.toByte()) pos++

            val filename = readPartHeaders() ?: return
            val stream = PartStream()
            if (filename.isNotBlank()) {
                block(filename, stream)
            }
            // Vide ce qui resterait : le bloc peut s'être arrêté avant la fin, et la
            // position doit de toute façon atteindre la frontière suivante.
            stream.drain()
            // La frontière est encore dans le tampon : on la franchit.
            if (!ensure(delim.size)) return
            pos += delim.size
        }
    }

    // ------------------------------------------------------------------

    /** Renvoie le nom de fichier de la partie, "" si c'est un champ ordinaire. */
    private fun readPartHeaders(): String? {
        var filename = ""
        var lines = 0
        while (lines++ < 50) {
            val line = readHeaderLine() ?: return null
            if (line.isEmpty()) return filename
            val lower = line.lowercase()
            if (lower.startsWith("content-disposition:")) {
                // filename*=UTF-8''nom%20encodé  (RFC 5987), sinon filename="nom"
                Regex("filename\\*\\s*=\\s*[^']*''([^;]+)").find(line)?.let {
                    filename = runCatching {
                        java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8")
                    }.getOrDefault(it.groupValues[1].trim())
                }
                if (filename.isBlank()) {
                    Regex("filename\\s*=\\s*\"([^\"]*)\"").find(line)?.let {
                        filename = it.groupValues[1]
                    }
                }
            }
        }
        return filename
    }

    private fun seekFirstBoundary(): Boolean {
        var guard = 0
        while (guard++ < 10_000) {
            val idx = indexOf(firstDelim)
            if (idx >= 0) {
                pos = idx + firstDelim.size
                return true
            }
            // Conserve une queue de la taille du motif, au cas où il serait à cheval.
            val keep = firstDelim.size - 1
            if (pending() > keep) pos = end - keep
            if (fill() < 0) return false
        }
        return false
    }

    /**
     * Lit une ligne d'en-tête et la décode en **UTF-8**.
     *
     * Chrome, Edge et Firefox envoient le nom de fichier en octets UTF-8 bruts à
     * l'intérieur de `filename="…"` — la forme `filename*` de la RFC 5987 n'est
     * quasiment jamais utilisée à l'envoi. Décoder octet par octet revenait à lire du
     * Latin-1 : « Facture décembre.pdf » arrivait sur la carte SD sous le nom
     * « Facture dÃ©cembre.pdf ».
     */
    private fun readHeaderLine(): String? {
        val bytes = java.io.ByteArrayOutputStream(96)
        var guard = 0
        while (guard++ < 16 * 1024) {
            if (pending() == 0 && fill() < 0) {
                return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            }
            val b = buf[pos++]
            if (b == '\n'.code.toByte()) {
                var line = bytes.toString("UTF-8")
                if (line.endsWith("\r")) line = line.dropLast(1)
                return line
            }
            bytes.write(b.toInt())
        }
        return bytes.toString("UTF-8")
    }

    // ------------------------------------------------------------------
    // Tampon
    // ------------------------------------------------------------------

    /**
     * Octets en attente dans le tampon.
     *
     * **Le nom compte.** `PartStream` hérite de `InputStream`, et depuis une classe
     * interne un appel non qualifié va d'abord chercher un membre de la classe
     * interne. Nommée `available()`, cette méthode était masquée par
     * `InputStream.available()`, qui renvoie 0 : le lecteur croyait le tampon
     * éternellement vide et tout fichier dépassant 128 Kio figeait le transfert.
     * Nommée `buffered()`, elle était masquée par l'extension Kotlin
     * `InputStream.buffered()`. D'où ce nom-ci, qui n'entre en collision avec rien —
     * et l'appel qualifié `this@MultipartReader.pending()` dans la classe interne.
     */
    private fun pending() = end - pos

    /** @return le nombre d'octets lus, ou -1 en fin de flux. */
    private fun fill(): Int {
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, end - pos)
            end -= pos
            pos = 0
        }
        if (end >= buf.size) return 0
        val n = try { src.read(buf, end, buf.size - end) } catch (e: Exception) { -1 }
        if (n < 0) { eof = true; return -1 }
        end += n
        return n
    }

    private fun ensure(min: Int): Boolean {
        while (pending() < min) {
            if (eof) return false
            if (fill() < 0) return pending() >= min
        }
        return true
    }

    /** Position absolue du motif dans le tampon, ou -1. */
    private fun indexOf(pattern: ByteArray): Int {
        val limit = end - pattern.size
        var i = pos
        outer@ while (i <= limit) {
            var j = 0
            while (j < pattern.size) {
                if (buf[i + j] != pattern[j]) { i++; continue@outer }
                j++
            }
            return i
        }
        return -1
    }

    // ------------------------------------------------------------------

    /** Flux d'une partie : s'arrête pile avant la frontière, sans la consommer. */
    private inner class PartStream : InputStream() {

        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        /** Redéfini pour être exact : la version héritée renvoie toujours 0. */
        override fun available(): Int =
            if (done) 0 else maxOf(0, this@MultipartReader.pending() - (delim.size - 1))

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done || len == 0) return if (done) -1 else 0
            while (true) {
                val idx = indexOf(delim)
                if (idx >= 0) {
                    val upTo = idx - pos
                    if (upTo == 0) { done = true; return -1 }
                    val take = minOf(upTo, len)
                    System.arraycopy(buf, pos, b, off, take)
                    pos += take
                    return take
                }
                // Pas de frontière en vue : tout est livrable sauf une queue de la
                // taille du motif moins un, qui pourrait en être le début.
                val safe = this@MultipartReader.pending() - (delim.size - 1)
                if (safe > 0) {
                    val take = minOf(safe, len)
                    System.arraycopy(buf, pos, b, off, take)
                    pos += take
                    return take
                }
                if (fill() < 0) {
                    // Flux coupé avant la frontière finale : l'envoi est incomplet.
                    // Remonter l'erreur permet d'effacer le fichier partiel plutôt
                    // que de le laisser passer pour un transfert réussi.
                    done = true
                    throw EOFException("Envoi interrompu avant la fin")
                }
            }
        }

        fun drain() {
            val trash = ByteArray(16 * 1024)
            runCatching { while (read(trash, 0, trash.size) > 0) { /* rien */ } }
        }
    }
}
