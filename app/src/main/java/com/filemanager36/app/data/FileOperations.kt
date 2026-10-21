package com.filemanager36.app.data

import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Opérations sur les fichiers et les dossiers : copier, couper (déplacer),
 * supprimer, renommer, compresser (ZIP), extraire, informations.
 *
 * Toutes ces fonctions sont bloquantes : elles doivent être appelées depuis
 * un contexte d'exécution en arrière-plan (Dispatchers.IO).
 */
object FileOperations {

    class OperationResult(
        val succeeded: Int,
        val failed: List<String>
    ) {
        val hasError: Boolean get() = failed.isNotEmpty()
    }

    // ------------------------------------------------------------------
    // Garde-fous de récursion
    // ------------------------------------------------------------------

    private const val MAX_DEPTH = 32

    /**
     * Vrai si le chemin est un lien symbolique.
     *
     * Indispensable : « / » expose /sdcard, qui pointe vers le stockage interne, et
     * /proc/<pid>/root, qui pointe vers « / ». Suivre ces liens ferait supprimer tout
     * le stockage en croyant effacer un dossier, ou boucler jusqu'au StackOverflow.
     */
    fun isSymlink(file: File): Boolean =
        runCatching { file.canonicalFile.absolutePath != file.absoluteFile.absolutePath }
            .getOrDefault(true)

    private fun traversable(file: File, depth: Int): Boolean =
        file.isDirectory && depth < MAX_DEPTH && !isSymlink(file)

    // ------------------------------------------------------------------
    // Comptage / progression
    // ------------------------------------------------------------------

    fun countEntries(file: File, depth: Int = 0): Int {
        if (!traversable(file, depth)) return 1
        var n = 1
        file.listFiles()?.forEach { n += countEntries(it, depth + 1) }
        return n
    }

    fun computeSize(file: File, depth: Int = 0): Long {
        if (!traversable(file, depth)) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += computeSize(it, depth + 1) }
        return total
    }

    // ------------------------------------------------------------------
    // Copier / déplacer
    // ------------------------------------------------------------------

    /** Évite d'écraser : « photo.jpg » → « photo (1).jpg ». */
    fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists()) {
            val newName = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            candidate = File(dir, newName)
            i++
        }
        return candidate
    }

    private fun isDescendant(parent: File, child: File): Boolean {
        val p = parent.canonicalPath
        val c = child.canonicalPath
        return c == p || c.startsWith("$p${File.separator}")
    }

    fun copyOrMove(
        sources: List<File>,
        destDir: File,
        move: Boolean,
        overwrite: Boolean = false,
        onProgress: (name: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ): OperationResult {
        val total = sources.sumOf { countEntries(it) }
        var done = 0
        val failed = mutableListOf<String>()
        var ok = 0

        if (!destDir.exists()) destDir.mkdirs()

        for (src in sources) {
            if (!src.exists()) { failed += src.name; continue }
            val sameFolder = runCatching {
                src.parentFile?.canonicalFile == destDir.canonicalFile
            }.getOrDefault(false)
            if (move && sameFolder) {
                // Déplacer un élément dans son propre dossier n'a aucun effet :
                // sans ce garde-fou, l'anti-écrasement le renommerait en « nom (1) ».
                ok++
                done += countEntries(src)
                onProgress(src.name, done, total)
                continue
            }
            val inside = runCatching { src.isDirectory && isDescendant(src, destDir) }.getOrDefault(false)
            if (inside) {
                failed += "${src.name} (destination à l'intérieur de la source)"
                continue
            }
            val target = if (overwrite) File(destDir, src.name) else uniqueTarget(destDir, src.name)

            // Déplacement rapide sur le même volume
            if (move && runCatching { src.renameTo(target) }.getOrDefault(false)) {
                done += countEntries(target)
                onProgress(src.name, done, total)
                ok++
                continue
            }

            val success = try {
                copyRecursive(src, target, { name ->
                    done++
                    onProgress(name, done, total)
                })
                true
            } catch (e: Exception) {
                failed += "${src.name} : ${e.message ?: "erreur"}"
                false
            }

            if (success) {
                ok++
                if (move) deleteRecursive(src)
            }
        }
        return OperationResult(ok, failed)
    }

    private fun copyRecursive(src: File, dst: File, tick: (String) -> Unit, depth: Int = 0) {
        if (traversable(src, depth)) {
            if (!dst.exists() && !dst.mkdirs()) throw IllegalStateException("Création impossible : ${dst.name}")
            tick(src.name)
            src.listFiles()?.forEach { child -> copyRecursive(child, File(dst, child.name), tick, depth + 1) }
        } else {
            dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER)
                }
            }
            dst.setLastModified(src.lastModified())
            tick(src.name)
        }
    }

    // ------------------------------------------------------------------
    // Supprimer
    // ------------------------------------------------------------------

    fun deleteRecursive(file: File, depth: Int = 0): Boolean {
        // Sur un lien symbolique on efface le lien, jamais sa cible.
        if (traversable(file, depth)) {
            file.listFiles()?.forEach { deleteRecursive(it, depth + 1) }
        }
        return file.delete()
    }

    fun deleteAll(
        files: List<File>,
        onProgress: (name: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ): OperationResult {
        val total = files.size
        var done = 0
        val failed = mutableListOf<String>()
        var ok = 0
        for (f in files) {
            if (deleteRecursive(f)) ok++ else failed += f.name
            done++
            onProgress(f.name, done, total)
        }
        return OperationResult(ok, failed)
    }

    // ------------------------------------------------------------------
    // Renommer
    // ------------------------------------------------------------------

    fun rename(file: File, newName: String): Result<File> {
        val clean = newName.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Nom vide"))
        if (clean.contains('/')) return Result.failure(IllegalArgumentException("Le nom ne peut pas contenir « / »"))
        val target = File(file.parentFile, clean)
        if (target.exists()) return Result.failure(IllegalStateException("« $clean » existe déjà"))
        return if (file.renameTo(target)) Result.success(target)
        else Result.failure(IllegalStateException("Renommage impossible"))
    }

    // ------------------------------------------------------------------
    // Compression ZIP
    // ------------------------------------------------------------------

    fun zip(
        sources: List<File>,
        destZip: File,
        onProgress: (name: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<File> = runCatching {
        val total = sources.sumOf { countEntries(it) }
        var done = 0
        ZipOutputStream(FileOutputStream(destZip).buffered()).use { zos ->
            for (src in sources) {
                addToZip(zos, src, src.name, { name ->
                    done++
                    onProgress(name, done, total)
                })
            }
        }
        destZip
    }

    private fun addToZip(
        zos: ZipOutputStream,
        file: File,
        entryName: String,
        tick: (String) -> Unit,
        depth: Int = 0
    ) {
        if (traversable(file, depth)) {
            val dirEntry = if (entryName.endsWith("/")) entryName else "$entryName/"
            zos.putNextEntry(ZipEntry(dirEntry))
            zos.closeEntry()
            tick(file.name)
            file.listFiles()?.forEach { addToZip(zos, it, "$entryName/${it.name}", tick, depth + 1) }
        } else {
            zos.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zos, DEFAULT_BUFFER) }
            zos.closeEntry()
            tick(file.name)
        }
    }

    /**
     * Extrait une archive ZIP.
     *
     * Trois garde-fous, tous nécessaires :
     *  - **Zip Slip** : une entrée nommée `../../secret` sortirait du dossier de
     *    destination. Le séparateur dans la comparaison est indispensable, sinon
     *    « ../dossier2/x » passerait le test face à « …/dossier ».
     *  - **Bombe de décompression** : rien n'obligeait une archive à être raisonnable.
     *    42 Kio de zéros compressés donnent 4 Gio ; l'ancienne version remplissait le
     *    stockage jusqu'à l'échec du système. On plafonne le volume produit, le nombre
     *    d'entrées et le taux de compression.
     *  - **Espace disponible** : on refuse d'entamer une extraction qui ne tient pas.
     */
    fun unzip(
        zipFile: File,
        destDir: File,
        onProgress: (name: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<File> = runCatching {
        if (!destDir.exists()) destDir.mkdirs()
        val canonicalDest = destDir.canonicalPath
        val budget = availableBytes(destDir)
        var done = 0
        var written = 0L
        ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (done >= MAX_ZIP_ENTRIES) {
                    throw SecurityException(
                        "Archive refusée : plus de $MAX_ZIP_ENTRIES fichiers"
                    )
                }
                val out = File(destDir, entry.name)
                val canonicalOut = out.canonicalPath
                if (canonicalOut != canonicalDest &&
                    !canonicalOut.startsWith(canonicalDest + File.separator)
                ) {
                    throw SecurityException("Entrée d'archive invalide : ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    // Copie bornée : la taille annoncée dans l'en-tête d'une entrée est
                    // déclarative et peut mentir, on compte donc les octets réellement
                    // écrits plutôt que de faire confiance à `entry.size`.
                    written += copyBounded(zis, out, MAX_UNZIP_BYTES - written, budget - written)
                }
                done++
                onProgress(entry.name, done, 0)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        destDir
    }

    /** Octets libres sur le volume qui accueille [dir], ou [Long.MAX_VALUE] si inconnu. */
    private fun availableBytes(dir: File): Long = runCatching {
        val st = android.os.StatFs(dir.absolutePath)
        st.availableBlocksLong * st.blockSizeLong
    }.getOrDefault(Long.MAX_VALUE)

    /**
     * Copie [input] vers [target] en s'arrêtant net au premier plafond franchi, et en
     * effaçant le fichier partiel : mieux vaut une extraction refusée qu'un stockage
     * saturé et un fichier tronqué qui a l'air complet.
     */
    private fun copyBounded(input: java.io.InputStream, target: File, vararg limits: Long): Long {
        val limit = limits.min().coerceAtLeast(0L)
        var count = 0L
        val buffer = ByteArray(DEFAULT_BUFFER)
        try {
            FileOutputStream(target).use { out ->
                while (true) {
                    val r = input.read(buffer)
                    if (r <= 0) break
                    count += r
                    if (count > limit) {
                        throw SecurityException(
                            "Archive refusée : elle produirait plus que l'espace disponible " +
                                "ou que la limite de ${MAX_UNZIP_BYTES / (1024 * 1024 * 1024)} Go"
                        )
                    }
                    out.write(buffer, 0, r)
                }
            }
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
        return count
    }

    // ------------------------------------------------------------------
    // Informations
    // ------------------------------------------------------------------

    fun details(file: File): FileDetails {
        var files = 0
        var folders = 0
        var size = 0L
        if (file.isDirectory && !isSymlink(file)) {
            fun walk(f: File, depth: Int) {
                if (depth >= MAX_DEPTH) return
                f.listFiles()?.forEach { c ->
                    if (c.isDirectory) {
                        folders++
                        if (!isSymlink(c)) walk(c, depth + 1)
                    } else {
                        files++
                        size += c.length()
                    }
                }
            }
            walk(file, 0)
        } else {
            files = 1
            size = file.length()
        }
        return FileDetails(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            sizeBytes = size,
            fileCount = files,
            folderCount = folders,
            lastModified = file.lastModified(),
            mimeType = mimeOf(file),
            readable = file.canRead(),
            writable = file.canWrite(),
            hidden = file.name.startsWith(".")
        )
    }

    fun mimeOf(file: File): String {
        if (file.isDirectory) return "dossier"
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun newFolder(parent: File, name: String): Result<File> {
        val clean = name.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Nom vide"))
        val f = File(parent, clean)
        if (f.exists()) return Result.failure(IllegalStateException("« $clean » existe déjà"))
        return if (f.mkdirs()) Result.success(f) else Result.failure(IllegalStateException("Création impossible"))
    }

    private const val DEFAULT_BUFFER = 128 * 1024

    /** Plafonds d'extraction : voir le KDoc de [unzip]. */
    private const val MAX_UNZIP_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 100_000
}
