package com.filemanager36.app.data

import com.filemanager36.app.util.Formatters
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Copier et déplacer des fichiers et des dossiers, avec une progression exacte en octets,
 * le nombre de fichiers restants, et l'annulation.
 *
 * ## Déroulé
 * 1. **Renommage** (déplacement seulement) : sur le même volume, un dossier entier se
 *    déplace d'un coup, quelle que soit sa taille. Rien à copier.
 * 2. **Inventaire**, en parallèle ([TreeScanner]) : la liste des fichiers et le total
 *    d'octets, d'où la barre, la vitesse et le temps restant. La place libre est vérifiée
 *    avant d'écrire le moindre octet.
 * 3. **Copie**, à 4 fils : sur le stockage partagé d'Android, créer un fichier coûte
 *    ~45 ms quelle que soit sa taille ; à 4 en même temps, 6 fois plus de petits fichiers
 *    par seconde (mesures en § 9.7 ter du dossier de projet). Les dossiers sont créés au
 *    fil de l'eau, juste avant leur premier fichier.
 * 4. **Effacement des originaux** (déplacement), en parallèle ([TreeDeleter]) — et
 *    seulement pour un élément copié **sans aucune erreur**. Une copie incomplète ne
 *    coûte donc jamais l'original.
 *
 * ## Annulation
 * - Déplacement : les copies déjà faites sont retirées, les originaux n'ont pas bougé —
 *   comme si rien ne s'était passé.
 * - Copie : les fichiers terminés restent, seul le fichier en cours est retiré (jamais de
 *   fichier tronqué).
 *
 * Aucune dépendance Android : couvert par des tests JVM (`TreeCopierTest`).
 */
object TreeCopier {

    private const val WRITERS = 4
    private const val BUFFER = 256 * 1024

    /** Marge gardée libre sur la destination. */
    private const val SPACE_MARGIN = 16L shl 20

    enum class Phase { INVENTORY, COPY, CLEANUP }

    class Progress(
        val phase: Phase,
        val name: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        /** Fichiers traités (copiés, ou originaux effacés en phase [Phase.CLEANUP]). */
        val filesDone: Long,
        /** Fichiers à traiter ; pendant l'inventaire, éléments trouvés jusque-là. */
        val filesTotal: Long
    )

    class Outcome(
        /** Éléments sélectionnés entièrement copiés ou déplacés. */
        val succeeded: Int,
        val failed: List<String>,
        val cancelled: Boolean,
        /** Annulé pendant l'effacement des originaux : la copie, elle, est complète. */
        val cancelledDuringCleanup: Boolean,
        val filesCopied: Long,
        val filesTotal: Long,
        val bytesCopied: Long
    )

    private class Job(val src: File, val target: File, val createdTarget: Boolean) {
        val error = AtomicReference<String?>(null)
    }

    private enum class Kind { DIR, FILE, LINK, OTHER }

    private class Item(val job: Int, val src: Path, val dst: File, val kind: Kind, val size: Long, val modified: Long)

    private class Cancelled : RuntimeException()

    fun run(
        sources: List<File>,
        destDir: File,
        move: Boolean,
        overwrite: Boolean = false,
        cancelRequest: () -> Boolean = { false },
        /** Faux dans les tests seulement : force la copie, comme entre deux volumes. */
        allowRename: Boolean = true,
        onProgress: (Progress) -> Unit = {}
    ): Outcome {
        // Une annulation vue reste acquise : le nettoyage qui la suit ne doit pas la défaire.
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val isCancelled: () -> Boolean = {
            if (!stopped.get() && cancelRequest()) stopped.set(true)
            stopped.get()
        }
        val failed = Collections.synchronizedList(mutableListOf<String>())
        var ok = 0
        if (!destDir.exists()) destDir.mkdirs()

        // 1. Ce qui se règle sans copie : absents, sans effet, interdits, renommés.
        val jobs = ArrayList<Job>()
        for (src in sources) {
            if (!Files.exists(src.toPath(), LinkOption.NOFOLLOW_LINKS)) { failed += src.name; continue }
            val sameFolder = runCatching { src.parentFile?.canonicalFile == destDir.canonicalFile }.getOrDefault(false)
            if (move && sameFolder) {
                // Déplacer un élément dans son propre dossier n'a aucun effet :
                // sans ce garde-fou, l'anti-écrasement le renommerait en « nom (1) ».
                ok++
                continue
            }
            if (TreeDeleter.isRealDirectory(src) && isDescendant(src, destDir)) {
                failed += "${src.name} (destination à l'intérieur de la source)"
                continue
            }
            val target = if (overwrite) File(destDir, src.name) else FileOperations.uniqueTarget(destDir, src.name)
            if (move && allowRename && !target.exists() && runCatching { src.renameTo(target) }.getOrDefault(false)) {
                ok++
                continue
            }
            jobs += Job(src, target, createdTarget = !target.exists())
        }
        if (jobs.isEmpty() || isCancelled()) {
            return Outcome(ok, failed.toList(), isCancelled(), false, 0, 0, 0)
        }

        // 2. Inventaire.
        val items = ConcurrentLinkedQueue<Item>()
        val found = AtomicLong()
        val throttle = Throttle()
        val unreadable = TreeScanner.walk(jobs.map { it.src }, isCancelled) { root, path, depth, attrs ->
            val job = jobs[root]
            val dst = if (depth == 0) job.target else File(job.target, job.src.toPath().relativize(path).toString())
            val kind = when {
                attrs.isSymbolicLink -> Kind.LINK
                attrs.isDirectory -> Kind.DIR
                attrs.isRegularFile -> Kind.FILE
                else -> Kind.OTHER
            }
            items.add(Item(root, path, dst, kind, if (kind == Kind.FILE) attrs.size() else 0L, attrs.lastModifiedTime().toMillis()))
            val n = found.incrementAndGet()
            if (throttle.ready()) onProgress(Progress(Phase.INVENTORY, "", 0, 0, 0, n))
        }
        if (isCancelled()) return Outcome(ok, failed.toList(), true, false, 0, 0, 0)
        // Un dossier qu'on n'a pas pu lister ne serait pas copié : l'élément est signalé en
        // échec, et son original n'est donc jamais effacé.
        unreadable.forEachIndexed { i, n ->
            if (n > 0) jobs[i].error.compareAndSet(null, "${jobs[i].src.name} : $n dossier(s) illisible(s), original conservé")
        }

        val dirs = items.filter { it.kind == Kind.DIR }.sortedBy { it.dst.path.length }
        val files = items.filter { it.kind != Kind.DIR }.sortedBy { it.dst.path }
        val bytesTotal = files.sumOf { it.size }
        val filesTotal = files.size.toLong()

        val free = runCatching { destDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        // Sans place connue (0 : volume inaccessible), on tente quand même : l'écriture dira.
        if (free in 1 until bytesTotal + SPACE_MARGIN) {
            failed += "Place insuffisante : ${Formatters.size(bytesTotal)} à copier, ${Formatters.size(free)} libres"
            return Outcome(ok, failed.toList(), false, false, 0, filesTotal, 0)
        }

        // 3. Copie.
        val bytesDone = AtomicLong()
        val filesDone = AtomicLong()
        val knownDirs = ConcurrentHashMap.newKeySet<String>()
        val inProgress = ConcurrentHashMap.newKeySet<File>()
        fun report(name: String, force: Boolean = false) {
            if (force || throttle.ready()) {
                onProgress(Progress(Phase.COPY, name, bytesDone.get(), bytesTotal, filesDone.get(), filesTotal))
            }
        }

        fun ensureDir(dir: File) {
            if (knownDirs.contains(dir.path)) return
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("dossier impossible à créer : ${dir.name}")
            }
            knownDirs.add(dir.path)
        }

        fun copyOne(item: Item, buffer: ByteArray) {
            val job = jobs[item.job]
            if (job.error.get() != null) return
            try {
                item.dst.parentFile?.let { ensureDir(it) }
                when (item.kind) {
                    Kind.FILE -> {
                        inProgress.add(item.dst)
                        try {
                            FileInputStream(item.src.toFile()).use { input ->
                                FileOutputStream(item.dst).use { output ->
                                    while (true) {
                                        if (isCancelled()) throw Cancelled()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        output.write(buffer, 0, n)
                                        bytesDone.addAndGet(n.toLong())
                                        report(item.dst.name)
                                    }
                                }
                            }
                            item.dst.setLastModified(item.modified)
                        } catch (e: Throwable) {
                            item.dst.delete()
                            throw e
                        } finally {
                            inProgress.remove(item.dst)
                        }
                    }
                    Kind.LINK -> Files.createSymbolicLink(item.dst.toPath(), Files.readSymbolicLink(item.src))
                    Kind.OTHER -> throw IOException("fichier spécial, non copié")
                    Kind.DIR -> Unit
                }
                filesDone.incrementAndGet()
                report(item.dst.name)
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                val what = if (item.dst == job.target) "" else " : ${item.dst.name}"
                job.error.compareAndSet(null, "${job.src.name}$what : ${e.message ?: e.javaClass.simpleName}")
            }
        }

        val pool = Executors.newFixedThreadPool(WRITERS) { r -> Thread(r, "copie").apply { isDaemon = true } }
        val next = AtomicInteger()
        try {
            val workers = (1..minOf(WRITERS, files.size.coerceAtLeast(1))).map {
                pool.submit(Runnable {
                    val buffer = ByteArray(BUFFER)
                    while (!isCancelled()) {
                        val i = next.getAndIncrement()
                        if (i >= files.size) break
                        copyOne(files[i], buffer)
                    }
                })
            }
            for (w in workers) {
                try {
                    w.get()
                } catch (e: ExecutionException) {
                    if (e.cause !is Cancelled) throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdown()
        }
        inProgress.forEach { it.delete() }

        if (!isCancelled()) {
            // Les dossiers vides, et les racines elles-mêmes.
            for (d in dirs) {
                val job = jobs[d.job]
                if (job.error.get() != null) continue
                try {
                    ensureDir(d.dst)
                } catch (e: IOException) {
                    job.error.compareAndSet(null, "${job.src.name} : ${e.message}")
                }
            }
            report("", force = true)
        }

        if (isCancelled()) {
            // Déplacement annulé : on défait la copie, les originaux sont intacts.
            if (move) {
                val created = jobs.filter { it.createdTarget }.map { it.target }
                if (created.isNotEmpty()) TreeDeleter.delete(created)
            }
            return Outcome(ok, failed.toList(), true, false, filesDone.get(), filesTotal, bytesDone.get())
        }

        // 4. Bilan, et effacement des originaux.
        val toDelete = ArrayList<Job>()
        for (job in jobs) {
            val err = job.error.get()
            if (err != null) failed += err
            else if (move) toDelete += job
            else ok++
        }
        var cleanupCancelled = false
        if (toDelete.isNotEmpty()) {
            val outcome = TreeDeleter.delete(toDelete.map { it.src }, isCancelled) { name, done, total ->
                onProgress(Progress(Phase.CLEANUP, name, bytesTotal, bytesTotal, done.toLong(), total.toLong()))
            }
            cleanupCancelled = outcome.cancelled
            val left = outcome.remaining.map { it.path }.toSet()
            for (job in toDelete) {
                if (job.src.path in left) {
                    if (!outcome.cancelled) failed += "${job.src.name} : copié, mais l'original n'a pas pu être effacé"
                } else ok++
            }
        }
        return Outcome(ok, failed.toList(), cleanupCancelled, cleanupCancelled, filesDone.get(), filesTotal, bytesDone.get())
    }

    private fun isDescendant(parent: File, child: File): Boolean = runCatching {
        val p = parent.canonicalPath
        val c = child.canonicalPath
        c == p || c.startsWith("$p${File.separator}")
    }.getOrDefault(false)

    /** Une mise à jour toutes les 50 ms au plus, quel que soit le nombre de fils. */
    private class Throttle {
        private val last = AtomicLong(0)
        fun ready(): Boolean {
            val now = System.nanoTime()
            val t = last.get()
            return now - t >= 50_000_000L && last.compareAndSet(t, now)
        }
    }
}
