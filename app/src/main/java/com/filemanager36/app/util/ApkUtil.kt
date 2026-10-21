package com.filemanager36.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Installation d'un fichier .apk depuis l'application.
 *
 * Un simple `ACTION_VIEW` ne suffit pas : depuis Android 8, l'installateur du système
 * refuse la demande si l'application appelante ne détient pas
 * `REQUEST_INSTALL_PACKAGES` **et** l'autorisation « Installer des applications
 * inconnues » accordée par l'utilisateur pour cette application précise. Sans cela,
 * toucher un APK ne produisait rien du tout.
 */
object ApkUtil {

    const val MIME = "application/vnd.android.package-archive"

    /** Ce que l'on sait d'un APK sans l'installer. */
    data class ApkInfo(
        val label: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int,
        val icon: Bitmap?,
        /** Version déjà installée sur le téléphone, si ce paquet est présent. */
        val installedVersion: String?,
        val installedVersionCode: Long?
    )

    /**
     * Lit l'APK sans l'installer (nom, éditeur, version, icône).
     * Bloquant : à appeler hors du fil principal.
     */
    fun read(context: Context, file: File): ApkInfo? = runCatching {
        val pm = context.packageManager
        val pkg = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        val app = pkg.applicationInfo
        // Sans ces deux chemins, loadLabel/loadIcon cherchent les ressources dans une
        // application installée (inexistante ici) et ne renvoient que le nom du paquet.
        app?.sourceDir = file.absolutePath
        app?.publicSourceDir = file.absolutePath

        // loadLabel ne lève pas d'exception quand les ressources de l'archive sont
        // illisibles : elle retombe sur le nom de la classe Application puis sur le
        // nom du paquet. On refuse ces deux valeurs, sinon le titre affiché serait
        // « com.acme.MyApplication » au lieu du nom de l'application.
        val label = app
            ?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != app.packageName && it != app.name }
            ?: file.nameWithoutExtension
        val icon = app?.let {
            runCatching { drawableToBitmap(pm.getApplicationIcon(it)) }.getOrNull()
        }
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
        else pkg.versionCode.toLong()

        // Best-effort : depuis Android 11, getPackageInfo échoue pour un paquet que
        // le filtrage de visibilité masque. Le <queries> MAIN/LAUNCHER du manifeste
        // rend visibles les applications lançables, ce qui couvre le cas courant
        // (mise à jour d'une application déjà installée) ; les services sans icône
        // restent invisibles et la fiche n'affichera simplement pas « déjà installée ».
        val installed = runCatching { pm.getPackageInfo(pkg.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val installedCode = installed?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
            else it.versionCode.toLong()
        }

        ApkInfo(
            label = label,
            packageName = pkg.packageName,
            versionName = pkg.versionName ?: "?",
            versionCode = code,
            minSdk = app?.minSdkVersion ?: 0,
            icon = icon,
            installedVersion = installed?.versionName,
            installedVersionCode = installedCode
        )
    }.getOrNull()

    /** L'utilisateur a-t-il autorisé 36FileManager à installer des applications ? */
    fun canInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * Ouvre l'écran système « Installer des applications inconnues » pour cette
     * application. Repli sur les paramètres de sécurité si le constructeur n'expose
     * pas l'écran par application.
     */
    fun openInstallSettings(context: Context): Boolean {
        // On lance directement en rattrapant ActivityNotFoundException, sans passer
        // par resolveActivity : le filtrage de visibilité d'Android 11+ peut rendre
        // l'écran des réglages « invisible » à la résolution alors qu'il s'ouvre
        // parfaitement. Le pré-test refusait alors la seule voie qui fonctionne.
        val candidates = listOf(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            // Repli ultime : la fiche de l'application, d'où l'utilisateur atteint
            // « Installer des applications inconnues ». ACTION_SECURITY_SETTINGS n'est
            // volontairement pas proposé : ce réglage n'y figure plus depuis Android 8,
            // et l'utilisateur y tournait en rond en croyant avoir réussi.
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
        for (intent in candidates) {
            if (start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true
        }
        return false
    }

    /**
     * Lance l'installateur du système sur cet APK.
     * @return null si tout va bien, sinon le message d'erreur à afficher.
     */
    fun install(context: Context, file: File): String? {
        if (!file.canRead()) return "Fichier illisible : ${file.name}"
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(ShareUtil.uriFor(context, file), MIME)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            // Pas de sélecteur : l'installateur du système est le seul destinataire
            // légitime, et le sélecteur masquait l'échec quand rien ne répondait.
            // Pas de resolveActivity préalable non plus — voir openInstallSettings.
            context.startActivity(intent)
            null
        } catch (e: android.content.ActivityNotFoundException) {
            "Aucun installateur d'applications sur cet appareil"
        } catch (e: Exception) {
            "Installation impossible : ${e.message}"
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable): Bitmap =
        Drawables.toBitmap(d, 144)
}
