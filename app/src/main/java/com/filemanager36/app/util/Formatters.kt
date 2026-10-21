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

    fun date(millis: Long): String = if (millis <= 0) "—" else dateFmt.format(Date(millis))

    fun dateShort(millis: Long): String = if (millis <= 0) "—" else dateShortFmt.format(Date(millis))
}
