package com.filemanager36.app.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.filemanager36.app.util.SafeName
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Accès aux lecteurs réseau LAN via SMB / CIFS (partages Windows, NAS, Samba…).
 *
 * La connexion se fait en deux temps :
 *  1. `connect()` ouvre une session authentifiée sur la machine (adresse IP + identifiants) ;
 *  2. `listShares()` demande au serveur la liste de ses dossiers partagés, puis
 *     `openShare()` en ouvre un pour la navigation.
 *
 * L'énumération passe par le service MS-SRVS (`NetrShareEnum`) sur le tube nommé `IPC$` :
 * c'est exactement ce que fait l'Explorateur Windows quand on saisit `\\192.168.1.20`.
 *
 * Toutes les méthodes sont bloquantes et doivent être appelées depuis Dispatchers.IO.
 */
class SmbRepository {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    var connectedServer: SmbServer? = null
        private set

    var openShareName: String? = null
        private set

    /** Session ouverte sur la machine, avec ou sans partage sélectionné. */
    val isSessionOpen: Boolean get() = session != null && connection?.isConnected == true

    /** Un partage est ouvert : la navigation dans les fichiers est possible. */
    val isConnected: Boolean get() = diskShare?.isConnected == true

    private fun buildConfig(): SmbConfig {
        val builder = SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(45, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
        // Le délai d'établissement TCP de smbj est de 5 s par défaut : trop court sur un
        // Wi-Fi encombré ou quand la machine sort de veille.
        runCatching {
            builder.withSocketFactory(
                com.hierynomus.protocol.commons.socket.ProxySocketFactory(CONNECT_TIMEOUT_MS)
            )
        }
        // BouncyCastle fournit AES-CMAC / AES-GCM nécessaires à SMB 3.x sur Android.
        runCatching {
            val provider = Class.forName("com.hierynomus.security.bc.BCSecurityProvider")
                .getDeclaredConstructor().newInstance() as com.hierynomus.security.SecurityProvider
            builder.withSecurityProvider(provider)
        }
        return builder.build()
    }

    /** Ouvre une session sur la machine : aucun partage n'est encore sélectionné. */
    fun connect(server: SmbServer): Result<Unit> = runCatching {
        disconnect()
        val c = SMBClient(buildConfig())
        val conn = c.connect(server.host, server.port)
        val auth = if (server.username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(
                server.username,
                server.password.toCharArray(),
                server.domain.ifBlank { null }
            )
        }
        val sess = conn.authenticate(auth)
        client = c; connection = conn; session = sess
        connectedServer = server
    }.onFailure { disconnect() }

    /**
     * Dossiers partagés publiés par la machine.
     *
     * Les partages d'impression et le tube IPC$ sont écartés ; les partages
     * administratifs (C$, ADMIN$…) sont conservés mais signalés comme tels.
     */
    fun listShares(): Result<List<SmbShare>> = runCatching {
        val sess = session ?: error("Session non ouverte")
        val transport = SMBTransportFactories.SRVSVC.getTransport(sess)
        val service = ServerService(transport)
        service.shares1
            .asSequence()
            .mapNotNull { info ->
                // Les chaînes NDR sont terminées par un caractère nul : le laisser
                // dans le nom ferait échouer l'ouverture en STATUS_BAD_NETWORK_NAME.
                val name = info.netName?.trim { it <= ' ' || it == '\u0000' }
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // Les 2 bits de poids fort portent les indicateurs (temporaire / spécial) ;
                // le type réel tient dans les bits de poids faible.
                val type = info.type and 0x0F
                if (type != STYPE_DISKTREE) return@mapNotNull null
                SmbShare(
                    name = name,
                    comment = info.remark.orEmpty(),
                    isAdmin = name.endsWith("$")
                )
            }
            .sortedWith(compareBy({ it.isAdmin }, { it.name.lowercase() }))
            .toList()
    }

    /** Ouvre un partage pour naviguer dedans. */
    fun openShare(name: String): Result<Unit> = runCatching {
        val sess = session ?: error("Session non ouverte")
        closeShare()
        val clean = name.trim { it <= ' ' || it == '\u0000' }.trim('\\', '/')
        val share = sess.connectShare(clean) as? DiskShare
            ?: error("« $clean » n'est pas un partage de fichiers")
        diskShare = share
        openShareName = name
    }.onFailure { closeShare() }

    fun closeShare() {
        runCatching { diskShare?.close() }
        diskShare = null
        openShareName = null
    }

    fun disconnect() {
        closeShare()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        session = null; connection = null; client = null
        connectedServer = null
    }

    private fun norm(path: String): String =
        path.trim().trim('/', '\\').replace('/', '\\')

    fun list(path: String, showHidden: Boolean, sortField: SortField, ascending: Boolean): List<RemoteItem> {
        val share = diskShare ?: error("Aucun partage ouvert")
        val items = share.list(norm(path))
            .asSequence()
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val attrs = info.fileAttributes
                RemoteItem(
                    name = info.fileName,
                    isDirectory = (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                    size = info.endOfFile,
                    lastModified = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(0L)
                )
            }
            .filter { showHidden || !it.name.startsWith(".") }
            .toMutableList()

        val cmp = when (sortField) {
            SortField.NAME -> compareBy<RemoteItem> { it.name.lowercase() }
            SortField.SIZE -> compareBy<RemoteItem> { it.size }
            SortField.DATE -> compareBy<RemoteItem> { it.lastModified }
        }
        val comparator = compareByDescending<RemoteItem> { it.isDirectory }
            .then(if (ascending) cmp else cmp.reversed())
        items.sortWith(comparator)
        return items
    }

    fun download(remotePath: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<File> =
        runCatching {
            val share = diskShare ?: error("Aucun partage ouvert")
            val remote = share.openFile(
                norm(remotePath),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            val totalSize = runCatching { remote.fileInformation.standardInformation.endOfFile }.getOrDefault(0L)
            target.parentFile?.mkdirs()
            remote.use { rf ->
                rf.inputStream.use { input ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(128 * 1024)
                        var copied = 0L
                        while (true) {
                            val r = input.read(buffer)
                            if (r <= 0) break
                            out.write(buffer, 0, r)
                            copied += r
                            onProgress(copied, totalSize)
                        }
                    }
                }
            }
            target
        }

    fun upload(local: File, remoteDir: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<Unit> =
        runCatching {
            val share = diskShare ?: error("Aucun partage ouvert")
            val remotePath = norm(if (remoteDir.isBlank()) local.name else "$remoteDir\\${local.name}")
            val remote = share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
            val totalSize = local.length()
            remote.use { rf ->
                rf.outputStream.use { out ->
                    FileInputStream(local).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var copied = 0L
                        while (true) {
                            val r = input.read(buffer)
                            if (r <= 0) break
                            out.write(buffer, 0, r)
                            copied += r
                            onProgress(copied, totalSize)
                        }
                    }
                }
            }
        }

    fun uploadRecursive(local: File, remoteDir: String, onProgress: (String) -> Unit = {}): Result<Unit> =
        runCatching {
            if (local.isDirectory) {
                val newDir = norm(if (remoteDir.isBlank()) local.name else "$remoteDir\\${local.name}")
                mkdir(newDir)
                local.listFiles()?.forEach { child ->
                    uploadRecursive(child, newDir, onProgress).getOrThrow()
                }
            } else {
                onProgress(local.name)
                upload(local, remoteDir).getOrThrow()
            }
        }

    /**
     * Récupère un fichier ou un dossier entier depuis le serveur.
     *
     * Les noms renvoyés par la machine distante sont **assainis avant d'être écrits sur
     * le disque** : `File(dossier, nom)` interprète les séparateurs, et un serveur
     * hostile — ou simplement une entrée corrompue — répondant `..` ou `../../fichier`
     * faisait écrire hors du dossier choisi par l'utilisateur. La profondeur est bornée
     * pour la même raison qu'ailleurs dans le projet : un partage qui se contient
     * lui-même produirait une descente infinie.
     */
    fun downloadRecursive(
        remotePath: String,
        isDirectory: Boolean,
        targetDir: File,
        // `depth` est placé avant `onProgress` pour que la lambda finale des appelants
        // continue de désigner le rappel de progression.
        depth: Int = 0,
        onProgress: (String) -> Unit = {}
    ): Result<Unit> = runCatching {
        val share = diskShare ?: error("Aucun partage ouvert")
        if (depth > MAX_REMOTE_DEPTH) error("Arborescence distante trop profonde")
        val leaf = SafeName.orDefault(norm(remotePath).substringAfterLast('\\'), "sans-nom")
        if (isDirectory) {
            val localDir = File(targetDir, leaf).apply { mkdirs() }
            share.list(norm(remotePath))
                .filter { it.fileName != "." && it.fileName != ".." }
                .forEach { info ->
                    val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    downloadRecursive(
                        "$remotePath\\${info.fileName}", isDir, localDir, depth + 1, onProgress
                    ).getOrThrow()
                }
        } else {
            onProgress(leaf)
            download(remotePath, File(targetDir, leaf)).getOrThrow()
        }
    }

    fun mkdir(remotePath: String): Result<Unit> = runCatching {
        val share = diskShare ?: error("Aucun partage ouvert")
        val p = norm(remotePath)
        if (!share.folderExists(p)) share.mkdir(p)
    }

    fun delete(remotePath: String, isDirectory: Boolean): Result<Unit> = runCatching {
        val share = diskShare ?: error("Aucun partage ouvert")
        val p = norm(remotePath)
        if (isDirectory) share.rmdir(p, true) else share.rm(p)
    }

    fun rename(remotePath: String, newName: String, isDirectory: Boolean): Result<Unit> = runCatching {
        val share = diskShare ?: error("Aucun partage ouvert")
        val p = norm(remotePath)
        val parent = p.substringBeforeLast('\\', "")
        val target = if (parent.isBlank()) newName else "$parent\\$newName"
        if (isDirectory) {
            share.openDirectory(
                p,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { it.rename(target) }
        } else {
            share.openFile(
                p,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { it.rename(target) }
        }
    }

    private companion object {
        /** MS-SRVS : partage de fichiers ordinaire (les autres sont imprimante, IPC…). */
        const val STYPE_DISKTREE = 0x00

        /** Établissement de la connexion TCP, en millisecondes. */
        const val CONNECT_TIMEOUT_MS = 12_000

        /** Descente maximale dans l'arborescence distante lors d'un téléchargement. */
        const val MAX_REMOTE_DEPTH = 24
    }
}
