package com.filemanager36.app.data

import kotlinx.serialization.Serializable
import java.io.File

/** Un élément du système de fichiers local (fichier ou dossier). */
data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean,
    val childCount: Int = -1
) {
    val file: File get() = File(path)
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    companion object {
        fun from(f: File): FileItem = FileItem(
            path = f.absolutePath,
            name = f.name,
            isDirectory = f.isDirectory,
            size = if (f.isDirectory) 0L else f.length(),
            lastModified = f.lastModified(),
            isHidden = f.name.startsWith(".")
        )
    }
}

enum class SortField(val label: String) {
    NAME("Nom"),
    SIZE("Taille"),
    DATE("Date d'ajout")
}

enum class ViewMode { LIST, GRID }

/** Un volume de stockage : interne, carte SD, clé USB… */
data class StorageVolumeInfo(
    val label: String,
    val path: String,
    val isRemovable: Boolean,
    val isUsb: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
    val readable: Boolean = true,
    val writable: Boolean = true
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

/** Métadonnées d'un favori relevées en arrière-plan (existence, taille, date). */
data class FavStat(
    val exists: Boolean,
    val size: Long,
    val lastModified: Long
)

/** Raccourci favori : le fichier n'est ni copié ni déplacé. */
@Serializable
data class FavoriteEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val addedAt: Long = 0L
)

/** Système d'exploitation de la machine qui héberge le partage. */
enum class ServerOs(val label: String) {
    WINDOWS("Windows"),
    LINUX("Linux"),
    ANDROID("Android"),
    NAS("NAS / autre")
}

/** Serveur SMB / lecteur réseau LAN enregistré. */
@Serializable
data class SmbServer(
    val id: String,
    val label: String,
    val host: String,
    /** Dernier partage ouvert, rouvert automatiquement à la reconnexion. Vide = liste. */
    val share: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val port: Int = 445,
    val os: String = ServerOs.WINDOWS.name
) {
    val osEnum: ServerOs
        get() = runCatching { ServerOs.valueOf(os) }.getOrDefault(ServerOs.WINDOWS)
}

/**
 * Dossier cloud connecté via le système de documents d'Android (SAF) :
 * Google Drive, OneDrive, Dropbox, Nextcloud… selon les applications installées.
 */
@Serializable
data class CloudRoot(
    val id: String,
    val label: String,
    val treeUri: String,
    val addedAt: Long = 0L
)

/** Élément d'un dossier cloud (SAF). */
data class CloudItem(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

/**
 * Entrée du coffre-fort chiffré.
 *
 * [format] indique la disposition du fichier chiffré correspondant :
 *  - **1** — historique, écrit jusqu'à la 2.0.2 : `IV(16) || AES-256-CBC(contenu)`,
 *    sans authentification ;
 *  - **2** — depuis la 2.1.0 : `IV(16) || AES-256-CBC(contenu) || HMAC-SHA256(32)`.
 *
 * Le champ a une valeur par défaut : une entrée écrite par une version antérieure ne le
 * porte pas et se relit donc en format 1, sans que l'utilisateur ait à restaurer quoi que
 * ce soit. **Les coffres existants continuent de s'ouvrir normalement.**
 */
@Serializable
data class VaultEntry(
    val id: String,
    val name: String,
    val originalPath: String,
    val size: Long,
    val addedAt: Long,
    val isDirectory: Boolean = false,
    val format: Int = FORMAT_LEGACY
) {
    companion object {
        const val FORMAT_LEGACY = 1
        const val FORMAT_AUTHENTICATED = 2
    }
}

/** Presse-papiers interne (copier / couper). */
data class Clipboard(
    val paths: List<String> = emptyList(),
    val isMove: Boolean = false
) {
    val isEmpty: Boolean get() = paths.isEmpty()
}

/** Progression d'une opération longue. */
data class OpProgress(
    val title: String,
    val currentName: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val indeterminate: Boolean = false
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** Détails affichés dans la fiche « Informations ». */
data class FileDetails(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val fileCount: Int,
    val folderCount: Int,
    val lastModified: Long,
    val mimeType: String,
    val readable: Boolean,
    val writable: Boolean,
    val hidden: Boolean
)

/** Dossier partagé publié par une machine du réseau. */
data class SmbShare(
    val name: String,
    val comment: String,
    val isAdmin: Boolean
)

/** Élément distant SMB. */
data class RemoteItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)
