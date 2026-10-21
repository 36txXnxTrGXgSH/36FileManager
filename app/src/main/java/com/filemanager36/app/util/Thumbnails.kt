package com.filemanager36.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Verrou partagé autour de pdfium : avant l'API 28, AOSP ne sérialise pas les appels
 * natifs de PdfRenderer, et deux rendus simultanés font planter le processus.
 */
object PdfLock { val lock = Any() }

/**
 * Génération des mini-aperçus affichés à côté du nom des fichiers :
 * images, vidéos, PDF, pochettes audio et icônes d'APK.
 * Résultats mis en cache mémoire (LRU) pour un défilement fluide.
 */
object Thumbnails {

    private const val TARGET = 128

    /** Taille maximale de la liste des échecs. */
    private const val MAX_FAILED = 4096

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Fichiers dont la vignette a déjà échoué, pour ne pas retenter à chaque défilement.
     *
     * Ensemble **borné** : un `HashSet` ordinaire n'était jamais purgé, et parcourir un
     * dossier de dizaines de milliers d'images illisibles le faisait grossir pour toute
     * la durée de vie du processus. `LinkedHashSet` en mode accès + éviction de la plus
     * ancienne entrée : le comportement d'un cache LRU, comme celui des vignettes.
     */
    private val failed = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
                    size > MAX_FAILED
            }
        )
    )

    private fun key(file: File) = "${file.absolutePath}|${file.lastModified()}|${file.length()}"

    fun cached(file: File): Bitmap? = cache.get(key(file))

    suspend fun load(context: Context, file: File, kind: FileKind): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(file)
        cache.get(k)?.let { return@withContext it }
        if (failed.contains(k)) return@withContext null
        val bmp = runCatching {
            when (kind) {
                FileKind.IMAGE -> decodeImage(file)
                FileKind.VIDEO -> decodeVideo(file)
                FileKind.AUDIO -> decodeAudioArt(file)
                FileKind.PDF -> decodePdf(file)
                FileKind.APK -> decodeApkIcon(context, file)
                else -> null
            }
        }.getOrNull()
        if (bmp == null) failed.add(k) else cache.put(k, bmp)
        bmp
    }

    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)?.let { square(it) }
    }

    private fun sampleSize(w: Int, h: Int): Int {
        var sample = 1
        var width = w
        var height = h
        while (width / 2 >= TARGET && height / 2 >= TARGET) {
            width /= 2; height /= 2; sample *= 2
        }
        return sample
    }

    private fun decodeVideo(file: File): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val frame = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime
            frame?.let { square(it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun decodeAudioArt(file: File): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val art = mmr.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(art, 0, art.size)?.let { square(it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun decodePdf(file: File): Bitmap? = synchronized(PdfLock.lock) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val ratio = page.height.toFloat() / page.width.toFloat()
                val w = TARGET
                val h = (TARGET * ratio).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                square(bmp)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    private fun decodeApkIcon(context: Context, file: File): Bitmap? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        info.applicationInfo?.let {
            it.sourceDir = file.absolutePath
            it.publicSourceDir = file.absolutePath
            return drawableToBitmap(it.loadIcon(pm))
        }
        return null
    }

    // Le recadrage carré reste ici : c'est ce qui distingue une vignette de liste d'une
    // simple conversion d'icône.
    private fun drawableToBitmap(d: Drawable): Bitmap =
        if (d is BitmapDrawable && d.bitmap != null) square(d.bitmap)
        else Drawables.toBitmap(d, TARGET)

    /** Recadre au centre en carré pour un rendu homogène dans la liste. */
    private fun square(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (side <= 0) return src
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = runCatching { Bitmap.createBitmap(src, x, y, side, side) }.getOrDefault(src)
        return if (cropped.width > TARGET) {
            Bitmap.createScaledBitmap(cropped, TARGET, TARGET, true)
        } else cropped
    }

    /**
     * Décodage plein écran pour la visionneuse, borné à [maxSide] pixels de côté :
     * une photo de 108 mégapixels décodée telle quelle dépasserait la mémoire allouée.
     * Non mis en cache, contrairement aux vignettes.
     */
    fun loadFull(file: File, maxSide: Int = 2048): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Condition sur la taille obtenue, et non sur la moitié suivante : sinon on
        // acceptait jusqu'au double du côté demandé, soit quatre fois la mémoire.
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()

    fun clear() {
        cache.evictAll()
        failed.clear()
    }
}
