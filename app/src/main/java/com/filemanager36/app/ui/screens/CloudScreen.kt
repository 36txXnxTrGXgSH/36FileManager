package com.filemanager36.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.ui.components.ConfirmDialog
import com.filemanager36.app.ui.components.EmptyState
import com.filemanager36.app.ui.components.FolderPickerDialog
import com.filemanager36.app.ui.components.TextInputDialog
import com.filemanager36.app.util.FileKinds
import com.filemanager36.app.util.Formatters

/**
 * Navigation dans un espace cloud connecté (Google Drive, OneDrive, Dropbox, Nextcloud…)
 * par l'intermédiaire du système de documents d'Android.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CloudScreen(vm: FmViewModel) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var downloadPicker by remember { mutableStateOf(false) }
    var uploadPicker by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    BackHandler { if (!vm.cloudUp()) vm.goHome() }

    if (downloadPicker) {
        FolderPickerDialog(
            title = "Récupérer vers…",
            volumes = vm.volumes,
            startPath = null,
            onDismiss = { downloadPicker = false },
            onPick = { vm.downloadCloudSelection(it); downloadPicker = false }
        )
    }

    if (uploadPicker) {
        FolderPickerDialog(
            title = "Choisir le dossier local à envoyer",
            volumes = vm.volumes,
            startPath = null,
            onDismiss = { uploadPicker = false },
            onPick = { vm.uploadToCloud(listOf(it)); uploadPicker = false }
        )
    }

    if (newFolder) {
        TextInputDialog(
            title = "Nouveau dossier dans le cloud",
            label = "Nom",
            onDismiss = { newFolder = false },
            onConfirm = { vm.createCloudFolder(it); newFolder = false }
        )
    }

    if (renameDialog) {
        val id = vm.cloudSelected.firstOrNull()
        val item = vm.cloudEntries.firstOrNull { it.documentId == id }
        if (item != null) {
            TextInputDialog(
                title = "Renommer",
                label = "Nouveau nom",
                initial = item.name,
                onDismiss = { renameDialog = false },
                onConfirm = { vm.renameCloud(item.documentId, it); renameDialog = false }
            )
        }
        // La cible a disparu entre l'appui et l'affichage : sans ce retour à faux,
        // le bouton « Renommer » resterait inopérant jusqu'à quitter l'écran.
        LaunchedEffect(item) { if (item == null) renameDialog = false }
    }

    if (deleteDialog) {
        ConfirmDialog(
            title = "Supprimer dans le cloud",
            message = "Supprimer ${vm.cloudSelected.size} élément(s) de votre espace cloud ?",
            confirmLabel = "Supprimer",
            destructive = true,
            onDismiss = { deleteDialog = false },
            onConfirm = { vm.deleteCloudSelection(); deleteDialog = false }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { if (!vm.cloudUp()) vm.goHome() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                        }
                    },
                    title = {
                        Text(
                            if (vm.cloudSelected.isNotEmpty()) "${vm.cloudSelected.size} sélectionné(s)"
                            else vm.cloudRootInUse?.label ?: "Cloud",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Tout sélectionner") },
                                    leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                    onClick = { vm.selectAllCloud(); menu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Nouveau dossier") },
                                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                    onClick = { newFolder = true; menu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Envoyer un dossier local ici") },
                                    leadingIcon = { Icon(Icons.Filled.CloudUpload, null) },
                                    onClick = { uploadPicker = true; menu = false }
                                )
                                if (vm.cloudSelected.isNotEmpty()) {
                                    androidx.compose.material3.HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Récupérer vers…") },
                                        leadingIcon = { Icon(Icons.Filled.CloudDownload, null) },
                                        onClick = { downloadPicker = true; menu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Renommer") },
                                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                                        enabled = vm.cloudSelected.size == 1,
                                        onClick = { renameDialog = true; menu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Supprimer") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { deleteDialog = true; menu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Annuler la sélection") },
                                        onClick = { vm.clearCloudSelection(); menu = false }
                                    )
                                }
                            }
                        }
                    }
                )
                CloudBreadcrumbs(vm)
            }
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when {
                vm.cloudLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                vm.cloudError != null -> EmptyState(
                    Icons.Filled.CloudOff,
                    "Espace cloud indisponible",
                    vm.cloudError ?: ""
                )
                vm.cloudEntries.isEmpty() -> EmptyState(
                    Icons.Filled.CloudOff,
                    "Dossier vide",
                    "Utilisez « Envoyer un dossier local ici » pour y déposer des fichiers."
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(vm.cloudEntries, key = { it.documentId }) { item ->
                        val selected = vm.cloudSelected.contains(item.documentId)
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (vm.cloudSelected.isNotEmpty()) vm.toggleCloudSelect(item.documentId)
                                            else if (item.isDirectory) vm.openCloudFolder(item)
                                            else vm.openCloudFile(context, item)
                                        },
                                        onLongClick = { vm.toggleCloudSelect(item.documentId) }
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

@Composable
private fun CloudBreadcrumbs(vm: FmViewModel) {
    if (vm.cloudStack.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            vm.cloudStack.forEachIndexed { i, (_, name) ->
                if (i > 0) {
                    Icon(
                        Icons.Filled.ChevronRight, null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i == vm.cloudStack.lastIndex) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { vm.cloudJumpTo(i) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}
