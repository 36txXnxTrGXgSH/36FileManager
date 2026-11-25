package com.filemanager36.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

/** La visionneuse texte doit afficher les accents quel que soit l'encodage du fichier. */
class TextDecodingTest {

    private val phrase = "Sous-titre : « Où êtes-vous, Émilie ? » — 1 €"

    @Test
    fun `utf-8 reste de l'utf-8`() {
        val r = TextDecoding.decode(phrase.toByteArray(Charsets.UTF_8))
        assertEquals("UTF-8", r.charset)
        assertEquals(phrase, r.text)
    }

    @Test
    fun `un srt windows-1252 retrouve ses accents`() {
        val r = TextDecoding.decode(phrase.toByteArray(Charset.forName("windows-1252")))
        assertEquals("Windows-1252", r.charset)
        assertEquals(phrase, r.text)
    }

    @Test
    fun `les marques d'ordre des octets sont respectees`() {
        val bomUtf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + phrase.toByteArray(Charsets.UTF_8)
        assertEquals(phrase, TextDecoding.decode(bomUtf8).text)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + phrase.toByteArray(Charsets.UTF_16LE)
        assertEquals(phrase, TextDecoding.decode(utf16).text)
        val utf16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + phrase.toByteArray(Charsets.UTF_16BE)
        assertEquals(phrase, TextDecoding.decode(utf16be).text)
    }

    /**
     * La visionneuse ne lit que les 256 premiers Kio : couper au milieu d'un « é » ne
     * doit pas faire basculer tout le fichier en Windows-1252.
     */
    @Test
    fun `un caractere coupe en fin de lecture ne trompe pas la detection`() {
        val bytes = "abc€".toByteArray(Charsets.UTF_8)          // « € » = 3 octets
        val r = TextDecoding.decode(bytes, bytes.size - 1, truncated = true)
        assertEquals("UTF-8", r.charset)
        assertEquals("abc", r.text)
    }
}
