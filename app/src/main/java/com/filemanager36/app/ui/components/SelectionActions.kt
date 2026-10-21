package com.filemanager36.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filemanager36.app.ui.FmViewModel

/**
 * Actions applicables à la sélection courante.
 *
 * Le même bloc est utilisé dans la barre d'outils du bas et dans le menu « ⋮ »
 * de la barre supérieure, pour que tout soit accessible depuis les deux endroits.
 */
@Composable
fun SelectionActionItems(
    vm: FmViewModel,
    onRename: () -> Unit,
    onCompress: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onCopyTo: () -> Unit,
    close: () -> Unit,
    // Faux dans les catégories (Images, Vidéos, Documents) : il n'y a pas de dossier
    // courant, donc « coller ici », « compresser » et « extraire ici » n'ont pas de sens.
    folderContext: Boolean = true
) {
    val context = LocalContext.current
    val single = vm.selected.size == 1

    Text(
        "${vm.selected.size} élément(s) sélectionné(s)",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )

    DropdownMenuItem(
        text = { Text("Copier") },
        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
        onClick = { vm.copySelection(); close() }
    )
    DropdownMenuItem(
        text = { Text("Couper") },
        leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
        onClick = { vm.cutSelection(); close() }
    )
    if (folderContext && !vm.clipboard.isEmpty) {
        DropdownMenuItem(
            text = { Text("Coller ici (${vm.clipboard.paths.size})") },
            leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
            onClick = { vm.paste(); close() }
        )
    }
    DropdownMenuItem(
        text = { Text("Déplacer vers…") },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
        onClick = { onMove(); close() }
    )
    DropdownMenuItem(
        text = { Text("Copier vers…") },
        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
        onClick = { onCopyTo(); close() }
    )
    DropdownMenuItem(
        text = { Text("Renommer") },
        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
        enabled = single,
        onClick = { onRename(); close() }
    )
    DropdownMenuItem(
        text = { Text("Supprimer") },
        leadingIcon = { Icon(Icons.Filled.Delete, null) },
        onClick = { onDelete(); close() }
    )

    HorizontalDivider()

    if (folderContext) {
        DropdownMenuItem(
            text = { Text("Compresser (ZIP)") },
            leadingIcon = { Icon(Icons.Filled.FolderZip, null) },
            onClick = { onCompress(); close() }
        )
        DropdownMenuItem(
            text = { Text("Extraire ici") },
            leadingIcon = { Icon(Icons.Filled.Unarchive, null) },
            onClick = { vm.extractSelection(); close() }
        )
    }
    DropdownMenuItem(
        text = { Text("Partager") },
        leadingIcon = { Icon(Icons.Filled.Share, null) },
        onClick = { vm.shareSelection(context); close() }
    )

    HorizontalDivider()

    DropdownMenuItem(
        text = { Text("Déplacer vers le coffre-fort") },
        leadingIcon = { Icon(Icons.Filled.Lock, null) },
        onClick = { vm.moveSelectionToVault(); close() }
    )
    DropdownMenuItem(
        text = { Text("Ajouter aux favoris") },
        leadingIcon = { Icon(Icons.Filled.Star, null) },
        onClick = { vm.addSelectionToFavorites(); close() }
    )
    if (vm.smbShareConnected) {
        DropdownMenuItem(
            text = { Text("Envoyer vers le lecteur réseau") },
            leadingIcon = { Icon(Icons.Filled.Lan, null) },
            onClick = { vm.uploadToSmb(vm.selected.toList()); close() }
        )
    }
    vm.cloudUploadTargetName?.let { target ->
        DropdownMenuItem(
            text = { Text("Envoyer vers le cloud ($target)") },
            leadingIcon = { Icon(Icons.Filled.CloudUpload, null) },
            onClick = { vm.uploadToCloud(vm.selected.toList()); close() }
        )
    }

    HorizontalDivider()

    DropdownMenuItem(
        text = { Text("Informations") },
        leadingIcon = { Icon(Icons.Filled.Info, null) },
        enabled = single,
        onClick = { vm.selected.firstOrNull()?.let { vm.showDetails(it) }; close() }
    )
    DropdownMenuItem(
        text = { Text("Annuler la sélection") },
        leadingIcon = { Icon(Icons.Filled.Close, null) },
        onClick = { vm.clearSelection(); close() }
    )
}
