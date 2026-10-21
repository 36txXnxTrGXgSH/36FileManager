package com.filemanager36.app

import android.app.Application
import android.os.Environment
import com.filemanager36.app.data.Prefs
import com.filemanager36.app.data.ShareRepository
import com.filemanager36.app.util.Thumbnails
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FmApplication : Application() {

    /**
     * Le partage réseau vit au niveau de l'application, pas du ViewModel.
     *
     * Le serveur doit survivre à la fermeture de l'écran — l'utilisateur range son
     * téléphone pendant qu'il récupère des fichiers depuis son PC — et le service de
     * premier plan doit désigner exactement la même instance que l'interface. Un
     * ViewModel serait détruit avec l'activité et couperait le transfert en cours.
     */
    val share: ShareRepository by lazy { ShareRepository(this, Prefs(this)) }

    /**
     * Vrai si l'application vient d'être installée pour la première fois — et non mise à
     * jour. Android ne distingue les deux que par les dates : une première installation
     * n'a jamais été mise à jour.
     *
     * En cas d'échec de l'interrogation, on répond `false` : mieux vaut présenter des
     * nouveautés à quelqu'un qui découvre l'application que de les cacher à qui vient de
     * mettre à jour.
     */
    val premiereInstallation: Boolean by lazy {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(false)
    }

    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
    }

    /**
     * Écrit la trace de tout plantage non rattrapé dans un fichier lisible.
     *
     * Cette application est installée à la main, sans magasin ni service de rapport :
     * quand elle se ferme au lancement, il ne reste rien à examiner. Une version s'est
     * ainsi retrouvée impossible à démarrer sans le moindre indice à envoyer. Le
     * fichier atterrit dans « Téléchargements / 36FileManager », donc directement
     * accessible et partageable.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(thread, error) }
            // On repasse la main au gestionnaire d'origine : le système doit garder
            // son comportement normal (fermeture propre, remontée éventuelle).
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.FRANCE).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("36FileManager ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Date : $stamp")
            appendLine("Fil : ${thread.name}")
            appendLine()
            append(trace)
        }

        // Trois destinations par ordre de commodité pour l'utilisateur ; la dernière
        // est toujours accessible en écriture, même sans aucune autorisation.
        val candidates = listOfNotNull(
            runCatching {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "36FileManager"
                )
            }.getOrNull(),
            runCatching { getExternalFilesDir(null) }.getOrNull(),
            filesDir
        )
        for (dir in candidates) {
            val ok = runCatching {
                if (!dir.exists() && !dir.mkdirs()) return@runCatching false
                File(dir, "crash-$stamp.txt").writeText(report)
                true
            }.getOrDefault(false)
            if (ok) return
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Thumbnails.clear()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Les constantes TRIM_MEMORY_* sont marquées obsolètes depuis Android 14, où le
        // système ne délivre plus que TRIM_MEMORY_UI_HIDDEN. Le rappel reste bien appelé
        // avec les autres niveaux sur tout le parc visé (minSdk 26) : libérer le cache
        // des vignettes quand la mémoire se raréfie garde donc tout son intérêt.
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_MODERATE) Thumbnails.clear()
    }
}
