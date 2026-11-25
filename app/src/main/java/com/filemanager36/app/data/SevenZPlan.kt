package com.filemanager36.app.data

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Plan de décodage parallèle d'un 7z : ses blocs compressés (« folders »), chacun avec sa
 * position dans le fichier, sa chaîne de décodeurs et la liste ordonnée de ses entrées.
 *
 * ## Pourquoi la réflexion
 * Commons Compress ne décode un 7z que d'entrée en entrée, dans un seul fil, et chaque
 * instance de [SevenZFile] garde en mémoire tout l'en-tête — 245 000 entrées, plus de
 * 100 Mo, pour l'archive qui a motivé ce code : trois instances en parallèle épuisaient
 * déjà la mémoire de l'application. Les informations nécessaires (positions des blocs,
 * décodeurs) sont dans l'instance, mais pas exposées. On les lit donc par réflexion,
 * une fois, et l'on reconstruit pour chaque bloc la même chaîne de décodeurs que la
 * bibliothèque elle-même (`SevenZFile.buildDecoderStack`).
 *
 * **Toute anomalie renvoie null** et l'extraction se fait alors par le chemin séquentiel
 * habituel : plus lente, jamais fausse. `ArchivesTest` échoue si une mise à jour de la
 * bibliothèque fait retomber les archives de test sur ce chemin.
 *
 * Champs lus (Commons Compress 1.28) : `SevenZFile.archive` ; `Archive.packPos`,
 * `packSizes`, `folders`, `files`, `streamMap` ; `StreamMap.folderFirstPackStreamIndex`,
 * `packStreamOffsets`, `fileFolderIndex` ; `Folder.packedStreams`,
 * `numUnpackSubStreams`, `hasCrc`, `crc`, `getOrderedCoders()`, `getUnpackSizeForCoder()` ;
 * `Coder.numInStreams`, `numOutStreams`, `decompressionMethodId`, `properties` ;
 * `Coders.addDecoder(…)`.
 */
internal class SevenZPlan private constructor(
    /** Dossiers, fichiers vides, éléments supprimés : aucune donnée dans un bloc. */
    val streamless: List<SevenZArchiveEntry>,
    val blocks: List<Block>,
    private val addDecoderMethod: Method
) {
    class Block(
        val index: Int,
        /** Position du flux compressé dans l'archive (volumes mis bout à bout). */
        val offset: Long,
        val packedSize: Long,
        /** Décodeurs dans l'ordre d'application, avec la taille de sortie de chacun. */
        val coders: List<Pair<Any, Long>>,
        /** Entrées du bloc, dans l'ordre du flux décodé. */
        val entries: List<SevenZArchiveEntry>,
        /** Mémoire du décodeur : son dictionnaire, alloué d'un coup. */
        val memory: Long,
        /**
         * Somme de contrôle du bloc décodé entier. Pour un bloc d'un seul fichier, c'est
         * souvent la seule : l'entrée elle-même n'en porte pas (archives de Commons
         * Compress notamment).
         */
        val folderCrc: Long?
    ) {
        val averageSize: Long get() = if (entries.isEmpty()) 0 else entries.sumOf { it.size } / entries.size
    }

    /** Le même empilement que la bibliothèque, décodeur par décodeur. */
    fun addDecoder(archiveName: String, input: InputStream, unpackSize: Long, coder: Any, limitKiB: Int): InputStream =
        try {
            addDecoderMethod.invoke(null, archiveName, input, unpackSize, coder, null, limitKiB) as InputStream
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    companion object {
        /** Taille de l'en-tête de signature, avant le premier flux compressé. */
        private const val SIGNATURE_HEADER_SIZE = 32L

        private val AES = byteArrayOf(0x06, 0xF1.toByte(), 0x07, 0x01)
        private val LZMA2 = byteArrayOf(0x21)
        private val LZMA = byteArrayOf(0x03, 0x01, 0x01)

        /** Le plan, ou null si l'archive (ou la bibliothèque) ne s'y prête pas. */
        fun of(file: File, open: (File, CharArray?) -> SevenZFile): SevenZPlan? = try {
            open(file, null).use { build(it) }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }

        private fun build(z: SevenZFile): SevenZPlan? {
            val archive = z.field("archive") ?: return null
            val packPos = archive.field("packPos") as Long
            val packSizes = archive.field("packSizes") as LongArray
            val folders = archive.field("folders") as Array<*>
            val files = archive.field("files") as Array<*>
            val map = archive.field("streamMap") ?: return null
            val firstPack = map.field("folderFirstPackStreamIndex") as IntArray
            val packOffsets = map.field("packStreamOffsets") as LongArray
            val fileFolder = map.field("fileFolderIndex") as IntArray
            if (fileFolder.size != files.size || firstPack.size != folders.size) return null

            val streamless = ArrayList<SevenZArchiveEntry>()
            val perFolder = Array(folders.size) { ArrayList<SevenZArchiveEntry>() }
            for (i in files.indices) {
                val e = files[i] as SevenZArchiveEntry
                val f = fileFolder[i]
                if (f < 0) {
                    if (e.hasStream()) return null
                    streamless += e
                } else {
                    if (!e.hasStream() || f >= folders.size) return null
                    perFolder[f] += e
                }
            }

            val codersClass = Class.forName("org.apache.commons.compress.archivers.sevenz.Coders")
            var addDecoder: Method? = null
            val blocks = ArrayList<Block>()
            for (fi in folders.indices) {
                val folder = folders[fi] ?: return null
                val entries = perFolder[fi]
                if (entries.isEmpty()) continue
                if ((folder.field("numUnpackSubStreams") as Int) != entries.size) return null
                if ((folder.field("packedStreams") as LongArray).size != 1) return null

                val ordered = folder.call("getOrderedCoders") as Iterable<*>
                val coders = ArrayList<Pair<Any, Long>>()
                var memory = 16L shl 20
                for (coder in ordered) {
                    coder ?: return null
                    if (coder.field("numInStreams") as Long != 1L || coder.field("numOutStreams") as Long != 1L) return null
                    val id = coder.field("decompressionMethodId") as ByteArray
                    if (id.contentEquals(AES)) return null
                    val props = coder.field("properties") as ByteArray?
                    memory = maxOf(memory, dictionarySize(id, props))
                    val method = folder.javaClass.getDeclaredMethod("getUnpackSizeForCoder", coder.javaClass)
                        .apply { isAccessible = true }
                    coders += coder to (method.invoke(folder, coder) as Long)
                    if (addDecoder == null) {
                        addDecoder = codersClass.getDeclaredMethod(
                            "addDecoder",
                            String::class.java, InputStream::class.java, Long::class.javaPrimitiveType,
                            coder.javaClass, ByteArray::class.java, Int::class.javaPrimitiveType
                        ).apply { isAccessible = true }
                    }
                }
                if (coders.isEmpty()) return null
                val pack = firstPack[fi]
                if (pack < 0 || pack >= packSizes.size) return null
                blocks += Block(
                    index = fi,
                    offset = SIGNATURE_HEADER_SIZE + packPos + packOffsets[pack],
                    packedSize = packSizes[pack],
                    coders = coders,
                    entries = entries,
                    memory = memory,
                    folderCrc = if (folder.field("hasCrc") as Boolean) folder.field("crc") as Long else null
                )
            }
            val method = addDecoder ?: codersClass.declaredMethods.firstOrNull { it.name == "addDecoder" }
                ?.apply { isAccessible = true } ?: return null
            return SevenZPlan(streamless, blocks, method)
        }

        /** Dictionnaire LZMA/LZMA2 d'après les propriétés du décodeur. */
        private fun dictionarySize(id: ByteArray, props: ByteArray?): Long {
            if (props == null) return 0
            if (id.contentEquals(LZMA2) && props.isNotEmpty()) {
                val p = props[0].toInt() and 0xFF
                if (p >= 40) return 0xFFFFFFFFL
                return (2L or (p and 1).toLong()) shl (p / 2 + 11)
            }
            if (id.contentEquals(LZMA) && props.size >= 5) {
                return (props[1].toLong() and 0xFF) or ((props[2].toLong() and 0xFF) shl 8) or
                    ((props[3].toLong() and 0xFF) shl 16) or ((props[4].toLong() and 0xFF) shl 24)
            }
            return 0
        }

        private fun Any.field(name: String): Any? {
            var c: Class<*>? = javaClass
            while (c != null) {
                try {
                    return c.getDeclaredField(name).apply { isAccessible = true }.get(this)
                } catch (e: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            throw NoSuchFieldException(name)
        }

        private fun Any.call(name: String): Any? =
            javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)
    }
}
