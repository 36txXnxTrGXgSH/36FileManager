package com.filemanager36.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager36.app.data.ServerGuides
import com.filemanager36.app.data.ServerOs
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.ui.components.ConfirmDialog
import com.filemanager36.app.ui.components.EmptyState
import com.filemanager36.app.ui.components.FolderPickerDialog
import com.filemanager36.app.ui.components.TextInputDialog
import com.filemanager36.app.util.FileKinds
import com.filemanager36.app.util.Formatters

/**
 * Lecteur réseau : d'abord la liste des dossiers partagés publiés par la machine,
 * puis la navigation dans le partage choisi.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NetworkScreen(vm: FmViewModel) {
    var menu by remember { mutableStateOf(false) }
    var downloadPicker by remember { mutableStateOf(false) }
    var uploadPicker by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    val inShare = vm.smbShareOpen != null

    BackHandler { if (!vm.smbUp()) vm.goHome() }

    if (downloadPicker) {
        FolderPickerDialog(
            title = "Récupérer vers…",
            volumes = vm.volumes,
            startPath = null,
            onDismiss = { downloadPicker = false },
            onPick = { vm.downloadSmbSelection(it); downloadPicker = false }
        )
    }

    if (uploadPicker) {
        FolderPickerDialog(
            title = "Choisir le dossier à envoyer",
            volumes = vm.volumes,
            startPath = null,
            onDismiss = { uploadPicker = false },
            onPick = { vm.uploadToSmb(listOf(it)); uploadPicker = false }
        )
    }

    if (newFolder) {
        TextInputDialog(
            title = "Nouveau dossier distant",
            label = "Nom",
            onDismiss = { newFolder = false },
            onConfirm = { vm.createSmbFolder(it); newFolder = false }
        )
    }

    if (renameDialog) {
        val name = vm.smbSelected.firstOrNull()
        if (name != null) {
            TextInputDialog(
                title = "Renommer",
                label = "Nouveau nom",
                initial = name,
                onDismiss = { renameDialog = false },
                onConfirm = { vm.renameSmb(name, it); renameDialog = false }
            )
        }
        // Hors composition : écrire l'état ici même provoquerait une recomposition en boucle.
        LaunchedEffect(name) { if (name == null) renameDialog = false }
    }

    if (deleteDialog) {
        ConfirmDialog(
            title = "Supprimer sur le lecteur réseau",
            message = "Supprimer ${vm.smbSelected.size} élément(s) du serveur ?",
            confirmLabel = "Supprimer",
            destructive = true,
            onDismiss = { deleteDialog = false },
            onConfirm = { vm.deleteSmbSelection(); deleteDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (!vm.smbUp()) vm.goHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                title = {
                    Column {
                        Text(
                            when {
                                vm.smbSelected.isNotEmpty() -> "${vm.smbSelected.size} sélectionné(s)"
                                inShare -> vm.smbShareOpen.orEmpty()
                                else -> vm.smbServerInUse?.label ?: "Lecteur réseau"
                            },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        vm.smbServerInUse?.let { srv ->
                            Text(
                                buildString {
                                    append("\\\\").append(srv.host)
                                    vm.smbShareOpen?.let { append("\\").append(it) }
                                    if (vm.smbPath.isNotBlank()) append("\\").append(vm.smbPath)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    if (vm.smbSessionOpen) {
                        IconButton(onClick = { vm.disconnectSmb(); vm.goHome() }) {
                            Icon(Icons.Filled.LinkOff, "Déconnecter")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (!inShare) {
                                    DropdownMenuItem(
                                        text = { Text("Actualiser la liste des partages") },
                                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                        onClick = {
                                            vm.smbServerInUse?.let { vm.connectServer(it) }
                                            menu = false
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Tout sélectionner") },
                                        leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                        onClick = { vm.selectAllSmb(); menu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Envoyer un dossier local ici") },
                                        leadingIcon = { Icon(Icons.Filled.CloudUpload, null) },
                                        onClick = { uploadPicker = true; menu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Nouveau dossier distant") },
                                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                        onClick = { newFolder = true; menu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Changer de partage") },
                                        leadingIcon = { Icon(Icons.Filled.FolderShared, null) },
                                        onClick = { vm.backToSmbShares(); menu = false }
                                    )
                                    if (vm.smbSelected.isNotEmpty()) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Récupérer vers…") },
                                            leadingIcon = { Icon(Icons.Filled.CloudDownload, null) },
                                            onClick = { downloadPicker = true; menu = false }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Renommer") },
                                            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                                            enabled = vm.smbSelected.size == 1,
                                            onClick = { renameDialog = true; menu = false }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Supprimer") },
                                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                            onClick = { deleteDialog = true; menu = false }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Annuler la sélection") },
                                            onClick = { vm.clearSmbSelection(); menu = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when {
                vm.smbLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                // L'erreur ne remplace l'écran que s'il n'y a rien à montrer : sinon on
                // effacerait la liste des partages et l'utilisateur serait bloqué.
                vm.smbError != null && vm.smbShares.isEmpty() -> SmbErrorPanel(vm)

                !vm.smbSessionOpen -> EmptyState(
                    Icons.Filled.Lan,
                    "Aucun lecteur connecté",
                    "Choisissez un lecteur réseau depuis l'accueil."
                )

                // Niveau 1 : les dossiers partagés publiés par la machine
                !inShare -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Text(
                            "Dossiers partagés sur ${vm.smbServerInUse?.host.orEmpty()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                    items(vm.smbShares, key = { it.name }) { share ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.openSmbShare(share) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (share.isAdmin) Icons.Filled.AdminPanelSettings else Icons.Filled.FolderShared,
                                null,
                                tint = if (share.isAdmin) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(share.name, fontWeight = FontWeight.Medium)
                                Text(
                                    when {
                                        share.comment.isNotBlank() -> share.comment
                                        share.isAdmin -> "Partage administratif"
                                        else -> "Dossier partagé"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Filled.ChevronRight, null)
                        }
                    }
                }

                // Niveau 2 : contenu du partage
                vm.smbEntries.isEmpty() -> EmptyState(Icons.Filled.Folder, "Dossier distant vide")

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(vm.smbEntries, key = { it.name }) { item ->
                        val selected = vm.smbSelected.contains(item.name)
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (vm.smbSelected.isNotEmpty()) vm.toggleSmbSelect(item.name)
                                            else if (item.isDirectory) vm.openSmbFolder(item.name)
                                            else vm.toggleSmbSelect(item.name)
                                        },
                                        onLongClick = { vm.toggleSmbSelect(item.name) }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val kind = FileKinds.of(item.name, item.isDirectory)
                                Icon(
                                    FileKinds.icon(kind), null,
                                    tint = FileKinds.color(kind),
                                    modifier = Modifier.size(30.dp)
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (item.isDirectory) "Dossier"
                                        else "${Formatters.size(item.size)} · ${Formatters.dateShort(item.lastModified)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Écran d'échec : le message traduit, puis la liste des points à vérifier sur la machine
 * cible. Un message technique seul ne dit pas à l'utilisateur quoi faire ensuite.
 */
@Composable
private fun SmbErrorPanel(vm: FmViewModel) {
    val server = vm.smbLastAttempt
    val guide = remember(server?.os) { ServerGuides.of(server?.osEnum ?: ServerOs.WINDOWS) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp)
    ) {
        item {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    if (vm.smbSessionOpen) Icons.Filled.FolderShared else Icons.Filled.WifiOff,
                    null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (vm.smbSessionOpen) "Aucun dossier partagé" else "Connexion impossible",
                    style = MaterialTheme.typography.titleMedium
                )
                server?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${it.host} · port ${it.port} · ${it.osEnum.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    vm.smbError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (server != null) {
                        Button(onClick = { vm.connectServer(server) }) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Réessayer")
                        }
                    }
                    // Sortie de secours toujours visible : un écran d'erreur ne doit
                    // jamais pouvoir retenir l'utilisateur.
                    OutlinedButton(onClick = { vm.disconnectSmb(); vm.goHome() }) {
                        Icon(Icons.Filled.Home, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Accueil")
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        item {
            Text(
                "À vérifier sur ${server?.osEnum?.label ?: "la machine"}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        val points = if (vm.smbSessionOpen) guide.steps else guide.troubleshooting
        items(points) { point ->
            Row(Modifier.padding(bottom = 10.dp)) {
                Icon(
                    Icons.Filled.ChevronRight, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(point, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
