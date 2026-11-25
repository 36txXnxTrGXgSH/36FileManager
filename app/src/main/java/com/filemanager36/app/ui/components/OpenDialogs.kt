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
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.ContextCompat
import com.filemanager36.app.data.ArchiveSummary
import com.filemanager36.app.data.Archives
import com.filemanager36.app.data.StorageVolumeInfo
import kotlinx.coroutines.launch
import java.io.File
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
 * Archive touchée dans une liste : ZIP, 7z, RAR, TAR… L'application l'extrait elle-même,
 * ce qui évite de dépendre d'un décompresseur tiers rarement installé.
 *
 * Avant d'extraire, la fenêtre lit l'inventaire de l'archive — sans la décompresser — et
 * dit ce qui va se passer : combien de fichiers, combien de place, s'il en reste assez,
 * s'il faut un mot de passe. Une extraction de vingt minutes qui échoue faute de place à
 * la fin, c'est exactement ce qu'elle évite.
 */
@Composable
fun ArchiveActionDialog(
    item: FileItem,
    volumes: List<StorageVolumeInfo>,
    onExtract: (password: CharArray?, destination: File?) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember(item.path) { mutableStateOf<ArchiveSummary?>(null) }
    var readError by remember(item.path) { mutableStateOf<String?>(null) }
    var destination by remember(item.path) { mutableStateOf<File?>(null) }
    var picking by remember { mutableStateOf(false) }
    var password by remember(item.path) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var passwordError by remember(item.path) { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    LaunchedEffect(item.path) {
        withContext(Dispatchers.IO) {
            runCatching { Archives.summarize(item.file) }
                .onSuccess { summary = it }
                .onFailure { readError = Archives.describe(it) }
        }
    }

    val target = destination ?: item.file.parentFile
    // Même marge que l'extraction elle-même : 512 Mio, ou 2 % du volume.
    val free = remember(target?.path) {
        target?.let { (it.usableSpace - maxOf(512L shl 20, it.totalSpace / 50)).coerceAtLeast(0) } ?: 0L
    }
    val known = summary
    val noRoom = known?.totalBytes?.let { it > free } == true

    // Android 13+ : sans cette permission, la notification de progression reste
    // invisible — l'extraction se fait quand même, on ne bloque rien sur un refus.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun launch(pw: CharArray?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        onExtract(pw, destination)
        onDismiss()
    }

    if (picking) {
        FolderPickerDialog(
            title = "Extraire dans…",
            volumes = volumes,
            startPath = target?.absolutePath,
            onDismiss = { picking = false },
            onPick = { destination = File(it); picking = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Archive, null) },
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val format = known?.format?.label ?: Archives.formatFromName(item.name)?.label ?: ""
                Text(
                    "Archive $format de ${Formatters.size(item.size)}" +
                        (known?.volumes?.takeIf { it > 1 }?.let { " en $it parties" } ?: "") + "."
                )
                when {
                    readError != null -> Text(readError!!, color = MaterialTheme.colorScheme.error)
                    known == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Lecture du contenu…", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        val files = known.files
                        val bytes = known.totalBytes
                        Text(
                            when {
                                files != null && bytes != null ->
                                    "${Formatters.count(files)} fichier${if (files > 1) "s" else ""} · ${Formatters.size(bytes)} une fois extrait"
                                bytes != null -> "${Formatters.size(bytes)} une fois extrait"
                                known.needsPassword -> "Contenu protégé : il apparaîtra avec le mot de passe."
                                else -> "La taille extraite n'est connue qu'en décompressant."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Destination, modifiable : une carte SD a souvent plus de place.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dans : ${target?.name ?: "?"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Formatters.size(free)} disponibles",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (noRoom) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { picking = true }) { Text("Changer…") }
                }
                if (noRoom) {
                    Text(
                        "Pas assez de place à cet endroit. Choisissez un autre emplacement, " +
                            "une carte SD par exemple.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Carte SD ou clé USB : formatées en exFAT/FAT, et montées par Android en
                // écriture synchrone. Mesuré sur tablette : 5 à 7 fichiers créés par seconde,
                // contre ~140 sur le stockage interne. Une archive de 200 000 fichiers y
                // prendrait plus de huit heures : on le dit avant, pas après.
                val manyFiles = known?.files ?: 0
                val removable = target?.absolutePath?.startsWith("/storage/emulated/") == false
                if (removable && manyFiles >= 2_000) {
                    val hours = manyFiles / 6 / 3600
                    Text(
                        "Carte SD ou clé USB : Android y crée les fichiers très lentement, quelques-uns " +
                            "par seconde. Pour ${Formatters.count(manyFiles)} fichiers, comptez " +
                            (if (hours >= 1) "environ $hours h" else "de longues minutes") +
                            ". Le stockage interne est bien plus rapide.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (known?.needsPassword == true) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; passwordError = null },
                        label = { Text("Mot de passe") },
                        singleLine = true,
                        isError = passwordError != null,
                        supportingText = passwordError?.let { e -> { Text(e) } },
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    if (showPassword) "Masquer" else "Afficher"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "L'extraction crée un dossier du même nom. Les liens symboliques que " +
                        "contiendrait l'archive ne sont jamais recréés.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = known != null && readError == null && !noRoom && !checking &&
                    (known.needsPassword.not() || password.isNotEmpty()),
                onClick = {
                    if (known?.needsPassword != true) { launch(null); return@TextButton }
                    // Le mot de passe est vérifié tout de suite, sur le début du premier
                    // fichier : mieux vaut l'apprendre ici qu'au bout de vingt minutes.
                    checking = true
                    val pw = password.toCharArray()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Archives.checkPassword(item.file, pw.copyOf()) }
                        checking = false
                        if (ok) launch(pw) else { passwordError = "Mot de passe incorrect"; pw.fill('\u0000') }
                    }
                }
            ) {
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Unarchive, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Extraire")
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
