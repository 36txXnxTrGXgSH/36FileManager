package com.filemanager36.app.data

import com.filemanager36.app.util.SafeName
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/** Formats d'archive que l'application sait extraire elle-même. */
enum class ArchiveFormat(val label: String, val singleFile: Boolean = false) {
    ZIP("ZIP"),
    SEVEN_Z("7z"),
    RAR("RAR"),
    TAR("TAR"),
    TAR_GZ("TAR.GZ"),
    TAR_BZ2("TAR.BZ2"),
    TAR_XZ("TAR.XZ"),
    /** Un seul fichier compressé (« journal.txt.gz ») : il n'y a pas de dossier à créer. */
    GZ("GZ", singleFile = true),
    BZ2("BZ2", singleFile = true),
    XZ("XZ", singleFile = true)
}

/**
 * Ce que l'on peut savoir d'une archive **sans la décompresser** : de quoi afficher
 * « 1 234 fichiers · 18,4 Go une fois extraits » et refuser d'avance une extraction qui
 * ne tiendrait pas.
 *
 * ZIP, 7z et RAR rangent cette table des matières dans l'archive ; un TAR compressé n'en
 * a pas — il faudrait tout décompresser pour compter —, d'où les valeurs nulles.
 */
class ArchiveSummary(
    val format: ArchiveFormat,
    /** Nombre de fichiers (dossiers exclus), ou null si inconnu. */
    val files: Int?,
    /** Taille une fois extrait, ou null si inconnue. */
    val totalBytes: Long?,
    /** Un mot de passe sera demandé. */
    val needsPassword: Boolean,
    /** Nombre de fichiers formant l'archive (« .7z.001 », « .7z.002 »…). */
    val volumes: Int = 1
)

/** Résultat d'une extraction réussie. */
class ExtractResult(
    /** Le dossier créé — ou, pour un .gz/.bz2/.xz isolé, le fichier créé. */
    val output: File,
    val files: Int,
    val bytes: Long,
    /** Liens symboliques et fichiers spéciaux volontairement ignorés. */
    val skipped: Int
)

/** L'archive est protégée et aucun mot de passe n'a été donné. */
class ArchivePasswordRequired : IOException("Mot de passe requis")

/** Le mot de passe donné ne permet pas de déchiffrer l'archive. */
class ArchiveWrongPassword : IOException("Mot de passe incorrect")

/** L'utilisateur a interrompu l'extraction. */
class ArchiveCancelled : IOException("Extraction annulée")

/** L'extraction ne tient pas dans l'espace libre (marge de sécurité comprise). */
class ArchiveNoSpace(val needed: Long, val available: Long) : IOException("Espace insuffisant")

/** Archive lisible en théorie, mais pas par ce téléphone ou pas sous cette forme. */
class ArchiveUnsupported(message: String) : IOException(message)

/**
 * Extraction des archives : ZIP, 7z, RAR, TAR et ses variantes compressées, et les
 * fichiers isolés .gz, .bz2, .xz.
 *
 * ## Ce que chaque archive traverse
 * Une archive vient presque toujours d'ailleurs : elle est traitée comme une donnée
 * hostile, quel que soit son format, par **le même code** pour tous les formats.
 * - **Chemins** : `..`, chemin absolu, lettre de lecteur — une entrée qui tenterait de
 *   sortir du dossier d'extraction fait refuser toute l'archive ; chaque segment passe
 *   par [SafeName] ; le dossier de sortie est neuf et aucun lien n'y est créé, le
 *   chemin écrit est donc bien celui qu'on a vérifié.
 * - **Liens** : liens symboliques, liens physiques, fichiers spéciaux ne sont jamais
 *   créés. Un lien qui pointerait vers `/sdcard` ferait écrire l'entrée suivante hors du
 *   dossier d'extraction ; on les compte et on le dit.
 * - **Espace** : la place nécessaire est vérifiée **avant** d'écrire quand l'archive
 *   l'annonce, et le volume réellement écrit reste borné pendant l'extraction — une
 *   archive peut mentir sur sa taille, et 42 Kio de zéros compressés en donnent 4 Gio.
 *   Une marge reste toujours libre : un téléphone au stockage plein ne fonctionne plus.
 * - **Nombre d'entrées** borné, et **mémoire** bornée pour le 7z, dont le décodeur
 *   alloue d'un coup tout son dictionnaire.
 *
 * ## Échec, annulation
 * La sortie est toujours un dossier (ou un fichier) **neuf**, créé pour l'occasion :
 * en cas d'annulation ou de manque de place, elle est supprimée en entier — il ne reste
 * rien de partiel. Pour une archive endommagée, les fichiers entièrement extraits avant
 * l'erreur sont gardés ; seul le fichier en cours, forcément tronqué, est supprimé.
 *
 * Aucune dépendance Android : tout est couvert par des tests JVM (`ArchivesTest`).
 */
object Archives {

    /** Profondeur maximale d'un chemin dans une archive. */
    private const val MAX_DEPTH = 64

    /** Nombre maximal d'entrées : une archive de millions de fichiers vides épuise le système de fichiers sans peser un octet. */
    const val MAX_ENTRIES = 500_000

    /** Espace toujours laissé libre : au moins 512 Mio, ou 2 % du volume. */
    private const val MIN_RESERVE = 512L * 1024 * 1024

    private const val BUFFER = 256 * 1024

    /**
     * Fichiers jusqu'à cette taille : lus d'un bloc, puis écrits par plusieurs fils à la
     * fois. Au-delà, le fil qui décompresse écrit lui-même, en flux, sans tout garder en
     * mémoire.
     */
    private const val SMALL_FILE = 1L shl 20

    /** Octets lus mais pas encore écrits, au plus : borne la mémoire de la file d'écriture. */
    private const val MAX_PENDING_BYTES = 48 shl 20

    /**
     * Écritures simultanées. Sur le stockage partagé d'Android (FUSE), créer un fichier
     * coûte ~45 ms quand on les enchaîne un par un : mesuré sur une tablette Android 16,
     * 22 fichiers/s seul, 143 avec 4 écritures en parallèle, 126 avec 8. Une archive de
     * 200 000 petits fichiers passait ainsi de 2 h 30 à 25 min d'écriture.
     */
    private const val WRITERS = 4

    /** Suffixes reconnus, du plus long au plus court : « .tar.gz » avant « .gz ». */
    private val SUFFIXES = listOf(
        ".tar.gz" to ArchiveFormat.TAR_GZ, ".tgz" to ArchiveFormat.TAR_GZ,
        ".tar.bz2" to ArchiveFormat.TAR_BZ2, ".tbz2" to ArchiveFormat.TAR_BZ2, ".tbz" to ArchiveFormat.TAR_BZ2,
        ".tar.xz" to ArchiveFormat.TAR_XZ, ".txz" to ArchiveFormat.TAR_XZ,
        ".zip" to ArchiveFormat.ZIP, ".jar" to ArchiveFormat.ZIP, ".cbz" to ArchiveFormat.ZIP,
        ".7z" to ArchiveFormat.SEVEN_Z, ".cb7" to ArchiveFormat.SEVEN_Z,
        ".rar" to ArchiveFormat.RAR, ".cbr" to ArchiveFormat.RAR,
        ".tar" to ArchiveFormat.TAR,
        ".gz" to ArchiveFormat.GZ, ".bz2" to ArchiveFormat.BZ2, ".xz" to ArchiveFormat.XZ
    )

    /** « jeu.7z.001 », « jeu.7z.002 »… : un 7z découpé en volumes. */
    private val SEVEN_Z_VOLUME = Regex("^(.+\\.7z)\\.(\\d{3})$", RegexOption.IGNORE_CASE)

    /** « film.part1.rar », « film.part02.rar »… : un RAR découpé en volumes. */
    private val RAR_VOLUME = Regex("^(.+)\\.part(\\d+)\\.rar$", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------
    // Reconnaissance
    // ------------------------------------------------------------------

    /** Format d'après le nom seul — rapide, sans lire le fichier : sert aux listes. */
    fun formatFromName(name: String): ArchiveFormat? {
        if (SEVEN_Z_VOLUME.matches(name)) return ArchiveFormat.SEVEN_Z
        val lower = name.lowercase()
        return SUFFIXES.firstOrNull { (suffix, _) -> lower.endsWith(suffix) && lower.length > suffix.length }?.second
    }

    /** L'application sait-elle extraire ce fichier ? (D'après son nom.) */
    fun isArchiveName(name: String): Boolean = formatFromName(name) != null

    /**
     * Format réel, d'après les premiers octets : un « .zip » qui est en fait un 7z, ou un
     * fichier sans extension, sont reconnus quand même. Le nom ne sert qu'en dernier
     * recours, et pour distinguer un .tar.gz d'un simple .gz.
     */
    fun detect(file: File): ArchiveFormat? {
        val head = ByteArray(8)
        val n = runCatching { FileInputStream(file).use { readFully(it, head) } }.getOrDefault(0)
        val byName = formatFromName(file.name)
        fun at(vararg bytes: Int) = n >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
        return when {
            at(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> ArchiveFormat.SEVEN_Z
            at(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> ArchiveFormat.RAR
            at(0x50, 0x4B, 0x03, 0x04) || at(0x50, 0x4B, 0x05, 0x06) || at(0x50, 0x4B, 0x07, 0x08) ->
                ArchiveFormat.ZIP
            at(0x1F, 0x8B) -> if (containsTar(file, ArchiveFormat.GZ)) ArchiveFormat.TAR_GZ else ArchiveFormat.GZ
            at(0x42, 0x5A, 0x68) -> if (containsTar(file, ArchiveFormat.BZ2)) ArchiveFormat.TAR_BZ2 else ArchiveFormat.BZ2
            at(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) ->
                if (containsTar(file, ArchiveFormat.XZ)) ArchiveFormat.TAR_XZ else ArchiveFormat.XZ
            isTar(file) -> ArchiveFormat.TAR
            // Volume 7z autre que le premier : il ne commence pas par la signature.
            byName == ArchiveFormat.SEVEN_Z && SEVEN_Z_VOLUME.matches(file.name) -> ArchiveFormat.SEVEN_Z
            else -> null
        }
    }

    /** Nom du dossier (ou du fichier) à créer : « photos.tar.gz » → « photos ». */
    fun outputName(file: File): String {
        val name = file.name
        SEVEN_Z_VOLUME.matchEntire(name)?.let { return it.groupValues[1].dropLast(3).ifBlank { "archive" } }
        RAR_VOLUME.matchEntire(name)?.let { return it.groupValues[1].ifBlank { "archive" } }
        val lower = name.lowercase()
        val suffix = SUFFIXES.firstOrNull { (s, _) -> lower.endsWith(s) && lower.length > s.length }?.first
        val base = if (suffix != null) name.dropLast(suffix.length) else file.nameWithoutExtension
        return base.ifBlank { "archive" }
    }

    // ------------------------------------------------------------------
    // Inventaire
    // ------------------------------------------------------------------

    /**
     * Inventaire rapide. Ne décompresse rien — sauf pour un .xz, dont l'index final donne
     * la taille décompressée à moindre coût.
     *
     * @throws IOException si le fichier n'est pas une archive lisible.
     */
    fun summarize(file: File, password: CharArray? = null): ArchiveSummary {
        val format = detect(file) ?: throw ArchiveUnsupported("Ce fichier n'est pas une archive reconnue")
        return when (format) {
            ArchiveFormat.SEVEN_Z -> summarize7z(file, password)
            ArchiveFormat.ZIP -> summarizeZip(file)
            ArchiveFormat.RAR -> summarizeRar(file, password)
            ArchiveFormat.XZ, ArchiveFormat.TAR_XZ ->
                ArchiveSummary(format, if (format == ArchiveFormat.XZ) 1 else null, xzLength(file), false)
            ArchiveFormat.GZ, ArchiveFormat.BZ2 -> ArchiveSummary(format, 1, null, false)
            else -> ArchiveSummary(format, null, null, false)
        }
    }

    /**
     * Le mot de passe ouvre-t-il l'archive ? Vérifié en déchiffrant le début du premier
     * fichier protégé : quelques millisecondes, au lieu de découvrir l'erreur au bout de
     * vingt minutes d'extraction.
     */
    fun checkPassword(file: File, password: CharArray): Boolean = try {
        when (detect(file)) {
            ArchiveFormat.SEVEN_Z -> open7z(file, password).use { z ->
                var e = z.nextEntry
                while (e != null && (e.isDirectory || !e.hasStream())) e = z.nextEntry
                if (e != null) z.read(ByteArray(64 * 1024))
                true
            }
            ArchiveFormat.ZIP -> openZip(file, password).use { zip ->
                val header = zip.fileHeaders.firstOrNull { it.isEncrypted && !it.isDirectory }
                if (header != null) zip.getInputStream(header).use { it.read(ByteArray(64 * 1024)) }
                true
            }
            ArchiveFormat.RAR -> checkRarPassword(file, password)
            else -> true
        }
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // Extraction
    // ------------------------------------------------------------------

    /**
     * Extrait [archive] dans [parent], dans un dossier neuf nommé d'après l'archive — ou,
     * pour un .gz/.bz2/.xz isolé, en un fichier neuf.
     *
     * @param freeSpace place libre sur le volume de [parent] ; injectable pour les tests.
     * @param onProgress nom de l'entrée en cours, octets traités, total (0 si inconnu).
     * @param isCancelled consulté entre deux blocs : `true` interrompt et efface la sortie.
     */
    fun extract(
        archive: File,
        parent: File,
        password: CharArray? = null,
        freeSpace: (File) -> Long = { it.usableSpace },
        isCancelled: () -> Boolean = { false },
        /**
         * Appelé dès que la sortie existe : si le processus meurt en route (mise à jour
         * de l'application, plantage, système à court de mémoire), l'appelant saura au
         * lancement suivant quel dossier partiel retirer — ce que ce code-ci, tué net, ne
         * peut plus faire.
         */
        onOutputCreated: (File) -> Unit = {},
        onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }
    ): ExtractResult {
        val format = detect(archive) ?: throw ArchiveUnsupported("Ce fichier n'est pas une archive reconnue")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Dossier de destination introuvable")
        val free = freeSpace(parent)
        val reserve = maxOf(MIN_RESERVE, (parent.totalSpace / 50))
        val budget = (free - reserve).coerceAtLeast(0)

        // Refus d'avance quand l'archive annonce sa taille : mieux vaut le savoir tout de
        // suite qu'après vingt minutes de décompression.
        val summary = runCatching { summarize(archive, password) }.getOrNull()
        summary?.totalBytes?.let { if (it > budget) throw ArchiveNoSpace(it, budget) }
        summary?.files?.let { if (it > MAX_ENTRIES) throw ArchiveUnsupported("Plus de $MAX_ENTRIES fichiers dans cette archive") }

        val output = FileOperations.uniqueTarget(parent, outputName(archive))
        val sink = Sink(
            root = if (format.singleFile) parent else output,
            budget = budget,
            total = summary?.totalBytes ?: 0L,
            isCancelled = isCancelled,
            onProgress = onProgress
        )
        if (!format.singleFile && !output.mkdirs()) throw IOException("Création du dossier impossible")
        onOutputCreated(output)

        try {
            when (format) {
                ArchiveFormat.SEVEN_Z -> extract7z(archive, password, sink)
                ArchiveFormat.ZIP -> extractZip(archive, password, sink)
                ArchiveFormat.RAR -> extractRar(archive, password, sink)
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ ->
                    extractTar(archive, format, sink)
                ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ ->
                    extractSingle(archive, format, output, sink)
            }
            // Attend les écritures encore en file, et relance leur éventuelle erreur.
            sink.close()
        } catch (e: Exception) {
            // L'erreur d'origine, même survenue dans un autre fil que celui-ci ; les
            // fichiers entamés, forcément tronqués, sont retirés.
            val cause = sink.abort(e)
            // Annulation, manque de place, archive hostile : on ne laisse rien. Pour une
            // archive simplement endommagée, ce qui a été extrait en entier reste.
            val wipe = cause is ArchiveCancelled || cause is ArchiveNoSpace || cause is SecurityException ||
                cause is ArchiveWrongPassword || cause is ArchivePasswordRequired || sink.files == 0
            if (wipe) FileOperations.deleteRecursive(output)
            throw cause
        }
        if (!format.singleFile && sink.files == 0 && output.listFiles().isNullOrEmpty()) {
            output.delete()
            throw ArchiveUnsupported("L'archive ne contient aucun fichier")
        }
        return ExtractResult(output, sink.files, sink.written, sink.skipped)
    }

    /**
     * Message en français pour une erreur d'extraction — ce que l'utilisateur lit dans la
     * barre de notification, donc sans jargon.
     */
    fun describe(e: Throwable): String = when (e) {
        is ArchiveCancelled -> "Extraction annulée"
        is ArchivePasswordRequired -> "Cette archive est protégée par un mot de passe"
        is ArchiveWrongPassword -> "Mot de passe incorrect"
        is ArchiveNoSpace -> "Espace insuffisant : il faut ${gigas(e.needed)}, ${gigas(e.available)} disponibles"
        is ArchiveUnsupported -> e.message ?: "Archive non prise en charge"
        is SecurityException -> "Archive refusée : ${e.message}"
        else -> "Archive endommagée ou illisible" + (e.message?.let { " ($it)" } ?: "")
    }

    private fun gigas(bytes: Long): String =
        if (bytes >= 1L shl 30) String.format(java.util.Locale.FRANCE, "%.1f Go", bytes / 1073741824.0)
        else String.format(java.util.Locale.FRANCE, "%.0f Mo", bytes / 1048576.0)

    // ------------------------------------------------------------------
    // Écriture commune à tous les formats
    // ------------------------------------------------------------------

    /**
     * Tout ce qui sort d'une archive passe par ici : c'est le seul endroit où un chemin
     * venu de l'archive devient un fichier sur le téléphone.
     */
    private class Sink(
        val root: File,
        val budget: Long,
        val total: Long,
        val isCancelled: () -> Boolean,
        val onProgress: (String, Long, Long) -> Unit,
        writers: Int = WRITERS
    ) {
        private val rootPath: String = root.absolutePath
        /** Octets engagés : comptés dès la lecture, c'est sur eux que porte le budget. */
        private val reserved = AtomicLong()
        /** Octets réellement écrits : c'est eux que montre la progression. */
        private val doneBytes = AtomicLong()
        private val fileCount = AtomicInteger()
        private val skippedCount = AtomicInteger()
        private val entries = AtomicInteger()
        /** Fichiers ouverts en écriture : tronqués si l'extraction s'arrête là. */
        private val inProgress: MutableSet<File> = ConcurrentHashMap.newKeySet()
        /** Dossiers déjà créés : évite une interrogation du stockage par fichier. */
        private val knownDirs: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val pending = Semaphore(MAX_PENDING_BYTES)
        private val lastReport = AtomicLong()
        @Volatile private var failure: Exception? = null
        /** Progression par octets lus dans l'archive, quand la taille finale est inconnue. */
        @Volatile var sourceProgress: (() -> Pair<Long, Long>)? = null

        private val pool: ExecutorService? = if (writers <= 1) null else
            Executors.newFixedThreadPool(writers) { r -> Thread(r, "extraction-ecriture").apply { isDaemon = true } }

        val written: Long get() = doneBytes.get()
        val files: Int get() = fileCount.get()
        val skipped: Int get() = skippedCount.get()

        /**
         * Erreur survenue dans un fil quelconque : la première l'emporte, les autres fils
         * s'arrêtent à leur prochain [checkCancel]. Une erreur de mémoire devient un
         * message — elle ne doit pas faire tomber l'application.
         */
        fun fail(t: Throwable) {
            val e = when (t) {
                is Exception -> t
                is OutOfMemoryError -> ArchiveUnsupported("Mémoire insuffisante pour décompresser cette archive")
                else -> IOException(t.message ?: t.javaClass.simpleName, t)
            }
            synchronized(this) { if (failure == null) failure = e }
        }

        fun checkCancel() {
            failure?.let { throw it }
            if (isCancelled()) throw ArchiveCancelled()
        }

        private fun countEntry() {
            if (entries.incrementAndGet() > MAX_ENTRIES) throw ArchiveUnsupported("Plus de $MAX_ENTRIES fichiers dans cette archive")
        }

        /**
         * Chemin sûr sous la racine, ou null pour une entrée à ignorer (nom vide).
         *
         * Pas de chemin canonique ici : sur le stockage partagé, le calculer interroge le
         * système de fichiers pour chaque niveau du chemin, à chaque fichier. Il serait de
         * toute façon redondant — [safeRelativePath] refuse toute remontée et tout chemin
         * absolu, la racine est un dossier **neuf** créé par l'extraction, et aucun lien
         * symbolique n'y est jamais créé : le chemin écrit est donc bien celui qu'on lit.
         */
        fun resolve(rawName: String): File? {
            val rel = safeRelativePath(rawName) ?: return null
            val target = File(root, rel)
            if (!target.absolutePath.startsWith(rootPath + File.separator)) {
                throw SecurityException("entrée hors du dossier d'extraction : $rawName")
            }
            return target
        }

        private fun ensureDir(dir: File) {
            if (dir.path in knownDirs) return
            // mkdirs peut échouer parce qu'un autre fil vient de créer le même dossier.
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Création du dossier impossible : ${dir.name}")
            }
            knownDirs.add(dir.path)
        }

        fun directory(rawName: String) {
            checkCancel(); countEntry()
            val dir = resolve(rawName) ?: return
            if (pool == null) { ensureDir(dir); return }
            pool.execute {
                try {
                    if (isCancelled()) fail(ArchiveCancelled())
                    if (failure == null) ensureDir(dir)
                } catch (t: Throwable) { fail(t) }
            }
        }

        fun skip() { countEntry(); skippedCount.incrementAndGet() }

        /**
         * Une entrée et son contenu. [size] : taille annoncée par l'archive, -1 si
         * inconnue ; un petit fichier est lu d'un bloc et confié aux écrivains.
         */
        fun file(rawName: String, input: InputStream, size: Long = -1L) {
            checkCancel(); countEntry()
            val target = resolve(rawName) ?: return
            if (pool != null && size in 0..SMALL_FILE) {
                val data = ByteArray(size.toInt())
                if (readFully(input, data) < data.size) throw IOException("Contenu tronqué dans l'archive : $rawName")
                // Lire jusqu'au bout : c'est là que zip4j et le 7z vérifient la somme de
                // contrôle. Un contenu plus long qu'annoncé est une archive incohérente.
                if (input.read() >= 0) throw IOException("Taille annoncée inexacte : $rawName")
                submit(target, rawName, data)
            } else {
                write(target, rawName, input)
            }
        }

        /** Un petit fichier déjà lu en mémoire (RAR). */
        fun fileBytes(rawName: String, data: ByteArray) {
            checkCancel(); countEntry()
            val target = resolve(rawName) ?: return
            if (pool != null) submit(target, rawName, data) else write(target, rawName, java.io.ByteArrayInputStream(data))
        }

        private fun submit(target: File, displayName: String, data: ByteArray) {
            reserve(data.size.toLong())
            val permits = data.size.coerceIn(1, MAX_PENDING_BYTES)
            // Bloque le décodeur tant que les écrivains ont trop de retard : la mémoire
            // engagée reste bornée, quelle que soit la vitesse du stockage. L'attente se
            // fait par tranches, pour voir passer une annulation : sur un stockage lent,
            // la file peut mettre des minutes à se vider.
            while (!pending.tryAcquire(permits, 200, TimeUnit.MILLISECONDS)) checkCancel()
            try {
                pool!!.execute {
                    try {
                        if (isCancelled()) fail(ArchiveCancelled())
                        if (failure == null) {
                            target.parentFile?.let { ensureDir(it) }
                            inProgress.add(target)
                            java.io.FileOutputStream(target).use { it.write(data) }
                            inProgress.remove(target)
                            doneBytes.addAndGet(data.size.toLong())
                            fileCount.incrementAndGet()
                            report(displayName)
                        }
                    } catch (t: Throwable) {
                        fail(t)
                    } finally {
                        pending.release(permits)
                    }
                }
            } catch (t: Throwable) {
                pending.release(permits)
                throw t
            }
        }

        private fun reserve(n: Long) {
            val now = reserved.addAndGet(n)
            if (now > budget) throw ArchiveNoSpace(maxOf(total, now), budget)
        }

        /** Fichier dont on reçoit le contenu par un flux de sortie (RAR). */
        fun fileOutput(rawName: String): Pair<File, OutputStream>? {
            checkCancel(); countEntry()
            val target = resolve(rawName) ?: return null
            target.parentFile?.let { ensureDir(it) }
            inProgress.add(target)
            return target to BoundedOutput(target, rawName)
        }

        fun write(target: File, displayName: String, input: InputStream) {
            target.parentFile?.let { ensureDir(it) }
            inProgress.add(target)
            BoundedOutput(target, displayName).use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) out.write(buffer, 0, n)
                }
            }
            finished(target)
        }

        fun finished(target: File) { inProgress.remove(target); fileCount.incrementAndGet() }

        /** Au plus une mise à jour toutes les 50 ms, quel que soit le nombre de fils. */
        private fun report(displayName: String) {
            val now = System.nanoTime()
            val last = lastReport.get()
            if (now - last < 50_000_000L || !lastReport.compareAndSet(last, now)) return
            val source = sourceProgress?.invoke()
            if (total > 0 || source == null) onProgress(displayName, doneBytes.get(), total)
            else onProgress(displayName, source.first, source.second)
        }

        /** Fin normale : attend les écritures en file et relance leur erreur éventuelle. */
        fun close() {
            pool?.let { it.shutdown(); it.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS) }
            // Une annulation arrivée pendant que des écritures attendaient leur tour : les
            // écrivains les ont abandonnées, l'extraction n'est donc pas complète.
            checkCancel()
        }

        /**
         * Arrêt sur erreur : les autres fils s'arrêtent, on attend qu'ils l'aient fait, puis
         * les fichiers entamés sont retirés. Renvoie l'erreur d'origine — celle du fil qui
         * a échoué le premier, pas celle, secondaire, de l'arrêt des autres.
         */
        fun abort(e: Exception): Exception {
            fail(e)
            pool?.let { it.shutdown(); it.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS) }
            inProgress.forEach { it.delete() }
            inProgress.clear()
            return failure ?: e
        }

        /** Flux d'écriture qui compte, borne et laisse interrompre. */
        inner class BoundedOutput(target: File, private val displayName: String) : OutputStream() {
            private val out = java.io.FileOutputStream(target)
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                checkCancel()
                reserve(len.toLong())
                out.write(b, off, len)
                doneBytes.addAndGet(len.toLong())
                report(displayName)
            }
            override fun flush() = out.flush()
            override fun close() = out.close()
        }
    }

    /**
     * « dossier/sous-dossier/fichier.txt », assaini segment par segment ; null pour une
     * entrée sans nom utilisable (« ./ »), à ignorer.
     *
     * @throws SecurityException sur une remontée `..` : une archive qui en contient ne
     * peut pas être de bonne foi, elle est refusée en entier.
     */
    internal fun safeRelativePath(raw: String): String? {
        var parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        // Lettre de lecteur Windows en tête (« C: ») : un chemin absolu, ramené au dossier.
        if (parts.isNotEmpty() && parts[0].length == 2 && parts[0][1] == ':') parts = parts.drop(1)
        if (parts.any { it == ".." }) throw SecurityException("remontée de dossier dans « $raw »")
        if (parts.isEmpty()) return null
        if (parts.size > MAX_DEPTH) throw SecurityException("chemin trop profond : « $raw »")
        val clean = parts.map { SafeName.of(it) }
        if (clean.any { it.isBlank() }) return null
        return clean.joinToString("/")
    }

    // ------------------------------------------------------------------
    // 7z
    // ------------------------------------------------------------------

    /** Les volumes d'un 7z découpé, dans l'ordre, ou le fichier seul. */
    private fun sevenZVolumes(file: File): List<File> {
        val m = SEVEN_Z_VOLUME.matchEntire(file.name) ?: return listOf(file)
        val dir = file.parentFile ?: return listOf(file)
        val base = m.groupValues[1]
        val width = m.groupValues[2].length
        val parts = mutableListOf<File>()
        var i = 1
        while (true) {
            val part = File(dir, base + "." + i.toString().padStart(width, '0'))
            if (!part.isFile) break
            parts += part
            i++
        }
        if (parts.isEmpty()) return listOf(file)
        return parts
    }

    /** Mémoire accordée au décodeur : les trois quarts de ce que l'application peut utiliser. */
    private fun memoryLimitKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 * 3 / 4)
        .coerceIn(16 * 1024L, Int.MAX_VALUE.toLong()).toInt()

    private fun open7z(file: File, password: CharArray?): SevenZFile {
        val volumes = sevenZVolumes(file)
        // setMaxMemoryLimitKiB et non ...Kb : la seconde est interprétée mille fois plus petite
        // (384 Kio au lieu de 384 Mio), et plus aucun 7z ne s'ouvrait. ArchivesTest le garde.
        val builder = SevenZFile.builder().setMaxMemoryLimitKiB(memoryLimitKb())
        if (volumes.size > 1) {
            builder.setSeekableByteChannel(MultiReadOnlySeekableByteChannel.forFiles(*volumes.toTypedArray()))
                .setDefaultName(outputName(file))
        } else {
            builder.setFile(file)
        }
        if (password != null) builder.setPassword(password)
        return try {
            builder.get()
        } catch (e: PasswordRequiredException) {
            throw ArchivePasswordRequired()
        } catch (e: MemoryLimitException) {
            throw tooMuchMemory(e)
        } catch (e: IOException) {
            // En-têtes chiffrés et mauvais mot de passe : l'en-tête ne se décode pas.
            if (password != null) throw ArchiveWrongPassword()
            throw e
        }
    }

    private fun tooMuchMemory(e: MemoryLimitException) = ArchiveUnsupported(
        "Cette archive demande ${e.memoryNeededInKb / 1024} Mo de mémoire pour se décompresser, " +
            "plus que ce dont dispose l'application (${e.memoryLimitInKb / 1024} Mo). " +
            "Elle a été créée avec un réglage de compression extrême."
    )

    private fun summarize7z(file: File, password: CharArray?): ArchiveSummary {
        val volumes = sevenZVolumes(file).size
        val z = try {
            open7z(file, password)
        } catch (e: ArchivePasswordRequired) {
            // En-têtes chiffrés : même la liste des fichiers est secrète.
            return ArchiveSummary(ArchiveFormat.SEVEN_Z, null, null, true, volumes)
        }
        z.use {
            var files = 0
            var bytes = 0L
            for (e in it.entries) {
                if (e.isDirectory || e.isAntiItem) continue
                files++
                bytes += e.size.coerceAtLeast(0)
            }
            // Les méthodes de compression d'une entrée ne sont connues qu'en avançant
            // jusqu'à elle par nextEntry — la table de getEntries() les laisse vides, et
            // une archive chiffrée passait alors pour non protégée : son mot de passe
            // n'était jamais demandé. nextEntry ne décompresse rien.
            var encrypted = false
            try {
                var e = it.nextEntry
                while (e != null && (e.isDirectory || !e.hasStream())) e = it.nextEntry
                encrypted = e?.contentMethods?.any { m -> m.method == SevenZMethod.AES256SHA256 } == true
            } catch (x: PasswordRequiredException) {
                encrypted = true
            }
            return ArchiveSummary(ArchiveFormat.SEVEN_Z, files, bytes, encrypted, volumes)
        }
    }

    private fun extract7z(file: File, password: CharArray?, sink: Sink) = try {
        extract7zEntries(file, password, sink)
    } catch (e: MemoryLimitException) {
        // Levée au premier fichier d'un bloc compressé, n'importe où dans la boucle.
        throw tooMuchMemory(e)
    }

    /**
     * Dernier mode d'extraction 7z utilisé — `true` = blocs décodés en parallèle. Lu par
     * les tests : une mise à jour de Commons Compress qui casserait l'accès aux blocs ne
     * doit pas faire revenir silencieusement au mode lent.
     */
    @Volatile internal var lastSevenZParallel = false

    private fun extract7zEntries(file: File, password: CharArray?, sink: Sink) {
        // Chiffré : chemin séquentiel, qui sait reconnaître un mauvais mot de passe.
        val plan = if (password == null) SevenZPlan.of(file, this::open7z) else null
        lastSevenZParallel = plan != null
        if (plan != null) { extract7zParallel(file, plan, sink); return }
        open7z(file, password).use { z ->
            var e = try { z.nextEntry } catch (x: MemoryLimitException) { throw tooMuchMemory(x) }
            while (e != null) {
                sink.checkCancel()
                val entry = e
                when {
                    entry.isAntiItem -> Unit
                    entry.isDirectory -> sink.directory(entry.name)
                    isLink7z(entry.hasWindowsAttributes, entry.windowsAttributes) -> sink.skip()
                    else -> {
                        val writtenBefore = sink.written
                        try {
                            if (entry.hasStream()) sink.file(entry.name, SevenZEntryStream(z), entry.size)
                            else sink.file(entry.name, java.io.ByteArrayInputStream(ByteArray(0)), 0)
                        } catch (x: PasswordRequiredException) {
                            throw ArchivePasswordRequired()
                        } catch (x: MemoryLimitException) {
                            throw tooMuchMemory(x)
                        } catch (x: IOException) {
                            // Contenu chiffré et mauvais mot de passe : le premier fichier
                            // ne se décode pas. Plus loin, c'est une archive endommagée.
                            if (x !is ArchiveCancelled && x !is ArchiveNoSpace &&
                                password != null && sink.files == 0 && writtenBefore == 0L
                            ) throw ArchiveWrongPassword()
                            throw x
                        }
                    }
                }
                e = z.nextEntry
            }
        }
    }

    /**
     * 7-Zip range le mode Unix dans les 16 bits hauts des attributs quand le drapeau
     * 0x8000 est posé ; sous Windows, un lien est un « point d'analyse » (0x400).
     */
    private fun isLink7z(has: Boolean, attributes: Int): Boolean {
        if (!has) return false
        if (attributes and 0x400 != 0) return true
        if (attributes and 0x8000 != 0) return (attributes ushr 16) and 0xF000 == 0xA000
        return false
    }

    /**
     * Extraction 7z en parallèle : chaque bloc compressé (« folder ») est un flux
     * indépendant, décodé par son propre fil. Le décodeur LZMA en Java pur plafonne à
     * ~10 Mo/s par cœur sur une tablette : un seul fil mettait plus d'une heure pour
     * 45 Gio. L'en-tête, lui, n'est lu qu'une fois — chaque [SevenZFile] supplémentaire
     * aurait recopié en mémoire la liste complète des entrées.
     */
    private fun extract7zParallel(file: File, plan: SevenZPlan, sink: Sink) {
        if (plan.blocks.isNotEmpty()) decodeBlocks(file, plan, sink)
        // Entrées sans contenu APRÈS les fichiers : dossiers, fichiers vides. Presque tous
        // les dossiers existent alors déjà — créés comme parents des fichiers — et ne
        // coûtent plus rien. Les créer d'abord mettait 43 000 créations de dossiers en
        // tête de la file d'écriture : sur une carte SD, plus de vingt minutes avant le
        // premier octet extrait.
        for (e in plan.streamless) {
            sink.checkCancel()
            when {
                e.isAntiItem -> Unit
                e.isDirectory -> sink.directory(e.name)
                isLink7z(e.hasWindowsAttributes, e.windowsAttributes) -> sink.skip()
                else -> sink.file(e.name, java.io.ByteArrayInputStream(ByteArray(0)), 0)
            }
        }
    }

    private fun decodeBlocks(file: File, plan: SevenZPlan, sink: Sink) {

        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val perDecoder = plan.blocks.maxOf { it.memory } + (4L shl 20)
        val byMemory = ((free - MAX_PENDING_BYTES - (64L shl 20)) / perDecoder).toInt()
        val decoders = minOf(plan.blocks.size, 4, (rt.availableProcessors() / 2).coerceAtLeast(1), byMemory)
            .coerceAtLeast(1)

        val scheduler = BlockScheduler(plan.blocks)
        val limitKiB = memoryLimitKb()
        val threads = (1..decoders).map { n ->
            Thread({
                try {
                    openChannel(file).use { channel ->
                        var block: SevenZPlan.Block? = null
                        while (true) {
                            sink.checkCancel()
                            block = scheduler.next(block) ?: break
                            decodeBlock(file.name, plan, block, channel, limitKiB, sink)
                        }
                    }
                } catch (x: MemoryLimitException) {
                    sink.fail(tooMuchMemory(x))
                } catch (t: Throwable) {
                    sink.fail(t)
                }
            }, "extraction-7z-$n").apply { isDaemon = true; start() }
        }
        threads.forEach { it.join() }
        sink.checkCancel()
    }

    /**
     * Distribution des blocs aux décodeurs : **au plus un bloc de petits fichiers à la
     * fois.** Un seul suffit à saturer les écrivains — c'est le service des médias
     * d'Android qui fixe alors le rythme, ~50 à 140 fichiers/s. Mis à plusieurs sur de
     * petits fichiers, les décodeurs attendaient tous les mêmes écrivains pendant que les
     * blocs de gros fichiers, eux limités par le processeur, restaient en file : sur
     * l'archive de test, 29 Go de gros fichiers attendaient la fin de 200 000 petits.
     * Ainsi, les deux avancent de front.
     */
    internal class BlockScheduler(blocks: List<SevenZPlan.Block>) {
        private val small = ArrayDeque(blocks.filter { isSmall(it) }.sortedBy { it.index })
        private val big = ArrayDeque(blocks.filterNot { isSmall(it) }.sortedBy { it.index })
        private var smallRunning = 0

        /** Le bloc suivant pour un décodeur qui vient de finir [previous] ; null s'il n'y en a plus. */
        @Synchronized
        fun next(previous: SevenZPlan.Block?): SevenZPlan.Block? {
            if (previous != null && isSmall(previous)) smallRunning--
            val block = when {
                smallRunning == 0 && small.isNotEmpty() -> small.removeFirst()
                big.isNotEmpty() -> big.removeFirst()
                // Plus de gros blocs : les décodeurs libres prennent les petits restants.
                else -> small.removeFirstOrNull()
            }
            if (block != null && isSmall(block)) smallRunning++
            return block
        }

        private companion object {
            fun isSmall(b: SevenZPlan.Block) = b.averageSize < SMALL_FILE
        }
    }

    /** Le canal de lecture propre à un fil : chaque décodeur lit sa propre position. */
    private fun openChannel(file: File): SeekableByteChannel {
        val volumes = sevenZVolumes(file)
        return if (volumes.size > 1) MultiReadOnlySeekableByteChannel.forFiles(*volumes.toTypedArray())
        else java.nio.channels.FileChannel.open(file.toPath(), StandardOpenOption.READ)
    }

    private fun decodeBlock(
        archiveName: String,
        plan: SevenZPlan,
        block: SevenZPlan.Block,
        channel: SeekableByteChannel,
        limitKiB: Int,
        sink: Sink
    ) {
        var input: InputStream = BufferedInputStream(ChannelRange(channel, block.offset, block.packedSize), 1 shl 20)
        for ((coder, unpackSize) in block.coders) input = plan.addDecoder(archiveName, input, unpackSize, coder, limitKiB)
        val single = block.entries.size == 1
        // Des entrées sans somme propre dans un bloc qui en a une : on vérifie le bloc
        // entier en plus, au fil du décodage.
        val wholeBlock = if (!single && block.folderCrc != null && block.entries.any { !it.hasCrc })
            java.util.zip.CheckedInputStream(input, CRC32()) else null
        (wholeBlock ?: input).use { stream ->
            for (e in block.entries) {
                sink.checkCancel()
                val crc = when {
                    e.hasCrc -> e.crcValue
                    single -> block.folderCrc
                    else -> null
                }
                val content = EntryStream(stream, e.size, crc, e.name)
                when {
                    e.isAntiItem -> Unit
                    isLink7z(e.hasWindowsAttributes, e.windowsAttributes) -> sink.skip()
                    else -> sink.file(e.name, content, e.size)
                }
                // Ce qui n'a pas été lu (lien ignoré, nom vide) est consommé : l'entrée
                // suivante commence juste après dans le flux.
                content.finish()
            }
            if (wholeBlock != null && wholeBlock.checksum.value != (block.folderCrc!! and 0xFFFFFFFFL)) {
                throw IOException("Données endommagées (somme de contrôle du bloc ${block.index})")
            }
        }
    }

    /** Une portion d'un canal, lue à position explicite. */
    private class ChannelRange(
        private val channel: SeekableByteChannel,
        private var position: Long,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            channel.position(position)
            val r = channel.read(ByteBuffer.wrap(b, off, n))
            if (r <= 0) throw IOException("Archive tronquée")
            position += r; remaining -= r
            return r
        }
    }

    /**
     * Le contenu d'une entrée dans le flux décodé de son bloc : borné à sa taille, et
     * vérifié par sa somme de contrôle CRC32 dès le dernier octet lu.
     */
    private class EntryStream(
        private val source: InputStream,
        private var remaining: Long,
        private val crc: Long?,
        private val name: String
    ) : InputStream() {
        private val checksum = CRC32()
        private var verified = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) { verify(); return -1 }
            if (len == 0) return 0
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw IOException("Archive tronquée : $name")
            checksum.update(b, off, n)
            remaining -= n
            if (remaining == 0L) verify()
            return n
        }

        private fun verify() {
            if (verified) return
            verified = true
            if (crc != null && checksum.value != (crc and 0xFFFFFFFFL)) {
                throw IOException("Données endommagées (somme de contrôle) : $name")
            }
        }

        fun finish() {
            val scratch = ByteArray(64 * 1024)
            while (remaining > 0) read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            verify()
        }
    }

    /** Le contenu de l'entrée courante d'un [SevenZFile], vu comme un flux. */
    private class SevenZEntryStream(private val z: SevenZFile) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = z.read(b, off, len)
    }

    // ------------------------------------------------------------------
    // ZIP (zip4j)
    // ------------------------------------------------------------------

    /**
     * Pages de code essayées pour les noms qui ne sont pas en UTF-8. L'Explorateur
     * Windows écrit les noms en page OEM — 850 en Europe de l'Ouest, 437 aux États-Unis :
     * lus comme de l'UTF-8, « Été.txt » devenait « �t�.txt », et java.util.zip refusait
     * carrément l'archive (« MALFORMED »).
     */
    private val LEGACY_CHARSETS = listOf("IBM850", "IBM437", "windows-1252")

    private fun openZip(file: File, password: CharArray?): net.lingala.zip4j.ZipFile {
        val first = net.lingala.zip4j.ZipFile(file, password)
        val names = runCatching { first.fileHeaders.map { it.fileName } }
            .getOrElse { first.close(); throw ArchiveUnsupported("Archive ZIP endommagée ou illisible") }
        if (names.none { it.contains('�') }) return first
        first.close()
        for (name in LEGACY_CHARSETS) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val retry = net.lingala.zip4j.ZipFile(file, password)
            retry.charset = charset
            if (runCatching { retry.fileHeaders.size }.isSuccess) return retry
            retry.close()
        }
        return net.lingala.zip4j.ZipFile(file, password)
    }

    private fun summarizeZip(file: File): ArchiveSummary = openZip(file, null).use { zip ->
        val headers = zip.fileHeaders
        val files = headers.filter { !it.isDirectory }
        ArchiveSummary(
            ArchiveFormat.ZIP,
            files.size,
            files.sumOf { it.uncompressedSize.coerceAtLeast(0) },
            files.any { it.isEncrypted }
        )
    }

    private fun extractZip(file: File, password: CharArray?, sink: Sink) {
        openZip(file, password).use { zip ->
            for (h in zip.fileHeaders) {
                sink.checkCancel()
                when {
                    h.isDirectory -> sink.directory(h.fileName)
                    isUnixLink(h.externalFileAttributes) -> sink.skip()
                    else -> {
                        if (h.isEncrypted && password == null) throw ArchivePasswordRequired()
                        try {
                            zip.getInputStream(h).use { sink.file(h.fileName, it, h.uncompressedSize) }
                        } catch (e: net.lingala.zip4j.exception.ZipException) {
                            if (e.type == net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD) {
                                throw ArchiveWrongPassword()
                            }
                            throw e
                        }
                    }
                }
            }
        }
    }

    /** Mode Unix d'une entrée ZIP (16 bits hauts des attributs externes) : lien symbolique ? */
    private fun isUnixLink(attributes: ByteArray?): Boolean {
        if (attributes == null || attributes.size < 4) return false
        val mode = (attributes[2].toInt() and 0xFF) or ((attributes[3].toInt() and 0xFF) shl 8)
        return mode and 0xF000 == 0xA000
    }

    // ------------------------------------------------------------------
    // RAR (junrar)
    // ------------------------------------------------------------------

    private fun openRar(file: File, password: CharArray?): com.github.junrar.Archive {
        // Un volume « .part2.rar » touché par l'utilisateur : on repart du premier.
        val first = RAR_VOLUME.matchEntire(file.name)?.let { m ->
            val width = m.groupValues[2].length
            File(file.parentFile, m.groupValues[1] + ".part" + "1".padStart(width, '0') + ".rar").takeIf { it.isFile }
        } ?: file
        val options = com.github.junrar.ArchiveOptions.builder().apply {
            if (password != null) password(password)
        }.build()
        return try {
            com.github.junrar.Archive(first, options)
        } catch (e: com.github.junrar.exception.WrongPasswordException) {
            // En-têtes chiffrés sans mot de passe : junrar parle de « mauvais » mot de
            // passe, alors qu'il n'en a simplement reçu aucun.
            throw if (password == null) ArchivePasswordRequired() else ArchiveWrongPassword()
        } catch (e: com.github.junrar.exception.RarException) {
            if (password == null && e.message.orEmpty().contains("password", true)) throw ArchivePasswordRequired()
            throw rarError(e)
        }
    }

    private fun rarError(e: Throwable): IOException = when (e) {
        is com.github.junrar.exception.WrongPasswordException -> ArchiveWrongPassword()
        is com.github.junrar.exception.MissingNextVolumeException ->
            ArchiveUnsupported("Il manque une partie de l'archive (fichier .partN.rar suivant)")
        is com.github.junrar.exception.UnsupportedRarEncryptedException -> ArchivePasswordRequired()
        is com.github.junrar.exception.UnsupportedRarMethodException,
        is com.github.junrar.exception.UnsupportedRarVersionException ->
            ArchiveUnsupported("Méthode de compression RAR non prise en charge")
        is IOException -> e
        else -> IOException(e.message ?: "Archive RAR endommagée", e)
    }

    private fun summarizeRar(file: File, password: CharArray?): ArchiveSummary {
        val rar = try {
            openRar(file, password)
        } catch (e: ArchivePasswordRequired) {
            return ArchiveSummary(ArchiveFormat.RAR, null, null, true)
        }
        rar.use {
            val files = it.fileHeaders.filter { h -> !h.isDirectory }
            val encrypted = runCatching { it.isEncrypted }.getOrDefault(false) || files.any { h -> h.isEncrypted }
            return ArchiveSummary(ArchiveFormat.RAR, files.size, files.sumOf { h -> h.fullUnpackSize.coerceAtLeast(0) }, encrypted)
        }
    }

    private class EnoughRead : IOException()

    /** Contenu d'un petit fichier RAR, plafonné à sa taille annoncée : une archive qui ment n'emplit pas la mémoire. */
    private class CappedBytes(private val cap: Int) : java.io.ByteArrayOutputStream(cap) {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (count + len > cap) throw IOException("Taille annoncée inexacte dans l'archive RAR")
            super.write(b, off, len)
        }
    }

    private fun checkRarPassword(file: File, password: CharArray): Boolean = openRar(file, password).use { rar ->
        val h = rar.fileHeaders.firstOrNull { it.isEncrypted && !it.isDirectory } ?: return true
        val probe = object : OutputStream() {
            var seen = 0
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) { seen += len; if (seen >= 64 * 1024) throw EnoughRead() }
        }
        try {
            rar.extractFile(h, probe); true
        } catch (e: Exception) {
            generateSequence(e as Throwable) { it.cause }.any { it is EnoughRead }
        }
    }

    private fun extractRar(file: File, password: CharArray?, sink: Sink) {
        openRar(file, password).use { rar ->
            for (h in rar.fileHeaders) {
                sink.checkCancel()
                val name = h.fileName
                when {
                    h.isDirectory -> sink.directory(name)
                    h.redirection != null || isRarUnixLink(h) -> sink.skip()
                    else -> {
                        if (h.isEncrypted && password == null) throw ArchivePasswordRequired()
                        val size = h.fullUnpackSize
                        try {
                            if (size in 0..SMALL_FILE) {
                                // Petit fichier : décompressé en mémoire, écrit en parallèle.
                                val bytes = CappedBytes(size.toInt())
                                rar.extractFile(h, bytes)
                                sink.fileBytes(name, bytes.toByteArray())
                            } else {
                                val (target, out) = sink.fileOutput(name) ?: continue
                                out.use { rar.extractFile(h, it) }
                                sink.finished(target)
                            }
                        } catch (e: Exception) {
                            // junrar enveloppe les erreurs de notre flux : on retrouve la vraie.
                            val ours = generateSequence(e as Throwable) { it.cause }
                                .firstOrNull {
                                    it is ArchiveCancelled || it is ArchiveNoSpace ||
                                        it is SecurityException || it is ArchiveUnsupported
                                }
                            if (ours != null) throw ours
                            throw rarError(e)
                        }
                    }
                }
            }
        }
    }

    private fun isRarUnixLink(h: com.github.junrar.rarfile.FileHeader): Boolean =
        runCatching { h.hostOS == com.github.junrar.rarfile.HostSystem.unix && (h.fileAttr and 0xF000) == 0xA000 }
            .getOrDefault(false)

    // ------------------------------------------------------------------
    // TAR et fichiers compressés isolés
    // ------------------------------------------------------------------

    /** Compte les octets lus dans le fichier : la progression d'un .tar.gz se mesure ainsi. */
    private class CountingInput(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private fun decompress(input: InputStream, format: ArchiveFormat): InputStream = when (format) {
        ArchiveFormat.GZ, ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(input, true)
        ArchiveFormat.BZ2, ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(input, true)
        ArchiveFormat.XZ, ArchiveFormat.TAR_XZ -> XZCompressorInputStream(input, true)
        else -> input
    }

    /** Le flux décompressé commence-t-il par un en-tête TAR ? (« photos.gz » qui est un .tar.gz) */
    private fun containsTar(file: File, format: ArchiveFormat): Boolean = runCatching {
        BufferedInputStream(FileInputStream(file)).use { raw ->
            decompress(raw, format).use { stream ->
                val head = ByteArray(512)
                val n = readFully(stream, head)
                n == 512 && TarArchiveInputStream.matches(head, n)
            }
        }
    }.getOrDefault(false)

    private fun isTar(file: File): Boolean = runCatching {
        FileInputStream(file).use { val head = ByteArray(512); val n = readFully(it, head); n == 512 && TarArchiveInputStream.matches(head, n) }
    }.getOrDefault(false)

    private fun xzLength(file: File): Long? = runCatching {
        org.tukaani.xz.SeekableXZInputStream(org.tukaani.xz.SeekableFileInputStream(file)).use { it.length() }
    }.getOrNull()

    private fun extractTar(file: File, format: ArchiveFormat, sink: Sink) {
        val counting = CountingInput(BufferedInputStream(FileInputStream(file), BUFFER))
        val size = file.length()
        sink.sourceProgress = { counting.count to size }
        counting.use {
            TarArchiveInputStream(decompress(counting, format)).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    sink.checkCancel()
                    when {
                        e.isDirectory -> sink.directory(e.name)
                        e.isFile && !e.isLink && !e.isSymbolicLink -> sink.file(e.name, tar, e.size)
                        // Liens et fichiers spéciaux (périphériques, tubes) : jamais créés.
                        else -> sink.skip()
                    }
                    e = tar.nextEntry
                }
            }
        }
    }

    private fun extractSingle(file: File, format: ArchiveFormat, output: File, sink: Sink) {
        val counting = CountingInput(BufferedInputStream(FileInputStream(file), BUFFER))
        val size = file.length()
        sink.sourceProgress = { counting.count to size }
        counting.use { decompress(it, format).use { stream -> sink.write(output, output.name, stream); sink.checkCancel() } }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var n = 0
        while (n < buffer.size) {
            val r = input.read(buffer, n, buffer.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}
