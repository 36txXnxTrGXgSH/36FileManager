package com.filemanager36.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.filemanager36.app.data.FileItem
import com.filemanager36.app.ui.FmViewModel
import com.filemanager36.app.util.FileKind
import com.filemanager36.app.util.FileKinds
import com.filemanager36.app.util.Formatters
import com.filemanager36.app.util.PdfLock
import com.filemanager36.app.util.ShareUtil
import com.filemanager36.app.util.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Visionneuse plein écran : un balayage horizontal passe au fichier suivant du dossier.
 *
 * Chaque page s'adapte au type : image zoomable, aperçu de la première page pour un PDF.
 * Vidéo, musique et formats non affichables proposent l'ouverture par une application
 * dédiée du téléphone, sans jamais interrompre le balayage.
 */
@Composable
fun ViewerOverlay(vm: FmViewModel) {
    val context = LocalContext.current
    val items = vm.viewerItems
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = vm.viewerIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size }
    )
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }

    BackHandler { vm.closeViewer() }

    LaunchedEffect(pagerState) {
        // settledPage et non currentPage : ce dernier bascule dès la moitié du geste.
        snapshotFlow { pagerState.settledPage }.collect { page ->
            vm.updateViewerIndex(page)
            zoomed = false
        }
    }

    // currentPage et non viewerIndex : pendant l'animation de calage, les actions
    // (retouche, partage, favori) porteraient sur le fichier précédent.
    val current = items.getOrNull(pagerState.currentPage.coerceIn(0, items.lastIndex))

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            // Le balayage est désactivé quand l'image est agrandie, sinon le déplacement
            // dans l'image ferait changer de fichier.
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            when (FileKinds.of(item.name, false)) {
                FileKind.IMAGE -> ImagePage(
                    item = item,
                    onZoomChanged = { z -> if (page == pagerState.currentPage) zoomed = z },
                    onTap = { chromeVisible = !chromeVisible }
                )
                // Vidéo et musique : la lecture est confiée à une application dédiée
                // du téléphone. Un lecteur intégré s'était révélé instable, et VLC ou
                // MX Player font le travail bien mieux.
                FileKind.VIDEO -> MediaHandoffPage(
                    item = item,
                    subtitle = "Vidéo",
                    onTap = { chromeVisible = !chromeVisible }
                )
                FileKind.AUDIO -> MediaHandoffPage(
                    item = item,
                    subtitle = "Fichier audio",
                    onTap = { chromeVisible = !chromeVisible }
                )
                FileKind.PDF -> PdfPage(item = item, onTap = { chromeVisible = !chromeVisible })
                // Un APK ne s'affiche pas : on propose sa fiche d'installation.
                FileKind.APK -> ExternalFallback(
                    item = item,
                    title = "Application Android",
                    detail = "Ce fichier s'installe au lieu de s'ouvrir.",
                    onOpen = { ShareUtil.open(context, item.file) },
                    onTap = { chromeVisible = !chromeVisible },
                    primaryLabel = "Installer l'application",
                    onPrimary = { vm.apkTarget = item }
                )
                // Une archive atteinte au balayage propose la même extraction
                // intégrée que dans la liste : les deux chemins restent cohérents.
                FileKind.ARCHIVE -> if (FileKinds.isZip(item.name)) {
                    ExternalFallback(
                        item = item,
                        title = "Archive ZIP",
                        detail = "L'application peut extraire cette archive sur place.",
                        onOpen = { ShareUtil.open(context, item.file) },
                        onTap = { chromeVisible = !chromeVisible },
                        primaryLabel = "Extraire ici",
                        onPrimary = { vm.archiveTarget = item }
                    )
                } else {
                    UnsupportedPage(
                        item = item,
                        onTap = { chromeVisible = !chromeVisible },
                        onOpen = { ShareUtil.open(context, item.file) }
                    )
                }
                else -> if (FileKinds.isText(item.name)) {
                    TextPage(item = item, onTap = { chromeVisible = !chromeVisible })
                } else {
                    UnsupportedPage(
                        item = item,
                        onTap = { chromeVisible = !chromeVisible },
                        onOpen = { ShareUtil.open(context, item.file) }
                    )
                }
            }
        }

        if (chromeVisible && current != null) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.closeViewer() }) {
                        Icon(Icons.Filled.Close, "Fermer", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.name,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${pagerState.currentPage + 1} / ${items.size} · ${Formatters.size(current.size)}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (FileKinds.of(current.name, false) == FileKind.IMAGE) {
                        IconButton(onClick = { ShareUtil.edit(context, current.file) }) {
                            Icon(Icons.Filled.Edit, "Modifier l'image", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { vm.toggleFavorite(current) }) {
                        Icon(
                            Icons.Filled.Star, "Favori",
                            tint = if (vm.isFavorite(current.path)) Color(0xFFF5B301)
                            else Color.White.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = { ShareUtil.share(context, listOf(current.file)) }) {
                        Icon(Icons.Filled.Share, "Partager", tint = Color.White)
                    }
                    IconButton(onClick = { ShareUtil.open(context, current.file) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Ouvrir avec", tint = Color.White)
                    }
                    IconButton(onClick = { vm.showDetails(current.path) }) {
                        Icon(Icons.Filled.Info, "Informations", tint = Color.White)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Pages
// ----------------------------------------------------------------------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImagePage(
    item: FileItem,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(item.path) { mutableStateOf(false) }
    var scale by remember(item.path) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.path) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.path) { mutableFloatStateOf(0f) }
    var boxSize by remember(item.path) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    LaunchedEffect(item.path) {
        val loaded = withContext(Dispatchers.IO) { Thumbnails.loadFull(item.file) }
        if (loaded == null) failed = true else bitmap = loaded
    }

    LaunchedEffect(scale) { onZoomChanged(scale > 1.02f) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        if (scale > 1.02f) {
            // Bornage : sans lui, un glissement rapide expédiait l'image — et sa zone
            // tactile — hors de l'écran, sans aucun moyen de la ramener.
            val maxX = (boxSize.width * (scale - 1f)) / 2f
            val maxY = (boxSize.height * (scale - 1f)) / 2f
            offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
            offsetY = (offsetY + panChange.y).coerceIn(-maxY, maxY)
        } else {
            offsetX = 0f; offsetY = 0f
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    // canPan : sans ce filtre, transformable consomme TOUS les gestes de
                    // glissement, y compris à l'échelle 1 — le pager ne recevait jamais le
                    // balayage et il était impossible de passer à l'image suivante.
                    .transformable(
                        state = transformState,
                        canPan = { scale > 1.02f }
                    )
                    .pointerInput(item.path) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                if (scale > 1.02f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                } else scale = 2.5f
                            }
                        )
                    }
            )
            failed -> UnsupportedPage(
                item = item,
                onTap = onTap,
                onOpen = { ShareUtil.open(context, item.file) }
            )
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * Aperçu PDF : seule la première page est rendue.
 *
 * Un rendu complet était trop lent — pdfium met plusieurs centaines de millisecondes
 * par page complexe, et un document de cent pages donnait l'impression d'un chargement
 * sans fin. Pour la lecture réelle, on passe la main à une application dédiée.
 */
@Composable
private fun PdfPage(item: FileItem, onTap: () -> Unit) {
    val context = LocalContext.current
    var cover by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var pageCount by remember(item.path) { mutableIntStateOf(0) }
    var failed by remember(item.path) { mutableStateOf(false) }
    var loading by remember(item.path) { mutableStateOf(true) }

    LaunchedEffect(item.path) {
        val result = withContext(Dispatchers.IO) { renderFirstPage(item.file) }
        loading = false
        if (result == null) failed = true else {
            cover = result.first
            pageCount = result.second
        }
    }

    val bmp = cover
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.path) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(color = Color.White)
            failed || bmp == null -> ExternalFallback(
                item = item,
                title = "Aperçu impossible",
                detail = "Ce PDF ne peut pas être prévisualisé ici.",
                onOpen = { ShareUtil.open(context, item.file) }
            )
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    if (pageCount > 1) "Première page sur $pageCount" else "Document d'une page",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { ShareUtil.open(context, item.file) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ouvrir le document en entier")
                }
            }
        }
    }
}

/**
 * Vidéo ou musique : aperçu, puis remise à une application dédiée du téléphone.
 *
 * Un lecteur intégré (ExoPlayer) avait été tenté puis retiré : il faisait planter
 * l'application, et VLC ou MX Player offrent de toute façon bien plus de formats,
 * de pistes audio et de sous-titres qu'un lecteur minimal.
 */
@Composable
private fun MediaHandoffPage(item: FileItem, subtitle: String, onTap: () -> Unit) {
    val context = LocalContext.current
    val kind = FileKinds.of(item.name, false)
    var preview by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var apps by remember(item.path) { mutableStateOf<List<ShareUtil.OpenerApp>>(emptyList()) }

    // Deux chargements indépendants : enchaînés, l'extraction d'une image sur une
    // grosse vidéo retardait de plusieurs secondes l'affichage des applications.
    LaunchedEffect(item.path) {
        launch {
            preview = withContext(Dispatchers.IO) { Thumbnails.load(context, item.file, kind) }
        }
        launch {
            apps = withContext(Dispatchers.IO) { ShareUtil.openers(context, item.file) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            // Défilement indispensable : en paysage, la rangée d'applications et les
            // boutons sortaient de l'écran sans aucun moyen de les atteindre.
            .pointerInput(item.path) { detectTapGestures { onTap() } }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(170.dp)
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = preview
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                )
            } else {
                Icon(
                    FileKinds.icon(kind), null,
                    tint = FileKinds.color(kind),
                    modifier = Modifier.size(80.dp)
                )
            }
            if (kind == FileKind.VIDEO) {
                Icon(
                    Icons.Filled.PlayCircle, null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            item.name,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "$subtitle · ${Formatters.size(item.size)}",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium
        )

        Spacer(Modifier.height(22.dp))

        if (apps.isEmpty()) {
            OutlinedButton(onClick = { ShareUtil.open(context, item.file) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ouvrir avec une application")
            }
        } else {
            Text(
                "Lire avec",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(10.dp))
            // Applications proposées directement : un appui suffit, sans passer par
            // le sélecteur système.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(76.dp)
                            .clickable { ShareUtil.openWith(context, item.file, app) }
                    ) {
                        val icon = app.icon
                        if (icon != null) {
                            Image(
                                bitmap = icon.asImageBitmap(),
                                contentDescription = app.label,
                                modifier = Modifier.size(46.dp)
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew, null,
                                tint = Color.White,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            app.label,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = { ShareUtil.open(context, item.file) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Autre application…")
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Balayez pour passer au fichier suivant",
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Affiche un fichier texte (txt, log, json, sous-titres, code…).
 *
 * De nombreux téléphones n'ont aucune application déclarant text/plain : le sélecteur
 * système restait vide et le fichier paraissait impossible à ouvrir.
 *
 * Deux garde-fous : la lecture est bornée à 256 Kio, et le rendu se fait ligne par
 * ligne dans une LazyColumn. Un seul composant Text contenant un journal entier
 * bloquerait la mise en page assez longtemps pour déclencher un « l'application ne
 * répond pas ».
 */
@Composable
private fun TextPage(item: FileItem, onTap: () -> Unit) {
    val context = LocalContext.current
    var lines by remember(item.path) { mutableStateOf<List<String>?>(null) }
    var truncated by remember(item.path) { mutableStateOf(false) }
    var failed by remember(item.path) { mutableStateOf(false) }

    LaunchedEffect(item.path) {
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val maxBytes = 256 * 1024
                val maxLines = 20_000
                val f = item.file
                val size = f.length()
                val buf = ByteArray(minOf(size, maxBytes.toLong()).toInt().coerceAtLeast(1))
                val read = f.inputStream().use { input ->
                    var got = 0
                    while (got < buf.size) {
                        val n = input.read(buf, got, buf.size - got)
                        if (n <= 0) break
                        got += n
                    }
                    got
                }
                // Une coupure au milieu d'un caractère multi-octets ne produit qu'un
                // caractère de remplacement en fin de texte, sans exception.
                val text = String(buf, 0, read, Charsets.UTF_8)
                // Découpage des lignes trop longues : un JSON minifié ou un .min.js
                // tient sur une seule ligne de 260 000 caractères, que Compose met
                // plusieurs secondes à mettre en page — et dont la hauteur dépasse la
                // limite de Constraints, ce qui lève une exception sur bien des
                // appareils. On la débite en tronçons affichables.
                val maxCol = 400
                val out = ArrayList<String>()
                var cutLines = false
                for (raw in text.split('\n')) {
                    if (out.size >= maxLines) { cutLines = true; break }
                    val line = raw.trimEnd('\r')
                    if (line.length <= maxCol) {
                        out.add(line)
                    } else {
                        var i = 0
                        while (i < line.length && out.size < maxLines) {
                            out.add(line.substring(i, minOf(i + maxCol, line.length)))
                            i += maxCol
                        }
                        if (i < line.length) cutLines = true
                    }
                }
                out to (size > maxBytes || cutLines)
            }
        }
        res.fold({ lines = it.first; truncated = it.second }, { failed = true })
    }

    val loaded = lines
    when {
        failed -> UnsupportedPage(
            item = item,
            onTap = onTap,
            onOpen = { ShareUtil.open(context, item.file) }
        )
        loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        else -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101418))
                .windowInsetsPadding(WindowInsets.systemBars)
                .pointerInput(item.path) { detectTapGestures { onTap() } },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 64.dp)
        ) {
            if (loaded.isEmpty() || (loaded.size == 1 && loaded[0].isEmpty())) {
                item {
                    Text(
                        "(fichier vide)",
                        color = Color(0xFF8A94A0),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(loaded.size) { i ->
                    Text(
                        loaded[i].ifEmpty { " " },
                        color = Color(0xFFE6E9EC),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (truncated) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "… fichier tronqué pour l'affichage. Ouvrez-le dans une " +
                            "application dédiée pour le lire en entier.",
                        color = Color(0xFFE0A030),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/** Écran de repli commun : explication puis ouverture par une application dédiée. */
@Composable
private fun ExternalFallback(
    item: FileItem,
    title: String,
    detail: String,
    onOpen: () -> Unit,
    onTap: (() -> Unit)? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null
) {
    val kind = FileKinds.of(item.name, false)
    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (onTap != null)
                    Modifier.pointerInput(item.path) { detectTapGestures { onTap() } }
                else Modifier
            )
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            FileKinds.icon(kind), null,
            tint = FileKinds.color(kind),
            modifier = Modifier.size(76.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            item.name,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        if (primaryLabel != null && onPrimary != null) {
            Button(onClick = onPrimary) { Text(primaryLabel) }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = onOpen) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ouvrir avec une autre application")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Le balayage continue de fonctionner",
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun UnsupportedPage(item: FileItem, onTap: () -> Unit, onOpen: () -> Unit) {
    ExternalFallback(
        item = item,
        title = "Aperçu impossible",
        detail = "Ce format ne peut pas être affiché ici.",
        onOpen = onOpen,
        onTap = onTap
    )
}

/**
 * Rend la première page d'un PDF et renvoie aussi le nombre total de pages.
 * Bloquant : à appeler depuis Dispatchers.IO.
 *
 * Sérialisé par [PdfLock] : avant Android 9, pdfium n'est pas protégé côté système
 * et deux rendus simultanés font planter le processus natif.
 */
private fun renderFirstPage(file: File, targetWidth: Int = 1080): Pair<Bitmap, Int>? =
    synchronized(PdfLock.lock) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            if (count == 0) return null
            renderer.openPage(0).use { page ->
                val ratio = page.height.toFloat() / page.width.toFloat()
                val h = (targetWidth * ratio).toInt().coerceIn(1, 4096)
                // ARGB_8888 obligatoire : PdfRenderer.render lève « Unsupported pixel
                // format » pour toute autre configuration, ce qui faisait échouer
                // silencieusement l'aperçu de tous les PDF.
                val bmp = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AndroidColor.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp to count
            }
        } catch (e: Throwable) {
            // OutOfMemoryError est une Error, pas une Exception : sans ce filet elle
            // remonterait au gestionnaire par défaut et tuerait l'application.
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }


