package com.filemanager36.app.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Décodage d'un fichier texte dont on ignore l'encodage.
 *
 * Tout lire comme de l'UTF-8 affichait des « � » à la place de chaque accent dans les
 * fichiers écrits par Windows — sous-titres .srt français, vieux .txt, journaux, .nfo —
 * qui sont en Windows-1252. Ordre suivi, du plus sûr au moins sûr :
 * 1. une marque d'ordre des octets (BOM) UTF-8 ou UTF-16 dit l'encodage sans ambiguïté ;
 * 2. sinon, un UTF-8 **strictement** valide est de l'UTF-8 : du Windows-1252 accentué ne
 *    l'est presque jamais par hasard ;
 * 3. sinon, Windows-1252 — qui décode n'importe quel octet et couvre le Latin-1.
 */
object TextDecoding {

    class Decoded(val text: String, val charset: String)

    private val WINDOWS_1252: Charset =
        runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /**
     * @param truncated la lecture a été coupée avant la fin du fichier : un caractère
     * multi-octets peut être tronqué en fin de tampon, ce qui ne doit pas faire prendre
     * un fichier UTF-8 pour du Windows-1252.
     */
    fun decode(bytes: ByteArray, length: Int = bytes.size, truncated: Boolean = false): Decoded {
        val len = length.coerceIn(0, bytes.size)
        fun at(vararg b: Int) = len >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        when {
            at(0xEF, 0xBB, 0xBF) -> return Decoded(String(bytes, 3, len - 3, Charsets.UTF_8), "UTF-8")
            at(0xFF, 0xFE) -> return Decoded(String(bytes, 2, (len - 2) and 1.inv(), Charsets.UTF_16LE), "UTF-16")
            at(0xFE, 0xFF) -> return Decoded(String(bytes, 2, (len - 2) and 1.inv(), Charsets.UTF_16BE), "UTF-16")
        }
        val end = if (truncated) completeUtf8End(bytes, len) else len
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            Decoded(decoder.decode(ByteBuffer.wrap(bytes, 0, end)).toString(), "UTF-8")
        } catch (e: CharacterCodingException) {
            Decoded(String(bytes, 0, len, WINDOWS_1252), "Windows-1252")
        }
    }

    /** Fin du tampon sans le dernier caractère UTF-8 s'il est incomplet. */
    internal fun completeUtf8End(bytes: ByteArray, len: Int): Int {
        var i = len - 1
        var back = 0
        // Remonte les octets de continuation (10xxxxxx), au plus trois.
        while (i >= 0 && back < 3 && (bytes[i].toInt() and 0xC0) == 0x80) { i--; back++ }
        if (i < 0) return len
        val lead = bytes[i].toInt() and 0xFF
        val need = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> return len
        }
        return if (len - i < need) i else len
    }
}
