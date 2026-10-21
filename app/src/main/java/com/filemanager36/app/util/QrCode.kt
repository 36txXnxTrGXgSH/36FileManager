package com.filemanager36.app.util

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Génération du QR code qui ouvre le partage depuis un autre appareil. */
object QrCode {

    /**
     * @param size côté de l'image en pixels
     * @param dark couleur des modules (ARGB)
     * @param light couleur du fond (ARGB)
     *
     * Bloquant mais rapide (quelques millisecondes) ; à produire tout de même hors
     * du fil principal pour ne pas retarder la première image de l'écran.
     */
    fun bitmap(
        content: String,
        size: Int = 640,
        dark: Int = 0xFF000000.toInt(),
        light: Int = 0xFFFFFFFF.toInt()
    ): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // Marge de 1 module : le cadre blanc est dessiné par l'interface, pas ici.
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) dark else light
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }.getOrNull()
}
