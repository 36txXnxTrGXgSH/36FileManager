package com.filemanager36.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.filemanager36.app.util.ApkUtil
import com.filemanager36.app.util.Formatters
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * Construction de la fiche « Informations », pour tout type d'élément : fichier, dossier,
 * lien, ou plusieurs éléments sélectionnés ensemble.
 *
 * En deux temps, pour que la fiche s'ouvre **tout de suite** :
 * - [basic] ne lit que l'élément lui-même (quelques millisecondes) ;
 * - le contenu d'un dossier est compté ensuite par [TreeScanner.measure], en direct, et
 *   [extras] lit à part ce qui dépend du type (dimensions d'une photo, durée d'une vidéo,
 *   version d'un APK, contenu d'une archive, pages d'un PDF…).
 *
 * Bloquant : hors du fil principal.
 */
object FileDetailsReader {

    /** Au-delà, compter les lignes d'un texte prendrait trop longtemps pour une fiche. */
    private const val TEXT_LINES_LIMIT = 64L shl 20

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "tsv", "log", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "java",
        "py", "c", "h", "cpp", "sh", "bat", "ini", "conf", "cfg", "yaml", "yml", "toml", "srt", "vtt", "sql",
        "properties", "gradle", "kts"
    )

    /** Ce qui se lit d'un coup. Un dossier ou une sélection sort avec `scanning = true`. */
    fun basic(files: List<File>): FileDetails {
        if (files.size > 1) {
            val parents = files.mapNotNull { it.parentFile?.absolutePath }.distinct()
            return FileDetails(
                name = "${Formatters.count(files.size)} éléments sélectionnés",
                path = parents.singleOrNull() ?: "Plusieurs dossiers",
                isDirectory = false,
                sizeBytes = 0,
                fileCount = 0,
                folderCount = 0,
                lastModified = files.maxOf { it.lastModified() },
                mimeType = "sélection",
                readable = files.all { it.canRead() },
                writable = files.all { it.canWrite() },
                hidden = false,
                itemCount = files.size,
                scanning = true
            )
        }
        val file = files.single()
        val attrs = TreeScanner.attributes(file.toPath())
        val link = attrs?.isSymbolicLink == true
        val isDir = if (attrs != null) attrs.isDirectory else file.isDirectory
        return FileDetails(
            name = file.name.ifEmpty { file.absolutePath },
            path = file.absolutePath,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0 else attrs?.size() ?: file.length(),
            fileCount = if (isDir) 0 else 1,
            folderCount = 0,
            lastModified = attrs?.lastModifiedTime()?.toMillis() ?: file.lastModified(),
            mimeType = when {
                link -> "lien symbolique"
                isDir -> "dossier"
                else -> FileOperations.mimeOf(file)
            },
            readable = file.canRead(),
            writable = file.canWrite(),
            hidden = file.name.startsWith("."),
            scanning = isDir,
            linkTarget = if (link) runCatching { Files.readSymbolicLink(file.toPath()).toString() }.getOrNull() else null,
            extrasLoading = !isDir && !link && attrs?.isRegularFile != false
        )
    }

    /** Propriétés propres au type du fichier ; une liste vide si rien de particulier. */
    fun extras(context: Context, file: File): List<Pair<String, String>> {
        val mime = FileOperations.mimeOf(file)
        val ext = file.extension.lowercase()
        val out = ArrayList<Pair<String, String>>()
        when {
            ext == "apk" || mime == ApkUtil.MIME -> apk(context, file, out)
            Archives.isArchiveName(file.name) -> archive(file, out)
            mime.startsWith("image/") -> image(file, out)
            mime.startsWith("video/") -> media(file, out, video = true)
            mime.startsWith("audio/") -> media(file, out, video = false)
            ext == "pdf" -> pdf(file, out)
            mime.startsWith("text/") || ext in TEXT_EXTENSIONS -> text(file, out)
        }
        if (ext.isNotEmpty()) out += "Extension" to ".$ext"
        return out
    }

    private fun image(file: File, out: MutableList<Pair<String, String>>) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            val mpx = opts.outWidth.toLong() * opts.outHeight / 1_000_000.0
            out += "Dimensions" to "${opts.outWidth} × ${opts.outHeight} px" +
                if (mpx >= 0.1) " (${String.format(java.util.Locale.FRANCE, "%.1f", mpx)} Mpx)" else ""
        }
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.takeIf { it.isNotBlank() }?.let {
                // « 2024:07:14 18:03:22 » → « 14/07/2024 18:03 »
                val m = Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2})""").find(it)
                out += "Prise de vue" to (m?.let { r -> val (y, mo, d, h, mi) = r.destructured; "$d/$mo/$y $h:$mi" } ?: it)
            }
            val camera = listOfNotNull(
                exif.getAttribute(ExifInterface.TAG_MAKE)?.trim(),
                exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            ).filter { it.isNotEmpty() }.distinct()
            if (camera.isNotEmpty()) out += "Appareil" to camera.joinToString(" ")
            if (exif.getLatLong(FloatArray(2))) out += "Position" to "enregistrée dans la photo"
        }
    }

    private fun media(file: File, out: MutableList<Pair<String, String>>, video: Boolean) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            fun key(k: Int) = runCatching { r.extractMetadata(k) }.getOrNull()?.takeIf { it.isNotBlank() }
            key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { out += "Durée" to duration(it) }
            if (video) {
                val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                val rot = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w != null && h != null) {
                    out += "Résolution" to if (rot == 90 || rot == 270) "$h × $w px" else "$w × $h px"
                }
            }
            key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let {
                out += "Débit" to if (it >= 1_000_000) String.format(java.util.Locale.FRANCE, "%.1f Mbit/s", it / 1e6)
                else "${it / 1000} kbit/s"
            }
            key(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { out += "Titre" to it }
            key(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { out += "Artiste" to it }
            key(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { out += "Album" to it }
            key(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let { out += "Année" to it }
            key(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { out += "Genre" to it }
        } catch (e: Exception) {
            out += "Lecture" to "propriétés illisibles"
        } finally {
            runCatching { r.release() }
        }
    }

    private fun apk(context: Context, file: File, out: MutableList<Pair<String, String>>) {
        val info = ApkUtil.read(context, file)
        if (info == null) {
            out += "APK" to "illisible (archive endommagée ?)"
            return
        }
        out += "Application" to info.label
        out += "Paquet" to info.packageName
        out += "Version" to "${info.versionName} (${info.versionCode})"
        if (info.minSdk > 0) out += "Android min." to "API ${info.minSdk}"
        out += "Installée" to (info.installedVersion?.let { "oui, version $it" } ?: "non")
    }

    private fun archive(file: File, out: MutableList<Pair<String, String>>) {
        try {
            val s = Archives.summarize(file)
            out += "Archive" to s.format.label
            s.files?.let { out += "Contient" to "${Formatters.count(it)} fichier(s)" }
            s.totalBytes?.let { out += "Une fois extrait" to Formatters.size(it) }
            if (s.needsPassword) out += "Mot de passe" to "requis"
            if (s.volumes > 1) out += "Volumes" to "${s.volumes} fichiers"
        } catch (e: Exception) {
            out += "Archive" to Archives.describe(e)
        }
    }

    private fun pdf(file: File, out: MutableList<Pair<String, String>>) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { out += "Pages" to Formatters.count(it.pageCount) }
            }
        } catch (e: SecurityException) {
            out += "PDF" to "protégé par mot de passe"
        } catch (e: Exception) {
            out += "PDF" to "illisible"
        }
    }

    private fun text(file: File, out: MutableList<Pair<String, String>>) {
        if (file.length() > TEXT_LINES_LIMIT) return
        runCatching {
            var lines = 0L
            var last = '\n'.code.toByte()
            val buf = ByteArray(256 * 1024)
            FileInputStream(file).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) lines++
                    if (n > 0) last = buf[n - 1]
                }
            }
            if (file.length() > 0 && last != '\n'.code.toByte()) lines++
            out += "Lignes" to Formatters.count(lines)
        }
    }

    /** « 3:25 », « 1:02:03 ». */
    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(java.util.Locale.FRANCE, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(java.util.Locale.FRANCE, "%d:%02d", s / 60, s % 60)
    }
}
