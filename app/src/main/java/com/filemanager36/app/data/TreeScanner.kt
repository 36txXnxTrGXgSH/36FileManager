package com.filemanager36.app.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Parcours d'arborescence **en parallèle**, sans jamais suivre les liens.
 *
 * ## Pourquoi
 * Sur le stockage partagé d'Android (FUSE), lire les attributs d'un élément coûte un
 * aller-retour avec le service des médias. L'ancien calcul de « Informations » lisait
 * chaque élément quatre fois (`isDirectory`, `canonicalFile` pour détecter les liens,
 * `length`…), un par un : un dossier extrait de 245 000 éléments prenait plusieurs
 * minutes, pendant lesquelles rien ne s'affichait — « il ne se passe rien ».
 * Ici : une seule lecture par élément (`readAttributes`, sans suivre les liens), et un
 * fil par dossier, jusqu'à 8 à la fois.
 *
 * ## Liens symboliques : jamais suivis
 * Un lien vers un dossier est vu comme un élément isolé : on ne descend pas dedans.
 * C'est ce qui évite de compter deux fois le stockage via `/sdcard`, ou de boucler.
 *
 * Aucune dépendance Android : couvert par des tests JVM (`TreeCopierTest`).
 */
object TreeScanner {

    private const val THREADS = 8

    /** Garde-fou contre une arborescence démesurée ; au-delà, on ne descend plus. */
    const val MAX_DEPTH = 256

    /**
     * Ce que voit le parcours. Appelé **depuis plusieurs fils à la fois** : l'implémentation
     * doit être sûre en parallèle. [root] est l'indice de la racine dans la liste donnée,
     * [depth] vaut 0 pour la racine elle-même.
     */
    fun interface Visitor {
        fun visit(root: Int, path: Path, depth: Int, attrs: BasicFileAttributes)
    }

    /**
     * Parcourt [roots] et tout leur contenu. Un dossier est toujours visité avant son contenu.
     * @return pour chaque racine, le nombre de dossiers illisibles (droits, dossier disparu
     *   en route) — leur contenu n'a pas été vu.
     */
    fun walk(roots: List<File>, isCancelled: () -> Boolean = { false }, visitor: Visitor): IntArray {
        val unreadable = Array(roots.size) { AtomicInteger() }
        val pool = ForkJoinPool(THREADS)
        try {
            val tasks = roots.mapIndexed { index, root ->
                pool.submit(Runnable {
                    val p = root.toPath()
                    val attrs = attributes(p)
                    if (attrs == null) {
                        unreadable[index].incrementAndGet()
                        return@Runnable
                    }
                    visitor.visit(index, p, 0, attrs)
                    if (attrs.isDirectory) scan(index, p, 0, visitor, isCancelled, unreadable[index])
                })
            }
            tasks.forEach { join(it) }
        } finally {
            pool.shutdown()
        }
        return IntArray(roots.size) { unreadable[it].get() }
    }

    private fun scan(
        root: Int,
        dir: Path,
        depth: Int,
        visitor: Visitor,
        isCancelled: () -> Boolean,
        unreadable: AtomicInteger
    ) {
        if (isCancelled() || depth >= MAX_DEPTH) return
        val subdirs = ArrayList<ForkJoinTask<*>>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    if (isCancelled()) break
                    val attrs = attributes(child) ?: continue
                    visitor.visit(root, child, depth + 1, attrs)
                    if (attrs.isDirectory) {
                        subdirs += ForkJoinTask.adapt {
                            scan(root, child, depth + 1, visitor, isCancelled, unreadable)
                        }.fork()
                    }
                }
            }
        } catch (e: IOException) {
            unreadable.incrementAndGet()
        } catch (e: SecurityException) {
            unreadable.incrementAndGet()
        }
        subdirs.forEach { it.join() }
    }

    /** Totaux d'un ensemble d'éléments, pour la fiche « Informations ». */
    class Totals(
        /** Fichiers, liens compris. */
        val files: Long,
        /** Dossiers contenus (les racines sélectionnées ne comptent pas). */
        val folders: Long,
        val bytes: Long,
        val unreadable: Int,
        val cancelled: Boolean
    )

    /**
     * Compte fichiers, dossiers et octets sous [roots].
     * @param onProgress appelé au plus toutes les 150 ms, puis une dernière fois à la fin.
     */
    fun measure(
        roots: List<File>,
        isCancelled: () -> Boolean = { false },
        onProgress: (files: Long, folders: Long, bytes: Long) -> Unit = { _, _, _ -> }
    ): Totals {
        val files = AtomicLong()
        val folders = AtomicLong()
        val bytes = AtomicLong()
        val last = AtomicLong(System.nanoTime())
        val unreadable = walk(roots, isCancelled) { _, _, depth, attrs ->
            if (attrs.isDirectory) {
                if (depth > 0) folders.incrementAndGet()
            } else {
                files.incrementAndGet()
                if (attrs.isRegularFile) bytes.addAndGet(attrs.size())
            }
            val now = System.nanoTime()
            val t = last.get()
            if (now - t >= 150_000_000L && last.compareAndSet(t, now)) {
                onProgress(files.get(), folders.get(), bytes.get())
            }
        }
        onProgress(files.get(), folders.get(), bytes.get())
        return Totals(files.get(), folders.get(), bytes.get(), unreadable.sum(), isCancelled())
    }

    fun attributes(p: Path): BasicFileAttributes? = try {
        Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun join(task: ForkJoinTask<*>) {
        try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
