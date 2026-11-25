package com.filemanager36.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    private val dateShortFmt = SimpleDateFormat("dd/MM/yy", Locale.FRANCE)

    fun size(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes o"
        val units = arrayOf("Ko", "Mo", "Go", "To", "Po")
        var value = bytes.toDouble() / 1024.0
        var i = 0
        while (value >= 1024.0 && i < units.size - 1) {
            value /= 1024.0
            i++
        }
        return String.format(Locale.FRANCE, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
    }

    /** « 77 024 » : espace fine insécable entre les milliers, comme en français. */
    fun count(n: Long): String {
        val digits = n.toString()
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0 && c != '-' && digits[i - 1] != '-') sb.append(' ')
            sb.append(c)
        }
        return sb.toString()
    }

    fun count(n: Int): String = count(n.toLong())

    /** Temps restant : « moins d'une minute », « ~3 min », « ~1 h 05 ». */
    fun remaining(seconds: Long): String = when {
        seconds < 60 -> "moins d'une minute"
        seconds < 3600 -> "~${(seconds + 30) / 60} min"
        else -> {
            val m = (seconds + 30) / 60
            String.format(Locale.FRANCE, "~%d h %02d", m / 60, m % 60)
        }
    }

    fun date(millis: Long): String = if (millis <= 0) "—" else dateFmt.format(Date(millis))

    fun dateShort(millis: Long): String = if (millis <= 0) "—" else dateShortFmt.format(Date(millis))
}
