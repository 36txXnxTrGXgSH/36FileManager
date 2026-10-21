package com.filemanager36.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.filemanager36.app.data.FileItem
import com.filemanager36.app.data.FileSearch
import com.filemanager36.app.data.StorageVolumeInfo
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.ui.components.EmptyState
import com.filemanager36.app.ui.components.FileRow
import java.io.File

/**
 * Recherche par nom dans tous les sous-dossiers — d'un dossier ou de tout le téléphone.
 *
 * Toucher un résultat l'ouvre ; l'icône de dossier, ou un appui long, ouvre l'endroit
 * où il se trouve, élément sélectionné. Un retour arrière depuis ce dossier ramène ici,
 * résultats intacts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: FmViewModel) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    BackHandler { vm.closeSearch() }

    // Le clavier s'ouvre seulement sur un champ vide : en revenant d'un résultat, il
    // masquerait la moitié de la liste que l'on vient consulter.
    LaunchedEffect(Unit) {
        if (vm.searchText.isEmpty()) runCatching { focus.requestFocus() }
    }

    val scope = vm.searchScope
    val origin = vm.searchOrigin
    val scopeLabel = scope?.let { File(it).name.ifBlank { it } }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { vm.closeSearch() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                        }
                    },
                    title = {
                        TextField(
                            value = vm.searchText,
                            onValueChange = { vm.updateSearchText(it) },
                            placeholder = {
                                Text(
                                    if (scopeLabel == null) "Rechercher sur le téléphone"
                                    else "Rechercher dans $scopeLabel",
                                    maxLines = 1
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                        )
                    },
                    actions = {
                        if (vm.searchText.isNotEmpty()) {
                            IconButton(onClick = {
                                vm.updateSearchText("")
                                runCatching { focus.requestFocus() }
                            }) { Icon(Icons.Filled.Close, "Effacer la saisie") }
                        }
                    }
                )

                // La portée ne se choisit que si la recherche vient d'un dossier : depuis
                // l'accueil, « tout le téléphone » est la seule qui ait un sens.
                if (origin != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = scope == origin,
                            onClick = { vm.changeSearchScope(origin) },
                            label = { Text("Dans ${File(origin).name.ifBlank { origin }}", maxLines = 1) },
                            leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)) }
                        )
                        FilterChip(
                            selected = scope == null,
                            onClick = { vm.changeSearchScope(null) },
                            label = { Text("Tout le téléphone") },
                            leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(18.dp)) }
                        )
                    }
                }

                if (vm.searchRunning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    ) { inner ->
        val results = vm.searchResults
        val outcome = vm.searchOutcome
        val query = vm.searchText.trim()

        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when {
                FileSearch.terms(query).isEmpty() -> EmptyState(
                    Icons.Filled.Search,
                    "Rechercher un fichier",
                    "Tapez une partie du nom. Majuscules et accents sont ignorés, et les mots " +
                        "peuvent venir dans n'importe quel ordre : « facture 2025 » trouve " +
                        "« 2025_Facture_EDF.pdf »."
                )

                results.isEmpty() && outcome != null -> EmptyState(
                    Icons.Filled.SearchOff,
                    "Aucun résultat",
                    "Rien ne correspond à « $query » " +
                        (if (scopeLabel == null) "sur le téléphone." else "dans $scopeLabel.") +
                        if (origin != null && scope != null) " Essayez « Tout le téléphone »." else ""
                )

                results.isEmpty() -> EmptyState(Icons.Filled.Search, "Recherche en cours…", "")

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Text(
                            summary(results.size, vm.searchRunning, outcome),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(results, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            selected = false,
                            isFavorite = vm.isFavorite(item.path),
                            showThumbnails = vm.showThumbnails,
                            onClick = {
                                if (item.isDirectory) vm.openSearchFolder(item)
                                // Les autres fichiers trouvés servent de voisins à la
                                // visionneuse : on balaie d'un résultat au suivant.
                                else vm.openFile(context, item, results.filter { !it.isDirectory })
                            },
                            onLongClick = { vm.revealInFolder(item) },
                            detail = locationOf(item, vm.volumes),
                            trailing = {
                                IconButton(onClick = { vm.revealInFolder(item) }) {
                                    Icon(
                                        Icons.Filled.FolderOpen,
                                        "Ouvrir l'emplacement",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun summary(count: Int, running: Boolean, outcome: FileSearch.Outcome?): String = when {
    running -> "$count trouvé${if (count > 1) "s" else ""} pour l'instant…"
    outcome?.truncated == true ->
        "Les $count premiers résultats — précisez la recherche pour trouver les autres"
    outcome != null ->
        "$count résultat${if (count > 1) "s" else ""} · ${outcome.scanned} éléments parcourus"
    else -> ""
}

/**
 * « Stockage interne › Documents › Impôts » : où se trouve le résultat. Sans cette
 * ligne, deux « facture.pdf » à deux endroits seraient indiscernables.
 */
private fun locationOf(item: FileItem, volumes: List<StorageVolumeInfo>): String {
    val parent = File(item.path).parent ?: return ""
    val volume = volumes
        .filter { it.path != "/" && (parent == it.path || parent.startsWith(it.path + "/")) }
        .maxByOrNull { it.path.length }
        ?: return parent
    val rest = parent.removePrefix(volume.path).trim('/')
    return if (rest.isEmpty()) volume.label else volume.label + " › " + rest.replace("/", " › ")
}
