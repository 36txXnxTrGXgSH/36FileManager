package com.filemanager36.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.filemanager36.app.ui.components.FilePreviewIcon
import com.filemanager36.app.ui.components.FileRow
import com.filemanager36.app.ui.components.FolderPickerDialog
import com.filemanager36.app.ui.components.SelectionActionItems
import com.filemanager36.app.ui.components.TextInputDialog
import com.filemanager36.app.util.FileKinds
import com.filemanager36.app.util.Formatters
import java.io.File

/**
 * Catégorie transversale : toutes les images, toutes les vidéos ou tous les documents
 * de l'appareil, quel que soit le dossier ou le volume où ils se trouvent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(vm: FmViewModel) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var movePicker by remember { mutableStateOf(false) }
    var copyPicker by remember { mutableStateOf(false) }

    val category = vm.categoryInUse
    val folderName = vm.categoryFolder?.substringAfterLast('/')
    // Mode « par dossier » : on liste les dossiers tant qu'aucun n'est ouvert.
    val showFolders = vm.categoryGrouped && vm.categoryFolder == null

    BackHandler {
        when {
            vm.selectionMode -> vm.clearSelection()
            vm.searchActive -> { vm.searchActive = false; vm.searchQuery = "" }
            vm.closeCategoryFolder() -> Unit
            else -> vm.goHome()
        }
    }

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

    if (deleteDialog) {
        ConfirmDialog(
            title = "Supprimer",
            message = "Supprimer définitivement ${vm.selected.size} fichier(s) ?",
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
            startPath = null,
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
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            vm.selectionMode -> vm.clearSelection()
                            vm.closeCategoryFolder() -> Unit
                            else -> vm.goHome()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                title = {
                    if (vm.searchActive) {
                        TextField(
                            value = vm.searchQuery,
                            onValueChange = { vm.searchQuery = it },
                            placeholder = { Text("Rechercher") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column {
                            Text(
                                when {
                                    vm.selectionMode -> "${vm.selected.size} sélectionné(s)"
                                    folderName != null -> folderName
                                    else -> category?.label ?: "Catégorie"
                                },
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!vm.selectionMode) {
                                Text(
                                    when {
                                        vm.categoriesLoading -> "Analyse en cours…"
                                        showFolders -> "${vm.categoryFolders.size} dossier(s)"
                                        folderName != null ->
                                            "${category?.label} · ${vm.categoryEntries.size} fichier(s)"
                                        else -> "${vm.categoryEntries.size} fichier(s) sur l'appareil"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
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
                            SortField.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.label) },
                                    leadingIcon = {
                                        if (vm.sortField == f) Icon(Icons.Filled.Check, null)
                                        else Spacer(Modifier.size(24.dp))
                                    },
                                    trailingIcon = {
                                        if (vm.sortField == f) Text(if (vm.sortAscending) "↑" else "↓")
                                    },
                                    onClick = { vm.setSort(f); sortOpen = false }
                                )
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!showFolders) {
                                DropdownMenuItem(
                                    text = { Text("Tout sélectionner") },
                                    leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                    onClick = { vm.selectAll(); menuOpen = false }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (vm.categoryGrouped) "Afficher tous les fichiers"
                                        else "Regrouper par dossier"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (vm.categoryGrouped) Icons.AutoMirrored.Filled.Subject
                                        else Icons.Filled.FolderCopy,
                                        null
                                    )
                                },
                                onClick = { vm.toggleCategoryGrouped(); menuOpen = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Réanalyser l'appareil") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { vm.scanCategories(force = true); menuOpen = false }
                            )

                            if (vm.selectionMode) {
                                HorizontalDivider()
                                SelectionActionItems(
                                    vm = vm,
                                    onRename = { renameDialog = true },
                                    onCompress = { },
                                    onDelete = { deleteDialog = true },
                                    onMove = { movePicker = true },
                                    onCopyTo = { copyPicker = true },
                                    close = { menuOpen = false },
                                    folderContext = false
                                )
                            }

                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(if (vm.showHidden) "Masquer les fichiers cachés" else "Afficher les fichiers masqués")
                                },
                                leadingIcon = {
                                    Icon(if (vm.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                                },
                                onClick = { vm.toggleHidden(); vm.scanCategories(force = true); menuOpen = false }
                            )
                            DropdownMenuItem(
                                text = { Text(if (vm.viewMode == ViewMode.LIST) "Affichage en grille" else "Affichage en liste") },
                                leadingIcon = {
                                    Icon(if (vm.viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList, null)
                                },
                                onClick = { vm.toggleViewMode(); menuOpen = false }
                            )
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
            val entries = vm.categoryEntries
            val folders = if (showFolders) vm.categoryFolders else emptyList()
            when {
                vm.categoriesLoading && entries.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Analyse de l'appareil…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Index multimédia + parcours des volumes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                showFolders -> {
                    if (folders.isEmpty()) {
                        EmptyState(
                            Icons.Filled.FolderCopy,
                            "Aucun dossier",
                            "Aucun fichier de cette catégorie n'a été trouvé sur l'appareil."
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(folders, key = { it.path }) { folder ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.openCategoryFolder(folder) }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Vignette du fichier le plus récent : reconnaître un
                                    // dossier de photos à son contenu, pas à son nom.
                                    val sample = folder.sample
                                    if (sample != null && vm.showThumbnails) {
                                        FilePreviewIcon(sample.file, false, true, 46.dp)
                                    } else {
                                        Icon(
                                            Icons.Filled.Folder, null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(46.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            folder.name,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${folder.count} fichier(s) · ${Formatters.size(folder.totalSize)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            folder.path,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(Icons.Filled.ChevronRight, null)
                                }
                            }
                        }
                    }
                }

                entries.isEmpty() -> EmptyState(
                    if (vm.searchQuery.isNotBlank()) Icons.Filled.SearchOff
                    else FileKinds.icon(
                        FileKinds.of(
                            when (category?.name) {
                                "IMAGES" -> "a.jpg"
                                "VIDEOS" -> "a.mp4"
                                else -> "a.pdf"
                            },
                            false
                        )
                    ),
                    if (vm.searchQuery.isNotBlank()) "Aucun résultat" else "Aucun fichier trouvé",
                    if (vm.searchQuery.isNotBlank()) "Rien ne correspond à « ${vm.searchQuery} »"
                    else "Vérifiez que l'accès à tous les fichiers est accordé, puis relancez l'analyse."
                )

                vm.viewMode == ViewMode.LIST -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(entries, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            selected = vm.selected.contains(item.path),
                            isFavorite = vm.isFavorite(item.path),
                            showThumbnails = vm.showThumbnails,
                            onClick = {
                                if (vm.selectionMode) vm.toggleSelect(item.path)
                                else vm.openFile(context, item, entries)
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
                    contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.path }) { item ->
                        FileGridCell(
                            item = item,
                            selected = vm.selected.contains(item.path),
                            isFavorite = vm.isFavorite(item.path),
                            showThumbnails = vm.showThumbnails,
                            onClick = {
                                if (vm.selectionMode) vm.toggleSelect(item.path)
                                else vm.openFile(context, item, entries)
                            },
                            onLongClick = { vm.toggleSelect(item.path) }
                        )
                    }
                }
            }
        }
    }
}
