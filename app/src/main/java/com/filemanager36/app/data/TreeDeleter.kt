package com.filemanager36.app.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Suppression d'arborescences, en parallèle et avec une vraie progression.
 *
 * ## Pourquoi en parallèle
 * Sur le stockage partagé d'Android (FUSE), chaque effacement est un aller-retour avec le
 * service qui tient l'index des médias : ~7 ms par élément, quelle que soit sa taille.
 * Mesuré sur une tablette Android 16 : 134 éléments/s un par un, 623/s à 8 en même temps.
 * Un dossier extrait de 77 000 éléments passe de 10 minutes à 2 — et, surtout, la
 * progression s'affiche : l'ancienne suppression tournait sans rien dire.
 *
 * ## En trois temps
 * 1. **Inventaire**, en parallèle : chaque dossier est listé par son propre fil.
 * 2. **Fichiers**, en parallèle, avec la progression exacte « 12 000 / 77 000 ».
 * 3. **Dossiers**, du plus profond au moins profond — un dossier ne s'efface que vide.
 *
 * ## Liens symboliques : jamais suivis
 * Le type de chaque élément est lu **sans suivre les liens** (`NOFOLLOW_LINKS`) : un lien
 * vers un dossier est effacé comme un fichier — le lien disparaît, sa cible reste
 * intacte. C'est ce qui empêche d'effacer tout le stockage en supprimant `/sdcard`.
 * **Ne jamais remplacer par une lecture qui suit les liens** (`File.isDirectory`).
 *
 * Aucune dépendance Android : couvert par des tests JVM (`TreeDeleterTest`).
 */
object TreeDeleter {

    /** 8 : mesuré plus rapide que 4 (448/s) et que 16 (535/s) sur la même tablette. */
    private const val THREADS = 8

    /** Garde-fou contre une arborescence démesurée ; au-delà, le dossier reste en place. */
    private const val MAX_DEPTH = 256

    class Outcome(
        /** Éléments effacés (fichiers et dossiers). */
        val deleted: Int,
        /** Éléments trouvés à l'inventaire. */
        val total: Int,
        /** Racines encore présentes : suppression refusée, ou interrompue. */
        val remaining: List<File>,
        val cancelled: Boolean
    )

    /**
     * Supprime [roots] et tout leur contenu.
     *
     * @param onProgress pendant l'inventaire : ("", trouvés, 0) ; ensuite : (nom, effacés, total).
     */
    fun delete(
        roots: List<File>,
        isCancelled: () -> Boolean = { false },
        onProgress: (name: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Outcome {
        val files = ConcurrentLinkedQueue<Path>()
        val dirsByDepth = ConcurrentHashMap<Int, ConcurrentLinkedQueue<Path>>()
        val found = AtomicInteger()
        val lastReport = AtomicLong()

        fun report(name: String, done: Int, total: Int, force: Boolean = false) {
            val now = System.nanoTime()
            val last = lastReport.get()
            if (!force && (now - last < 50_000_000L || !lastReport.compareAndSet(last, now))) return
            onProgress(name, done, total)
        }

        val pool = ForkJoinPool(THREADS)
        try {
            // 1. Inventaire.
            val scans = roots.map { root ->
                pool.submit(Runnable {
                    val p = root.toPath()
                    val attrs = attributes(p) ?: return@Runnable
                    if (attrs.isDirectory) {
                        scanDir(p, 0, files, dirsByDepth, found, isCancelled) { report("", found.get(), 0) }
                    } else {
                        files.add(p)
                        found.incrementAndGet()
                    }
                })
            }
            scans.forEach { join(it) }
            val total = found.get()
            if (isCancelled()) return Outcome(0, total, roots.filter { exists(it) }, cancelled = true)

            // 2. Fichiers (et liens), tous en parallèle.
            val deleted = AtomicInteger()
            val step: (Path) -> Unit = { p ->
                if (p.toFile().delete()) {
                    val d = deleted.incrementAndGet()
                    report(p.fileName?.toString().orEmpty(), d, total)
                }
            }
            runAll(pool, files.toList(), isCancelled, step)

            // 3. Dossiers, du plus profond au moins profond.
            for (depth in dirsByDepth.keys.sortedDescending()) {
                if (isCancelled()) break
                runAll(pool, dirsByDepth[depth]!!.toList(), isCancelled, step)
            }
            report("", deleted.get(), total, force = true)
            return Outcome(deleted.get(), total, roots.filter { exists(it) }, cancelled = isCancelled())
        } finally {
            pool.shutdown()
        }
    }

    /** Un élément est-il un vrai dossier (et non un lien vers un dossier) ? */
    fun isRealDirectory(file: File): Boolean = attributes(file.toPath())?.isDirectory == true

    private fun attributes(p: Path): BasicFileAttributes? = try {
        Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun scanDir(
        dir: Path,
        depth: Int,
        files: ConcurrentLinkedQueue<Path>,
        dirsByDepth: ConcurrentHashMap<Int, ConcurrentLinkedQueue<Path>>,
        found: AtomicInteger,
        isCancelled: () -> Boolean,
        tick: () -> Unit
    ) {
        dirsByDepth.computeIfAbsent(depth) { ConcurrentLinkedQueue() }.add(dir)
        found.incrementAndGet()
        if (isCancelled()) return
        if (depth >= MAX_DEPTH) return
        val subdirs = ArrayList<ForkJoinTask<*>>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    val attrs = attributes(child) ?: continue
                    if (attrs.isDirectory) {
                        subdirs += ForkJoinTask.adapt {
                            scanDir(child, depth + 1, files, dirsByDepth, found, isCancelled, tick)
                        }.fork()
                    } else {
                        files.add(child)
                        found.incrementAndGet()
                    }
                }
            }
        } catch (e: IOException) {
            // Dossier illisible : il restera, et la suppression le dira.
        } catch (e: SecurityException) {
        }
        tick()
        subdirs.forEach { it.join() }
    }

    /** Applique [action] à toute la liste avec tous les fils du réservoir. */
    private fun runAll(pool: ForkJoinPool, items: List<Path>, isCancelled: () -> Boolean, action: (Path) -> Unit) {
        if (items.isEmpty()) return
        val next = AtomicInteger()
        val workers = (1..minOf(THREADS, items.size)).map {
            pool.submit(Runnable {
                while (!isCancelled()) {
                    val i = next.getAndIncrement()
                    if (i >= items.size) break
                    action(items[i])
                }
            })
        }
        workers.forEach { join(it) }
    }

    private fun join(task: ForkJoinTask<*>) {
        try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
