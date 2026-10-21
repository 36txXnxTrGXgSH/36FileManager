package com.filemanager36.app.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement des petits secrets rangés dans les préférences — aujourd'hui les mots de
 * passe des lecteurs réseau SMB.
 *
 * Jusqu'à la 2.0.2 ils y étaient écrits **en clair**. Le fichier de préférences est bien
 * privé à l'application, et `allowBackup="false"` empêche la sauvegarde ADB, mais un
 * appareil rooté, une image de la mémoire ou une restauration d'usine mal faite les
 * exposaient tels quels. La clé vit ici dans l'AndroidKeystore : elle ne quitte jamais
 * le matériel sécurisé, et le contenu des préférences devient inexploitable seul.
 *
 * Aucune authentification utilisateur n'est exigée sur cette clé, contrairement à celle
 * du coffre-fort : une connexion réseau doit pouvoir se rétablir sans réveiller
 * l'utilisateur. Ce n'est donc pas la même protection que le coffre — c'est la
 * différence entre « illisible dans un dump » et « illisible sans le doigt du
 * propriétaire ».
 */
object SecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "36fm_prefs_key"
    private const val GCM_TAG_BITS = 128

    /** Préfixe de format. Son absence signale une valeur héritée, écrite en clair. */
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: generate()
    }.getOrNull()

    private fun generate(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /**
     * @return la valeur chiffrée, ou la valeur d'origine si le Keystore est indisponible.
     *
     * Le repli en clair est délibéré : sur une poignée de surcouches, le Keystore refuse
     * de générer une clé. Perdre le mot de passe du lecteur réseau serait une régression
     * bien réelle pour un gain hypothétique. [decrypt] reconnaît les deux formes.
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        val k = key() ?: return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + b64(cipher.iv) + ":" + b64(out)
        }.getOrDefault(plain)
    }

    /**
     * Déchiffre une valeur produite par [encrypt].
     *
     * Une valeur sans préfixe est rendue telle quelle : c'est un mot de passe enregistré
     * par une version antérieure à la 2.1.0. Il sera ré-écrit chiffré au premier
     * enregistrement des serveurs, sans que l'utilisateur ait rien à ressaisir.
     */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val parts = stored.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return ""
        val k = key() ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, unb64(parts[0])))
            String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
