package com.filemanager36.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.filemanager36.app.data.FileDetails
import com.filemanager36.app.data.ServerGuide
import com.filemanager36.app.data.ServerGuides
import com.filemanager36.app.data.ServerOs
import com.filemanager36.app.data.SmbServer
import com.filemanager36.app.data.StorageVolumeInfo
import com.filemanager36.app.util.Formatters
import java.io.File

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "Valider",
    isPassword: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirmer",
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun DetailsDialog(details: FileDetails, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Informations") },
        text = {
            Column {
                InfoLine("Nom", details.name)
                InfoLine("Type", if (details.isDirectory) "Dossier" else details.mimeType)
                InfoLine("Chemin", details.path)
                InfoLine("Taille", Formatters.size(details.sizeBytes))
                if (details.isDirectory) {
                    InfoLine("Contenu", "${details.fileCount} fichier(s), ${details.folderCount} dossier(s)")
                }
                InfoLine("Modifié le", Formatters.date(details.lastModified))
                InfoLine(
                    "Attributs",
                    buildList {
                        if (details.readable) add("lecture")
                        if (details.writable) add("écriture")
                        if (details.hidden) add("masqué")
                    }.joinToString(", ").ifBlank { "—" }
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun listSubfolders(path: String): List<File> =
    File(path).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()

/** Sélecteur de dossier de destination (déplacement, restauration, téléchargement). */
@Composable
fun FolderPickerDialog(
    title: String,
    volumes: List<StorageVolumeInfo>,
    startPath: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var current by remember { mutableStateOf(startPath ?: "") }
    var children by remember { mutableStateOf<List<File>>(emptyList()) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var currentWritable by remember { mutableStateOf(false) }
    // Une destination en lecture seule (« / », /system…) ferait échouer chaque fichier :
    // on ne la propose pas.
    val writableVolumes = remember(volumes) { volumes.filter { it.writable } }

    // Le listage se fait hors du fil principal : un dossier de plusieurs milliers
    // d'entrées provoquerait sinon un blocage visible, voire une ANR.
    LaunchedEffect(current) {
        if (current.isBlank()) {
            children = emptyList()
            currentWritable = false
        } else {
            val result = withContext(Dispatchers.IO) {
                listSubfolders(current) to File(current).canWrite()
            }
            children = result.first
            currentWritable = result.second
        }
    }

    if (newFolderDialog) {
        val scope = rememberCoroutineScope()
        TextInputDialog(
            title = "Nouveau dossier",
            label = "Nom",
            onDismiss = { newFolderDialog = false },
            onConfirm = { name ->
                newFolderDialog = false
                // `mkdirs()` puis un listage complet sur le fil principal : sur une carte
                // SD lente ou un dossier très fourni, l'interface se figeait le temps de
                // l'opération.
                scope.launch {
                    children = withContext(Dispatchers.IO) {
                        File(current, name).mkdirs()
                        listSubfolders(current)
                    }
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (current.isNotBlank()) {
                    IconButton(onClick = { newFolderDialog = true }) {
                        Icon(Icons.Filled.CreateNewFolder, "Nouveau dossier")
                    }
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                if (current.isNotBlank()) {
                    Text(
                        current,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                }
                LazyColumn {
                    if (current.isBlank()) {
                        items(writableVolumes) { v ->
                            PickerRow(
                                icon = when {
                                    v.isUsb -> Icons.Filled.Usb
                                    v.isRemovable -> Icons.Filled.Sd
                                    else -> Icons.Filled.Smartphone
                                },
                                label = v.label,
                                onClick = { current = v.path }
                            )
                        }
                    } else {
                        item {
                            PickerRow(Icons.Filled.ArrowUpward, "Dossier parent") {
                                val parent = File(current).parentFile
                                current = if (volumes.any { it.path == current } || parent == null) "" else parent.absolutePath
                            }
                        }
                        items(children) { d ->
                            PickerRow(Icons.Filled.Folder, d.name) { current = d.absolutePath }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (current.isNotBlank() && currentWritable) onPick(current) },
                enabled = current.isNotBlank() && currentWritable
            ) { Text(if (current.isNotBlank() && !currentWritable) "Lecture seule" else "Choisir ce dossier") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun PickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/**
 * Ajout / modification d'un lecteur réseau, en deux étapes.
 *
 * Étape 1 : l'adresse de la machine. Étape 2 : l'identifiant et le mot de passe.
 * Le nom du partage n'est plus demandé : une fois connecté, l'application interroge
 * la machine et affiche tous ses dossiers partagés.
 */
@Composable
fun ServerDialog(
    initial: SmbServer?,
    onDismiss: () -> Unit,
    onSave: (SmbServer) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var step by remember { mutableStateOf(if (initial == null) 1 else 2) }
    var os by remember { mutableStateOf(initial?.osEnum ?: ServerOs.WINDOWS) }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }
    var guest by remember { mutableStateOf(initial != null && initial.username.isBlank()) }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 445).toString()) }
    var helpOpen by remember { mutableStateOf(initial == null) }
    var advancedOpen by remember { mutableStateOf(false) }
    var touchedPort by remember { mutableStateOf(initial != null) }

    val guide = remember(os) { ServerGuides.of(os) }

    fun selectOs(newOs: ServerOs) {
        os = newOs
        if (!touchedPort) port = ServerGuides.of(newOs).defaultPort.toString()
    }

    fun buildServer() = SmbServer(
        id = initial?.id ?: "",
        label = label.ifBlank { host },
        host = host.trim(),
        share = initial?.share.orEmpty(),
        username = if (guest) "" else user.trim(),
        password = if (guest) "" else pass,
        domain = domain.trim(),
        // Un port hors plage passerait l'enregistrement et échouerait à la connexion
        // avec un message technique incompréhensible.
        port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: guide.defaultPort,
        os = os.name
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    if (initial == null) "Nouveau lecteur réseau" else "Modifier le lecteur",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (step == 1) "Étape 1 sur 2 — adresse de la machine"
                    else "Étape 2 sur 2 — identifiants",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (step == 1) {
                    Text(
                        "Système de la machine à atteindre",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ServerOs.entries.forEach { candidate ->
                            FilterChip(
                                selected = os == candidate,
                                onClick = { selectOs(candidate) },
                                label = { Text(candidate.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Field(guide.hostLabel, host, guide.hostExample) { host = it }
                    Text(
                        "C'est la seule information nécessaire pour l'instant : " +
                            "les dossiers partagés seront découverts automatiquement.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(10.dp))
                    HelpCard(os.label, guide, helpOpen) { helpOpen = !helpOpen }
                } else {
                    Text(
                        "Connexion à ${host.ifBlank { "la machine" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { guest = !guest }
                    ) {
                        Checkbox(checked = guest, onCheckedChange = { guest = it })
                        Column {
                            Text("Connexion invité", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Sans identifiant. Windows le refuse par défaut.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (!guest) {
                        Field(guide.userLabel, user, guide.userExample) { user = it }
                        Field("Mot de passe", pass, "", isPassword = true) { pass = it }
                        Text(
                            guide.credentialHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { advancedOpen = !advancedOpen }
                    ) {
                        Text(
                            "Options avancées",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            null, Modifier.size(18.dp)
                        )
                    }
                    if (advancedOpen) {
                        Field("Nom affiché", label, guide.labelExample) { label = it }
                        Field("Domaine (facultatif)", domain, guide.defaultDomain) { domain = it }
                        Field("Port", port, guide.defaultPort.toString(), numeric = true) {
                            port = it
                            touchedPort = true
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    HelpCard(os.label, guide, helpOpen) { helpOpen = !helpOpen }
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                TextButton(enabled = host.isNotBlank(), onClick = { step = 2 }) { Text("Suivant") }
            } else {
                TextButton(
                    enabled = host.isNotBlank() && (guest || user.isNotBlank()),
                    onClick = { onSave(buildServer()) }
                ) { Text("Se connecter") }
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null && step == 2) {
                    TextButton(onClick = onDelete) {
                        Text("Supprimer", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (step == 2) {
                    TextButton(onClick = { step = 1 }) { Text("Retour") }
                }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        }
    )
}

@Composable
private fun HelpCard(
    osLabel: String,
    guide: ServerGuide,
    open: Boolean,
    onToggle: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
            ) {
                Icon(Icons.Filled.Info, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "À faire sur $osLabel",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null, Modifier.size(18.dp)
                )
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                guide.steps.forEachIndexed { i, stepText ->
                    Row(Modifier.padding(bottom = 5.dp)) {
                        Text(
                            "${i + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(18.dp)
                        )
                        Text(stepText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    guide.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Bilan honnête de ce que l'application peut atteindre sur cet appareil,
 * et de ce qu'Android interdit à toute application sans root.
 */
@Composable
fun StorageAccessDialog(
    hasFullAccess: Boolean,
    volumes: List<StorageVolumeInfo>,
    onRequest: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accès aux fichiers") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                AccessLine(
                    ok = hasFullAccess,
                    title = "Accès à tous les fichiers",
                    detail = if (hasFullAccess)
                        "Accordé : lecture et écriture sur tout le stockage partagé."
                    else
                        "Non accordé : l'application ne voit presque rien. Appuyez sur « Accorder »."
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text(
                    "Volumes détectés",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (volumes.isEmpty()) {
                    Text(
                        "Aucun volume monté.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Les droits sont relevés lors de l'énumération des volumes, en
                    // arrière-plan : aucun appel disque pendant la composition.
                    volumes.forEach { v ->
                        AccessLine(
                            ok = v.readable,
                            title = v.label,
                            detail = v.path + " — " + when {
                                v.readable && v.writable -> "lecture et écriture"
                                v.readable -> "lecture seule"
                                else -> "inaccessible"
                            }
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Ce qu'Android interdit à toute application",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                LimitLine(
                    "Android/data et Android/obb",
                    "Bloqués depuis Android 11, y compris avec l'accès à tous les fichiers. " +
                        "Aucun gestionnaire de fichiers ne peut les ouvrir sans root."
                )
                LimitLine(
                    "Données privées des applications (/data/data)",
                    "Réservées à chaque application par le noyau. Inaccessibles sans root."
                )
                LimitLine(
                    "Partitions système (/system, /vendor…)",
                    "Visibles en lecture seule, montées ainsi par le système."
                )
                LimitLine(
                    "Profils professionnels et second utilisateur",
                    "Espaces séparés par Android : leurs fichiers ne sont jamais visibles ici."
                )
            }
        },
        confirmButton = {
            if (!hasFullAccess) {
                TextButton(onClick = onRequest) { Text("Accorder l'accès") }
            } else {
                TextButton(onClick = onDismiss) { Text("Fermer") }
            }
        },
        dismissButton = {
            if (!hasFullAccess) TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}

@Composable
private fun AccessLine(ok: Boolean, title: String, detail: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LimitLine(title: String, detail: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Icon(
            Icons.Filled.Block, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    numeric: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                isPassword -> KeyboardType.Password
                numeric -> KeyboardType.Number
                else -> KeyboardType.Text
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
fun StorageBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(5.dp)
                .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
        )
    }
}

@Composable
fun DialogActionsRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { content() }
}
