package com.filemanager36.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Conversion d'une icône du système en bitmap.
 *
 * Trois copies de cette fonction coexistaient — dans `ShareUtil`, `ApkUtil` et
 * `Thumbnails` — avec des tailles différentes et un traitement légèrement divergent du
 * cas `BitmapDrawable`. Une seule implémentation, la taille en paramètre.
 */
object Drawables {

    fun toBitmap(drawable: Drawable, size: Int): Bitmap {
        // Une icône déjà bitmap est rendue telle quelle : la redessiner sur un canevas
        // la rééchantillonnerait pour rien.
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }
        val side = size.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, side, side)
        drawable.draw(canvas)
        return bmp
    }
}
