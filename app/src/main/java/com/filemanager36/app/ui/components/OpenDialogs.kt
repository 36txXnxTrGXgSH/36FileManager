package com.filemanager36.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.filemanager36.app.data.FileItem
import com.filemanager36.app.util.ApkUtil
import com.filemanager36.app.util.Formatters
import com.filemanager36.app.util.ShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fiche d'un fichier .apk : nom réel de l'application, éditeur, version, taille,
 * version déjà installée — puis installation.
 *
 * Android exige deux conditions pour installer depuis une autre application :
 * l'autorisation `REQUEST_INSTALL_PACKAGES` dans le manifeste, et le réglage
 * « Installer des applications inconnues » activé par l'utilisateur pour
 * 36FileManager. La seconde est demandée ici, avec un bouton qui mène droit
 * à l'écran système, et l'état est revérifié au retour.
 */
@Composable
fun ApkInstallDialog(
    item: FileItem,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var info by remember(item.path) { mutableStateOf<ApkUtil.ApkInfo?>(null) }
    var loading by remember(item.path) { mutableStateOf(true) }
    var allowed by remember { mutableStateOf(ApkUtil.canInstall(context)) }

    LaunchedEffect(item.path) {
        info = withContext(Dispatchers.IO) { ApkUtil.read(context, item.file) }
        loading = false
    }

    // L'utilisateur part dans les réglages système accorder l'autorisation : il faut
    // relire l'état à son retour, sinon le bouton « Installer » reste caché.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = ApkUtil.canInstall(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val installed = info?.installedVersionCode
    val actionLabel = when {
        installed == null -> "Installer"
        info!!.versionCode > installed -> "Mettre à jour"
        info!!.versionCode == installed -> "Réinstaller"
        else -> "Installer (version antérieure)"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Android, null) },
        title = { Text(info?.label ?: item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Lecture du paquet…")
                    }
                } else if (info == null) {
                    Text(
                        "Ce fichier ne semble pas être une application Android valide, " +
                            "ou il est incomplet.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    val a = info!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bmp = a.icon
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Android, null, Modifier.size(40.dp))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "Version ${a.versionName} (${a.versionCode})",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                a.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                Formatters.size(item.size),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (a.installedVersion != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Déjà installée : version ${a.installedVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (a.minSdk > android.os.Build.VERSION.SDK_INT) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning, null,
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Demande Android niveau ${a.minSdk} ; ce téléphone est en " +
                                    "${android.os.Build.VERSION.SDK_INT}. L'installation échouera.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (!allowed) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Android bloque l'installation tant que « Installer des applications " +
                            "inconnues » n'est pas activé pour 36FileManager.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (!allowed) {
                TextButton(onClick = {
                    if (!ApkUtil.openInstallSettings(context)) {
                        onMessage("Écran des réglages introuvable sur cet appareil")
                    }
                }) { Text("Autoriser") }
            } else {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        ApkUtil.install(context, item.file)?.let(onMessage)
                        onDismiss()
                    }
                ) { Text(actionLabel) }
            }
        },
        dismissButton = {
            DialogActionsRow {
                TextButton(onClick = {
                    ShareUtil.openOrExplain(context, item.file)?.let(onMessage)
                    onDismiss()
                }) { Text("Ouvrir avec…") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        }
    )
}

/**
 * Archive .zip touchée dans une liste : l'application sait l'extraire elle-même,
 * ce qui évite de dépendre d'un décompresseur tiers rarement installé.
 */
@Composable
fun ArchiveActionDialog(
    item: FileItem,
    onExtract: () -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Archive, null) },
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Text(
                "Archive ZIP de ${Formatters.size(item.size)}.\n\n" +
                    "L'extraction crée un dossier du même nom à côté de l'archive."
            )
        },
        confirmButton = {
            TextButton(onClick = { onExtract(); onDismiss() }) {
                Icon(Icons.Filled.Unarchive, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Extraire ici")
            }
        },
        dismissButton = {
            DialogActionsRow {
                TextButton(onClick = {
                    ShareUtil.openOrExplain(context, item.file)?.let(onMessage)
                    onDismiss()
                }) { Text("Ouvrir avec…") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        }
    )
}

/**
 * Aucune application ne répond pour ce type de fichier : on laisse l'utilisateur
 * imposer un type, exactement comme le « Ouvrir en tant que » des gestionnaires de
 * fichiers de bureau. Un `.bin` qui est en réalité une vidéo s'ouvre alors dans VLC.
 */
@Composable
fun OpenAsDialog(
    item: FileItem,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val ext = item.extension
    val choices = listOf(
        // En tête : le sélecteur complet. C'est le bon choix pour un format qu'une
        // application gère par son extension et non par son type (émulateurs, lecteurs
        // de bandes dessinées, installateurs de paquets fractionnés…).
        Triple("Toutes les applications", "*/*", Icons.AutoMirrored.Filled.InsertDriveFile),
        Triple("Texte", "text/plain", Icons.Filled.Description),
        Triple("Image", "image/*", Icons.Filled.Photo),
        Triple("Vidéo", "video/*", Icons.Filled.VideoFile),
        Triple("Audio", "audio/*", Icons.Filled.AudioFile)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
        title = { Text("Ouvrir en tant que…") },
        text = {
            Column {
                Text(
                    if (ext.isEmpty())
                        "Le type de ce fichier n'a pas pu être déterminé. Choisissez avec " +
                            "quoi l'ouvrir."
                    else
                        "Le type des fichiers .$ext n'a pas pu être déterminé, ou aucune " +
                            "application ne le déclare. Choisissez avec quoi l'ouvrir.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                // Chaque hasHandler est un appel binder qui rapatrie la liste des
                // activités du téléphone. Mémorisé — sinon les cinq repartaient à chaque
                // recomposition — et désormais calculé **hors du fil principal** : cinq
                // allers-retours vers le PackageManager pendant la composition, c'est
                // exactement ce qui fait sauter la première image d'un dialogue.
                var availability by remember(item.path) {
                    mutableStateOf<Map<String, Boolean>?>(null)
                }
                LaunchedEffect(item.path) {
                    availability = withContext(Dispatchers.IO) {
                        choices.associate { (_, mime, _) ->
                            mime to ShareUtil.hasHandler(context, item.file, mime)
                        }
                    }
                }
                choices.forEach { (label, mime, icon) ->
                    // Tant que la réponse n'est pas revenue (quelques millisecondes), on
                    // suppose disponible : afficher « aucune application » puis se
                    // dédire produirait un clignotement plus gênant que l'attente.
                    val known = availability
                    val available = known == null || known[mime] == true
                    TextButton(
                        enabled = available,
                        onClick = {
                            ShareUtil.openOrExplain(context, item.file, mime)?.let(onMessage)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(icon, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (known == null || available) label
                                else "$label — aucune application"
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}
