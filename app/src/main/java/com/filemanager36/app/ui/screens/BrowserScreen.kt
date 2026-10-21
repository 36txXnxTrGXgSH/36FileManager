package com.filemanager36.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager36.app.data.SortField
import com.filemanager36.app.data.ViewMode
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.ui.components.ConfirmDialog
import com.filemanager36.app.ui.components.EmptyState
import com.filemanager36.app.ui.components.FileGridCell
import com.filemanager36.app.ui.components.FileRow
import com.filemanager36.app.ui.components.FolderPickerDialog
import com.filemanager36.app.ui.components.SelectionActionItems
import com.filemanager36.app.ui.components.TextInputDialog
import com.filemanager36.app.data.StorageRepo
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: FmViewModel) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var compressDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var movePicker by remember { mutableStateOf(false) }
    var copyPicker by remember { mutableStateOf(false) }

    BackHandler { vm.navigateUp() }

    if (renameDialog) {
        val target = vm.selected.firstOrNull()
        if (target != null) {
            TextInputDialog(
                title = "Renommer",
                label = "Nouveau nom",
                initial = File(target).name,
                onDismiss = { renameDialog = false },
                onConfirm = { vm.rename(target, it); renameDialog = false }
            )
        }
        LaunchedEffect(target) { if (target == null) renameDialog = false }
    }

    if (newFolderDialog) {
        TextInputDialog(
            title = "Nouveau dossier",
            label = "Nom du dossier",
            onDismiss = { newFolderDialog = false },
            onConfirm = { vm.createFolder(it); newFolderDialog = false }
        )
    }

    if (compressDialog) {
        val suggested = if (vm.selected.size == 1) File(vm.selected.first()).nameWithoutExtension
        else File(vm.currentPath).name.ifBlank { "archive" }
        TextInputDialog(
            title = "Compresser en ZIP",
            label = "Nom de l'archive",
            initial = "$suggested.zip",
            onDismiss = { compressDialog = false },
            onConfirm = { vm.compressSelection(it); compressDialog = false }
        )
    }

    if (deleteDialog) {
        ConfirmDialog(
            title = "Supprimer",
            message = "Supprimer définitivement ${vm.selected.size} élément(s) ? " +
                "Les dossiers seront supprimés avec tout leur contenu.",
            confirmLabel = "Supprimer",
            destructive = true,
            onDismiss = { deleteDialog = false },
            onConfirm = { vm.deleteSelection(); deleteDialog = false }
        )
    }

    if (movePicker || copyPicker) {
        val isMove = movePicker
        FolderPickerDialog(
            title = if (isMove) "Déplacer vers…" else "Copier vers…",
            volumes = vm.volumes,
            startPath = vm.currentPath,
            onDismiss = { movePicker = false; copyPicker = false },
            onPick = { dest ->
                if (isMove) vm.cutSelection() else vm.copySelection()
                vm.paste(dest)
                movePicker = false; copyPicker = false
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { vm.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                        }
                    },
                    title = {
                        if (vm.searchActive) {
                            TextField(
                                value = vm.searchQuery,
                                onValueChange = { vm.searchQuery = it },
                                placeholder = { Text("Rechercher dans ce dossier") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (vm.selectionMode) {
                            Text("${vm.selected.size} sélectionné(s)", fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(
                                File(vm.currentPath).name.ifBlank { "Stockage" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            vm.searchActive = !vm.searchActive
                            if (!vm.searchActive) vm.searchQuery = ""
                        }) { Icon(Icons.Filled.Search, "Rechercher") }

                        Box {
                            IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Trier") }
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                                Text(
                                    "Trier par",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                SortField.entries.forEach { f ->
                                    DropdownMenuItem(
                                        text = { Text(f.label) },
                                        leadingIcon = {
                                            if (vm.sortField == f) Icon(Icons.Filled.Check, null)
                                            else Spacer(Modifier.size(24.dp))
                                        },
                                        trailingIcon = {
                                            if (vm.sortField == f) {
                                                Text(if (vm.sortAscending) "↑" else "↓")
                                            }
                                        },
                                        onClick = { vm.setSort(f); sortOpen = false }
                                    )
                                }
                                androidx.compose.material3.HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Dossiers en premier") },
                                    leadingIcon = {
                                        if (vm.foldersFirst) Icon(Icons.Filled.Check, null)
                                        else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = { vm.toggleFoldersFirst(); sortOpen = false }
                                )
                            }
                        }

                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Tout sélectionner") },
                                    leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                    onClick = { vm.selectAll(); menuOpen = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Nouveau dossier") },
                                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                    onClick = { newFolderDialog = true; menuOpen = false }
                                )
                                if (!vm.clipboard.isEmpty) {
                                    if (!vm.selectionMode) {
                                        DropdownMenuItem(
                                            text = { Text("Coller ici (${vm.clipboard.paths.size})") },
                                            leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                                            onClick = { vm.paste(); menuOpen = false }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Vider le presse-papiers") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Backspace, null) },
                                        onClick = { vm.clearClipboard(); menuOpen = false }
                                    )
                                }

                                // Toutes les actions de sélection sont également
                                // disponibles ici, à côté de « Tout sélectionner ».
                                if (vm.selectionMode) {
                                    androidx.compose.material3.HorizontalDivider()
                                    SelectionActionItems(
                                        vm = vm,
                                        onRename = { renameDialog = true },
                                        onCompress = { compressDialog = true },
                                        onDelete = { deleteDialog = true },
                                        onMove = { movePicker = true },
                                        onCopyTo = { copyPicker = true },
                                        close = { menuOpen = false }
                                    )
                                }

                                androidx.compose.material3.HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (vm.showHidden) "Masquer les fichiers cachés" else "Afficher les fichiers masqués") },
                                    leadingIcon = {
                                        Icon(if (vm.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                                    },
                                    onClick = { vm.toggleHidden(); menuOpen = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (vm.viewMode == ViewMode.LIST) "Affichage en grille" else "Affichage en liste") },
                                    leadingIcon = {
                                        Icon(if (vm.viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList, null)
                                    },
                                    onClick = { vm.toggleViewMode(); menuOpen = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (vm.showThumbnails) "Désactiver les aperçus" else "Activer les aperçus") },
                                    onClick = { vm.toggleThumbnails(); menuOpen = false }
                                )
                            }
                        }
                    }
                )
                Breadcrumbs(vm)
                SendTargetBanner(vm)
                DeepSearchBanner(vm)
            }
        },
        floatingActionButton = {
            if (!vm.clipboard.isEmpty && !vm.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { vm.paste() },
                    icon = { Icon(Icons.Filled.ContentPaste, null) },
                    text = { Text("Coller (${vm.clipboard.paths.size})") }
                )
            }
        }
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when {
                vm.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                vm.visibleEntries.isEmpty() -> EmptyState(
                    Icons.Filled.FolderOpen,
                    "Dossier vide",
                    if (vm.searchQuery.isNotBlank()) "Aucun résultat pour « ${vm.searchQuery} »" else ""
                )
                vm.viewMode == ViewMode.LIST -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 90.dp)
                ) {
                    items(vm.visibleEntries, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            selected = vm.selected.contains(item.path),
                            isFavorite = vm.isFavorite(item.path),
                            showThumbnails = vm.showThumbnails,
                            onClick = {
                                if (vm.selectionMode) vm.toggleSelect(item.path)
                                else vm.openFile(context, item, vm.visibleEntries)
                            },
                            onLongClick = { vm.toggleSelect(item.path) },
                            trailing = {
                                if (!vm.selectionMode) {
                                    IconButton(onClick = { vm.toggleFavorite(item) }) {
                                        Icon(
                                            Icons.Filled.Star, "Favori",
                                            tint = if (vm.isFavorite(item.path)) Color(0xFFF5B301)
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vm.visibleEntries, key = { it.path }) { item ->
                        FileGridCell(
                            item = item,
                            selected = vm.selected.contains(item.path),
                            isFavorite = vm.isFavorite(item.path),
                            showThumbnails = vm.showThumbnails,
                            onClick = {
                                if (vm.selectionMode) vm.toggleSelect(item.path)
                                else vm.openFile(context, item, vm.visibleEntries)
                            },
                            onLongClick = { vm.toggleSelect(item.path) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(vm: FmViewModel) {
    val crumbs = remember(vm.currentPath, vm.volumes) {
        StorageRepo.breadcrumbs(vm.currentPath, vm.volumes)
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            crumbs.forEachIndexed { i, (label, path) ->
                if (i > 0) {
                    Icon(
                        Icons.Filled.ChevronRight, null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { vm.openPath(path) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * Bandeau affiché pendant qu'on choisit les fichiers à envoyer à un autre appareil.
 *
 * Ce détour par le navigateur évite de passer par le sélecteur de documents
 * d'Android, qui rend des URI `content://` : il faudrait alors recopier chaque
 * fichier dans le cache avant de l'expédier, doublant l'écriture disque pour un
 * film de plusieurs gigaoctets.
 */
@Composable
private fun SendTargetBanner(vm: FmViewModel) {
    val peer = vm.shareSendTarget ?: return
    val count = vm.selected.size
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Envoi vers ${peer.name}",
                    fontWeight = FontWeight.SemiBold,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (count == 0) "Appui long pour sélectionner des fichiers"
                    else "$count fichier(s) sélectionné(s)",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { vm.cancelSendTarget() }) { Text("Annuler") }
            androidx.compose.material3.Button(
                onClick = { vm.confirmSendToTarget() },
                enabled = count > 0 && !vm.shareSending
            ) { Text("Envoyer") }
        }
    }
}

/**
 * Le filtre de la barre du haut ne regarde que ce dossier. Dès qu'une saisie existe,
 * ce bandeau propose d'aller chercher plus bas : c'est là que l'on se rend compte
 * qu'un fichier n'est pas où on le croyait, et il ne faut pas avoir à retaper.
 */
@Composable
private fun DeepSearchBanner(vm: FmViewModel) {
    if (!vm.searchActive || vm.searchQuery.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.openSearch(vm.currentPath, vm.searchQuery) }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Search, null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Chercher « ${vm.searchQuery} » dans les sous-dossiers",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
