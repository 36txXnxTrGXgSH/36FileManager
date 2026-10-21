package com.filemanager36.app.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Coffre-fort : les fichiers déplacés ici sont chiffrés en AES-256 dans le stockage
 * privé de l'application. Ils sont invisibles depuis les autres applications et
 * depuis un ordinateur relié en USB.
 */
class VaultRepository(context: Context, private val prefs: Prefs) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val vaultDir: File = File(appContext.filesDir, "vault").apply { mkdirs() }
    private val indexFile: File = File(vaultDir, "index.enc")

    /** Clé de données en mémoire, non nulle uniquement quand le coffre est déverrouillé. */
    @Volatile
    var dataKey: SecretKey? = null
        private set

    val isUnlocked: Boolean get() = dataKey != null
    val isConfigured: Boolean get() = prefs.isVaultConfigured

    // ------------------------------------------------------------------
    // Configuration / déverrouillage
    // ------------------------------------------------------------------

    /** Première configuration : crée le sel, la DEK et l'emballe avec le code choisi. */
    fun setup(password: CharArray): Result<Unit> = runCatching {
        val salt = VaultCrypto.randomBytes(16)
        val kek = VaultCrypto.deriveKey(password, salt)
        val dek = VaultCrypto.newDataKey()
        prefs.vaultSalt = VaultCrypto.encode(salt)
        prefs.vaultWrappedKey = VaultCrypto.wrapDataKey(kek, dek)
        dataKey = dek
        saveIndex(emptyList())
    }

    fun unlockWithPassword(password: CharArray): Boolean {
        val saltB64 = prefs.vaultSalt ?: return false
        val wrapped = prefs.vaultWrappedKey ?: return false
        return runCatching {
            val kek = VaultCrypto.deriveKey(password, VaultCrypto.decode(saltB64))
            dataKey = VaultCrypto.unwrapDataKey(kek, wrapped)
            true
        }.getOrDefault(false)
    }

    fun unlockWithKey(key: SecretKey) { dataKey = key }

    fun lock() { dataKey = null }

    fun changePassword(oldPassword: CharArray, newPassword: CharArray): Result<Unit> = runCatching {
        check(unlockWithPassword(oldPassword)) { "Code actuel incorrect" }
        val dek = dataKey!!
        val salt = VaultCrypto.randomBytes(16)
        val kek = VaultCrypto.deriveKey(newPassword, salt)
        prefs.vaultSalt = VaultCrypto.encode(salt)
        prefs.vaultWrappedKey = VaultCrypto.wrapDataKey(kek, dek)
    }

    // ------------------------------------------------------------------
    // Index chiffré
    // ------------------------------------------------------------------

    fun loadIndex(): List<VaultEntry> {
        val dek = dataKey ?: return emptyList()
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val blob = indexFile.readText()
            val plain = VaultCrypto.gcmDecrypt(dek, blob).toString(Charsets.UTF_8)
            json.decodeFromString<List<VaultEntry>>(plain)
        }.getOrDefault(emptyList())
    }

    private fun saveIndex(entries: List<VaultEntry>) {
        // Échouer bruyamment : un enregistrement silencieusement ignoré alors que le
        // coffre vient d'être verrouillé orphelinerait des fichiers déjà chiffrés.
        val dek = dataKey ?: error("Coffre verrouillé")
        val plain = json.encodeToString(entries).toByteArray(Charsets.UTF_8)
        val tmp = File(vaultDir, "index.tmp")
        tmp.writeText(VaultCrypto.gcmEncrypt(dek, plain))
        if (indexFile.exists()) indexFile.delete()
        tmp.renameTo(indexFile)
    }

    // ------------------------------------------------------------------
    // Ajouter / restaurer / supprimer
    // ------------------------------------------------------------------

    private fun blobFile(id: String) = File(vaultDir, "$id.bin")

    /**
     * Déplace des fichiers vers le coffre : chiffrement puis suppression de l'original.
     * Un dossier est d'abord compressé en ZIP, puis le ZIP est chiffré.
     */
    fun importFiles(
        sources: List<File>,
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }
    ): FileOperations.OperationResult {
        val dek = dataKey ?: return FileOperations.OperationResult(0, listOf("Coffre verrouillé"))
        val entries = loadIndex().toMutableList()
        val failed = mutableListOf<String>()
        var ok = 0
        val total = sources.size

        sources.forEachIndexed { index, src ->
            onProgress(src.name, index, total)
            try {
                val isDir = src.isDirectory
                val payload: File
                var temp: File? = null
                if (isDir) {
                    temp = File(appContext.cacheDir, "vault_${System.currentTimeMillis()}.zip")
                    FileOperations.zip(listOf(src), temp).getOrThrow()
                    payload = temp
                } else {
                    payload = src
                }

                val id = UUID.randomUUID().toString()
                val out = blobFile(id)
                encryptTo(payload, out, dek)

                entries += VaultEntry(
                    id = id,
                    name = if (isDir) "${src.name}.zip" else src.name,
                    originalPath = src.absolutePath,
                    size = payload.length(),
                    addedAt = System.currentTimeMillis(),
                    isDirectory = isDir,
                    format = VaultEntry.FORMAT_AUTHENTICATED
                )
                // L'index est écrit AVANT la suppression de l'original : si l'application
                // est tuée à cet instant, le fichier chiffré reste référencé.
                saveIndex(entries)
                temp?.delete()
                FileOperations.deleteRecursive(src)
                ok++
            } catch (e: Exception) {
                failed += "${src.name} : ${e.message ?: "erreur"}"
            }
        }
        saveIndex(entries)
        onProgress("", total, total)
        return FileOperations.OperationResult(ok, failed)
    }

    /** Restaure une entrée vers un dossier du stockage, puis la retire du coffre. */
    fun exportEntry(entry: VaultEntry, destDir: File, removeFromVault: Boolean): Result<File> = runCatching {
        val dek = dataKey ?: error("Coffre verrouillé")
        if (!destDir.exists()) destDir.mkdirs()
        val target = FileOperations.uniqueTarget(destDir, entry.name)
        try {
            decryptTo(entry, target, dek)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        // On ne détruit la version chiffrée qu'après une restauration vérifiée.
        if (entry.size > 0 && target.length() != entry.size) {
            target.delete()
            error("Restauration incomplète de « ${entry.name} »")
        }
        if (removeFromVault) {
            blobFile(entry.id).delete()
            saveIndex(loadIndex().filterNot { it.id == entry.id })
        }
        target
    }

    /** Déchiffre temporairement dans le cache pour ouverture / aperçu. */
    fun decryptToCache(entry: VaultEntry): Result<File> = runCatching {
        val dek = dataKey ?: error("Coffre verrouillé")
        val dir = File(appContext.cacheDir, "vault_open").apply { mkdirs() }
        val target = File(dir, entry.name)
        if (target.exists()) target.delete()
        decryptTo(entry, target, dek)
        target
    }

    /**
     * Chiffre [source] vers [target] au format authentifié (voir [VaultEntry.format]).
     *
     * Disposition : `IV(16) || AES-256-CBC(contenu) || HMAC-SHA256(32)`, le HMAC
     * couvrant l'IV **et** le texte chiffré — « chiffrer puis authentifier », le seul
     * ordre qui interdise à un attaquant de faire travailler le déchiffrement sur des
     * données de son choix.
     */
    private fun encryptTo(source: File, target: File, dek: SecretKey) {
        val iv = VaultCrypto.newFileIv()
        val cipher = VaultCrypto.fileCipher(dek, true, iv)
        val mac = VaultCrypto.newMac(dek)
        mac.update(iv)
        FileOutputStream(target).use { fos ->
            fos.write(iv)
            FileInputStream(source).use { fis ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val r = fis.read(buffer)
                    if (r <= 0) break
                    cipher.update(buffer, 0, r)?.let { chunk ->
                        mac.update(chunk)
                        fos.write(chunk)
                    }
                }
            }
            cipher.doFinal()?.let { tail ->
                mac.update(tail)
                fos.write(tail)
            }
            fos.write(mac.doFinal())
        }
    }

    /**
     * Déchiffrement explicite (sans CipherInputStream, qui avale silencieusement les
     * erreurs de remplissage et produirait un fichier tronqué présenté comme valide).
     *
     * Sur une entrée au format 2, le HMAC est recalculé pendant la lecture et vérifié à
     * la fin : une altération du fichier chiffré fait échouer la restauration au lieu de
     * livrer un contenu silencieusement modifié. Le fichier partiel est effacé — un
     * demi-fichier présenté comme valide serait pire que pas de fichier du tout.
     *
     * Le format 1 (coffres créés avant la 2.1.0) est lu comme avant, sans vérification :
     * il n'y a rien à vérifier. Ces entrées passent au format 2 dès qu'elles sont
     * restaurées puis replacées dans le coffre.
     */
    private fun decryptTo(entry: VaultEntry, target: File, dek: SecretKey) {
        val blob = blobFile(entry.id)
        if (!blob.exists()) error("Fichier chiffré introuvable")
        val authenticated = entry.format >= VaultEntry.FORMAT_AUTHENTICATED
        val macLength = if (authenticated) VaultCrypto.MAC_BYTES else 0
        var payload = blob.length() - 16 - macLength
        if (payload < 0) error("Fichier chiffré incomplet : « ${entry.name} »")

        try {
            java.io.DataInputStream(FileInputStream(blob).buffered()).use { input ->
                val iv = ByteArray(16)
                input.readFully(iv)
                val cipher = VaultCrypto.fileCipher(dek, false, iv)
                val mac = if (authenticated) VaultCrypto.newMac(dek).apply { update(iv) } else null
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (payload > 0) {
                        val want = minOf(buffer.size.toLong(), payload).toInt()
                        val r = input.read(buffer, 0, want)
                        if (r <= 0) break
                        payload -= r
                        mac?.update(buffer, 0, r)
                        cipher.update(buffer, 0, r)?.let { out.write(it) }
                    }
                    cipher.doFinal()?.let { out.write(it) }
                }
                if (mac != null) {
                    val expected = ByteArray(VaultCrypto.MAC_BYTES)
                    input.readFully(expected)
                    if (!VaultCrypto.macEquals(expected, mac.doFinal())) {
                        error("« ${entry.name} » a été altéré depuis son chiffrement")
                    }
                }
            }
        } catch (e: Throwable) {
            runCatching { target.delete() }
            throw e
        }
    }

    fun deleteEntries(ids: Set<String>) {
        ids.forEach { blobFile(it).delete() }
        saveIndex(loadIndex().filterNot { ids.contains(it.id) })
    }

    fun clearOpenCache() {
        File(appContext.cacheDir, "vault_open").listFiles()?.forEach { it.delete() }
    }
}
