package com.filemanager36.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.filemanager36.app.util.SafeName
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Accès aux espaces cloud (Google Drive, OneDrive, Dropbox, Nextcloud…) par
 * l'intermédiaire du système de documents d'Android (Storage Access Framework).
 *
 * L'utilisateur connecte une fois un dossier ; l'autorisation est conservée de façon
 * permanente et le dossier se parcourt ensuite depuis l'application, sans compte
 * développeur ni clé d'API.
 *
 * Les requêtes passent directement par `DocumentsContract` plutôt que par `DocumentFile` :
 * une seule requête ramène tous les enfants avec leurs métadonnées, là où `DocumentFile`
 * en déclenche une par fichier et par attribut.
 */
class CloudRepository(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    companion object {
        const val DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR

        /** Descente maximale dans une arborescence cloud lors d'une copie. */
        private const val MAX_DEPTH = 24

        /** Intent de sélection d'un dossier cloud à connecter. */
        fun openTreeIntent(): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }

        /** Intent de sélection de fichiers isolés (utile quand Drive refuse le dossier). */
        fun openDocumentsIntent(): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }

    /** Conserve l'autorisation au-delà du redémarrage de l'application. */
    fun persist(treeUri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        true
    }.getOrDefault(false)

    fun release(treeUri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** Nom lisible proposé pour un dossier fraîchement connecté. */
    fun guessLabel(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val leaf = docId?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val authority = treeUri.authority.orEmpty()
        val provider = when {
            authority.contains("google", true) && authority.contains("apps", true) -> "Google Drive"
            authority.contains("skydrive", true) || authority.contains("onedrive", true) ||
                authority.contains("microsoft", true) -> "OneDrive"
            authority.contains("dropbox", true) -> "Dropbox"
            authority.contains("nextcloud", true) || authority.contains("owncloud", true) -> "Nextcloud"
            authority.contains("mega", true) -> "MEGA"
            authority.contains("externalstorage", true) -> "Stockage local"
            else -> "Cloud"
        }
        return if (leaf.isNullOrBlank()) provider else "$provider — $leaf"
    }

    fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    private fun childrenUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)

    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /** Liste triée d'un dossier cloud. */
    fun list(
        treeUri: Uri,
        documentId: String,
        showHidden: Boolean,
        sortField: SortField,
        ascending: Boolean
    ): List<CloudItem> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val items = mutableListOf<CloudItem>()
        // Un curseur nul signifie que le fournisseur a disparu ou que l'autorisation a été
        // révoquée : il ne faut surtout pas le confondre avec un dossier vide.
        val cursor = resolver.query(childrenUri(treeUri, documentId), projection, null, null, null)
            ?: error("Espace cloud indisponible — reconnectez-le depuis l'accueil")
        cursor.use { c ->
            // Les fournisseurs ne sont pas tenus de respecter l'ordre de la projection :
            // on résout les colonnes par leur nom, une seule fois.
            val iId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val iSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val iDate = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (iId < 0 || iName < 0) error("Fournisseur cloud incompatible")
            while (c.moveToNext()) {
                val id = c.getString(iId) ?: continue
                val name = c.getString(iName) ?: continue
                if (!showHidden && name.startsWith(".")) continue
                val mime = if (iMime >= 0) c.getString(iMime).orEmpty() else ""
                items += CloudItem(
                    documentId = id,
                    name = name,
                    mimeType = mime,
                    size = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else 0L,
                    lastModified = if (iDate >= 0 && !c.isNull(iDate)) c.getLong(iDate) else 0L,
                    isDirectory = mime == DIR_MIME
                )
            }
        }
        val cmp = when (sortField) {
            SortField.NAME -> compareBy<CloudItem> { it.name.lowercase() }
            SortField.SIZE -> compareBy<CloudItem> { it.size }
            SortField.DATE -> compareBy<CloudItem> { it.lastModified }
        }
        items.sortWith(
            compareByDescending<CloudItem> { it.isDirectory }
                .then(if (ascending) cmp else cmp.reversed())
        )
        return items
    }

    // ------------------------------------------------------------------
    // Transferts
    // ------------------------------------------------------------------

    /**
     * Copie un élément cloud (fichier ou dossier entier) vers le stockage local.
     *
     * Le nom d'affichage vient du fournisseur, pas de l'application : il est assaini
     * avant de servir de nom de fichier, faute de quoi un `..` ou un séparateur ferait
     * écrire hors du dossier de destination. La profondeur est bornée : un fournisseur
     * exposant un dossier qui se contient lui-même produirait une descente infinie.
     */
    fun downloadRecursive(
        treeUri: Uri,
        item: CloudItem,
        destDir: File,
        // `depth` est placé avant `onProgress` pour que la lambda finale des appelants
        // continue de désigner le rappel de progression.
        depth: Int = 0,
        onProgress: (String) -> Unit = {}
    ): Result<File> = runCatching {
        if (!destDir.exists()) destDir.mkdirs()
        if (depth > MAX_DEPTH) error("Arborescence cloud trop profonde")
        val safe = SafeName.orDefault(item.name, "document")
        if (item.isDirectory) {
            val sub = FileOperations.uniqueTarget(destDir, safe).apply { mkdirs() }
            list(treeUri, item.documentId, showHidden = true, sortField = SortField.NAME, ascending = true)
                .forEach { child ->
                    downloadRecursive(treeUri, child, sub, depth + 1, onProgress).getOrThrow()
                }
            sub
        } else {
            onProgress(safe)
            val target = FileOperations.uniqueTarget(destDir, safe)
            val uri = documentUri(treeUri, item.documentId)
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Lecture impossible : ${item.name}" }
                FileOutputStream(target).use { out -> input.copyTo(out, 128 * 1024) }
            }
            target
        }
    }

    /** Copie un fichier ouvert par le sélecteur système vers le stockage local. */
    fun importUri(uri: Uri, destDir: File): Result<File> = runCatching {
        if (!destDir.exists()) destDir.mkdirs()
        val name = SafeName.orDefault(
            displayName(uri).orEmpty(), "document_${System.currentTimeMillis()}"
        )
        val target = FileOperations.uniqueTarget(destDir, name)
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Lecture impossible" }
            FileOutputStream(target).use { out -> input.copyTo(out, 128 * 1024) }
        }
        target
    }

    fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Envoie un fichier ou un dossier local dans le dossier cloud courant. */
    fun uploadRecursive(
        treeUri: Uri,
        parentDocumentId: String,
        local: File,
        onProgress: (String) -> Unit = {}
    ): Result<Unit> = runCatching {
        if (local.isDirectory) {
            val childId = createFolder(treeUri, parentDocumentId, local.name).getOrThrow()
            local.listFiles()?.forEach { uploadRecursive(treeUri, childId, it, onProgress).getOrThrow() }
        } else {
            onProgress(local.name)
            val parentUri = documentUri(treeUri, parentDocumentId)
            val mime = FileOperations.mimeOf(local)
            val created = DocumentsContract.createDocument(resolver, parentUri, mime, local.name)
                ?: error("Création refusée : ${local.name}")
            resolver.openOutputStream(created).use { out ->
                requireNotNull(out) { "Écriture impossible : ${local.name}" }
                FileInputStream(local).use { input -> input.copyTo(out, 128 * 1024) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Modifications
    // ------------------------------------------------------------------

    fun createFolder(treeUri: Uri, parentDocumentId: String, name: String): Result<String> = runCatching {
        val parentUri = documentUri(treeUri, parentDocumentId)
        val created = DocumentsContract.createDocument(resolver, parentUri, DIR_MIME, name)
            ?: error("Création du dossier refusée")
        DocumentsContract.getDocumentId(created)
    }

    fun rename(treeUri: Uri, documentId: String, newName: String): Result<Unit> = runCatching {
        val uri = documentUri(treeUri, documentId)
        DocumentsContract.renameDocument(resolver, uri, newName)
            ?: error("Renommage refusé par le fournisseur")
        Unit
    }

    fun delete(treeUri: Uri, documentId: String): Result<Unit> = runCatching {
        val uri = documentUri(treeUri, documentId)
        if (!DocumentsContract.deleteDocument(resolver, uri)) error("Suppression refusée")
    }
}
