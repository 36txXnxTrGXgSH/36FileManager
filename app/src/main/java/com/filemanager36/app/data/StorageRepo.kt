package com.filemanager36.app.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * Découverte des volumes de stockage (interne, carte SD, clé USB / OTG…)
 * et listage trié du contenu d'un dossier.
 */
object StorageRepo {

    /** Volumes montés et accessibles. */
    fun volumes(context: Context): List<StorageVolumeInfo> {
        val out = LinkedHashMap<String, StorageVolumeInfo>()

        // 1. Stockage interne partagé
        val internal = Environment.getExternalStorageDirectory()
        if (internal != null && internal.exists()) {
            out[internal.absolutePath] = buildInfo("Stockage interne", internal, removable = false, usb = false)
        }

        // 2. Volumes rapportés par le système (SD, USB OTG…)
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (sm != null) {
            for (v in sm.storageVolumes) {
                val dir = volumeDirectory(v) ?: continue
                if (!dir.exists() || !dir.canRead()) continue
                if (out.containsKey(dir.absolutePath)) continue
                val desc = runCatching { v.getDescription(context) }.getOrNull()
                val usb = looksLikeUsb(dir.absolutePath, desc)
                val label = when {
                    !desc.isNullOrBlank() -> desc
                    usb -> "Clé USB"
                    v.isRemovable -> "Carte SD"
                    else -> dir.name
                }
                out[dir.absolutePath] = buildInfo(label, dir, v.isRemovable, usb)
            }
        }

        // 3. Repli : balayage direct des points de montage classiques
        for (root in listOf("/storage", "/mnt/media_rw", "/mnt/usb")) {
            val f = File(root)
            val children = f.listFiles() ?: continue
            for (c in children) {
                val name = c.name
                if (name == "self" || name == "emulated" || name == "container") continue
                if (!c.isDirectory || !c.canRead()) continue
                if (out.containsKey(c.absolutePath)) continue
                val usb = looksLikeUsb(c.absolutePath, null)
                out[c.absolutePath] = buildInfo(
                    if (usb) "Clé USB ($name)" else "Carte SD ($name)",
                    c, removable = true, usb = usb
                )
            }
        }

        // 4. Racine du système : lecture seule, mais permet d'atteindre /sdcard, /mnt,
        // /system… que certains fabricants exposent hors des volumes déclarés.
        val root = File("/")
        if (root.canRead()) {
            out["/"] = StorageVolumeInfo(
                label = "Racine du système (lecture seule)",
                path = "/",
                isRemovable = false,
                isUsb = false,
                totalBytes = 0L,
                freeBytes = 0L,
                readable = true,
                writable = false
            )
        }

        return out.values.toList()
    }

    private fun looksLikeUsb(path: String, description: String?): Boolean {
        val p = path.lowercase()
        val d = description?.lowercase().orEmpty()
        return p.contains("usb") || p.contains("otg") || d.contains("usb") || d.contains("otg")
    }

    private fun volumeDirectory(v: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            v.directory?.let { return it }
        }
        // Repli par réflexion pour les API < 30
        return runCatching {
            val m = StorageVolume::class.java.getDeclaredMethod("getPath")
            m.isAccessible = true
            File(m.invoke(v) as String)
        }.getOrNull()
    }

    private fun buildInfo(label: String, dir: File, removable: Boolean, usb: Boolean): StorageVolumeInfo {
        var total = 0L
        var free = 0L
        runCatching {
            val st = StatFs(dir.absolutePath)
            total = st.blockCountLong * st.blockSizeLong
            free = st.availableBlocksLong * st.blockSizeLong
        }
        return StorageVolumeInfo(
            label, dir.absolutePath, removable, usb, total, free,
            readable = dir.canRead(), writable = dir.canWrite()
        )
    }

    /** Liste le contenu d'un dossier, filtré et trié. */
    fun list(
        dir: File,
        showHidden: Boolean,
        sortField: SortField,
        ascending: Boolean,
        foldersFirst: Boolean
    ): List<FileItem> {
        val raw = dir.listFiles() ?: return emptyList()
        val items = raw.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { f ->
                val link = f.isDirectory && FileOperations.isSymlink(f)
                FileItem(
                    path = f.absolutePath,
                    name = f.name,
                    isDirectory = f.isDirectory,
                    size = if (f.isDirectory) 0L else f.length(),
                    lastModified = f.lastModified(),
                    isHidden = f.name.startsWith("."),
                    // Les liens symboliques (/sdcard, /storage/self…) ne sont pas comptés :
                    // leur cible est déjà listée ailleurs.
                    childCount = if (f.isDirectory && !link) (f.list()?.size ?: 0) else -1
                )
            }
            .toMutableList()

        val cmp = when (sortField) {
            SortField.NAME -> compareBy<FileItem> { it.name.lowercase() }
            SortField.SIZE -> compareBy<FileItem> { if (it.isDirectory) it.childCount.toLong() else it.size }
            SortField.DATE -> compareBy<FileItem> { it.lastModified }
        }
        var comparator = if (ascending) cmp else cmp.reversed()
        if (foldersFirst) {
            comparator = compareByDescending<FileItem> { it.isDirectory }.then(comparator)
        }
        items.sortWith(comparator)
        return items
    }

    /** Segments cliquables du chemin courant, pour le fil d'Ariane. */
    fun breadcrumbs(path: String, volumes: List<StorageVolumeInfo>): List<Pair<String, String>> {
        val vol = volumes.filter { path == it.path || path.startsWith(it.path + "/") }
            .maxByOrNull { it.path.length }
        val crumbs = mutableListOf<Pair<String, String>>()
        val base: String
        if (vol != null) {
            crumbs += vol.label to vol.path
            base = vol.path
        } else {
            crumbs += "/" to "/"
            base = ""
        }
        val rest = path.removePrefix(base).trim('/')
        if (rest.isNotEmpty()) {
            var acc = base
            for (seg in rest.split('/')) {
                acc = "$acc/$seg"
                crumbs += seg to acc
            }
        }
        return crumbs
    }
}
