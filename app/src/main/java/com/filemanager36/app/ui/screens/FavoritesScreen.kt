package com.filemanager36.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.ui.components.EmptyState
import com.filemanager36.app.ui.components.FilePreviewIcon
import com.filemanager36.app.ui.components.SectionTitle
import com.filemanager36.app.util.Formatters
import com.filemanager36.app.util.ShareUtil
import java.io.File

/**
 * Espace favoris : tous les fichiers et dossiers mis en raccourci, réunis au même endroit.
 * Rien n'est copié ni déplacé — chaque entrée pointe vers son emplacement d'origine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(vm: FmViewModel) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    BackHandler { vm.goHome() }

    val all = vm.sortedFavorites
    val folders = all.filter { it.isDirectory }
    val files = all.filterNot { it.isDirectory }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.goHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                title = {
                    Column {
                        Text("Favoris", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${folders.size} dossier(s) · ${files.size} fichier(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
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
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Retirer les raccourcis cassés") },
                                leadingIcon = { Icon(Icons.Filled.CleaningServices, null) },
                                onClick = { vm.removeBrokenFavorites(); menu = false }
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
            if (all.isEmpty()) {
                EmptyState(
                    Icons.Filled.StarBorder,
                    "Aucun favori",
                    "Touchez l'étoile à côté d'un fichier, ou utilisez « Ajouter aux favoris » " +
                        "sur une sélection. Le fichier reste à sa place."
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (folders.isNotEmpty()) {
                        item { SectionTitle("Dossiers") }
                        items(folders, key = { it.path }) { fav -> FavoriteRow(vm, fav, context) }
                    }
                    if (files.isNotEmpty()) {
                        if (folders.isNotEmpty()) item { HorizontalDivider() }
                        item { SectionTitle("Fichiers") }
                        items(files, key = { it.path }) { fav -> FavoriteRow(vm, fav, context) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    vm: FmViewModel,
    fav: com.filemanager36.app.data.FavoriteEntry,
    context: android.content.Context
) {
    var itemMenu by remember { mutableStateOf(false) }
    val file = remember(fav.path) { File(fav.path) }
    // Les métadonnées viennent du relevé fait en arrière-plan : aucun accès disque
    // pendant la composition, donc aucun blocage si le volume n'est plus monté.
    val stat = vm.statOf(fav)
    val exists = stat?.exists ?: true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.openFavorite(context, fav) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilePreviewIcon(file, fav.isDirectory, vm.showThumbnails)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (exists) Icons.Filled.Star else Icons.Filled.Warning,
                    null,
                    tint = if (exists) Color(0xFFF5B301) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(fav.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (!exists) "Raccourci cassé — l'élément n'existe plus"
                else file.parent ?: fav.path,
                style = MaterialTheme.typography.labelSmall,
                color = if (exists) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (exists && !fav.isDirectory && stat != null) {
                Text(
                    "${Formatters.size(stat.size)} · ${Formatters.dateShort(stat.lastModified)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box {
            IconButton(onClick = { itemMenu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
            DropdownMenu(expanded = itemMenu, onDismissRequest = { itemMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Ouvrir l'emplacement") },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                    enabled = exists,
                    onClick = { vm.openFavoriteLocation(fav); itemMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Partager") },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                    enabled = exists && !fav.isDirectory,
                    onClick = { ShareUtil.share(context, listOf(file)); itemMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Informations") },
                    leadingIcon = { Icon(Icons.Filled.Info, null) },
                    enabled = exists,
                    onClick = { vm.showDetails(fav.path); itemMenu = false }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Retirer des favoris") },
                    leadingIcon = { Icon(Icons.Filled.StarBorder, null) },
                    onClick = { vm.removeFavorite(fav.path); itemMenu = false }
                )
            }
        }
    }
}
