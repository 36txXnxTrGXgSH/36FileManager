package com.filemanager36.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.filemanager36.app.data.Clipboard
import com.filemanager36.app.data.CloudItem
import com.filemanager36.app.data.CloudRepository
import com.filemanager36.app.data.CloudRoot
import com.filemanager36.app.data.FavStat
import com.filemanager36.app.data.FavoriteEntry
import com.filemanager36.app.data.FileDetails
import com.filemanager36.app.data.FileItem
import com.filemanager36.app.data.FileOperations
import com.filemanager36.app.data.FileSearch
import com.filemanager36.app.data.CategoryFolder
import com.filemanager36.app.data.MediaCategory
import com.filemanager36.app.data.MediaRepository
import com.filemanager36.app.data.OpProgress
import com.filemanager36.app.data.Prefs
import com.filemanager36.app.data.RemoteItem
import com.filemanager36.app.data.SmbRepository
import com.filemanager36.app.data.SmbServer
import com.filemanager36.app.data.SmbShare
import com.filemanager36.app.data.ShareConfig
import com.filemanager36.app.data.ShareOffer
import com.filemanager36.app.data.SharePeer
import com.filemanager36.app.data.ShareRepository
import com.filemanager36.app.data.ShareState
import com.filemanager36.app.data.ShareTransfer
import com.filemanager36.app.data.SortField
import com.filemanager36.app.data.StorageRepo
import com.filemanager36.app.data.StorageVolumeInfo
import com.filemanager36.app.data.VaultEntry
import com.filemanager36.app.data.VaultRepository
import com.filemanager36.app.data.ViewMode
import com.filemanager36.app.util.FileKind
import com.filemanager36.app.util.FileKinds
import com.filemanager36.app.FmApplication
import com.filemanager36.app.ShareService
import com.filemanager36.app.util.Permissions
import com.filemanager36.app.util.QrCode
import com.filemanager36.app.util.ShareUtil
import com.filemanager36.app.util.Thumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Screen { HOME, BROWSER, VAULT, NETWORK, FAVORITES, CLOUD, CATEGORY, SHARE, SEARCH }

/** Résultats affichés au plus par une recherche ; au-delà, l'écran invite à préciser. */
private const val MAX_SEARCH_RESULTS = 1000

/** Pause après la dernière frappe avant de lancer le parcours. */
private const val SEARCH_DEBOUNCE_MS = 350L

class FmViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    val prefs = Prefs(app)
    val vault = VaultRepository(app, prefs)
    val smb = SmbRepository()
    val cloud = CloudRepository(app)

    // ------------------------------------------------------------------
    // État général
    // ------------------------------------------------------------------

    var screen by mutableStateOf(Screen.HOME)
        private set

    var hasStorageAccess by mutableStateOf(false)
        private set

    var volumes by mutableStateOf<List<StorageVolumeInfo>>(emptyList())
        private set

    var favorites by mutableStateOf<List<FavoriteEntry>>(emptyList())
        private set

    var servers by mutableStateOf<List<SmbServer>>(emptyList())
        private set

    var progress by mutableStateOf<OpProgress?>(null)
        private set

    var message by mutableStateOf<String?>(null)

    var detailsTarget by mutableStateOf<FileDetails?>(null)

    // ------------------------------------------------------------------
    // Réglages
    // ------------------------------------------------------------------

    var sortField by mutableStateOf(prefs.sortField)
        private set
    var sortAscending by mutableStateOf(prefs.sortAscending)
        private set
    var showHidden by mutableStateOf(prefs.showHidden)
        private set
    var viewMode by mutableStateOf(prefs.viewMode)
        private set
    var foldersFirst by mutableStateOf(prefs.foldersFirst)
        private set
    var showThumbnails by mutableStateOf(prefs.showThumbnails)
        private set

    // ------------------------------------------------------------------
    // Navigateur local
    // ------------------------------------------------------------------

    var currentPath by mutableStateOf("")
        private set
    var entries by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var clipboard by mutableStateOf(Clipboard())
        private set
    var searchQuery by mutableStateOf("")
    var searchActive by mutableStateOf(false)

    val selectionMode: Boolean get() = selected.isNotEmpty()

    val visibleEntries: List<FileItem>
        get() = if (searchQuery.isBlank()) entries
        else entries.filter { it.name.contains(searchQuery, ignoreCase = true) }

    // ------------------------------------------------------------------
    // Coffre-fort
    // ------------------------------------------------------------------

    var vaultEntries by mutableStateOf<List<VaultEntry>>(emptyList())
        private set
    var vaultSelected by mutableStateOf<Set<String>>(emptySet())
        private set
    var pendingVaultImport by mutableStateOf<List<String>>(emptyList())
        private set

    // État observable par Compose : `vault.isUnlocked` est un champ ordinaire,
    // le lire directement ne déclencherait aucune recomposition.
    var vaultUnlocked by mutableStateOf(false)
        private set
    var vaultConfigured by mutableStateOf(false)
        private set

    // ------------------------------------------------------------------
    // Lecteurs réseau
    // ------------------------------------------------------------------

    var smbServerInUse by mutableStateOf<SmbServer?>(null)
        private set
    var smbPath by mutableStateOf("")
        private set
    var smbEntries by mutableStateOf<List<RemoteItem>>(emptyList())
        private set
    var smbLoading by mutableStateOf(false)
        private set
    var smbSelected by mutableStateOf<Set<String>>(emptySet())
        private set
    var smbError by mutableStateOf<String?>(null)

    /** Dernier serveur pour lequel une connexion a été tentée, même en cas d'échec :
     *  permet de proposer « Réessayer » et l'aide correspondant à son système. */
    var smbLastAttempt by mutableStateOf<SmbServer?>(null)
        private set

    /** Dossiers partagés publiés par la machine ; null tant qu'on n'a pas interrogé. */
    var smbShares by mutableStateOf<List<SmbShare>>(emptyList())
        private set

    /** Partage actuellement ouvert ; null = on affiche la liste des partages. */
    var smbShareOpen by mutableStateOf<String?>(null)
        private set

    // Les champs de SmbRepository sont de simples propriétés : Compose ne peut pas les
    // observer. On les reflète ici pour que l'interface se recompose vraiment.
    var smbSessionOpen by mutableStateOf(false)
        private set
    var smbShareConnected by mutableStateOf(false)
        private set

    /** Incrémenté à chaque connexion ou déconnexion : invalide les réponses tardives. */
    private var smbGeneration = 0
    private var smbJob: Job? = null

    private fun syncSmbFlags() {
        smbSessionOpen = smb.isSessionOpen
        smbShareConnected = smb.isConnected
    }

    // ------------------------------------------------------------------
    // Catégories transversales (Images, Vidéos, Documents)
    // ------------------------------------------------------------------

    var categoryInUse by mutableStateOf<MediaCategory?>(null)
        private set
    var categoryCache by mutableStateOf<Map<MediaCategory, List<FileItem>>>(emptyMap())
        private set
    var categoriesLoading by mutableStateOf(false)
        private set
    var categoriesScanned by mutableStateOf(false)
        private set

    /** Vrai : la catégorie montre ses dossiers ; faux : tous les fichiers en vrac. */
    var categoryGrouped by mutableStateOf(prefs.categoryGrouped)
        private set

    /** Dossier ouvert à l'intérieur d'une catégorie, en mode « par dossier ». */
    var categoryFolder by mutableStateOf<String?>(null)
        private set

    private fun categoryPool(): List<FileItem> {
        val cat = categoryInUse ?: return emptyList()
        val all = categoryCache[cat].orEmpty()
        val folder = categoryFolder
        return if (folder == null) all
        else all.filter { it.path.substringBeforeLast('/', "/") == folder }
    }

    // derivedStateOf : trier des dizaines de milliers de fichiers à chaque recomposition
    // bloquerait le fil principal à chaque frappe dans la recherche.
    /** Contenu de la catégorie ouverte, trié et filtré par la recherche. */
    val categoryEntries: List<FileItem> by derivedStateOf {
        // On filtre avant de trier : le tri porte alors sur bien moins d'éléments.
        val pool = categoryPool()
        val filtered = if (searchQuery.isBlank()) pool
        else pool.filter { it.name.contains(searchQuery, ignoreCase = true) }
        MediaRepository.sort(filtered, sortField, sortAscending)
    }

    /** Dossiers de la catégorie ouverte, en mode « par dossier ». */
    val categoryFolders: List<CategoryFolder> by derivedStateOf {
        val cat = categoryInUse
        if (cat == null || !categoryGrouped) emptyList()
        else {
            val folders = MediaRepository.foldersOf(
                categoryCache[cat].orEmpty(), sortField, sortAscending
            )
            if (searchQuery.isBlank()) folders
            else folders.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    fun categoryCount(cat: MediaCategory): Int = categoryCache[cat]?.size ?: 0

    fun toggleCategoryGrouped() {
        categoryGrouped = !categoryGrouped
        prefs.categoryGrouped = categoryGrouped
        categoryFolder = null
        clearSelection()
    }

    fun openCategoryFolder(folder: CategoryFolder) {
        categoryFolder = folder.path
        clearSelection()
        searchQuery = ""
        searchActive = false
    }

    /** Referme le dossier ouvert ; renvoie faux si l'on était déjà au niveau supérieur. */
    fun closeCategoryFolder(): Boolean {
        if (categoryFolder == null) return false
        categoryFolder = null
        clearSelection()
        return true
    }

    // ------------------------------------------------------------------
    // Visionneuse à balayage
    // ------------------------------------------------------------------

    var viewerItems by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var viewerIndex by mutableStateOf(0)
        private set
    var viewerOpen by mutableStateOf(false)
        private set

    /**
     * Ouvre la visionneuse interne sur un fichier, avec ses voisins du même dossier
     * pour permettre le balayage. Les types non affichables restent dans la liste :
     * ils proposent simplement « Ouvrir avec une autre application ».
     */
    fun openViewer(siblings: List<FileItem>, item: FileItem) {
        val files = siblings.filterNot { it.isDirectory }
        val index = files.indexOfFirst { it.path == item.path }
        if (index < 0) return
        viewerItems = files
        viewerIndex = index
        viewerOpen = true
    }

    fun updateViewerIndex(index: Int) {
        if (index in viewerItems.indices) viewerIndex = index
    }

    fun closeViewer() {
        viewerOpen = false
        viewerItems = emptyList()
        viewerIndex = 0
    }

    val viewerCurrent: FileItem?
        get() = viewerItems.getOrNull(viewerIndex)

    // ------------------------------------------------------------------
    // Espaces cloud (Google Drive, OneDrive… via le système de documents)
    // ------------------------------------------------------------------

    var cloudRoots by mutableStateOf<List<CloudRoot>>(emptyList())
        private set
    var cloudRootInUse by mutableStateOf<CloudRoot?>(null)
        private set

    /** Pile de navigation : (identifiant du document, nom affiché). */
    var cloudStack by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var cloudEntries by mutableStateOf<List<CloudItem>>(emptyList())
        private set
    var cloudLoading by mutableStateOf(false)
        private set
    var cloudSelected by mutableStateOf<Set<String>>(emptySet())
        private set
    var cloudError by mutableStateOf<String?>(null)

    init {
        favorites = prefs.loadFavorites()
        servers = prefs.loadServers()
        cloudRoots = prefs.loadCloudRoots()
        refreshFavoriteStats()
        vaultConfigured = vault.isConfigured
        refreshAccess()
        // ATTENTION : ne rien ajouter ici qui touche l'état du partage réseau.
        // Ses propriétés sont déclarées bien plus bas, et Kotlin exécute initialiseurs
        // et blocs init dans l'ordre du source : elles n'existent pas encore ici. Le
        // branchement du partage se fait dans un second bloc init, en toute fin de
        // classe — voir son commentaire.
    }

    // ==================================================================
    // Permissions / accueil
    // ==================================================================

    fun refreshAccess() {
        hasStorageAccess = Permissions.hasFullStorageAccess(ctx)
        if (!hasStorageAccess) return
        // Le balayage des points de montage et les StatFs peuvent bloquer plusieurs
        // centaines de millisecondes sur un volume OTG capricieux : jamais sur l'UI.
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) { StorageRepo.volumes(ctx) }
            volumes = v
        }
        refreshDownloads()
    }

    fun goHome() {
        screen = Screen.HOME
        searchEntryPath = null
        clearSelection()
        searchActive = false
        searchQuery = ""
        refreshAccess()
        refreshFavoriteStats()
    }

    fun goToScreen(s: Screen) {
        screen = s
        if (s == Screen.VAULT) reloadVault()
        if (s == Screen.FAVORITES || s == Screen.HOME) refreshFavoriteStats()
        if (s == Screen.SHARE) refreshShare()
    }

    // ==================================================================
    // Téléchargements (tuile de l'accueil)
    // ==================================================================

    /**
     * Le dossier Download du stockage interne. Ce n'est pas une catégorie par type —
     * il mélange APK, PDF, images et archives — mais c'est le dossier que l'on ouvre le
     * plus souvent : tout ce qui vient d'un navigateur, d'une messagerie ou du partage
     * réseau y arrive.
     */
    val downloadsDir: File
        get() = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )

    /** Éléments visibles dans Téléchargements ; -1 tant que le comptage n'a pas eu lieu. */
    var downloadsCount by mutableStateOf(-1)
        private set

    fun refreshDownloads() {
        if (!hasStorageAccess) return
        viewModelScope.launch {
            downloadsCount = withContext(Dispatchers.IO) {
                // `list()` plutôt que `listFiles()` : on ne veut qu'un nombre, inutile de
                // fabriquer un objet File par entrée.
                downloadsDir.list()?.count { showHidden || !it.startsWith(".") } ?: 0
            }
        }
    }

    fun openDownloads() {
        val dir = downloadsDir
        if (!dir.isDirectory && !dir.mkdirs()) {
            message = "Dossier Téléchargements introuvable"
            return
        }
        openPath(dir.absolutePath)
    }

    // ==================================================================
    // Recherche dans l'arborescence
    // ==================================================================
    //
    // À ne pas confondre avec `searchQuery`, le filtre instantané du dossier ouvert :
    // celui-ci ne regarde que les enfants directs. La recherche descend dans tous les
    // sous-dossiers, d'un dossier ou de tout le téléphone, avec le même moteur que la
    // page web du partage réseau (FileSearch).

    var searchText by mutableStateOf("")
        private set
    /** Dossier où chercher ; null = tous les volumes. */
    var searchScope by mutableStateOf<String?>(null)
        private set
    var searchResults by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var searchRunning by mutableStateOf(false)
        private set
    /** Bilan de la dernière recherche terminée ; null pendant la saisie. */
    var searchOutcome by mutableStateOf<FileSearch.Outcome?>(null)
        private set

    /** Dossier depuis lequel la recherche a été ouverte, pour pouvoir y revenir. */
    var searchOrigin by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null
    private var searchReturn: Screen = Screen.HOME

    /**
     * Dossier ouvert depuis un résultat. Un retour arrière depuis ce dossier ramène
     * aux résultats au lieu de remonter l'arborescence : sans cela, ouvrir un résultat
     * faisait perdre la recherche, et il fallait tout retaper pour voir le suivant.
     */
    private var searchEntryPath: String? = null

    /**
     * @param scopePath dossier de départ, ou null pour tout le téléphone.
     * @param initial saisie déjà tapée — celle du filtre du navigateur, pour ne pas la
     * faire retaper en passant aux sous-dossiers.
     */
    fun openSearch(scopePath: String?, initial: String = "") {
        if (screen != Screen.SEARCH) searchReturn = screen
        searchScope = scopePath
        searchOrigin = scopePath
        searchEntryPath = null
        searchText = initial
        searchResults = emptyList()
        searchOutcome = null
        clearSelection()
        screen = Screen.SEARCH
        runSearch(immediate = true)
    }

    fun updateSearchText(text: String) {
        searchText = text
        runSearch(immediate = false)
    }

    // « change » et non « set » : `setSearchScope` entre en collision avec le mutateur
    // JVM de la propriété `searchScope` (piège déjà rencontré, voir le dossier de projet).
    fun changeSearchScope(path: String?) {
        searchScope = path
        runSearch(immediate = true)
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchRunning = false
        screen = searchReturn
    }

    /** Ouvre le dossier qui contient [item], avec l'élément déjà sélectionné. */
    fun revealInFolder(item: FileItem) {
        val parent = File(item.path).parentFile ?: return
        openPath(parent.absolutePath)
        searchEntryPath = parent.absolutePath
        // openPath vide la sélection : on la pose ensuite, pour que l'élément cherché
        // saute aux yeux au milieu de son dossier.
        selected = setOf(item.path)
    }

    /** Ouvre un dossier trouvé par la recherche, en gardant le chemin du retour. */
    fun openSearchFolder(item: FileItem) {
        openPath(item.path)
        searchEntryPath = item.path
    }

    private fun runSearch(immediate: Boolean) {
        searchJob?.cancel()
        val query = searchText
        if (FileSearch.terms(query).isEmpty()) {
            searchResults = emptyList()
            searchRunning = false
            searchOutcome = null
            return
        }
        val roots = searchScope?.let { listOf(File(it)) }
            // La racine « / » n'est pas un volume de données : la parcourir ferait
            // repasser par /sdcard, /storage et /mnt — trois fois les mêmes fichiers.
            ?: volumes.filter { it.path != "/" }.map { File(it.path) }
        val hidden = showHidden

        searchJob = viewModelScope.launch {
            // Laisser finir la frappe : sans ce délai, chaque lettre relancerait un
            // parcours complet du stockage.
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            searchRunning = true
            searchOutcome = null
            searchResults = emptyList()

            val found = java.util.Collections.synchronizedList(ArrayList<FileItem>())
            // Les résultats s'affichent au fil du parcours, par paquets : un parcours du
            // stockage entier prend plusieurs secondes, et un écran vide pendant ce
            // temps ressemble à une recherche qui n'a rien trouvé.
            val ticker = launch {
                while (isActive) {
                    delay(200)
                    searchResults = synchronized(found) { found.toList() }
                }
            }
            val outcome = withContext(Dispatchers.IO) {
                FileSearch.search(roots, query, hidden, MAX_SEARCH_RESULTS, shouldStop = { !isActive }) {
                    found += FileItem.from(it)
                }
            }
            ticker.cancel()
            searchResults = synchronized(found) { found.toList() }
            searchOutcome = outcome
            searchRunning = false
        }
    }

    // ==================================================================
    // Navigation locale
    // ==================================================================

    fun openPath(path: String) {
        currentPath = path
        screen = Screen.BROWSER
        clearSelection()
        searchQuery = ""
        searchActive = false
        reload()
    }

    private var listJob: Job? = null

    fun reload() {
        val dir = File(currentPath)
        if (!dir.isDirectory) return
        // Un listage lent ne doit jamais écraser le contenu d'un dossier ouvert depuis.
        listJob?.cancel()
        loading = true
        listJob = viewModelScope.launch {
            val requested = dir.absolutePath
            val list = withContext(Dispatchers.IO) {
                StorageRepo.list(dir, showHidden, sortField, sortAscending, foldersFirst)
            }
            if (requested == currentPath) {
                entries = list
                loading = false
            }
        }
    }

    fun navigateUp(): Boolean {
        if (selectionMode) { clearSelection(); return true }
        if (searchActive) { searchActive = false; searchQuery = ""; return true }
        if (searchEntryPath != null && currentPath == searchEntryPath) {
            searchEntryPath = null
            screen = Screen.SEARCH
            return true
        }
        val parent = File(currentPath).parentFile
        val isVolumeRoot = volumes.any { it.path == currentPath }
        return if (!isVolumeRoot && parent != null && parent.absolutePath != "/") {
            openPath(parent.absolutePath)
            true
        } else {
            goHome()
            true
        }
    }

    // ==================================================================
    // Sélection
    // ==================================================================

    fun toggleSelect(path: String) {
        selected = if (selected.contains(path)) selected - path else selected + path
    }

    fun selectAll() {
        selected = if (screen == Screen.CATEGORY) categoryEntries.map { it.path }.toSet()
        else visibleEntries.map { it.path }.toSet()
    }

    fun clearSelection() { selected = emptySet() }

    fun selectedFiles(): List<File> = selected.map { File(it) }.filter { it.exists() }

    // ==================================================================
    // Réglages
    // ==================================================================

    fun setSort(field: SortField) {
        if (sortField == field) sortAscending = !sortAscending else { sortField = field; sortAscending = true }
        prefs.sortField = sortField
        prefs.sortAscending = sortAscending
        reload()
        if (smbServerInUse != null) reloadSmb()
    }

    fun toggleHidden() {
        showHidden = !showHidden
        prefs.showHidden = showHidden
        reload()
        if (smbServerInUse != null) reloadSmb()
    }

    fun toggleViewMode() {
        viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        prefs.viewMode = viewMode
    }

    fun toggleFoldersFirst() {
        foldersFirst = !foldersFirst
        prefs.foldersFirst = foldersFirst
        reload()
    }

    fun toggleThumbnails() {
        showThumbnails = !showThumbnails
        prefs.showThumbnails = showThumbnails
        if (!showThumbnails) Thumbnails.clear()
    }

    // ==================================================================
    // Opérations sur les fichiers
    // ==================================================================

    fun copySelection() {
        clipboard = Clipboard(selected.toList(), isMove = false)
        message = "${selected.size} élément(s) copié(s)"
        clearSelection()
    }

    fun cutSelection() {
        clipboard = Clipboard(selected.toList(), isMove = true)
        message = "${selected.size} élément(s) prêt(s) à être déplacé(s)"
        clearSelection()
    }

    fun clearClipboard() {
        clipboard = Clipboard()
        message = "Presse-papiers vidé"
    }

    fun paste(destination: String = currentPath) {
        val cb = clipboard
        if (cb.isEmpty) return
        val dest = File(destination)
        runOperation(if (cb.isMove) "Déplacement" else "Copie") { report ->
            val res = FileOperations.copyOrMove(
                cb.paths.map { File(it) }, dest, cb.isMove
            ) { name, done, total -> report(name, done, total) }
            clipboard = Clipboard()
            resultMessage(res, if (cb.isMove) "déplacé(s)" else "copié(s)")
        }
    }

    fun deleteSelection() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        runOperation("Suppression") { report ->
            val res = FileOperations.deleteAll(files) { name, done, total -> report(name, done, total) }
            resultMessage(res, "supprimé(s)")
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { FileOperations.rename(File(path), newName) }
            res.onSuccess { renamed -> replaceInCategoryCache(path, renamed) }
            message = res.fold({ "Renommé en « ${it.name} »" }, { it.message ?: "Renommage impossible" })
            clearSelection()
            refreshCurrentView()
        }
    }

    /** Met à jour l'entrée correspondante dans les catégories après un renommage. */
    private fun replaceInCategoryCache(oldPath: String, renamed: File) {
        if (categoryCache.isEmpty()) return
        categoryCache = categoryCache.mapValues { (_, list) ->
            list.map { item ->
                if (item.path == oldPath) item.copy(path = renamed.absolutePath, name = renamed.name)
                else item
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { FileOperations.newFolder(File(currentPath), name) }
            message = res.fold({ "Dossier « ${it.name} » créé" }, { it.message ?: "Création impossible" })
            reload()
        }
    }

    fun compressSelection(archiveName: String) {
        val files = selectedFiles()
        if (files.isEmpty()) return
        val name = if (archiveName.endsWith(".zip", true)) archiveName else "$archiveName.zip"
        val dest = FileOperations.uniqueTarget(File(currentPath), name)
        runOperation("Compression") { report ->
            val res = FileOperations.zip(files, dest) { n, d, t -> report(n, d, t) }
            message = res.fold({ "Archive « ${it.name} » créée" }, { "Compression impossible : ${it.message}" })
        }
    }

    fun extractSelection() {
        val files = selectedFiles().filter { it.extension.equals("zip", true) }
        if (files.isEmpty()) { message = "Sélectionnez une archive .zip"; return }
        runOperation("Extraction") { report ->
            for (f in files) {
                val dest = FileOperations.uniqueTarget(File(currentPath), f.nameWithoutExtension)
                FileOperations.unzip(f, dest) { n, d, t -> report(n, d, t) }
                    .onFailure { message = "Extraction impossible : ${it.message}" }
            }
            message = "Extraction terminée"
        }
    }

    fun showDetails(path: String) {
        viewModelScope.launch {
            // Un dossier système pathologique (/proc, montage figé) ne doit pas
            // remonter une exception jusqu'au gestionnaire par défaut.
            val res = withContext(Dispatchers.IO) {
                runCatching { FileOperations.details(File(path)) }
            }
            res.fold({ detailsTarget = it }, { message = "Informations indisponibles : ${it.message}" })
        }
    }

    fun shareSelection(context: Context) {
        val files = selectedFiles()
        if (files.isEmpty()) return
        val folders = files.filter { it.isDirectory }
        if (folders.isEmpty()) {
            ShareUtil.share(context, files)
            clearSelection()
            return
        }
        // Un dossier ne peut pas être partagé tel quel : on le compresse d'abord.
        runOperation("Préparation du partage") { report ->
            val cacheDir = File(context.cacheDir, "share").apply { mkdirs() }
            val zipName = if (files.size == 1) "${files.first().name}.zip" else "partage.zip"
            val zip = FileOperations.uniqueTarget(cacheDir, zipName)
            FileOperations.zip(files, zip) { n, d, t -> report(n, d, t) }
            withContext(Dispatchers.Main) { ShareUtil.share(context, listOf(zip)) }
        }
    }

    /**
     * Types affichés par la visionneuse interne.
     *
     * Vidéo et musique en sont volontairement exclues : la lecture est confiée à une
     * application dédiée du téléphone (VLC, MX Player, le lecteur du constructeur),
     * bien plus complète et sans risque pour la stabilité de l'application.
     */
    private fun isViewable(item: FileItem): Boolean = when (FileKinds.of(item.name, false)) {
        FileKind.IMAGE, FileKind.PDF -> true
        // Texte brut, code, sous-titres, journaux… : beaucoup de téléphones n'ont
        // aucune application pour ces formats, la visionneuse intégrée les affiche.
        else -> FileKinds.isText(item.name)
    }

    // ------------------------------------------------------------------
    // Fichiers à traitement particulier : APK et archives
    // ------------------------------------------------------------------

    /** APK dont la fiche d'installation est affichée. */
    var apkTarget by mutableStateOf<FileItem?>(null)

    /** Archive .zip pour laquelle on propose l'extraction. */
    var archiveTarget by mutableStateOf<FileItem?>(null)

    /** Fichier qu'aucune application ne sait ouvrir : on propose « Ouvrir en tant que… ». */
    var openAsTarget by mutableStateOf<FileItem?>(null)

    fun openFile(context: Context, item: FileItem, siblings: List<FileItem> = emptyList()) {
        when {
            item.isDirectory -> openPath(item.path)
            // Un APK n'est pas « ouvert » mais installé : fiche détaillée d'abord.
            FileKinds.of(item.name, false) == FileKind.APK -> apkTarget = item
            // Une archive ZIP est extraite par l'application elle-même : inutile de
            // dépendre d'une application tierce qui n'est souvent pas installée.
            FileKinds.isZip(item.name) -> archiveTarget = item
            // HTML, CSV, Markdown : le navigateur ou le tableur du téléphone les rend
            // bien mieux que du texte brut. La visionneuse intégrée ne les reprend que
            // si rien sur l'appareil ne sait les ouvrir.
            FileKinds.prefersExternalApp(item.name) &&
                ShareUtil.hasHandler(context, item.file) -> openExternally(context, item)
            isViewable(item) ->
                openViewer(if (siblings.isEmpty()) listOf(item) else siblings, item)
            else -> openExternally(context, item)
        }
    }

    /**
     * Confie le fichier à une application du téléphone. En cas d'échec, propose
     * « Ouvrir en tant que… » au lieu de laisser l'utilisateur devant un écran inerte.
     */
    fun openExternally(context: Context, item: FileItem) {
        // Un fichier disparu doit donner un message clair, pas la proposition
        // « Ouvrir en tant que… » dont aucune option ne pourrait fonctionner.
        if (!item.file.exists()) { message = "Ce fichier n'existe plus"; return }
        // allowGenericFallback = false : ici, et seulement ici, il y a « Ouvrir en
        // tant que… » derrière l'échec.
        val error = ShareUtil.openOrExplain(context, item.file, allowGenericFallback = false)
        if (error != null) openAsTarget = item
    }

    /** Extrait une archive .zip à côté d'elle, dans un dossier à son nom. */
    fun extractArchive(item: FileItem) {
        val source = item.file
        val parent = source.parentFile ?: File(currentPath)
        // Une archive nommée « .zip » donnerait un nom de dossier vide.
        val baseName = source.nameWithoutExtension.ifBlank { "archive" }
        runOperation("Extraction") { report ->
            val dest = FileOperations.uniqueTarget(parent, baseName)
            FileOperations.unzip(source, dest) { n, d, t -> report(n, d, t) }
                .fold(
                    {
                        // Une archive corrompue, chiffrée ou simplement mal nommée
                        // produit un dossier vide sans lever d'exception : sans ce
                        // contrôle, l'application annonçait « Extrait » pour rien.
                        val empty = dest.listFiles()?.isEmpty() ?: true
                        if (empty) {
                            dest.delete()
                            message = "Archive vide, protégée par mot de passe ou illisible"
                        } else {
                            message = "Extrait dans « ${dest.name} »"
                        }
                    },
                    {
                        // Le dossier partiellement extrait est conservé : y toucher
                        // pourrait détruire des fichiers déjà récupérés.
                        val salvaged = dest.listFiles()?.isNotEmpty() ?: false
                        if (!salvaged) dest.delete()
                        message = if (salvaged)
                            "Extraction interrompue : ${it.message}. Le dossier " +
                                "« ${dest.name} » contient ce qui a pu être extrait."
                        else "Extraction impossible : ${it.message}"
                    }
                )
        }
    }

    // ==================================================================
    // Favoris
    // ==================================================================

    fun isFavorite(path: String) = favorites.any { it.path == path }

    fun toggleFavorite(item: FileItem) {
        favorites = if (isFavorite(item.path)) {
            favorites.filterNot { it.path == item.path }
        } else {
            favorites + FavoriteEntry(item.path, item.name, item.isDirectory, System.currentTimeMillis())
        }
        prefs.saveFavorites(favorites)
        refreshFavoriteStats()
        message = if (isFavorite(item.path)) "Ajouté aux favoris" else "Retiré des favoris"
    }

    fun addSelectionToFavorites() {
        val add = selectedFiles().filterNot { isFavorite(it.absolutePath) }.map {
            FavoriteEntry(it.absolutePath, it.name, it.isDirectory, System.currentTimeMillis())
        }
        favorites = favorites + add
        prefs.saveFavorites(favorites)
        refreshFavoriteStats()
        message = "${add.size} favori(s) ajouté(s)"
        clearSelection()
    }

    fun removeFavorite(path: String) {
        favorites = favorites.filterNot { it.path == path }
        prefs.saveFavorites(favorites)
        refreshFavoriteStats()
    }

    fun openFavorite(context: Context, fav: FavoriteEntry) {
        val f = File(fav.path)
        if (!f.exists()) { message = "Ce raccourci ne pointe plus vers rien"; return }
        // Passe par openFile pour bénéficier des traitements particuliers (APK,
        // archive, texte) au lieu d'envoyer directement au sélecteur système.
        if (f.isDirectory) openPath(f.absolutePath) else openFile(context, FileItem.from(f))
    }

    // ==================================================================
    // Coffre-fort
    // ==================================================================

    fun reloadVault() {
        vaultUnlocked = vault.isUnlocked
        vaultConfigured = vault.isConfigured
        vaultEntries = if (vault.isUnlocked) vault.loadIndex() else emptyList()
    }

    fun setupVault(password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { vault.setup(password.toCharArray()).isSuccess }
            reloadVault()
            if (ok) consumePendingImport()
            message = if (ok) "Coffre-fort créé" else "Création impossible"
            onDone(ok)
        }
    }

    fun unlockVault(password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { vault.unlockWithPassword(password.toCharArray()) }
            reloadVault()
            if (ok) consumePendingImport()
            onDone(ok)
        }
    }

    fun onVaultUnlockedByBiometrics() {
        reloadVault()
        consumePendingImport()
    }

    fun lockVault() {
        if (operationRunning) {
            message = "Impossible de verrouiller pendant une opération"
            return
        }
        vault.lock()
        vault.clearOpenCache()
        vaultUnlocked = false
        vaultEntries = emptyList()
        vaultSelected = emptySet()
    }

    fun toggleVaultSelect(id: String) {
        vaultSelected = if (vaultSelected.contains(id)) vaultSelected - id else vaultSelected + id
    }

    fun clearVaultSelection() { vaultSelected = emptySet() }

    fun selectAllVault() { vaultSelected = vaultEntries.map { it.id }.toSet() }

    /** Depuis le navigateur : déplace la sélection vers le coffre. */
    fun moveSelectionToVault() {
        val paths = selected.toList()
        if (paths.isEmpty()) return
        clearSelection()
        if (!vault.isUnlocked) {
            pendingVaultImport = paths
            screen = Screen.VAULT
            message = "Déverrouillez le coffre pour terminer le transfert"
            return
        }
        importToVault(paths)
    }

    private fun consumePendingImport() {
        val paths = pendingVaultImport
        if (paths.isNotEmpty()) {
            pendingVaultImport = emptyList()
            importToVault(paths)
        }
    }

    fun cancelPendingImport() { pendingVaultImport = emptyList() }

    private fun importToVault(paths: List<String>) {
        runOperation("Chiffrement vers le coffre") { report ->
            val res = vault.importFiles(paths.map { File(it) }) { n, d, t -> report(n, d, t) }
            reloadVault()
            reload()
            resultMessage(res, "protégé(s)")
        }
    }

    fun restoreVaultSelection(destination: String) {
        val ids = vaultSelected
        if (ids.isEmpty()) return
        val dest = File(destination)
        runOperation("Restauration") { report ->
            val list = vaultEntries.filter { ids.contains(it.id) }
            list.forEachIndexed { i, entry ->
                report(entry.name, i, list.size)
                vault.exportEntry(entry, dest, removeFromVault = true)
                    .onFailure { message = "Échec : ${entry.name}" }
            }
            reloadVault()
            clearVaultSelection()
            message = "${list.size} élément(s) restauré(s) dans ${dest.name}"
        }
    }

    fun deleteVaultSelection() {
        val ids = vaultSelected
        if (ids.isEmpty()) return
        runOperation("Suppression") { _ ->
            vault.deleteEntries(ids)
            reloadVault()
            clearVaultSelection()
            message = "${ids.size} élément(s) supprimé(s) définitivement"
        }
    }

    fun openVaultEntry(context: Context, entry: VaultEntry) {
        viewModelScope.launch {
            progress = OpProgress("Déchiffrement", entry.name, indeterminate = true)
            val res = withContext(Dispatchers.IO) { vault.decryptToCache(entry) }
            progress = null
            res.fold({ ShareUtil.open(context, it) }, { message = "Ouverture impossible : ${it.message}" })
        }
    }

    // ==================================================================
    // Lecteurs réseau
    // ==================================================================

    fun saveServer(server: SmbServer, quiet: Boolean = false) {
        val id = server.id.ifBlank { UUID.randomUUID().toString() }
        // Un enregistrement silencieux ne doit jamais recréer un serveur supprimé.
        if (quiet && servers.none { it.id == id }) return
        val s = server.copy(id = id)
        servers = if (servers.any { it.id == id }) servers.map { if (it.id == id) s else it } else servers + s
        prefs.saveServers(servers)
        if (smbServerInUse?.id == id) smbServerInUse = s
        if (!quiet) message = "Lecteur réseau « ${s.label} » enregistré"
    }

    fun deleteServer(id: String) {
        if (smbServerInUse?.id == id) disconnectSmb()
        servers = servers.filterNot { it.id == id }
        prefs.saveServers(servers)
    }

    /** Étape 1 : ouvre la session sur la machine, puis demande la liste des partages. */
    fun connectServer(server: SmbServer) {
        // Sans ce garde-fou, un second appui reconnecterait par-dessus la session
        // qu'un téléchargement en cours est en train d'utiliser.
        if (smbLoading || operationRunning) {
            message = "Connexion déjà en cours"
            return
        }
        val generation = ++smbGeneration
        smbLoading = true
        smbError = null
        smbShares = emptyList()
        smbShareOpen = null
        smbEntries = emptyList()
        smbSelected = emptySet()
        smbPath = ""
        screen = Screen.NETWORK
        smbLastAttempt = server
        smbJob?.cancel()
        smbJob = viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                smb.connect(server).mapCatching { smb.listShares().getOrThrow() }
            }
            if (generation != smbGeneration) return@launch
            smbLoading = false
            syncSmbFlags()
            res.fold(
                { shares ->
                    smbServerInUse = server
                    smbShares = shares
                    if (shares.isEmpty()) {
                        smbError = "Connexion réussie, mais aucun dossier partagé n'est publié " +
                            "par ${server.host}. Partagez un dossier sur la machine, puis réessayez."
                    }
                },
                {
                    smbError = friendlySmbError(it)
                    smbServerInUse = null
                }
            )
        }
    }

    /** Traduit les échecs SMB les plus courants en langage compréhensible. */
    private fun friendlySmbError(e: Throwable): String {
        val raw = (e.message ?: e.toString())
        val lower = raw.lowercase()
        return when {
            lower.contains("logon_failure") || lower.contains("access_denied") ||
                lower.contains("password") ->
                "Identifiant ou mot de passe refusé par la machine. Avec un compte Microsoft, " +
                    "saisissez l'adresse e-mail complète."
            lower.contains("failed to connect") || lower.contains("timed out") ||
                lower.contains("timeout") || lower.contains("ehostunreach") ||
                lower.contains("enetunreach") || lower.contains("no route to host") ->
                "Aucune réponse de la machine sur le port du partage. Rien n'écoute à cette " +
                    "adresse : le pare-feu bloque, le partage de fichiers est désactivé, " +
                    "la machine est en veille, ou l'adresse IP n'est pas la bonne."
            lower.contains("connection refused") || lower.contains("econnrefused") ->
                "Connexion refusée : la machine est joignable, mais le service de partage de " +
                    "fichiers n'est pas démarré."
            lower.contains("econnreset") || lower.contains("connection reset") ->
                "La machine a coupé la connexion. Version de SMB trop ancienne, ou " +
                    "restriction de sécurité côté serveur."
            lower.contains("unknown host") || lower.contains("unresolved") ->
                "Adresse introuvable sur le réseau."
            lower.contains("account_restriction") || lower.contains("account_disabled") ->
                "Ce compte n'a pas le droit de se connecter au partage (compte sans mot de passe ?)."
            lower.contains("bad_network_name") ->
                "Ce nom de partage n'existe pas sur la machine. Revenez à la liste et " +
                    "choisissez un partage réellement publié par le serveur."
            else -> raw
        }
    }

    /** Étape 2 : ouvre un des partages listés. */
    fun openSmbShare(share: SmbShare) {
        if (smbLoading) return
        val generation = smbGeneration
        smbLoading = true
        smbJob?.cancel()
        smbJob = viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { smb.openShare(share.name) }
            if (generation != smbGeneration) return@launch
            smbLoading = false
            syncSmbFlags()
            res.fold(
                {
                    smbShareOpen = share.name
                    smbPath = ""
                    smbSelected = emptySet()
                    smbError = null
                    // Mémorise le dernier partage utilisé, sans ressusciter un serveur supprimé.
                    smbServerInUse?.let { srv ->
                        if (srv.share != share.name && servers.any { it.id == srv.id }) {
                            saveServer(srv.copy(share = share.name), quiet = true)
                        }
                    }
                    reloadSmb()
                },
                {
                    // Message transitoire : effacer smbError effacerait la liste des partages,
                    // et l'utilisateur n'aurait plus aucun moyen d'en choisir un autre.
                    message = friendlySmbError(it)
                    // Un partage mémorisé qui a disparu ne doit pas rester accroché au serveur.
                    smbServerInUse?.let { srv ->
                        if (srv.share == share.name) saveServer(srv.copy(share = ""), quiet = true)
                    }
                }
            )
        }
    }

    /** Revient à la liste des partages sans fermer la session. */
    fun backToSmbShares() {
        smbShareOpen = null
        smbEntries = emptyList()
        smbSelected = emptySet()
        smbPath = ""
        smbError = null
        // La fermeture d'un arbre SMB envoie un TREE_DISCONNECT : jamais sur le fil principal,
        // sinon l'exception est avalée et le partage reste en cache, définitivement mort.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { smb.closeShare() }
            syncSmbFlags()
        }
    }

    fun reloadSmb() {
        if (!smb.isConnected) { syncSmbFlags(); return }
        val generation = smbGeneration
        val requested = smbPath
        smbLoading = true
        smbJob?.cancel()
        smbJob = viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { smb.list(requested, showHidden, sortField, sortAscending) }
            }
            if (generation != smbGeneration || requested != smbPath) return@launch
            smbLoading = false
            res.fold({ smbEntries = it; smbError = null }, { message = friendlySmbError(it) })
        }
    }

    fun openSmbFolder(name: String) {
        smbPath = if (smbPath.isBlank()) name else "$smbPath\\$name"
        smbSelected = emptySet()
        reloadSmb()
    }

    /** Remonte d'un cran : dossier → racine du partage → liste des partages → accueil. */
    fun smbUp(): Boolean {
        if (smbSelected.isNotEmpty()) { smbSelected = emptySet(); return true }
        if (smbShareOpen != null) {
            if (smbPath.isBlank()) backToSmbShares() else {
                smbPath = smbPath.substringBeforeLast('\\', "")
                reloadSmb()
            }
            return true
        }
        disconnectSmb()
        return false
    }

    fun disconnectSmb() {
        smbGeneration++
        smbJob?.cancel()
        smbServerInUse = null
        smbShares = emptyList()
        smbShareOpen = null
        smbEntries = emptyList()
        smbPath = ""
        smbSelected = emptySet()
        smbError = null
        smbLoading = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) { smb.disconnect() }
            syncSmbFlags()
        }
    }

    fun toggleSmbSelect(name: String) {
        smbSelected = if (smbSelected.contains(name)) smbSelected - name else smbSelected + name
    }

    fun selectAllSmb() { smbSelected = smbEntries.map { it.name }.toSet() }

    fun clearSmbSelection() { smbSelected = emptySet() }

    /** Télécharge la sélection distante vers un dossier local. */
    fun downloadSmbSelection(destination: String) {
        val names = smbSelected.toList()
        if (names.isEmpty()) return
        val dest = File(destination).apply { mkdirs() }
        runOperation("Téléchargement depuis le lecteur réseau") { report ->
            names.forEachIndexed { i, name ->
                val item = smbEntries.firstOrNull { it.name == name } ?: return@forEachIndexed
                report(name, i, names.size)
                val remote = if (smbPath.isBlank()) name else "$smbPath\\$name"
                smb.downloadRecursive(remote, item.isDirectory, dest) { n -> report(n, i, names.size) }
                    .onFailure { message = "Échec : $name (${it.message})" }
            }
            clearSmbSelection()
            message = "${names.size} élément(s) téléchargé(s) dans ${dest.name}"
        }
    }

    /** Envoie des fichiers locaux vers le dossier distant courant. */
    fun uploadToSmb(localPaths: List<String>) {
        if (localPaths.isEmpty() || !smb.isConnected) return
        runOperation("Envoi vers le lecteur réseau") { report ->
            localPaths.forEachIndexed { i, p ->
                val f = File(p)
                report(f.name, i, localPaths.size)
                smb.uploadRecursive(f, smbPath) { n -> report(n, i, localPaths.size) }
                    .onFailure { message = "Échec : ${f.name} (${it.message})" }
            }
            reloadSmb()
            message = "${localPaths.size} élément(s) envoyé(s)"
        }
    }

    fun deleteSmbSelection() {
        val names = smbSelected.toList()
        if (names.isEmpty()) return
        runOperation("Suppression distante") { report ->
            names.forEachIndexed { i, name ->
                val item = smbEntries.firstOrNull { it.name == name } ?: return@forEachIndexed
                report(name, i, names.size)
                val remote = if (smbPath.isBlank()) name else "$smbPath\\$name"
                smb.delete(remote, item.isDirectory).onFailure { message = "Échec : $name" }
            }
            clearSmbSelection()
            reloadSmb()
        }
    }

    fun renameSmb(name: String, newName: String) {
        val item = smbEntries.firstOrNull { it.name == name } ?: return
        runOperation("Renommage distant") { _ ->
            val remote = if (smbPath.isBlank()) name else "$smbPath\\$name"
            smb.rename(remote, newName, item.isDirectory)
                .onSuccess { message = "Renommé en « $newName »" }
                .onFailure { message = "Renommage impossible : ${it.message}" }
            clearSmbSelection()
            reloadSmb()
        }
    }

    fun createSmbFolder(name: String) {
        runOperation("Création du dossier distant") { _ ->
            val remote = if (smbPath.isBlank()) name else "$smbPath\\$name"
            smb.mkdir(remote).onFailure { message = "Création impossible : ${it.message}" }
            reloadSmb()
        }
    }

    // ==================================================================
    // Espace favoris
    // ==================================================================

    /**
     * Métadonnées des favoris relevées une seule fois, hors du fil principal.
     * Interroger le disque pendant la composition bloquerait l'affichage dès qu'un
     * raccourci pointe vers une carte SD retirée ou un montage réseau endormi.
     */
    var favoriteStats by mutableStateOf<Map<String, FavStat>>(emptyMap())
        private set

    fun refreshFavoriteStats() {
        val snapshot = favorites
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                snapshot.associate { fav ->
                    val f = File(fav.path)
                    val exists = f.exists()
                    fav.path to FavStat(
                        exists = exists,
                        size = if (exists && !fav.isDirectory) f.length() else 0L,
                        lastModified = if (exists) f.lastModified() else 0L
                    )
                }
            }
            favoriteStats = stats
        }
    }

    fun statOf(fav: FavoriteEntry): FavStat? = favoriteStats[fav.path]

    /** Favoris triés selon le réglage courant, dossiers d'abord. */
    val sortedFavorites: List<FavoriteEntry>
        get() {
            val stats = favoriteStats
            val cmp = when (sortField) {
                SortField.NAME -> compareBy<FavoriteEntry> { it.name.lowercase() }
                SortField.SIZE -> compareBy<FavoriteEntry> { stats[it.path]?.size ?: 0L }
                SortField.DATE -> compareBy<FavoriteEntry> { it.addedAt }
            }
            val ordered = if (sortAscending) cmp else cmp.reversed()
            return favorites.sortedWith(
                if (foldersFirst) compareByDescending<FavoriteEntry> { it.isDirectory }.then(ordered)
                else ordered
            )
        }

    /** Ouvre le dossier qui contient le favori, plutôt que le favori lui-même. */
    fun openFavoriteLocation(fav: FavoriteEntry) {
        val parent = File(fav.path).parentFile
        if (parent != null && parent.isDirectory) openPath(parent.absolutePath)
        else message = "Emplacement introuvable"
    }

    fun removeBrokenFavorites() {
        viewModelScope.launch {
            val before = favorites.size
            val kept = withContext(Dispatchers.IO) { favorites.filter { File(it.path).exists() } }
            favorites = kept
            prefs.saveFavorites(kept)
            refreshFavoriteStats()
            val removed = before - kept.size
            message = if (removed == 0) "Aucun raccourci cassé"
            else "$removed raccourci(s) cassé(s) retiré(s)"
        }
    }

    // ==================================================================
    // Espaces cloud
    // ==================================================================

    /** Enregistre un dossier cloud choisi dans le sélecteur système. */
    fun addCloudRoot(treeUri: Uri) {
        if (!cloud.persist(treeUri)) {
            message = "Autorisation refusée par le fournisseur cloud"
            return
        }
        val uriString = treeUri.toString()
        if (cloudRoots.any { it.treeUri == uriString }) {
            message = "Ce dossier est déjà connecté"
            return
        }
        val root = CloudRoot(
            id = UUID.randomUUID().toString(),
            label = cloud.guessLabel(treeUri),
            treeUri = uriString,
            addedAt = System.currentTimeMillis()
        )
        cloudRoots = cloudRoots + root
        prefs.saveCloudRoots(cloudRoots)
        message = "« ${root.label} » connecté"
    }

    fun renameCloudRoot(id: String, label: String) {
        cloudRoots = cloudRoots.map { if (it.id == id) it.copy(label = label) else it }
        prefs.saveCloudRoots(cloudRoots)
    }

    fun removeCloudRoot(id: String) {
        cloudRoots.firstOrNull { it.id == id }?.let { cloud.release(Uri.parse(it.treeUri)) }
        cloudRoots = cloudRoots.filterNot { it.id == id }
        prefs.saveCloudRoots(cloudRoots)
        if (cloudRootInUse?.id == id) {
            cloudRootInUse = null
            cloudEntries = emptyList()
            cloudStack = emptyList()
        }
    }

    fun openCloudRoot(root: CloudRoot) {
        cloudRootInUse = root
        cloudError = null
        cloudSelected = emptySet()
        screen = Screen.CLOUD
        val uri = Uri.parse(root.treeUri)
        val rootId = runCatching { cloud.rootDocumentId(uri) }.getOrNull()
        if (rootId == null) {
            cloudError = "Dossier inaccessible — reconnectez-le depuis l'accueil"
            cloudStack = emptyList()
            return
        }
        cloudStack = listOf(rootId to root.label)
        reloadCloud()
    }

    private var cloudJob: Job? = null

    fun reloadCloud() {
        val root = cloudRootInUse ?: return
        val current = cloudStack.lastOrNull() ?: return
        // Même garde-fou que pour le stockage local : un listage lent ne doit pas
        // écraser le contenu d'un dossier ouvert entre-temps.
        cloudJob?.cancel()
        cloudLoading = true
        cloudJob = viewModelScope.launch {
            val requested = current.first
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    cloud.list(Uri.parse(root.treeUri), requested, showHidden, sortField, sortAscending)
                }
            }
            if (requested != cloudStack.lastOrNull()?.first) return@launch
            cloudLoading = false
            res.fold(
                { cloudEntries = it; cloudError = null },
                { cloudError = it.message ?: "Lecture impossible"; cloudEntries = emptyList() }
            )
        }
    }

    fun openCloudFolder(item: CloudItem) {
        cloudStack = cloudStack + (item.documentId to item.name)
        cloudSelected = emptySet()
        reloadCloud()
    }

    /** Remonte d'un cran ; renvoie faux si l'on était déjà à la racine. */
    fun cloudUp(): Boolean {
        if (cloudSelected.isNotEmpty()) { cloudSelected = emptySet(); return true }
        if (cloudStack.size <= 1) return false
        cloudStack = cloudStack.dropLast(1)
        reloadCloud()
        return true
    }

    fun cloudJumpTo(index: Int) {
        if (index < 0 || index >= cloudStack.size) return
        cloudStack = cloudStack.take(index + 1)
        cloudSelected = emptySet()
        reloadCloud()
    }

    fun toggleCloudSelect(id: String) {
        cloudSelected = if (cloudSelected.contains(id)) cloudSelected - id else cloudSelected + id
    }

    fun selectAllCloud() { cloudSelected = cloudEntries.map { it.documentId }.toSet() }

    fun clearCloudSelection() { cloudSelected = emptySet() }

    fun openCloudFile(context: Context, item: CloudItem) {
        val root = cloudRootInUse ?: return
        viewModelScope.launch {
            progress = OpProgress("Téléchargement", item.name, indeterminate = true)
            val res = withContext(Dispatchers.IO) {
                val base = File(context.cacheDir, "cloud_open").apply { mkdirs() }
                // Chaque ouverture reçoit son propre sous-dossier : purger le dossier
                // commun supprimerait le fichier qu'une autre application est en train
                // d'ouvrir. On ne nettoie que les copies vieilles de plus de 10 minutes.
                val cutoff = System.currentTimeMillis() - 10 * 60 * 1000
                base.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
                val dir = File(base, UUID.randomUUID().toString()).apply { mkdirs() }
                cloud.downloadRecursive(Uri.parse(root.treeUri), item, dir)
            }
            progress = null
            res.fold({ ShareUtil.open(context, it) }, { message = "Ouverture impossible : ${it.message}" })
        }
    }

    fun downloadCloudSelection(destination: String) {
        val root = cloudRootInUse ?: return
        val ids = cloudSelected
        if (ids.isEmpty()) return
        val items = cloudEntries.filter { ids.contains(it.documentId) }
        val dest = File(destination)
        runOperation("Téléchargement depuis le cloud") { report ->
            items.forEachIndexed { i, item ->
                report(item.name, i, items.size)
                cloud.downloadRecursive(Uri.parse(root.treeUri), item, dest) { n -> report(n, i, items.size) }
                    .onFailure { message = "Échec : ${item.name} (${it.message})" }
            }
            clearCloudSelection()
            message = "${items.size} élément(s) récupéré(s) dans ${dest.name}"
        }
    }

    /** Dossier cloud qui recevra un envoi, ou null si aucun espace n'est ouvert. */
    val cloudUploadTargetName: String?
        get() = if (cloudRootInUse == null) null
        else cloudStack.lastOrNull()?.second ?: cloudRootInUse?.label

    /** Envoie des fichiers ou dossiers locaux dans le dossier cloud affiché. */
    fun uploadToCloud(localPaths: List<String>) {
        val root = cloudRootInUse ?: run { message = "Aucun espace cloud ouvert"; return }
        // Repli sur la racine si la pile est vide, plutôt qu'un abandon silencieux.
        val parent = cloudStack.lastOrNull()?.first
            ?: runCatching { cloud.rootDocumentId(Uri.parse(root.treeUri)) }.getOrElse {
                message = "Espace cloud indisponible — reconnectez-le"
                return
            }
        if (localPaths.isEmpty()) return
        runOperation("Envoi vers le cloud") { report ->
            localPaths.forEachIndexed { i, p ->
                val f = File(p)
                report(f.name, i, localPaths.size)
                cloud.uploadRecursive(Uri.parse(root.treeUri), parent, f) { n -> report(n, i, localPaths.size) }
                    .onFailure { message = "Échec : ${f.name} (${it.message})" }
            }
            reloadCloud()
            message = "${localPaths.size} élément(s) envoyé(s)"
        }
    }

    /** Importe des fichiers choisis un à un dans le sélecteur système. */
    fun importCloudUris(uris: List<Uri>, destination: String) {
        if (uris.isEmpty()) return
        val dest = File(destination)
        runOperation("Import depuis le cloud") { report ->
            uris.forEachIndexed { i, uri ->
                report(cloud.displayName(uri) ?: "document", i, uris.size)
                cloud.importUri(uri, dest).onFailure { message = "Échec d'un import : ${it.message}" }
            }
            message = "${uris.size} fichier(s) importé(s) dans ${dest.name}"
        }
    }

    fun createCloudFolder(name: String) {
        val root = cloudRootInUse ?: return
        val parent = cloudStack.lastOrNull()?.first ?: return
        runOperation("Création du dossier") { _ ->
            cloud.createFolder(Uri.parse(root.treeUri), parent, name)
                .onFailure { message = "Création impossible : ${it.message}" }
            reloadCloud()
        }
    }

    fun renameCloud(documentId: String, newName: String) {
        val root = cloudRootInUse ?: return
        runOperation("Renommage") { _ ->
            cloud.rename(Uri.parse(root.treeUri), documentId, newName)
                .onSuccess { message = "Renommé en « $newName »" }
                .onFailure { message = "Renommage impossible : ${it.message}" }
            clearCloudSelection()
            reloadCloud()
        }
    }

    fun deleteCloudSelection() {
        val root = cloudRootInUse ?: return
        val ids = cloudSelected.toList()
        if (ids.isEmpty()) return
        runOperation("Suppression dans le cloud") { report ->
            ids.forEachIndexed { i, id ->
                report(cloudEntries.firstOrNull { it.documentId == id }?.name ?: "", i, ids.size)
                cloud.delete(Uri.parse(root.treeUri), id).onFailure { message = "Échec : ${it.message}" }
            }
            clearCloudSelection()
            reloadCloud()
        }
    }

    // ==================================================================
    // Catégories
    // ==================================================================

    private var categoryJob: Job? = null

    /**
     * Recense les images, vidéos et documents de tout l'appareil.
     * Opération longue (index multimédia + parcours des volumes) : toujours en arrière-plan.
     */
    fun scanCategories(force: Boolean = false) {
        if (!hasStorageAccess) return
        if (categoriesScanned && !force) return
        categoryJob?.cancel()
        categoriesLoading = true
        categoryJob = viewModelScope.launch {
            // La racine « / » est exclue : la parcourir entraînerait /proc, /sys et les
            // liens symboliques du système, pour des minutes d'analyse et rien d'utile.
            val roots = volumes.map { it.path }.filterNot { it == "/" }
                .ifEmpty { listOf("/storage/emulated/0") }
            val hidden = showHidden
            try {
                val result = withContext(Dispatchers.IO) {
                    val scope = this
                    MediaRepository.scan(ctx, roots, hidden) { scope.isActive }
                }
                categoryCache = result
                categoriesScanned = true
                categoriesLoading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                categoriesLoading = false
                message = "Analyse impossible : ${e.message ?: "erreur"}"
            }
        }
    }

    fun openCategory(cat: MediaCategory) {
        categoryInUse = cat
        categoryFolder = null
        screen = Screen.CATEGORY
        clearSelection()
        searchQuery = ""
        searchActive = false
        scanCategories()
    }

    // ==================================================================
    // Utilitaires internes
    // ==================================================================

    /** Empêche deux opérations lourdes de muter les mêmes fichiers simultanément. */
    var operationRunning by mutableStateOf(false)
        private set

    // ==================================================================
    // Partage réseau (serveur web pour le PC + transferts entre appareils)
    // ==================================================================

    /**
     * Le dépôt vit dans l'Application, pas ici : le serveur doit continuer à répondre
     * quand l'écran est fermé, et le service de premier plan doit désigner la même
     * instance. Le ViewModel ne fait qu'en refléter l'état pour Compose.
     */
    private val share: ShareRepository get() = (getApplication() as FmApplication).share

    var shareState by mutableStateOf(ShareState.STOPPED)
        private set
    var shareUrl by mutableStateOf<String?>(null)
        private set
    var shareCode by mutableStateOf<String?>(null)
        private set
    var shareError by mutableStateOf<String?>(null)
    var shareQr by mutableStateOf<android.graphics.Bitmap?>(null)
        private set
    var shareConfig by mutableStateOf(ShareConfig())
        private set
    var sharePeers by mutableStateOf<List<SharePeer>>(emptyList())
        private set
    var shareScanning by mutableStateOf(false)
        private set
    var shareTransfers by mutableStateOf<List<ShareTransfer>>(emptyList())
        private set
    var shareIncomingOffer by mutableStateOf<ShareOffer?>(null)
    var shareSending by mutableStateOf(false)
        private set

    /** Adresses des navigateurs vus dans les cinq dernières minutes. */
    private val webClients = java.util.concurrent.ConcurrentHashMap<String, Long>()
    var shareWebClients by mutableStateOf(0)
        private set

    private fun wireShare() {
        share.onState = { state, error ->
            viewModelScope.launch(Dispatchers.Main) {
                shareState = state
                shareError = error
                refreshShareAddress()
            }
        }
        share.onTransfer = {
            viewModelScope.launch(Dispatchers.Main) {
                shareTransfers = share.transferList().reversed()
            }
        }
        share.onOffer = { offer ->
            viewModelScope.launch(Dispatchers.Main) { shareIncomingOffer = offer }
        }
        share.onPeers = { list ->
            viewModelScope.launch(Dispatchers.Main) { sharePeers = list }
        }
        share.onWebClient = { address ->
            webClients[address] = System.currentTimeMillis()
            viewModelScope.launch(Dispatchers.Main) {
                val cutoff = System.currentTimeMillis() - 5 * 60_000
                webClients.entries.removeAll { it.value < cutoff }
                shareWebClients = webClients.size
            }
        }
        shareConfig = share.config
        shareState = share.state
        shareTransfers = share.transferList().reversed()
        refreshShareAddress()
    }

    /** Détache les rappels : voir [onCleared]. */
    private fun unwireShare() {
        share.onState = { _, _ -> }
        share.onTransfer = {}
        share.onOffer = {}
        share.onPeers = {}
        share.onWebClient = {}
    }

    fun refreshShare() {
        shareConfig = share.config
        shareState = share.state
        shareTransfers = share.transferList().reversed()
        // Une proposition arrivée pendant que l'application était en arrière-plan doit
        // réapparaître au retour : sans cela, l'expéditeur attendait quatre-vingt-dix
        // secondes pour rien.
        if (shareIncomingOffer == null) shareIncomingOffer = share.pendingOffers().firstOrNull()
        refreshShareAddress()
        if (shareState == ShareState.RUNNING) scanPeers()
    }

    private fun refreshShareAddress() {
        val url = if (share.state == ShareState.RUNNING) share.url() else null
        val changed = url != null && url != shareUrl
        shareUrl = url
        shareCode = share.accessCode
        if (url == null) { shareQr = null; return }
        // L'adresse change à chaque renouvellement de bail DHCP ou reconnexion Wi-Fi :
        // sans cela, la notification et le QR code affichaient indéfiniment l'ancienne.
        if (changed) ShareService.refresh(getApplication())
        // Le QR ne se recalcule que lorsque l'adresse bouge. `refreshShare()` est appelée
        // à chaque retour au premier plan : on reproduisait la même image à l'identique,
        // encodage et bitmap de 640×640 compris, plusieurs fois par session.
        if (!changed && shareQr != null) return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) { QrCode.bitmap(url) }
            shareQr = bmp
        }
    }

    /** Active ou coupe le partage. Le service de premier plan suit l'état. */
    fun toggleShare(context: Context) {
        if (share.state == ShareState.RUNNING) {
            share.stop()
            ShareService.stop(context)
            sharePeers = emptyList()
            webClients.clear()
            shareWebClients = 0
        } else {
            viewModelScope.launch {
                val error = withContext(Dispatchers.IO) { share.start() }
                if (error == null) {
                    ShareService.start(context)
                    scanPeers()
                } else {
                    message = error
                }
                refreshShareAddress()
            }
        }
    }

    /**
     * Efface le journal des transferts. Les transferts en cours restent affichés : le
     * dépôt refuse de les retirer, sans quoi leur progression disparaîtrait sans fin
     * visible.
     */
    fun clearShareTransfers() {
        val removed = share.clearTransfers()
        shareTransfers = share.transferList().reversed()
        message = when {
            removed == 0 -> "Aucun transfert terminé à effacer"
            shareTransfers.isEmpty() -> "Historique effacé"
            else -> "Historique effacé ; transfert en cours conservé"
        }
    }

    fun renewShareCode() {
        share.renewCode()
        shareCode = share.accessCode
        message = "Nouveau code généré ; les navigateurs connectés doivent le ressaisir"
    }

    /**
     * Un réglage modifié pendant que le partage tourne peut demander un redémarrage du
     * serveur — mais pas tous.
     *
     * Seuls le port, la racine partagée et l'exigence d'un code touchent à ce qui est
     * décidé à l'ouverture de la socket ; les autorisations, elles, sont relues à chaque
     * requête. Redémarrer pour elles **déconnectait le navigateur du PC** : l'arrêt vide
     * les sessions et le démarrage tire un nouveau code d'accès. Autoriser le renommage
     * en cours de partage — le cas normal, puisque le réglage est fermé par défaut —
     * obligeait donc à ressaisir un code sur le PC pour une case cochée sur le téléphone.
     */
    fun updateShareConfig(context: Context, config: ShareConfig) {
        val previous = shareConfig
        val needsRestart = config.port != previous.port ||
            config.rootPath != previous.rootPath ||
            config.requireCode != previous.requireCode
        val wasRunning = share.state == ShareState.RUNNING
        share.updateConfig(config)
        shareConfig = config
        if (wasRunning && needsRestart) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { share.stop() }
                val error = withContext(Dispatchers.IO) { share.start() }
                if (error != null) { message = error; ShareService.stop(context) }
                else ShareService.start(context)
                refreshShareAddress()
            }
        }
    }

    fun scanPeers() {
        if (shareScanning || share.state != ShareState.RUNNING) return
        shareScanning = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { share.scanPeers() }
            shareScanning = false
        }
    }

    /** Répond à une proposition d'envoi venue d'un autre appareil. */
    fun answerShareOffer(accept: Boolean) {
        val offer = shareIncomingOffer ?: return
        share.answerOffer(offer.id, accept)
        // Deux appareils peuvent proposer en même temps : on enchaîne au lieu de
        // perdre silencieusement la seconde demande.
        shareIncomingOffer = share.pendingOffers().firstOrNull()
        message = if (accept) "Réception en cours…" else "Transfert refusé"
    }

    /**
     * Appareil vers lequel l'utilisateur est en train de choisir des fichiers.
     * Le navigateur affiche alors un bandeau et un bouton de validation : c'est le
     * seul chemin qui évite de recopier les fichiers avant de les envoyer.
     */
    var shareSendTarget by mutableStateOf<SharePeer?>(null)
        private set

    fun beginSendTo(peer: SharePeer) {
        shareSendTarget = peer
        val start = android.os.Environment.getExternalStorageDirectory().absolutePath
        openPath(if (currentPath.isBlank()) start else currentPath)
        message = "Sélectionnez les fichiers à envoyer à ${peer.name}"
    }

    fun cancelSendTarget() {
        shareSendTarget = null
        clearSelection()
    }

    fun confirmSendToTarget() {
        val peer = shareSendTarget ?: return
        shareSendTarget = null
        sendSelectionToPeer(peer)
    }

    /** Envoie la sélection courante du navigateur vers un appareil Android. */
    fun sendSelectionToPeer(peer: SharePeer) {
        val files = selectedFiles().filter { it.isFile }
        if (files.isEmpty()) {
            message = "Sélectionnez d'abord des fichiers (les dossiers ne sont pas gérés)"
            return
        }
        sendFilesToPeer(peer, files)
    }

    fun sendFilesToPeer(peer: SharePeer, files: List<File>) {
        if (shareSending) { message = "Un envoi est déjà en cours"; return }
        shareSending = true
        message = "Proposition envoyée à ${peer.name}, en attente d'acceptation…"
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { share.sendFiles(peer, files) }
            shareSending = false
            message = error ?: "${files.size} fichier(s) envoyé(s) à ${peer.name}"
            clearSelection()
        }
    }

    /**
     * Ouvre le dossier où atterrissent les fichiers reçus.
     *
     * Le chemin est calculé par [ShareConfig.inboxDirectory] et nulle part ailleurs :
     * cet écran et le serveur avaient chacun leur copie du calcul, et rien ne garantissait
     * qu'ils désignent le même dossier.
     */
    fun openShareInbox() {
        val dir = shareConfig.inboxDirectory()
        if (!dir.exists()) dir.mkdirs()
        if (dir.isDirectory) openPath(dir.absolutePath)
        else message = "Dossier de réception introuvable"
    }

    private fun runOperation(
        title: String,
        block: suspend ((String, Int, Int) -> Unit) -> Unit
    ) {
        if (operationRunning) {
            message = "Une opération est déjà en cours"
            return
        }
        operationRunning = true
        viewModelScope.launch {
            progress = OpProgress(title, indeterminate = true)
            var lastTick = 0L
            try {
                withContext(Dispatchers.IO) {
                    block { name, done, total ->
                        // Rafraîchissement limité à ~20 images/s : sans cela, traiter des
                        // milliers de fichiers saturerait la composition.
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 50 || (total > 0 && done >= total)) {
                            lastTick = now
                            val snapshot = OpProgress(title, name, done, total, indeterminate = total <= 0)
                            viewModelScope.launch(Dispatchers.Main.immediate) { progress = snapshot }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "$title : ${e.message ?: "échec"}"
            } finally {
                operationRunning = false
                progress = null
                clearSelection()
                refreshCurrentView()
            }
        }
    }

    /**
     * Retire du cache les fichiers qui n'existent plus, après une suppression ou un
     * déplacement. Bien plus économe qu'une réanalyse complète de l'appareil, que
     * l'utilisateur peut toujours déclencher via « Réanalyser ».
     */
    private fun pruneCategoryCache() {
        val snapshot = categoryCache
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val cleaned = withContext(Dispatchers.IO) {
                snapshot.mapValues { (_, list) -> list.filter { File(it.path).exists() } }
            }
            categoryCache = cleaned
        }
    }

    /** Recharge la vue effectivement affichée, et pas seulement le navigateur local. */
    private fun refreshCurrentView() {
        when (screen) {
            Screen.CATEGORY -> pruneCategoryCache()
            Screen.CLOUD -> reloadCloud()
            Screen.NETWORK -> reloadSmb()
            Screen.FAVORITES -> refreshFavoriteStats()
            else -> reload()
        }
    }

    private fun resultMessage(res: FileOperations.OperationResult, verb: String) {
        message = if (res.hasError) {
            "${res.succeeded} $verb, ${res.failed.size} échec(s) : ${res.failed.first()}"
        } else {
            "${res.succeeded} élément(s) $verb"
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Les rappels du partage sont posés sur un dépôt porté par l'Application, qui
        // survit à ce ViewModel. Les cinq lambdas de `wireShare()` capturent `this` :
        // sans cette remise à zéro, le ViewModel détruit — et tout son état, caches de
        // catégories compris — restait vivant jusqu'à ce qu'un nouveau ViewModel
        // réécrive les rappels.
        unwireShare()
        // viewModelScope est déjà annulé : on ferme la session sur un fil dédié, car
        // la déconnexion SMB fait des entrées-sorties réseau interdites sur l'UI.
        val repo = smb
        Thread { runCatching { repo.disconnect() } }.start()
        vault.clearOpenCache()
    }

    /**
     * Branchement du partage réseau : **dernier bloc de la classe**, après toutes les
     * déclarations de propriétés.
     *
     * Kotlin exécute les initialiseurs de propriétés et les blocs `init` dans l'ordre du
     * source. Appelé depuis le bloc `init` du début, `wireShare()` affectait des
     * propriétés déléguées (`by mutableStateOf`) déclarées mille lignes plus bas : le
     * délégué valait encore null et l'affectation levait une NullPointerException dans le
     * constructeur du ViewModel. L'application se fermait instantanément au lancement,
     * sans jamais rien afficher.
     *
     * Il était jusqu'ici posé au milieu du fichier, où sa validité tenait à ce que
     * personne ne déclare de nouvel état en dessous — une convention que rien
     * n'appliquait. Ici, en toute fin de classe, la contrainte est respectée par
     * construction : il n'y a plus rien après.
     */
    init {
        wireShare()
    }
}
