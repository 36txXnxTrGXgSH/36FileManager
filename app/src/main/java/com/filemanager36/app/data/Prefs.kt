package com.filemanager36.app.data

import android.content.Context
import android.content.SharedPreferences
import com.filemanager36.app.util.SecretStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Préférences et données légères persistées : réglages d'affichage, favoris,
 * serveurs réseau enregistrés, matériel cryptographique du coffre-fort.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("36fm_prefs", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        migrateShareManage()
    }

    /**
     * Ouvre une fois `allowManage` sur les installations qui ont enregistré un réglage
     * de partage avant la 2.3.1.
     *
     * Sans cela, changer la valeur par défaut dans [ShareConfig] ne servirait à rien :
     * `encodeDefaults = true` écrit **tous** les champs, donc un réglage sauvegardé sous
     * la 2.2.0 contient `"allowManage":false` et écrase le nouveau défaut à la relecture.
     * L'appareil resterait sans boutons de renommage, exactement le symptôme signalé.
     *
     * Ne s'exécute qu'une fois : celui qui referme ensuite l'interrupteur le garde fermé.
     */
    private fun migrateShareManage() {
        if (sp.getBoolean(K_SHARE_MANAGE_MIGRATED, false)) return
        val raw = sp.getString(K_SHARE, null)
        if (raw != null) {
            val current = runCatching { json.decodeFromString<ShareConfig>(raw) }.getOrNull()
            if (current != null && !current.allowManage) {
                sp.edit().putString(K_SHARE, json.encodeToString(current.copy(allowManage = true))).apply()
            }
        }
        sp.edit().putBoolean(K_SHARE_MANAGE_MIGRATED, true).apply()
    }

    // ---------- Réglages d'affichage ----------

    var sortField: SortField
        get() = runCatching { SortField.valueOf(sp.getString(K_SORT, SortField.NAME.name)!!) }
            .getOrDefault(SortField.NAME)
        set(v) = sp.edit().putString(K_SORT, v.name).apply()

    var sortAscending: Boolean
        get() = sp.getBoolean(K_SORT_ASC, true)
        set(v) = sp.edit().putBoolean(K_SORT_ASC, v).apply()

    var showHidden: Boolean
        get() = sp.getBoolean(K_HIDDEN, false)
        set(v) = sp.edit().putBoolean(K_HIDDEN, v).apply()

    var viewMode: ViewMode
        get() = runCatching { ViewMode.valueOf(sp.getString(K_VIEW, ViewMode.LIST.name)!!) }
            .getOrDefault(ViewMode.LIST)
        set(v) = sp.edit().putString(K_VIEW, v.name).apply()

    var foldersFirst: Boolean
        get() = sp.getBoolean(K_FOLDERS_FIRST, true)
        set(v) = sp.edit().putBoolean(K_FOLDERS_FIRST, v).apply()

    /** Vrai : les catégories affichent les dossiers ; faux : tous les fichiers en vrac. */
    var categoryGrouped: Boolean
        get() = sp.getBoolean(K_CAT_GROUPED, false)
        set(v) = sp.edit().putBoolean(K_CAT_GROUPED, v).apply()

    var showThumbnails: Boolean
        get() = sp.getBoolean(K_THUMBS, true)
        set(v) = sp.edit().putBoolean(K_THUMBS, v).apply()

    // ---------- Nouveautés ----------

    /**
     * `versionCode` dont les nouveautés ont été présentées. Zéro : rien n'a jamais été
     * montré — première installation, ou mise à jour venue d'une version antérieure à
     * cette fenêtre. `Changelog.aAnnoncer` distingue les deux cas.
     */
    var derniereVersionVue: Int
        get() = sp.getInt(K_VERSION_VUE, 0)
        set(v) = sp.edit().putInt(K_VERSION_VUE, v).apply()

    // ---------- Partage réseau ----------

    var shareConfig: ShareConfig
        get() {
            val raw = sp.getString(K_SHARE, null) ?: return ShareConfig()
            return runCatching { json.decodeFromString<ShareConfig>(raw) }
                .getOrDefault(ShareConfig())
        }
        set(v) = sp.edit().putString(K_SHARE, json.encodeToString(v)).apply()

    // ---------- Favoris ----------

    fun loadFavorites(): List<FavoriteEntry> {
        val raw = sp.getString(K_FAVORITES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<FavoriteEntry>>(raw) }.getOrDefault(emptyList())
    }

    fun saveFavorites(list: List<FavoriteEntry>) {
        sp.edit().putString(K_FAVORITES, json.encodeToString(list)).apply()
    }

    // ---------- Identité de l'appareil sur le réseau ----------

    /**
     * Identifiant aléatoire tiré une fois pour toutes à la première utilisation.
     *
     * Il remplace l'ancien `(Build.MODEL + "|" + Build.DEVICE).hashCode()` : deux
     * téléphones du même modèle obtenaient le **même** identifiant, et comme la
     * découverte écarte « notre propre réponse », deux appareils identiques ne se
     * voyaient jamais. Aléatoire et local, il n'identifie l'appareil que sur le LAN.
     */
    val installId: String
        get() = sp.getString(K_INSTALL_ID, null) ?: run {
            // nextBytes et non generateSeed : ce dernier puise dans /dev/random et peut
            // bloquer plusieurs secondes au premier démarrage sur certains appareils.
            val bytes = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
            val fresh = bytes.joinToString("") { "%02x".format(it) }
            sp.edit().putString(K_INSTALL_ID, fresh).apply()
            fresh
        }

    // ---------- Serveurs réseau ----------

    /**
     * Les mots de passe sont chiffrés par une clé de l'AndroidKeystore ([SecretStore]),
     * jamais écrits en clair depuis la 2.1.0. Une entrée héritée d'une version
     * antérieure est lue telle quelle puis ré-écrite chiffrée au premier enregistrement,
     * sans rien à ressaisir.
     */
    fun loadServers(): List<SmbServer> {
        val raw = sp.getString(K_SERVERS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SmbServer>>(raw) }
            .getOrDefault(emptyList())
            .map { it.copy(password = SecretStore.decrypt(it.password)) }
    }

    fun saveServers(list: List<SmbServer>) {
        val sealed = list.map { it.copy(password = SecretStore.encrypt(it.password)) }
        sp.edit().putString(K_SERVERS, json.encodeToString(sealed)).apply()
    }

    // ---------- Dossiers cloud connectés ----------

    fun loadCloudRoots(): List<CloudRoot> {
        val raw = sp.getString(K_CLOUD, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<CloudRoot>>(raw) }.getOrDefault(emptyList())
    }

    fun saveCloudRoots(list: List<CloudRoot>) {
        sp.edit().putString(K_CLOUD, json.encodeToString(list)).apply()
    }

    // ---------- Coffre-fort ----------

    var vaultSalt: String?
        get() = sp.getString(K_VAULT_SALT, null)
        set(v) = sp.edit().putString(K_VAULT_SALT, v).apply()

    /** Clé de données (DEK) chiffrée par la clé dérivée du code : "iv:cipher" en Base64. */
    var vaultWrappedKey: String?
        get() = sp.getString(K_VAULT_WRAPPED, null)
        set(v) = sp.edit().putString(K_VAULT_WRAPPED, v).apply()

    /** DEK chiffrée par la clé Keystore protégée par la biométrie. */
    var vaultBioBlob: String?
        get() = sp.getString(K_VAULT_BIO, null)
        set(v) = sp.edit().putString(K_VAULT_BIO, v).apply()

    val isVaultConfigured: Boolean get() = vaultSalt != null && vaultWrappedKey != null

    val isBiometricEnabled: Boolean get() = vaultBioBlob != null

    fun clearVault() {
        sp.edit().remove(K_VAULT_SALT).remove(K_VAULT_WRAPPED).remove(K_VAULT_BIO).apply()
    }

    private companion object {
        const val K_SORT = "sort_field"
        const val K_SORT_ASC = "sort_asc"
        const val K_HIDDEN = "show_hidden"
        const val K_VIEW = "view_mode"
        const val K_FOLDERS_FIRST = "folders_first"
        const val K_THUMBS = "show_thumbs"
        const val K_CAT_GROUPED = "category_grouped"
        const val K_FAVORITES = "favorites"
        const val K_SHARE = "share_config"
        const val K_VERSION_VUE = "derniere_version_vue"
        /** Marque de la migration 2.3.1 : voir `migrateShareManage`. */
        const val K_SHARE_MANAGE_MIGRATED = "share_manage_migrated_v231"
        const val K_INSTALL_ID = "install_id"
        const val K_SERVERS = "servers"
        const val K_CLOUD = "cloud_roots"
        const val K_VAULT_SALT = "vault_salt"
        const val K_VAULT_WRAPPED = "vault_wrapped"
        const val K_VAULT_BIO = "vault_bio"
    }
}
