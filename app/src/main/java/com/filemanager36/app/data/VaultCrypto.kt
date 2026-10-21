package com.filemanager36.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives cryptographiques du coffre-fort.
 *
 * - Une clé de données aléatoire (DEK, AES-256) chiffre le contenu des fichiers.
 * - La DEK est « emballée » par une clé dérivée du code (PBKDF2-HMAC-SHA256, 120 000 itérations)
 *   et, si l'utilisateur l'active, par une clé Android Keystore exigeant la biométrie.
 * - Le code de l'utilisateur n'est jamais stocké.
 */
object VaultCrypto {

    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val KEYSTORE = "AndroidKeyStore"
    const val BIO_KEY_ALIAS = "36fm_vault_bio_key"

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Dérive une clé AES-256 à partir du code de l'utilisateur. */
    fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    fun newDataKey(): SecretKey = SecretKeySpec(randomBytes(KEY_BITS / 8), "AES")

    // ---------- Emballage AES-GCM (petits secrets) ----------

    fun gcmEncrypt(key: SecretKey, plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = randomBytes(12)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val out = cipher.doFinal(plain)
        return encode(iv) + ":" + encode(out)
    }

    fun gcmDecrypt(key: SecretKey, blob: String): ByteArray {
        val parts = blob.split(":")
        require(parts.size == 2) { "Format invalide" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, decode(parts[0])))
        return cipher.doFinal(decode(parts[1]))
    }

    fun wrapDataKey(kek: SecretKey, dek: SecretKey): String = gcmEncrypt(kek, dek.encoded)

    fun unwrapDataKey(kek: SecretKey, wrapped: String): SecretKey =
        SecretKeySpec(gcmDecrypt(kek, wrapped), "AES")

    // ---------- Chiffrement du contenu des fichiers ----------
    // AES-256-CBC : diffusion en flux, sans mise en mémoire tampon complète,
    // ce qui permet de traiter de très gros fichiers (vidéos) sans saturer la RAM.

    fun fileCipher(dek: SecretKey, encrypt: Boolean, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val spec = javax.crypto.spec.IvParameterSpec(iv)
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, dek, spec)
        return cipher
    }

    fun newFileIv(): ByteArray = randomBytes(16)

    // ---------- Authentification du contenu (format 2) ----------
    //
    // AES-CBC chiffre mais ne signe pas : une altération d'un blob — un octet retourné
    // par un secteur défaillant, une modification délibérée sur un appareil rooté —
    // produisait un fichier corrompu que rien ne distinguait d'un fichier valide. Un
    // HMAC-SHA256 couvrant l'IV **et** le texte chiffré rend l'altération détectable.
    //
    // La clé du HMAC est dérivée de la DEK plutôt que d'être la DEK elle-même : une même
    // clé ne doit jamais servir à deux primitives différentes.

    const val MAC_BYTES = 32

    fun macKey(dek: SecretKey): SecretKey {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(dek.encoded)
        digest.update("36fm-vault-mac".toByteArray(Charsets.US_ASCII))
        return SecretKeySpec(digest.digest(), "HmacSHA256")
    }

    fun newMac(dek: SecretKey): javax.crypto.Mac =
        javax.crypto.Mac.getInstance("HmacSHA256").apply { init(macKey(dek)) }

    /** Comparaison à temps constant : un `contentEquals` laisse fuir le préfixe correct. */
    fun macEquals(a: ByteArray, b: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(a, b)

    // ---------- Clé biométrique (Android Keystore) ----------

    fun createBiometricKey() {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(BIO_KEY_ALIAS)) ks.deleteEntry(BIO_KEY_ALIAS)
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            BIO_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        kg.init(spec)
        kg.generateKey()
    }

    fun deleteBiometricKey() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(BIO_KEY_ALIAS)) ks.deleteEntry(BIO_KEY_ALIAS)
        }
    }

    private fun biometricKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        ks.getKey(BIO_KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    /** Cipher en mode chiffrement, à passer à BiometricPrompt.CryptoObject. */
    fun biometricEncryptCipher(): Cipher? = runCatching {
        val key = biometricKey() ?: return null
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }.getOrNull()

    /** Cipher en mode déchiffrement à partir du blob « iv:cipher ». */
    fun biometricDecryptCipher(blob: String): Cipher? = runCatching {
        val key = biometricKey() ?: return null
        val iv = decode(blob.split(":")[0])
        Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv)) }
    }.getOrNull()

    fun sealWithBiometricCipher(cipher: Cipher, dek: SecretKey): String {
        val out = cipher.doFinal(dek.encoded)
        return encode(cipher.iv) + ":" + encode(out)
    }

    fun openWithBiometricCipher(cipher: Cipher, blob: String): SecretKey {
        val payload = decode(blob.split(":")[1])
        return SecretKeySpec(cipher.doFinal(payload), "AES")
    }
}
