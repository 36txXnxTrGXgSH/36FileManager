package com.filemanager36.app.data

import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
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

    /**
     * Copier ou déplacer, sans progression : pour le partage web. L'interface passe
     * directement par [TreeCopier] pour la barre, la vitesse et l'annulation.
     */
    fun copyOrMove(
        sources: List<File>,
        destDir: File,
        move: Boolean,
        overwrite: Boolean = false
    ): OperationResult {
        val outcome = TreeCopier.run(sources, destDir, move, overwrite)
        return OperationResult(outcome.succeeded, outcome.failed)
    }

    // ------------------------------------------------------------------
    // Supprimer
    // ------------------------------------------------------------------

    /**
     * Supprime un fichier, ou un dossier et tout son contenu — en parallèle, voir
     * [TreeDeleter]. Sur un lien symbolique on efface le lien, jamais sa cible.
     */
    fun deleteRecursive(file: File): Boolean {
        // Fichier ou lien : un seul effacement, inutile de mettre en route les fils.
        if (!TreeDeleter.isRealDirectory(file)) return file.delete()
        return TreeDeleter.delete(listOf(file)).remaining.isEmpty()
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

    // L'extraction d'archives vit dans [Archives] depuis la 2.6.0 : un seul chemin,
    // pour tous les formats, avec les mêmes garde-fous (chemins, liens, place, mémoire).

    // ------------------------------------------------------------------
    // Informations
    // ------------------------------------------------------------------

    // La fiche « Informations » est construite par [FileDetailsReader].

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

}
