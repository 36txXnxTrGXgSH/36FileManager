package com.filemanager36.app.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.filemanager36.app.util.FileKind
import com.filemanager36.app.util.FileKinds
import java.io.File

/** Catégories transversales affichées sur l'accueil. */
enum class MediaCategory(val label: String) {
    IMAGES("Images"),
    VIDEOS("Vidéos"),
    MUSIC("Musique"),
    DOCUMENTS("Documents")
}

/** Dossier regroupant les fichiers d'une catégorie, pour le mode « par dossier ». */
data class CategoryFolder(
    val path: String,
    val name: String,
    val count: Int,
    val totalSize: Long,
    val lastModified: Long,
    val sample: FileItem?
)

/**
 * Regroupe tous les fichiers d'un même type, où qu'ils se trouvent sur l'appareil.
 *
 * Deux sources complémentaires :
 *  1. l'index multimédia d'Android (MediaStore), rapide et couvrant tous les volumes ;
 *  2. un parcours direct du système de fichiers, qui rattrape ce que l'index ignore
 *     (documents jamais scannés, fichiers déposés par un ordinateur, cartes SD
 *     fraîchement insérées).
 *
 * Les deux jeux sont fusionnés par chemin absolu, donc aucun doublon.
 * Fonction bloquante : à appeler depuis Dispatchers.IO.
 */
object MediaRepository {

    /** Dossiers sans intérêt pour l'utilisateur, ou inaccessibles depuis Android 11. */
    private val skippedDirs = setOf(
        "Android", ".thumbnails", ".trash", ".trashed", "cache", ".cache",
        "LOST.DIR", ".android_secure"
    )

    /** Chemins système sans fichiers utilisateur, dont le parcours coûterait cher. */
    private val skippedRoots = setOf(
        "/proc", "/sys", "/dev", "/data", "/system", "/vendor", "/apex",
        "/product", "/config", "/acct", "/oem", "/odm"
    )

    private const val MAX_DEPTH = 12

    fun scan(
        context: Context,
        roots: List<String>,
        showHidden: Boolean,
        isActive: () -> Boolean = { true }
    ): Map<MediaCategory, List<FileItem>> {
        val found = linkedMapOf<String, FileItem>()

        queryMediaStore(context, showHidden, found, isActive)
        // Les racines déjà couvertes par une autre sont ignorées : sans cela, le lien
        // /sdcard ferait reparcourir tout le stockage interne.
        val visited = mutableSetOf<String>()
        for (root in roots) {
            val canonical = runCatching { File(root).canonicalPath }.getOrNull() ?: continue
            if (canonical == "/" || skippedRoots.any { canonical == it || canonical.startsWith("$it/") }) continue
            if (visited.any { canonical == it || canonical.startsWith("$it/") }) continue
            visited += canonical
            walk(File(canonical), 0, showHidden, found, isActive)
        }

        val result = MediaCategory.entries.associateWith { mutableListOf<FileItem>() }
        for (item in found.values) {
            categoryOf(item.name)?.let { result[it]?.add(item) }
        }
        return result.mapValues { it.value.toList() }
    }

    fun categoryOf(name: String): MediaCategory? = when (FileKinds.of(name, false)) {
        FileKind.IMAGE -> MediaCategory.IMAGES
        FileKind.VIDEO -> MediaCategory.VIDEOS
        FileKind.AUDIO -> MediaCategory.MUSIC
        FileKind.PDF, FileKind.DOC, FileKind.SHEET -> MediaCategory.DOCUMENTS
        else -> null
    }

    // ------------------------------------------------------------------
    // 1. Index multimédia
    // ------------------------------------------------------------------

    /** Types de documents demandés directement à l'index, plutôt que filtrés après coup. */
    private val documentMimes = listOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/rtf",
        "application/epub+zip",
        "text/plain",
        "text/csv",
        "text/markdown"
    )

    private fun queryMediaStore(
        context: Context,
        showHidden: Boolean,
        out: MutableMap<String, FileItem>,
        isActive: () -> Boolean
    ) {
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getExternalVolumeNames(context).toList() }
                .getOrDefault(listOf(MediaStore.VOLUME_EXTERNAL))
        } else {
            listOf("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        // Restreindre la requête évite de parcourir toute la table (audio, APK, caches…)
        // : sur un téléphone bien rempli, cela représente la majeure partie de l'attente.
        val selection = buildString {
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ")
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ")
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ")
            append("${MediaStore.Files.FileColumns.MIME_TYPE} IN (")
            append(documentMimes.joinToString(",") { "?" })
            append(")")
        }
        val args = (
            listOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()
            ) + documentMimes
            ).toTypedArray()

        for (volume in volumes) {
            if (!isActive()) return
            val uri = runCatching { MediaStore.Files.getContentUri(volume) }.getOrNull() ?: continue
            runCatching {
                context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                    val iData = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                    val iName = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                    val iDate = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    if (iData < 0) return@use
                    while (c.moveToNext()) {
                        if (!isActive()) return
                        val path = c.getString(iData) ?: continue
                        val name = (if (iName >= 0) c.getString(iName) else null)
                            ?: path.substringAfterLast('/')
                        if (categoryOf(name) == null) continue
                        if (!accept(path, name, showHidden)) continue
                        if (out.containsKey(path)) continue
                        // DATE_MODIFIED est en secondes ; SIZE peut être nul sur certaines ROM.
                        val size = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else 0L
                        val date = if (iDate >= 0 && !c.isNull(iDate)) c.getLong(iDate) * 1000L else 0L
                        out[path] = FileItem(
                            path = path,
                            name = name,
                            isDirectory = false,
                            size = size,
                            lastModified = date,
                            isHidden = name.startsWith(".")
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. Parcours du système de fichiers
    // ------------------------------------------------------------------

    private fun walk(
        dir: File,
        depth: Int,
        showHidden: Boolean,
        out: MutableMap<String, FileItem>,
        isActive: () -> Boolean
    ) {
        if (depth > MAX_DEPTH || !isActive()) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            val name = child.name
            if (child.isDirectory) {
                if (skippedDirs.contains(name)) continue
                if (!showHidden && name.startsWith(".")) continue
                // Un lien symbolique renvoyant vers un parent créerait une boucle infinie.
                val canonical = runCatching { child.canonicalPath }.getOrNull() ?: continue
                if (canonical != child.absolutePath) continue
                walk(child, depth + 1, showHidden, out, isActive)
            } else {
                if (categoryOf(name) == null) continue
                if (!showHidden && name.startsWith(".")) continue
                val path = child.absolutePath
                if (out.containsKey(path)) continue
                out[path] = FileItem(
                    path = path,
                    name = name,
                    isDirectory = false,
                    size = child.length(),
                    lastModified = child.lastModified(),
                    isHidden = name.startsWith(".")
                )
            }
        }
    }

    // Pas de File.exists() ici : ce serait un appel système par ligne d'index.
    // Le parcours du système de fichiers vérifie de toute façon les fichiers réels,
    // et une entrée d'index périmée est sans conséquence.
    private fun accept(path: String, name: String, showHidden: Boolean): Boolean {
        if (!showHidden && name.startsWith(".")) return false
        if (path.contains("/.thumbnails/")) return false
        if (path.contains("/Android/data/") || path.contains("/Android/obb/")) return false
        return true
    }

    /** Regroupe les fichiers d'une catégorie par dossier d'origine. */
    fun foldersOf(items: List<FileItem>, field: SortField, ascending: Boolean): List<CategoryFolder> {
        val grouped = items.groupBy { it.path.substringBeforeLast('/', "/") }
        val folders = grouped.map { (path, files) ->
            CategoryFolder(
                path = path,
                name = path.substringAfterLast('/').ifBlank { path },
                count = files.size,
                totalSize = files.sumOf { it.size },
                lastModified = files.maxOfOrNull { it.lastModified } ?: 0L,
                // Vignette du dossier : le fichier le plus récent qu'il contient.
                sample = files.maxByOrNull { it.lastModified }
            )
        }
        val cmp = when (field) {
            SortField.NAME -> compareBy<CategoryFolder> { it.name.lowercase() }
            SortField.SIZE -> compareBy<CategoryFolder> { it.totalSize }
            SortField.DATE -> compareBy<CategoryFolder> { it.lastModified }
        }
        return folders.sortedWith(if (ascending) cmp else cmp.reversed())
    }

    /** Applique le tri courant à une catégorie. */
    fun sort(items: List<FileItem>, field: SortField, ascending: Boolean): List<FileItem> {
        val cmp = when (field) {
            SortField.NAME -> compareBy<FileItem> { it.name.lowercase() }
            SortField.SIZE -> compareBy<FileItem> { it.size }
            SortField.DATE -> compareBy<FileItem> { it.lastModified }
        }
        return items.sortedWith(if (ascending) cmp else cmp.reversed())
    }
}
