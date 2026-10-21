package com.filemanager36.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.ArrayList

object ShareUtil {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * Types que la table du système ne connaît pas — et sans lesquels l'intention
     * partait avec le type générique, ce qui écarte les applications dont le filtre est précis
     * (l'installateur d'APK, les lecteurs d'ebooks, les éditeurs de texte…).
     */
    private val extraMimes = mapOf(
        "apk" to ApkUtil.MIME,
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "xz" to "application/x-xz",
        "iso" to "application/x-iso9660-image",
        "epub" to "application/epub+zip",
        "mobi" to "application/x-mobipocket-ebook",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "json" to "application/json",
        "yml" to "text/yaml",
        "yaml" to "text/yaml",
        // .log, .ini, .conf, .cfg, .properties, .toml ne figurent volontairement pas
        // ici : les faire passer pour « text/plain » suffisait à ce que WhatsApp ou
        // Messages les traitent comme du texte à coller et ignorent la pièce jointe.
        // La visionneuse intégrée les récupère de toute façon via isText().
        // text/csv plutôt que le « text/comma-separated-values » historique de la
        // table du système, que les tableurs ne déclarent presque jamais.
        "csv" to "text/csv",
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "mkv" to "video/x-matroska",
        // audio/ogg et non audio/opus : c'est ce que déclarent les lecteurs, et la
        // table du système ne connaît pas du tout « opus » avant Android 10 — le
        // fichier repartait alors en type générique.
        "opus" to "audio/ogg",
        "flac" to "audio/flac",
        "m4b" to "audio/mp4",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

    fun mimeOf(file: File): String = mimeOfName(file.name)

    /** Type MIME sûr, sans repli « texte » : c'est celui utilisé pour le partage. */
    fun strictMimeOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        // La table maison passe en premier : elle sert justement à corriger les
        // réponses manquantes ou discutables de la table du système.
        extraMimes[ext]?.let { return it }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    fun mimeOfName(name: String): String {
        strictMimeOf(name)?.let { return it }
        // Dernier recours pour tout ce qui est lisible comme du texte : sans cela un
        // .kt ou un .env repartait en type générique et n'ouvrait rien.
        if (FileKinds.isText(name)) return "text/plain"
        return "*/*"
    }

    /** Application du téléphone capable d'ouvrir un fichier donné. */
    data class OpenerApp(
        val label: String,
        val packageName: String,
        val activityName: String,
        val icon: android.graphics.Bitmap?
    )

    private fun viewIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mimeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Applications installées qui savent lire ce fichier — VLC, MX Player, le lecteur
     * du constructeur… Proposées directement, pour éviter un aller-retour par le
     * sélecteur système.
     */
    fun openers(context: Context, file: File, limit: Int = 8): List<OpenerApp> = runCatching {
        val pm = context.packageManager
        val intent = viewIntent(context, file)
        // MATCH_DEFAULT_ONLY : sans ce filtre, la liste contient des activités internes
        // non exportées ; un appui dessus lève SecurityException et retombe bêtement
        // sur le sélecteur système.
        pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .filter { it.activityInfo != null }
            .filterNot { it.activityInfo.packageName == context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .take(limit)
            .map { info ->
                OpenerApp(
                    label = runCatching { info.loadLabel(pm).toString() }
                        .getOrDefault(info.activityInfo.packageName),
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    icon = runCatching { drawableToBitmap(info.loadIcon(pm)) }.getOrNull()
                )
            }
            .toList()
    }.getOrDefault(emptyList())

    /** Ouvre le fichier directement avec l'application choisie. */
    fun openWith(context: Context, file: File, app: OpenerApp) {
        try {
            // setPackage plutôt que setClassName : on désigne l'application, et le
            // système choisit lui-même la bonne activité à l'intérieur.
            val intent = viewIntent(context, file).apply { setPackage(app.packageName) }
            context.startActivity(intent)
        } catch (e: Exception) {
            // L'activité peut avoir disparu depuis l'énumération : on repasse par le
            // sélecteur système plutôt que d'échouer sans rien dire.
            open(context, file)
        }
    }

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable) =
        Drawables.toBitmap(d, 96)

    /** Au moins une application déclare-t-elle savoir ouvrir ce fichier ? */
    fun hasHandler(context: Context, file: File, mime: String = mimeOf(file)): Boolean =
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(context, file), mime)
            }
            context.packageManager
                .queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                .isNotEmpty()
        }.getOrDefault(false)

    /**
     * Ouvre le fichier avec son type exact.
     *
     * @param forcedMime type imposé par l'utilisateur (« Ouvrir en tant que… »).
     * @return `null` si une application a bien été lancée, sinon le message à afficher.
     *
     * L'ancienne version lançait le sélecteur sans vérifier qu'une application
     * répondait : le sélecteur s'ouvrait vide ou ne s'ouvrait pas, et l'utilisateur
     * n'avait aucune explication. C'est ce qui donnait l'impression que « certains
     * fichiers ne s'ouvrent pas ».
     */
    fun openOrExplain(
        context: Context,
        file: File,
        forcedMime: String? = null,
        allowGenericFallback: Boolean = true
    ): String? {
        if (!file.exists()) return "Ce fichier n'existe plus"
        val mime = forcedMime ?: mimeOf(file)

        // Un type d'intention « tout type » correspond à n'importe quelle application
        // déclarant un type : le sélecteur listerait la galerie, le navigateur et les
        // lecteurs vidéo pour un fichier qu'aucun d'eux ne sait lire. Quand l'appelant
        // sait proposer « Ouvrir en tant que… » (allowGenericFallback = false), mieux
        // vaut lui rendre la main. Pour les autres appelants — bouton « Ouvrir avec
        // une autre application », coffre-fort, cloud — il n'y a rien derrière : on
        // ouvre le sélecteur complet, qui reste bien plus utile qu'un message d'échec.
        if (forcedMime == null && mime == "*/*" && !allowGenericFallback) {
            val ext = file.extension.lowercase()
            return if (ext.isEmpty()) "Type de fichier inconnu"
            else "Type de fichier inconnu (.$ext)"
        }

        if (hasHandler(context, file, mime)) {
            val launched = runCatching {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uriFor(context, file), mime)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
                context.startActivity(Intent.createChooser(intent, "Ouvrir avec").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }.getOrDefault(false)
            if (launched) return null
        }
        val ext = file.extension.lowercase()
        return if (ext.isEmpty()) "Aucune application installée n'ouvre ce fichier"
        else "Aucune application installée n'ouvre les fichiers .$ext"
    }

    /** Ouvre le fichier avec l'application par défaut du système. */
    fun open(context: Context, file: File) {
        openOrExplain(context, file)?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Ouvre le fichier dans un éditeur du téléphone : pour une photo, ce sont les outils
     * de retouche de la galerie ou de l'application Appareil photo (recadrage, filtres…).
     *
     * L'autorisation d'écriture est accordée sur l'URI pour que l'éditeur puisse
     * enregistrer par-dessus l'original s'il le propose.
     */
    fun edit(context: Context, file: File): Boolean {
        return try {
            val uri = uriFor(context, file)
            val mime = mimeOf(file)
            val intent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, mime)
                putExtra("mimeType", mime)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            // Le sélecteur se résout toujours : sans ce test préalable, l'utilisateur
            // obtenait une boîte de dialogue vide au lieu d'un message clair.
            val editors = context.packageManager.queryIntentActivities(
                intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            if (editors.isEmpty()) {
                Toast.makeText(
                    context,
                    "Aucune application de retouche installée sur cet appareil",
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }
            context.startActivity(Intent.createChooser(intent, "Modifier avec").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Aucune application de retouche trouvée sur cet appareil",
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    /**
     * Partage un ou plusieurs fichiers (WhatsApp, Gmail, Bluetooth, Drive…).
     * Les dossiers doivent être compressés au préalable.
     */
    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        try {
            val uris = ArrayList<Uri>(files.map { uriFor(context, it) })
            // Un fichier partagé en « text/plain » est traité comme du texte à coller
            // par WhatsApp ou Messages : ils lisent EXTRA_TEXT (vide) et ignorent la
            // pièce jointe. On envoie donc le type générique pour tout ce qui est
            // textuel — le fichier part alors bien comme fichier.
            val commonType = files
                .map { strictMimeOf(it.name)?.takeIf { m -> m != "text/plain" } ?: "*/*" }
                .distinct()
                .let { if (it.size == 1) it.first() else "*/*" }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = commonType
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = commonType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Partager via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Partage impossible : ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
