package com.filemanager36.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.ui.Screen
import com.filemanager36.app.ui.components.ApkInstallDialog
import com.filemanager36.app.ui.components.ArchiveActionDialog
import com.filemanager36.app.ui.components.DetailsDialog
import com.filemanager36.app.ui.components.OpenAsDialog
import com.filemanager36.app.ui.components.ProgressOverlay
import com.filemanager36.app.ui.components.NouveautesDialog
import com.filemanager36.app.ui.screens.BrowserScreen
import com.filemanager36.app.ui.screens.CategoryScreen
import com.filemanager36.app.ui.screens.CloudScreen
import com.filemanager36.app.ui.screens.FavoritesScreen
import com.filemanager36.app.ui.screens.HomeScreen
import com.filemanager36.app.ui.screens.NetworkScreen
import com.filemanager36.app.ui.screens.ShareOfferDialog
import com.filemanager36.app.ui.screens.SearchScreen
import com.filemanager36.app.ui.screens.ShareScreen
import com.filemanager36.app.ui.screens.VaultScreen
import com.filemanager36.app.ui.screens.ViewerOverlay
import com.filemanager36.app.ui.theme.FileManager36Theme

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FileManager36Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val vm: FmViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Rafraîchit les autorisations et les volumes au retour au premier plan.
    // DisposableEffect : sans retrait de l'observateur, ils s'accumuleraient.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshAccess()
                // Adresse IP, propositions reçues en arrière-plan, état du service.
                vm.refreshShare()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- Nouveautés de la version installée ----
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as FmApplication
    val prefs = remember { com.filemanager36.app.data.Prefs(context) }
    val nouveautes = remember {
        com.filemanager36.app.data.Changelog.aAnnoncer(
            versionActuelle = BuildConfig.VERSION_CODE,
            derniereVue = prefs.derniereVersionVue,
            premiereInstallation = application.premiereInstallation
        )
    }
    var nouveautesVues by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // À la première installation, rien n'est présenté — mais la version est tout de même
    // enregistrée. Sans cela, la mise à jour suivante croirait avoir affaire à quelqu'un
    // venu d'avant cette fenêtre, et n'annoncerait que sa propre version.
    LaunchedEffect(Unit) {
        if (nouveautes.isEmpty()) prefs.derniereVersionVue = BuildConfig.VERSION_CODE
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            when (vm.screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.BROWSER -> BrowserScreen(vm)
                Screen.VAULT -> VaultScreen(vm)
                Screen.NETWORK -> NetworkScreen(vm)
                Screen.FAVORITES -> FavoritesScreen(vm)
                Screen.CLOUD -> CloudScreen(vm)
                Screen.CATEGORY -> CategoryScreen(vm)
                Screen.SHARE -> ShareScreen(vm)
                Screen.SEARCH -> SearchScreen(vm)
            }

            // La visionneuse se superpose à l'écran courant : la pile de navigation
            // reste intacte, on revient exactement d'où l'on vient.
            if (vm.viewerOpen) ViewerOverlay(vm)

            vm.detailsTarget?.let { details ->
                DetailsDialog(details) { vm.detailsTarget = null }
            }

            // Fichiers à traitement particulier : APK (installation), archive ZIP
            // (extraction intégrée), format qu'aucune application ne gère.
            vm.apkTarget?.let { item ->
                ApkInstallDialog(
                    item = item,
                    onMessage = { vm.message = it },
                    onDismiss = { vm.apkTarget = null }
                )
            }

            vm.archiveTarget?.let { item ->
                ArchiveActionDialog(
                    item = item,
                    onExtract = { vm.extractArchive(item) },
                    onMessage = { vm.message = it },
                    onDismiss = { vm.archiveTarget = null }
                )
            }

            vm.openAsTarget?.let { item ->
                OpenAsDialog(
                    item = item,
                    onMessage = { vm.message = it },
                    onDismiss = { vm.openAsTarget = null }
                )
            }

            // Proposition de transfert : elle doit apparaître quel que soit l'écran
            // affiché, y compris par-dessus la visionneuse.
            ShareOfferDialog(vm)

            vm.progress?.let { ProgressOverlay(it) }

            // Par-dessus tout le reste, une seule fois : fermer enregistre la version.
            if (nouveautes.isNotEmpty() && !nouveautesVues) {
                NouveautesDialog(
                    versions = nouveautes,
                    onFermer = {
                        nouveautesVues = true
                        prefs.derniereVersionVue = BuildConfig.VERSION_CODE
                    }
                )
            }
        }
    }
}
