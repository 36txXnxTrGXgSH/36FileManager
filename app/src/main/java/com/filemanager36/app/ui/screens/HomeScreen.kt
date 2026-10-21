package com.filemanager36.app.ui.screens

import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager36.app.BuildConfig
import com.filemanager36.app.data.CloudRepository
import com.filemanager36.app.data.MediaCategory
import com.filemanager36.app.ui.components.NouveautesDialog
import com.filemanager36.app.data.SmbServer
import com.filemanager36.app.data.StorageVolumeInfo
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.ui.Screen
import com.filemanager36.app.ui.components.FolderPickerDialog
import com.filemanager36.app.ui.components.SectionTitle
import com.filemanager36.app.ui.components.ServerDialog
import com.filemanager36.app.ui.components.StorageAccessDialog
import com.filemanager36.app.ui.components.StorageBar
import com.filemanager36.app.ui.components.TextInputDialog
import com.filemanager36.app.util.FileKind
import com.filemanager36.app.util.FileKinds
import com.filemanager36.app.util.Formatters
import com.filemanager36.app.util.Permissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: FmViewModel) {
    val context = LocalContext.current
    var serverDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<SmbServer?>(null) }
    var pendingImport by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var renamingCloudId by remember { mutableStateOf<String?>(null) }
    var nouveautesOuvertes by remember { mutableStateOf(false) }
    var accessDialog by remember { mutableStateOf(false) }

    val legacyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshAccess() }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshAccess() }

    // Connexion d'un dossier cloud (Drive, OneDrive, Dropbox, Nextcloud…).
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { vm.addCloudRoot(it) }
    }

    // Import de fichiers isolés, pour les fournisseurs qui refusent un dossier entier.
    val documentsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> repeat(clip.itemCount) { uris += clip.getItemAt(it).uri } }
        data.data?.let { if (uris.isEmpty()) uris += it }
        pendingImport = uris
    }

    if (serverDialog) {
        ServerDialog(
            initial = editingServer,
            onDelete = editingServer?.let { s -> { vm.deleteServer(s.id); serverDialog = false; editingServer = null } },
            onDismiss = { serverDialog = false; editingServer = null },
            onSave = {
                vm.saveServer(it)
                serverDialog = false
                editingServer = null
            }
        )
    }

    if (pendingImport.isNotEmpty()) {
        FolderPickerDialog(
            title = "Enregistrer ${pendingImport.size} fichier(s) dans…",
            volumes = vm.volumes,
            startPath = null,
            onDismiss = { pendingImport = emptyList() },
            onPick = { dest ->
                vm.importCloudUris(pendingImport, dest)
                pendingImport = emptyList()
            }
        )
    }

    renamingCloudId?.let { id ->
        val root = vm.cloudRoots.firstOrNull { it.id == id }
        TextInputDialog(
            title = "Renommer l'espace",
            label = "Nom affiché",
            initial = root?.label ?: "",
            onDismiss = { renamingCloudId = null },
            onConfirm = { vm.renameCloudRoot(id, it); renamingCloudId = null }
        )
    }

    if (nouveautesOuvertes) {
        NouveautesDialog(
            versions = com.filemanager36.app.data.Changelog.versions,
            onFermer = { nouveautesOuvertes = false }
        )
    }

    if (accessDialog) {
        StorageAccessDialog(
            hasFullAccess = vm.hasStorageAccess,
            volumes = vm.volumes,
            onRequest = {
                accessDialog = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    allFilesLauncher.launch(Permissions.manageAllFilesIntent(context))
                } else {
                    legacyPermission.launch(Permissions.legacyPermissions())
                }
            },
            onDismiss = { accessDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                actions = {
                    IconButton(onClick = { vm.openSearch(null) }) {
                        Icon(Icons.Filled.Search, "Rechercher sur le téléphone")
                    }
                    IconButton(onClick = { accessDialog = true }) {
                        Icon(Icons.Filled.Shield, "Accès aux fichiers")
                    }
                },
                title = {
                    // Toucher le titre rouvre l'historique complet des nouveautés : c'est
                    // là que se trouve déjà le numéro de version, qu'on vient consulter
                    // pour savoir ce qu'on a entre les mains.
                    Column(Modifier.clickable { nouveautesOuvertes = true }) {
                        Text("36FileManager", fontWeight = FontWeight.Bold)
                        Text(
                            "Gestionnaire de fichiers · v${BuildConfig.VERSION_NAME} · Nouveautés",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (!vm.hasStorageAccess) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(10.dp))
                                Text("Autorisation requise", fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Pour parcourir et modifier vos fichiers, 36FileManager a besoin de l'accès " +
                                    "à l'ensemble du stockage.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    allFilesLauncher.launch(Permissions.manageAllFilesIntent(context))
                                } else {
                                    legacyPermission.launch(Permissions.legacyPermissions())
                                }
                            }) { Text("Accorder l'accès") }
                        }
                    }
                }
            }

            // ---------------- Partage réseau ----------------
            // Placé tout en haut et en couleur : c'est la fonction que l'on vient
            // chercher volontairement, contrairement au reste de l'accueil.
            item { ShareBanner(vm) }

            // ---------------- Catégories ----------------
            item { SectionTitle("Catégories") }
            item {
                // Quatre catégories : deux rangées de deux, plus lisibles que quatre
                // tuiles écrasées sur une seule ligne.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    MediaCategory.entries.chunked(2).forEach { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { cat ->
                                val kind = when (cat) {
                                    MediaCategory.IMAGES -> FileKind.IMAGE
                                    MediaCategory.VIDEOS -> FileKind.VIDEO
                                    MediaCategory.MUSIC -> FileKind.AUDIO
                                    MediaCategory.DOCUMENTS -> FileKind.DOC
                                }
                                SpaceTile(
                                    icon = FileKinds.icon(kind),
                                    tint = FileKinds.color(kind),
                                    label = cat.label,
                                    subtitle = when {
                                        vm.categoriesLoading -> "Analyse…"
                                        vm.categoriesScanned -> "${vm.categoryCount(cat)} fichier(s)"
                                        else -> "Appuyez pour analyser"
                                    },
                                    container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    onClick = { vm.openCategory(cat) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Rangée incomplète : on comble pour garder la même largeur.
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    // Téléchargements sur toute la largeur, sous les quatre catégories :
                    // ce n'est pas un regroupement par type mais un vrai dossier, et une
                    // cinquième tuile seule sur sa rangée aurait eu l'air oubliée.
                    SpaceTile(
                        icon = Icons.Filled.Download,
                        tint = MaterialTheme.colorScheme.primary,
                        label = "Téléchargements",
                        subtitle = when {
                            !vm.hasStorageAccess -> "Autorisation requise"
                            vm.downloadsCount < 0 -> "…"
                            vm.downloadsCount == 0 -> "Dossier vide"
                            vm.downloadsCount == 1 -> "1 élément"
                            else -> "${vm.downloadsCount} éléments"
                        },
                        container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        onClick = { vm.openDownloads() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    )
                }
            }

            // ---------------- Espaces ----------------
            item { SectionTitle("Espaces") }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SpaceTile(
                        icon = Icons.Filled.Star,
                        tint = Color(0xFFF5B301),
                        label = "Favoris",
                        subtitle = "${vm.favorites.size} raccourci(s)",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { vm.goToScreen(Screen.FAVORITES) },
                        modifier = Modifier.weight(1f)
                    )
                    SpaceTile(
                        icon = Icons.Filled.Lock,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        label = "Coffre-fort",
                        subtitle = when {
                            !vm.vaultConfigured -> "À configurer"
                            vm.vaultUnlocked -> "${vm.vaultEntries.size} élément(s)"
                            else -> "Verrouillé"
                        },
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        onClick = { vm.goToScreen(Screen.VAULT) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---------------- Stockages ----------------
            item { SectionTitle("Stockages") }
            items(vm.volumes) { v -> VolumeCard(v) { vm.openPath(v.path) } }
            if (vm.volumes.isEmpty()) {
                item {
                    Text(
                        "Aucun volume détecté",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                }
            }

            // ---------------- Cloud ----------------
            item {
                SectionTitle("Espaces cloud (Drive, OneDrive…)") {
                    TextButton(onClick = { treeLauncher.launch(CloudRepository.openTreeIntent()) }) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Connecter")
                    }
                }
            }
            if (vm.cloudRoots.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "« Connecter » ouvre le sélecteur Android : choisissez Google Drive, " +
                                    "OneDrive, Dropbox ou Nextcloud, puis le dossier à rattacher. " +
                                    "Il apparaîtra ici et se parcourra comme un dossier normal.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { documentsLauncher.launch(CloudRepository.openDocumentsIntent()) }
                            ) {
                                Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Importer des fichiers")
                            }
                            Text(
                                "Google Drive n'autorise pas toujours le rattachement d'un dossier entier : " +
                                    "dans ce cas, utilisez l'import fichier par fichier.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            } else {
                items(vm.cloudRoots, key = { it.id }) { root ->
                    var rowMenu by remember { mutableStateOf(false) }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { vm.openCloudRoot(root) }
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(root.label, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    "Connecté le ${Formatters.dateShort(root.addedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = { rowMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, "Options")
                                }
                                DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Renommer") },
                                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                        onClick = { renamingCloudId = root.id; rowMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Déconnecter") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { vm.removeCloudRoot(root.id); rowMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { documentsLauncher.launch(CloudRepository.openDocumentsIntent()) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Filled.CloudDownload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Importer des fichiers depuis le cloud")
                    }
                }
            }

            // ---------------- Lecteurs réseau ----------------
            item {
                SectionTitle("Lecteurs réseau (LAN)") {
                    TextButton(onClick = { editingServer = null; serverDialog = true }) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Ajouter")
                    }
                }
            }
            if (vm.servers.isEmpty()) {
                item {
                    Text(
                        "Ajoutez l'adresse IP d'un PC Windows, d'un NAS ou d'une machine Linux : " +
                            "l'application vous demandera vos identifiants, puis affichera tous " +
                            "les dossiers partagés par cette machine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                    )
                }
            } else {
                items(vm.servers, key = { it.id }) { s ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { vm.connectServer(s) }
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lan, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.label, fontWeight = FontWeight.Medium)
                                Text(
                                    buildString {
                                        append("\\\\").append(s.host)
                                        append(" · ").append(s.osEnum.label)
                                        if (s.share.isNotBlank()) append(" · dernier : ").append(s.share)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { editingServer = s; serverDialog = true }) {
                                Icon(Icons.Filled.Edit, "Modifier")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = tint)
            Spacer(Modifier.height(8.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VolumeCard(v: StorageVolumeInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when {
                        v.isUsb -> Icons.Filled.Usb
                        v.isRemovable -> Icons.Filled.Sd
                        else -> Icons.Filled.Smartphone
                    },
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.label, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                if (v.totalBytes > 0) {
                    StorageBar(v.usedFraction)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${Formatters.size(v.usedBytes)} utilisés sur ${Formatters.size(v.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        v.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ChevronRight, null)
        }
    }
}

/**
 * Bandeau du partage réseau sur l'accueil. Vert et animé quand il tourne, sobre
 * sinon : l'état doit se lire sans entrer dans l'écran, car un serveur laissé
 * ouvert sur le réseau est exactement ce qu'on ne veut pas oublier.
 */
@Composable
private fun ShareBanner(vm: FmViewModel) {
    val running = vm.shareState == com.filemanager36.app.data.ShareState.RUNNING
    val transition = rememberInfiniteTransition(label = "share")
    val pulse by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clickable { vm.goToScreen(Screen.SHARE) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            Modifier
                .background(
                    if (running) Brush.linearGradient(
                        listOf(Color(0xFF2D6BF0), Color(0xFF8B5CF6))
                    ) else Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                )
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
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
                    "Partage réseau",
                    fontWeight = FontWeight.Bold,
                    color = if (running) Color.White else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (running) (vm.shareUrl ?: "Actif")
                    else "Vers un PC par navigateur, ou vers un autre Android",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) Color.White.copy(alpha = 0.9f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (running) {
                Box(
                    Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6EE7A0).copy(alpha = pulse))
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.Filled.ChevronRight, null,
                tint = if (running) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
