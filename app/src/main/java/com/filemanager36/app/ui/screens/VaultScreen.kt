package com.filemanager36.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.filemanager36.app.data.VaultCrypto
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.ui.components.ConfirmDialog
import com.filemanager36.app.ui.components.EmptyState
import com.filemanager36.app.ui.components.FolderPickerDialog
import com.filemanager36.app.util.BiometricHelper
import com.filemanager36.app.util.FileKinds
import com.filemanager36.app.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(vm: FmViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var restorePicker by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    BackHandler {
        if (vm.vaultSelected.isNotEmpty()) vm.clearVaultSelection() else vm.goHome()
    }

    if (restorePicker) {
        FolderPickerDialog(
            title = "Restaurer vers…",
            volumes = vm.volumes,
            startPath = null,
            onDismiss = { restorePicker = false },
            onPick = { vm.restoreVaultSelection(it); restorePicker = false }
        )
    }

    if (deleteDialog) {
        ConfirmDialog(
            title = "Supprimer du coffre",
            message = "Ces éléments seront détruits définitivement et ne pourront pas être récupérés.",
            confirmLabel = "Supprimer",
            destructive = true,
            onDismiss = { deleteDialog = false },
            onConfirm = { vm.deleteVaultSelection(); deleteDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.goHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                title = {
                    Text(
                        if (vm.vaultSelected.isNotEmpty()) "${vm.vaultSelected.size} sélectionné(s)"
                        else "Coffre-fort",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    if (vm.vaultUnlocked) {
                        IconButton(onClick = { vm.lockVault() }) { Icon(Icons.Filled.Lock, "Verrouiller") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Tout sélectionner") },
                                    leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                    onClick = { vm.selectAllVault(); menu = false }
                                )
                                // Actions autrefois dans la barre du bas, désormais
                                // regroupées ici pour ne pas gêner la navigation système.
                                if (vm.vaultSelected.isNotEmpty()) {
                                    androidx.compose.material3.HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Restaurer vers…") },
                                        leadingIcon = { Icon(Icons.Filled.Unarchive, null) },
                                        onClick = { restorePicker = true; menu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Supprimer définitivement") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { deleteDialog = true; menu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Annuler la sélection") },
                                        onClick = { vm.clearVaultSelection(); menu = false }
                                    )
                                    androidx.compose.material3.HorizontalDivider()
                                }
                                if (activity != null && BiometricHelper.isAvailable(context)) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (vm.prefs.isBiometricEnabled) "Désactiver la biométrie"
                                                else "Activer la biométrie"
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Fingerprint, null) },
                                        onClick = {
                                            menu = false
                                            if (vm.prefs.isBiometricEnabled) {
                                                vm.prefs.vaultBioBlob = null
                                                VaultCrypto.deleteBiometricKey()
                                                vm.message = "Déverrouillage biométrique désactivé"
                                            } else {
                                                enableBiometrics(vm, activity)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            )
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when {
                !vm.vaultConfigured -> VaultSetup(vm)
                !vm.vaultUnlocked -> VaultUnlock(vm, activity)
                vm.vaultEntries.isEmpty() -> EmptyState(
                    Icons.Filled.Shield,
                    "Coffre vide",
                    "Depuis un dossier, sélectionnez des éléments puis « Déplacer vers le coffre-fort »."
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(vm.vaultEntries, key = { it.id }) { entry ->
                        val selected = vm.vaultSelected.contains(entry.id)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = selected, onCheckedChange = { vm.toggleVaultSelect(entry.id) })
                            val kind = FileKinds.of(entry.name, false)
                            Icon(
                                FileKinds.icon(kind), null,
                                tint = FileKinds.color(kind),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${Formatters.size(entry.size)} · ajouté le ${Formatters.dateShort(entry.addedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { vm.openVaultEntry(context, entry) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Ouvrir", Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultSetup(vm: FmViewModel) {
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Shield, null,
            Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text("Créer le coffre-fort", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Les fichiers déplacés ici sont chiffrés en AES-256 dans l'espace privé de l'application. " +
                "Le code n'est jamais enregistré : sans lui, les fichiers sont irrécupérables.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; error = null },
            label = { Text("Code ou mot de passe") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it; error = null },
            label = { Text("Confirmer") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                when {
                    code.length < 4 -> error = "Le code doit contenir au moins 4 caractères"
                    code != confirm -> error = "Les deux saisies ne correspondent pas"
                    else -> vm.setupVault(code) { }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Créer le coffre-fort") }
    }
}

@Composable
private fun VaultUnlock(vm: FmViewModel, activity: FragmentActivity?) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val bioReady = vm.prefs.isBiometricEnabled && activity != null && BiometricHelper.isAvailable(context)

    LaunchedEffect(bioReady) {
        if (bioReady) tryBiometricUnlock(vm, activity!!) { error = it }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Coffre verrouillé", style = MaterialTheme.typography.titleLarge)
        if (vm.pendingVaultImport.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${vm.pendingVaultImport.size} élément(s) seront chiffrés dès le déverrouillage.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { vm.cancelPendingImport() }) {
                        Text("Annuler ce transfert")
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; error = null },
            label = { Text("Code") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                vm.unlockVault(code) { ok -> if (!ok) error = "Code incorrect" }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.LockOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Déverrouiller")
        }
        if (bioReady) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { tryBiometricUnlock(vm, activity!!) { error = it } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Fingerprint, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Utiliser la biométrie")
            }
        }
    }
}

private fun tryBiometricUnlock(vm: FmViewModel, activity: FragmentActivity, onError: (String) -> Unit) {
    val blob = vm.prefs.vaultBioBlob ?: return
    val cipher = VaultCrypto.biometricDecryptCipher(blob) ?: run {
        vm.prefs.vaultBioBlob = null
        onError("Clé biométrique invalidée, utilisez votre code")
        return
    }
    BiometricHelper.authenticate(
        activity,
        "Coffre-fort",
        "Confirmez votre identité",
        cipher,
        onSuccess = { c ->
            runCatching { VaultCrypto.openWithBiometricCipher(c, blob) }
                .onSuccess { key ->
                    vm.vault.unlockWithKey(key)
                    vm.onVaultUnlockedByBiometrics()
                }
                .onFailure { onError("Déverrouillage impossible") }
        },
        onError = onError
    )
}

private fun enableBiometrics(vm: FmViewModel, activity: FragmentActivity) {
    val dek = vm.vault.dataKey ?: return
    runCatching { VaultCrypto.createBiometricKey() }
        .onFailure { vm.message = "Biométrie indisponible sur cet appareil"; return }
    val cipher = VaultCrypto.biometricEncryptCipher() ?: run {
        vm.message = "Biométrie indisponible"
        return
    }
    BiometricHelper.authenticate(
        activity,
        "Activer la biométrie",
        "Associez votre empreinte au coffre-fort",
        cipher,
        onSuccess = { c ->
            runCatching { VaultCrypto.sealWithBiometricCipher(c, dek) }
                .onSuccess {
                    vm.prefs.vaultBioBlob = it
                    vm.message = "Déverrouillage biométrique activé"
                }
                .onFailure { vm.message = "Activation impossible" }
        },
        onError = { vm.message = it }
    )
}
