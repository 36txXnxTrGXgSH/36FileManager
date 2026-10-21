package com.filemanager36.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filemanager36.app.data.ShareConfig
import com.filemanager36.app.data.SharePeer
import com.filemanager36.app.data.ShareState
import com.filemanager36.app.data.ShareTransfer
import com.filemanager36.app.data.TransferDirection
import com.filemanager36.app.data.TransferState
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.ui.components.FolderPickerDialog
import com.filemanager36.app.ui.components.SectionTitle
import com.filemanager36.app.ui.components.TextInputDialog
import com.filemanager36.app.util.Formatters

private val Accent1 = Color(0xFF2D6BF0)
private val Accent2 = Color(0xFF8B5CF6)
private val OkGreen = Color(0xFF16A34A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(vm: FmViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var portDialog by remember { mutableStateOf(false) }
    var rootPicker by remember { mutableStateOf(false) }
    var inboxPicker by remember { mutableStateOf(false) }

    val running = vm.shareState == ShareState.RUNNING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Partage réseau") },
                navigationIcon = {
                    IconButton(onClick = { vm.goHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                actions = {
                    if (running) {
                        IconButton(onClick = { vm.scanPeers() }) {
                            Icon(Icons.Filled.Refresh, "Rechercher des appareils")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item { HeroCard(vm, running, clipboard) }

            if (running) {
                item { ComputerSteps(vm) }
            }

            item { SectionTitle("Appareils Android à proximité") {
                if (vm.shareScanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            } }

            if (!running) {
                item { Hint("Activez le partage pour voir les appareils du réseau.") }
            } else if (vm.sharePeers.isEmpty()) {
                item {
                    Hint(
                        "Aucun appareil trouvé. L'autre téléphone doit être sur le même Wi-Fi, " +
                            "avec 36FileManager installé et le partage réseau activé lui aussi."
                    )
                }
            } else {
                items(vm.sharePeers) { peer -> PeerRow(peer, vm) }
            }

            if (vm.shareTransfers.isNotEmpty()) {
                item {
                    SectionTitle("Transferts") {
                        TextButton(onClick = { vm.clearShareTransfers() }) {
                            Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Effacer")
                        }
                    }
                }
                items(vm.shareTransfers.take(25)) { t -> TransferRow(t) }
            }

            item { SectionTitle("Fichiers reçus") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clickable { vm.openShareInbox() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = Accent1)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Ouvrir le dossier de réception", fontWeight = FontWeight.Medium)
                            Text(
                                vm.shareConfig.inboxPath.ifBlank { "Téléchargements / 36FileManager" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Réglages") }
            item {
                SettingsBlock(
                    vm = vm,
                    onPort = { portDialog = true },
                    onRoot = { rootPicker = true },
                    onInbox = { inboxPicker = true }
                )
            }
        }
    }

    if (portDialog) {
        TextInputDialog(
            title = "Port du serveur",
            label = "Entre 1024 et 65535",
            initial = vm.shareConfig.port.toString(),
            confirmLabel = "Appliquer",
            onDismiss = { portDialog = false },
            onConfirm = { value ->
                portDialog = false
                val port = value.trim().toIntOrNull()
                if (port == null || port !in 1024..65535) {
                    vm.message = "Port invalide : choisissez un nombre entre 1024 et 65535"
                } else {
                    vm.updateShareConfig(context, vm.shareConfig.copy(port = port))
                }
            }
        )
    }

    if (rootPicker) {
        FolderPickerDialog(
            title = "Dossier visible depuis le PC",
            volumes = vm.volumes,
            startPath = vm.shareConfig.rootPath.ifBlank { null },
            onDismiss = { rootPicker = false },
            onPick = { path ->
                rootPicker = false
                vm.updateShareConfig(context, vm.shareConfig.copy(rootPath = path))
            }
        )
    }

    if (inboxPicker) {
        FolderPickerDialog(
            title = "Dossier de réception",
            volumes = vm.volumes,
            startPath = vm.shareConfig.inboxPath.ifBlank { null },
            onDismiss = { inboxPicker = false },
            onPick = { path ->
                inboxPicker = false
                vm.updateShareConfig(context, vm.shareConfig.copy(inboxPath = path))
            }
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun HeroCard(vm: FmViewModel, running: Boolean, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val context = LocalContext.current
    // Pulsation lente du halo quand le partage est actif : la carte doit se voir
    // d'un coup d'œil depuis l'accueil comme depuis cet écran.
    val transition = rememberInfiniteTransition(label = "halo")
    val glow by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "glow"
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier
                .background(
                    if (running) Brush.linearGradient(listOf(Accent1, Accent2))
                    else Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (running) Color.White.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Wifi, null,
                        tint = if (running) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "Partage actif" else "Partage inactif",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (running) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        when {
                            vm.shareState == ShareState.STARTING -> "Démarrage…"
                            running && vm.shareWebClients > 0 ->
                                "${vm.shareWebClients} ordinateur(s) connecté(s)"
                            running -> "En attente d'une connexion"
                            else -> "Vos fichiers ne sont pas exposés"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (running) Color.White.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Sans l'autorisation de notification, le service tourne quand même,
                // mais l'utilisateur n'a plus ni témoin visible ni bouton « Arrêter » :
                // un serveur ouvert sur le réseau sans indicateur, ce qu'on ne veut pas.
                val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { vm.toggleShare(context) }

                Switch(
                    checked = running || vm.shareState == ShareState.STARTING,
                    onCheckedChange = {
                        val needsNotif = !running &&
                            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.POST_NOTIFICATIONS
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (needsNotif) {
                            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.toggleShare(context)
                        }
                    }
                )
            }

            vm.shareError?.let { err ->
                if (!running) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.ErrorOutline, null,
                            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            AnimatedVisibility(visible = running) {
                Column {
                    Spacer(Modifier.height(20.dp))

                    // Adresse à saisir dans le navigateur.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = glow * 0.3f))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "ADRESSE À OUVRIR DANS EDGE OU FIREFOX",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    vm.shareUrl ?: "…",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            IconButton(onClick = {
                                vm.shareUrl?.let {
                                    clipboard.setText(AnnotatedString(it))
                                    vm.message = "Adresse copiée"
                                }
                            }) {
                                Icon(Icons.Filled.ContentCopy, "Copier", tint = Color.White)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // QR code : l'autre téléphone n'a qu'à le scanner.
                        vm.shareQr?.let { bmp ->
                            Box(
                                Modifier
                                    .size(112.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White)
                                    .padding(7.dp)
                            ) {
                                Image(
                                    bmp.asImageBitmap(), "QR code de l'adresse",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            if (vm.shareCode != null) {
                                Text(
                                    "CODE D'ACCÈS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                                Text(
                                    vm.shareCode!!,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    letterSpacing = 5.sp
                                )
                                TextButton(onClick = { vm.renewShareCode() }) {
                                    Text("Changer le code", color = Color.White)
                                }
                            } else {
                                Text(
                                    "Aucun code demandé",
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    "Toute personne sur ce réseau peut ouvrir la page.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComputerSteps(vm: FmViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Computer, null, tint = Accent1)
                Spacer(Modifier.width(10.dp))
                Text("Depuis votre ordinateur", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            Step(1, "Vérifiez que le PC est sur le même Wi-Fi que ce téléphone.")
            Step(2, "Ouvrez ${vm.shareUrl ?: "l'adresse ci-dessus"} dans Edge ou Firefox.")
            Step(
                3,
                if (vm.shareCode != null) "Saisissez le code à six chiffres affiché ci-dessus."
                else "La page s'ouvre directement, aucun code n'est demandé."
            )
            Step(
                4,
                if (vm.shareConfig.allowManage)
                    "Parcourez, téléchargez, déposez par glisser-déposer, renommez ou supprimez."
                else "Parcourez, téléchargez, ou déposez des fichiers par glisser-déposer."
            )
            Step(
                5,
                if (vm.shareConfig.rootPath.isBlank())
                    "La page s'ouvre sur la liste des volumes — stockage interne, carte SD, " +
                        "clé USB — avec l'espace libre de chacun."
                else "Seul le dossier choisi ci-dessous est visible depuis le PC."
            )
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(bottom = 10.dp)) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Accent1.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$n",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Accent1
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

@Composable
private fun PeerRow(peer: SharePeer, vm: FmViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Accent1, Accent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Smartphone, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${peer.address}:${peer.port}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = { vm.beginSendTo(peer) },
                enabled = !vm.shareSending
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Envoyer")
            }
        }
    }
}

@Composable
private fun TransferRow(t: ShareTransfer) {
    val incoming = t.direction == TransferDirection.INCOMING
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = when (t.state) {
                TransferState.DONE -> OkGreen
                TransferState.FAILED, TransferState.REFUSED -> MaterialTheme.colorScheme.error
                else -> if (incoming) Accent2 else Accent1
            }
            Icon(
                when (t.state) {
                    TransferState.DONE -> Icons.Filled.CheckCircle
                    TransferState.FAILED, TransferState.REFUSED -> Icons.Filled.ErrorOutline
                    else -> if (incoming) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward
                },
                null, tint = tint, modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(if (incoming) "Reçu de " else "Envoyé à ")
                        append(t.peer)
                        if (t.total > 0) {
                            append(" · ")
                            append(Formatters.size(t.total))
                        }
                        t.error?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (t.state == TransferState.RUNNING || t.state == TransferState.PENDING) {
                    Spacer(Modifier.height(7.dp))
                    if (t.total > 0) {
                        LinearProgressIndicator(
                            progress = { t.fraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsBlock(
    vm: FmViewModel,
    onPort: () -> Unit,
    onRoot: () -> Unit,
    onInbox: () -> Unit
) {
    val context = LocalContext.current
    val config = vm.shareConfig
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            SettingRow(
                icon = Icons.Filled.Router,
                title = "Port du serveur",
                subtitle = config.port.toString(),
                onClick = onPort
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow(
                icon = Icons.Filled.Folder,
                title = "Dossier visible depuis le PC",
                subtitle = config.rootPath.ifBlank {
                    "Tous les volumes (interne, carte SD, clé USB)"
                },
                onClick = onRoot,
                // Sans cette croix, choisir un dossier serait sans retour : le sélecteur
                // rend toujours un chemin, et « vide » — tous les volumes — redeviendrait
                // inatteignable.
                trailing = if (config.rootPath.isNotBlank()) {
                    {
                        IconButton(onClick = {
                            vm.updateShareConfig(context, config.copy(rootPath = ""))
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                "Revenir à tous les volumes",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else null
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingToggle(
                title = "Exiger un code d'accès",
                subtitle = "Recommandé sur un Wi-Fi partagé",
                checked = config.requireCode,
                onCheck = { vm.updateShareConfig(context, config.copy(requireCode = it)) }
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingToggle(
                title = "Autoriser le dépôt de fichiers",
                subtitle = "Le PC peut écrire sur le téléphone",
                checked = config.allowUpload,
                onCheck = { vm.updateShareConfig(context, config.copy(allowUpload = it)) }
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingToggle(
                title = "Autoriser le renommage et la suppression",
                subtitle = "Le PC peut effacer définitivement, sans corbeille",
                checked = config.allowManage,
                onCheck = { vm.updateShareConfig(context, config.copy(allowManage = it)) }
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow(
                icon = Icons.Filled.Settings,
                title = "Dossier de réception",
                subtitle = config.inboxPath.ifBlank { "Téléchargements / 36FileManager" },
                onClick = onInbox
            )
        }
    }
    Text(
        if (config.allowManage)
            "La page web peut renommer et supprimer dans le dossier partagé. La " +
                "suppression est définitive : il n'y a pas de corbeille. Coupez ce " +
                "réglage sur un Wi-Fi que vous ne maîtrisez pas."
        else
            "La page web ne permet ni de supprimer ni de renommer : depuis le réseau, " +
                "on peut consulter, télécharger et déposer, rien de plus.",
        style = MaterialTheme.typography.bodySmall,
        color = if (config.allowManage) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheck)
    }
}

/**
 * Un autre appareil propose un envoi. Rien n'est écrit sur le téléphone tant que
 * l'utilisateur n'a pas accepté : l'expéditeur attend cette réponse avant d'ouvrir
 * la moindre connexion de transfert.
 */
@Composable
fun ShareOfferDialog(vm: FmViewModel) {
    val offer = vm.shareIncomingOffer ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.answerShareOffer(false) },
        icon = { Icon(Icons.Filled.ArrowDownward, null, tint = Accent1) },
        title = { Text("${offer.fromName} veut vous envoyer des fichiers") },
        text = {
            Column {
                Text(
                    "${offer.fileNames.size} fichier(s)" +
                        if (offer.totalBytes > 0) " · ${Formatters.size(offer.totalBytes)}" else "",
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(10.dp))
                offer.fileNames.take(6).forEach {
                    Text(
                        "• $it",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (offer.fileNames.size > 6) {
                    Text(
                        "…et ${offer.fileNames.size - 6} de plus",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Depuis ${offer.fromAddress}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.answerShareOffer(true) }) { Text("Accepter") }
        },
        dismissButton = {
            TextButton(onClick = { vm.answerShareOffer(false) }) { Text("Refuser") }
        }
    )
}
