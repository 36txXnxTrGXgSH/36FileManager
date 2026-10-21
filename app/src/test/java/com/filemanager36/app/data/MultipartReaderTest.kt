package com.filemanager36.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random

/**
 * Le lecteur multipart est le composant le plus risqué du partage réseau : il
 * découpe un flux d'octets à la recherche d'une frontière, et la moindre erreur
 * corrompt les fichiers reçus ou fige la connexion. Ces tests le sollicitent comme
 * une socket réelle — par petits morceaux de taille variable.
 */
class MultipartReaderTest {

    /** Rend les octets par tranches irrégulières, comme le fait une socket. */
    private class DripStream(
        private val data: ByteArray,
        private val maxChunk: Int,
        seed: Int = 42
    ) : InputStream() {
        private var pos = 0
        private val rnd = Random(seed)
        override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xff
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, data.size - pos, rnd.nextInt(1, maxChunk + 1))
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun body(boundary: String, filename: String, content: ByteArray): ByteArray {
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                "Content-Type: video/x-matroska\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return head + content + tail
    }

    /** @return contenu reçu et nom de fichier, ou échoue si le lecteur se bloque. */
    private fun parse(
        content: ByteArray,
        boundary: String,
        chunk: Int,
        filename: String = "film é.mkv"
    ): Pair<ByteArray, String> {
        val raw = body(boundary, filename, content)
        val out = ByteArrayOutputStream()
        var name = ""
        var failure: Throwable? = null
        val worker = Thread {
            try {
                MultipartReader(DripStream(raw, chunk), boundary).forEachFilePart { fn, s ->
                    name = fn
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = s.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            } catch (t: Throwable) {
                failure = t
            }
        }
        worker.isDaemon = true
        worker.start()
        // Échantillonnage : le flux avance-t-il, ou est-il vraiment figé ?
        val jalons = StringBuilder()
        repeat(10) {
            Thread.sleep(700)
            if (!worker.isAlive) return@repeat
            jalons.append("    t=").append((it + 1) * 700).append(" ms : ")
                .append(out.size()).append(" octets reçus\n")
        }
        worker.join(3_000)
        if (worker.isAlive) {
            val pile = worker.stackTrace.take(12).joinToString("\n") { "    à $it" }
            throw AssertionError(
                "Blocage. Progression :\n$jalons  Pile :\n$pile"
            )
        }
        failure?.let { throw AssertionError("Exception pendant la lecture : $it") }
        return out.toByteArray() to name
    }

    private val webkit = "----WebKitFormBoundaryQqSGmCX0y0mVJfPm"
    private val firefox = "---------------------------1234567890123456789"

    @Test fun gros_fichier_binaire_par_paquets_reseau() {
        val content = ByteArray(6 * 1024 * 1024).also { Random(7).nextBytes(it) }
        val (got, name) = parse(content, webkit, 1500)
        assertEquals("film é.mkv", name)
        assertArrayEquals(content, got)
    }

    @Test fun gros_fichier_frontiere_firefox_gros_paquets() {
        val content = ByteArray(4 * 1024 * 1024).also { Random(11).nextBytes(it) }
        assertArrayEquals(content, parse(content, firefox, 256 * 1024).first)
    }

    @Test fun octet_par_octet() {
        val content = ByteArray(120_000).also { Random(9).nextBytes(it) }
        assertArrayEquals(content, parse(content, webkit, 1).first)
    }

    /** Le contenu imite la frontière : le piège classique d'un tel analyseur. */
    @Test fun contenu_imitant_la_frontiere() {
        val content = ByteArrayOutputStream().apply {
            repeat(3000) {
                write("\r\n--${webkit.dropLast(2)}".toByteArray())
                write(Random(it).nextBytes(400))
                write("\r\n--".toByteArray())
                write("\r\n".toByteArray())
            }
        }.toByteArray()
        assertArrayEquals(content, parse(content, webkit, 997).first)
    }

    @Test fun fichier_vide() {
        assertArrayEquals(ByteArray(0), parse(ByteArray(0), webkit, 4096).first)
    }

    @Test fun un_seul_octet() {
        assertArrayEquals(byteArrayOf(42), parse(byteArrayOf(42), webkit, 4096).first)
    }

    /** Le contenu dépasse largement le tampon interne de 128 Kio. */
    @Test fun contenu_plus_grand_que_le_tampon() {
        val content = ByteArray(1_000_000) { (it % 251).toByte() }
        assertArrayEquals(content, parse(content, webkit, 200_000).first)
    }

    @Test fun nom_de_fichier_accentue_en_utf8() {
        val (_, name) = parse(byteArrayOf(1, 2, 3), webkit, 64, "Facture décembre — copie (1).pdf")
        assertEquals("Facture décembre — copie (1).pdf", name)
    }
}
