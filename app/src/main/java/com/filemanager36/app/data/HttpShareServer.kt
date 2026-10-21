package com.filemanager36.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.filemanager36.app.util.FileKind
import com.filemanager36.app.util.FileKinds
import com.filemanager36.app.util.NetworkUtil
import com.filemanager36.app.util.SafeName
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Serveur HTTP embarqué : le téléphone devient une page web que l'on ouvre depuis
 * Edge ou Firefox, sur le même réseau, à l'adresse `http://<ip>:<port>`.
 *
 * Écrit à la main sur `ServerSocket` plutôt qu'avec une bibliothèque : le besoin tient
 * en une dizaine de routes, et les bibliothèques légères du marché mettent en mémoire
 * tampon les corps de requête — ce qui interdit d'envoyer un fichier de 2 Go depuis
 * le PC. Ici tout est diffusé en flux, dans les deux sens.
 *
 * Chaque réponse ferme la connexion (`Connection: close`). Sur un réseau local, le
 * coût est négligeable, et cela supprime toute une classe de bogues de synchronisation
 * entre requêtes successives sur une même socket.
 */
class HttpShareServer(
    private val context: Context,
    private val hooks: Hooks
) {

    /** Tout ce que le serveur doit demander au reste de l'application. */
    interface Hooks {
        fun config(): ShareConfig
        /** Code à 6 chiffres exigé du navigateur, ou null si le code est désactivé. */
        fun accessCode(): String?
        /** Un navigateur vient d'agir : sert à afficher « 1 ordinateur connecté ». */
        fun onWebActivity(address: String)
        /**
         * Un appareil Android propose un envoi ; l'utilisateur doit répondre.
         * @return false si la file d'attente est pleine.
         */
        fun onOfferReceived(offer: ShareOffer): Boolean
        fun offerById(id: String): ShareOffer?
        /** La proposition a été entièrement honorée : elle doit cesser d'exister. */
        fun closeOffer(id: String)
        /** Début d'une réception ; renvoie l'identifiant de transfert à suivre. */
        fun onIncomingStart(name: String, total: Long, peerName: String): String
        fun onIncomingProgress(id: String, bytes: Long)
        fun onIncomingDone(id: String, saved: File?, error: String?)
    }

    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var pool: ExecutorService? = null

    /** Connexions ouvertes, pour pouvoir vraiment les couper à l'arrêt. */
    private val liveSockets = java.util.Collections.synchronizedSet(HashSet<Socket>())

    /**
     * Jetons de session délivrés aux navigateurs authentifiés, avec leur date
     * d'expiration.
     *
     * Un simple `Set` grandissait sans borne, et un jeton restait valable tant que le
     * partage tournait — alors que le cookie annonçait vingt-quatre heures. Le serveur
     * respecte maintenant sa propre promesse.
     */
    private val tokens = HashMap<String, Long>()

    /**
     * Requêtes en cours de traitement, et le verrou de réveil qui les accompagne.
     *
     * Compté plutôt que booléen : douze fils servent en parallèle, et le premier à
     * terminer relâcherait le verrou sous les autres.
     */
    private val awakeCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * Volumes partagés, avec l'instant du calcul. Voir [rootDirs] : le balayage des
     * points de montage est trop coûteux pour être refait à chaque requête.
     */
    @Volatile private var rootsCache: Pair<Long, List<File>>? = null

    /** Échecs de code par adresse, pour bloquer la recherche exhaustive. */
    private val loginFails = HashMap<String, LoginAttempts>()

    private class LoginAttempts(var count: Int = 0, var blockedUntil: Long = 0)

    val isRunning: Boolean get() = running.get()

    // ------------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------------

    /**
     * @param bindAddress adresse locale sur laquelle écouter. **Ne jamais passer null
     * en production** : une liaison générique (0.0.0.0 / ::) ouvrirait aussi le
     * serveur sur l'interface de données mobiles, où beaucoup d'opérateurs attribuent
     * une adresse IPv6 publique — le téléphone serait alors joignable depuis Internet.
     * @return le port réellement ouvert, ou une erreur explicite.
     */
    fun start(port: Int, bindAddress: String?): Result<Int> = runCatching {
        if (running.get()) return Result.success(serverSocket?.localPort ?: port)
        // Le dossier partagé a pu changer dans les réglages : ne pas repartir sur
        // l'ancien jeu de volumes.
        rootsCache = null
        val socket = ServerSocket()
        socket.reuseAddress = true
        val endpoint = if (bindAddress != null)
            java.net.InetSocketAddress(java.net.InetAddress.getByName(bindAddress), port)
        else java.net.InetSocketAddress(port)
        socket.bind(endpoint, 48)
        serverSocket = socket
        running.set(true)
        // File d'attente bornée : sans borne, un balayage de ports accumulerait des
        // milliers de sockets acceptées en attente et épuiserait les descripteurs.
        // corePoolSize = MAX_WORKERS : un ThreadPoolExecutor ne dépasse le noyau
        // qu'une fois la file pleine. Avec un noyau de 4 et une file de 16, le
        // cinquième fil n'apparaissait jamais — un navigateur n'ouvre que six
        // connexions — et une vidéo en lecture bloquait tout le reste de la page.
        pool = java.util.concurrent.ThreadPoolExecutor(
            MAX_WORKERS, MAX_WORKERS, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue(16),
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
        ).apply { allowCoreThreadTimeOut(true) }

        acceptThread = Thread({
            while (true) {
                // On compare l'identité de la socket : après un arrêt suivi d'un
                // redémarrage, l'ancien fil doit mourir au lieu de tourner à vide sur
                // une socket fermée — ce qui saturait un cœur du processeur.
                if (!running.get() || serverSocket !== socket || socket.isClosed) break
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (socket.isClosed || !running.get() || serverSocket !== socket) break
                    // Panne passagère : une courte pause évite la boucle folle.
                    try { Thread.sleep(60) } catch (i: InterruptedException) { break }
                    continue
                }
                accept(client)
            }
        }, "36fm-http-accept").apply { isDaemon = true; start() }

        socket.localPort
    }.recoverCatching { e ->
        stop()
        throw when (e) {
            is java.net.BindException -> IllegalStateException(
                "Le port $port est déjà utilisé sur ce téléphone. Choisissez-en un autre."
            )
            else -> e
        }
    }

    /** @return false si la connexion a été refusée et fermée. */
    private fun accept(client: Socket): Boolean {
        // Nombre de connexions simultanées borné : douze fils bloqués en lecture
        // suffiraient sinon à rendre le serveur muet (attaque « Slowloris »).
        if (!isLocalPeer(client)) {
            runCatching { client.close() }
            return false
        }
        // Plafond global *et* par adresse : sans le second, une seule machine pouvait
        // occuper les vingt-quatre places et rendre le partage muet pour les autres.
        val peer = client.inetAddress?.hostAddress.orEmpty()
        val fromPeer = synchronized(liveSockets) {
            liveSockets.count { it.inetAddress?.hostAddress == peer }
        }
        if (liveSockets.size >= MAX_CONNECTIONS || fromPeer >= MAX_PER_PEER) {
            runCatching { client.close() }
            return false
        }
        val executor = pool
        if (executor == null || executor.isShutdown) {
            runCatching { client.close() }
            return false
        }
        liveSockets.add(client)
        return try {
            executor.execute {
                try { handle(client) } catch (e: Throwable) { /* jamais fatal */ }
                finally {
                    liveSockets.remove(client)
                    runCatching { client.close() }
                }
            }
            true
        } catch (e: Exception) {
            liveSockets.remove(client)
            runCatching { client.close() }
            false
        }
    }

    /**
     * Refuse tout ce qui ne vient pas du réseau local. Deuxième barrière après la
     * liaison ciblée : même si l'appareil se retrouve joignable autrement, une
     * adresse publique n'obtient rien.
     */
    private fun isLocalPeer(client: Socket): Boolean {
        val addr = client.inetAddress ?: return false
        return addr.isLoopbackAddress || addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress || addr.isAnyLocalAddress
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Fermeture explicite des connexions : `shutdownNow` interrompt les fils, mais
        // une lecture bloquée sur une socket n'est pas interruptible. Sans cela, un
        // téléchargement continuait après l'arrêt annoncé du partage.
        synchronized(liveSockets) {
            liveSockets.toList().forEach { runCatching { it.close() } }
            liveSockets.clear()
        }
        runCatching { pool?.shutdownNow() }
        pool = null
        // On attend brièvement la fin du fil d'acceptation : sans cette jointure, un
        // redémarrage immédiat faisait cohabiter deux boucles sur le même port pendant
        // quelques millisecondes.
        val thread = acceptThread
        acceptThread = null
        runCatching { thread?.join(500) }
        synchronized(tokens) { tokens.clear() }
        synchronized(loginFails) { loginFails.clear() }
    }

    fun revokeSessions() { synchronized(tokens) { tokens.clear() } }

    /** Le jeton présenté est-il valide et non expiré ? */
    private fun tokenValid(token: String): Boolean {
        if (token.isEmpty()) return false
        return synchronized(tokens) {
            val now = System.currentTimeMillis()
            tokens.entries.removeAll { it.value < now }
            // Comparaison à temps constant : `HashMap.containsKey` s'arrête au premier
            // octet différent. Sur un réseau local le signal est noyé dans le bruit,
            // mais la parade ne coûte rien.
            var found = false
            for (known in tokens.keys) {
                if (constantTimeEquals(known, token)) found = true
            }
            found
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8)
        )

    // ------------------------------------------------------------------
    // Traitement d'une requête
    // ------------------------------------------------------------------

    private fun handle(client: Socket) {
        // 20 s pour les en-têtes : un client qui n'envoie rien immobilise un fil, et il
        // n'en existe qu'un petit nombre. Le corps, lui, obtient bien davantage — voir
        // BODY_TIMEOUT_MS juste avant l'appel à route().
        client.soTimeout = HEADER_TIMEOUT_MS
        client.tcpNoDelay = true
        val input = BufferedInputStream(client.getInputStream(), 32 * 1024)
        val output = BufferedOutputStream(client.getOutputStream(), 64 * 1024)

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) { sendText(output, 400, "Requête invalide"); return }
        val method = parts[0].uppercase()
        val rawTarget = parts[1]

        val headers = mutableMapOf<String, String>()
        var headerBytes = requestLine.length
        var headerLines = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headerBytes += line.length
            headerLines++
            // Budget global : l'ancienne borne ne comptait que les lignes contenant un
            // « : », si bien qu'un flot de lignes sans deux-points bloquait un fil sans
            // aucune limite.
            if (headerBytes > 32 * 1024 || headerLines > 80) {
                return sendText(output, 431, "En-têtes trop volumineux")
            }
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }

        val path = rawTarget.substringBefore('?')
        val query = parseQuery(rawTarget.substringAfter('?', ""))
        val remote = client.inetAddress?.hostAddress ?: "?"

        // Le corps d'une requête et la réponse voyagent bien plus lentement que des
        // en-têtes : écran éteint, le Wi-Fi passe en économie d'énergie et des pauses de
        // plusieurs dizaines de secondes apparaissent au milieu d'un envoi. Avec 20 s,
        // la lecture expirait et le navigateur affichait « Connexion perdue » sur un
        // transfert qui se portait bien.
        runCatching { client.soTimeout = BODY_TIMEOUT_MS }

        // Et le délai ne suffit pas : écran éteint, Android suspend le processeur entre
        // deux paquets, et les fils du serveur ne tournent plus. Le service de premier
        // plan empêche la mise en veille de l'application, pas celle du processeur. Ce
        // verrou n'est tenu que pendant qu'une requête est servie — jamais en
        // permanence, ce serait la batterie pour rien.
        acquireAwake()
        try {
            route(method, path, query, headers, input, output, remote)
        } catch (e: Throwable) {
            runCatching { sendText(output, 500, "Erreur interne : ${e.message}") }
        } finally {
            releaseAwake()
        }
        runCatching { output.flush() }
    }

    private fun route(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        remote: String
    ) {
        // Barrière contre la « redécouverte DNS » : un site web peut faire pointer un
        // nom de domaine vers l'adresse locale du téléphone et obtenir alors une
        // origine identique, ce qui contournerait toute vérification d'origine. En
        // exigeant que l'en-tête Host soit une adresse IP littérale — ce qu'envoie
        // toujours un navigateur à qui l'on a tapé http://192.168.x.y:8036 — cette
        // attaque devient impossible.
        val host = headers["host"].orEmpty().substringBeforeLast(':')
            .removePrefix("[").removeSuffix("]")
        if (!isIpLiteral(host)) {
            return sendText(
                output, 403,
                "Accès par nom de domaine refusé : utilisez directement l'adresse IP."
            )
        }

        // Barrière contre la falsification de requête entre sites (CSRF). Exiger une
        // adresse IP littérale dans `Host` bloque la redécouverte DNS, mais pas une page
        // hostile qui vise directement http://192.168.x.y:8036 : le navigateur de la
        // victime envoie alors le bon `Host`, et le cookie `SameSite=Lax` accompagne
        // encore certaines requêtes. Sans ce contrôle, n'importe quel site consulté sur
        // le même réseau pouvait déposer des fichiers sur le téléphone.
        if (!originAllowed(headers, host)) {
            return sendJson(
                output, 403,
                """{"error":"Requête refusée : elle ne vient pas de la page de partage."}"""
            )
        }

        val code = hooks.accessCode()

        // HEAD sur un fichier : en-têtes seuls, pour les gestionnaires de
        // téléchargement qui sondent la taille avant de récupérer le contenu.
        if (method == "HEAD") {
            if (path == "/api/file" && (code == null || tokenValid(cookieToken(headers)))) {
                hooks.onWebActivity(remote)
                return downloadHead(query, output)
            }
            return sendText(output, 405, "Méthode non gérée", extra = listOf("Allow: GET, POST"))
        }

        // ---- Endpoints entre appareils Android : pas de session, la sécurité vient
        // ---- de l'acceptation explicite par l'utilisateur qui reçoit.
        // `/api/hello` a été retiré en 2.1.0 : aucun appelant — la découverte des
        // appareils passe par la diffusion UDP — et il annonçait le modèle du téléphone
        // à quiconque sondait le port, sans authentification.
        when {
            path == "/api/push/offer" && method == "POST" -> return pushOffer(input, headers, output, remote)
            path == "/api/push/state" -> return pushState(query, output)
            path == "/api/push/send" && method == "POST" ->
                return pushSend(query, headers, input, output, remote)
        }

        // ---- Page web ----
        val authed = code == null || tokenValid(cookieToken(headers))

        if (path == "/" || path == "/index.html") {
            hooks.onWebActivity(remote)
            return sendAsset(output, "share/index.html", "text/html; charset=utf-8")
        }
        if (path == "/favicon.ico") return sendStatus(output, 404)

        if (path == "/api/session") {
            return sendJson(
                output, 200,
                """{"auth":${authed},"requireCode":${code != null},""" +
                    """"device":"${esc(NetworkUtil.deviceName())}",""" +
                    """"upload":${hooks.config().allowUpload},""" +
                    """"manage":${hooks.config().allowManage}}"""
            )
        }

        if (path == "/api/login" && method == "POST") {
            if (isLoginBlocked(remote)) {
                return sendJson(
                    output, 429,
                    """{"ok":false,"error":"Trop d'essais. Patientez une minute."}"""
                )
            }
            val body = readBody(input, headers, 4096)
            val given = parseQuery(String(body, Charsets.UTF_8))["code"].orEmpty().trim()
            // Comparaison à temps constant : `==` sur des chaînes s'arrête au premier
            // caractère différent et laisse fuir la position de l'erreur.
            return if (code != null && constantTimeEquals(given, code)) {
                noteLogin(remote, true)
                val token = java.util.UUID.randomUUID().toString()
                synchronized(tokens) { tokens[token] = System.currentTimeMillis() + SESSION_MS }
                hooks.onWebActivity(remote)
                // SameSite=Strict et non Lax : le cookie ne doit accompagner aucune
                // requête déclenchée par un autre site, pas même une navigation.
                sendJson(output, 200, """{"ok":true}""", extra = listOf(
                    "Set-Cookie: fm36=$token; Path=/; Max-Age=86400; SameSite=Strict; HttpOnly"
                ))
            } else {
                noteLogin(remote, false)
                // Une demi-seconde de pause : six chiffres se cassent en une demi-heure
                // sans ce frein, puisque chaque essai tient dans une requête.
                runCatching { Thread.sleep(500) }
                sendJson(output, 401, """{"ok":false,"error":"Code incorrect"}""")
            }
        }

        if (!authed) return sendJson(output, 401, """{"error":"Authentification requise"}""")
        hooks.onWebActivity(remote)

        when {
            path == "/api/list" -> listing(query, output)
            path == "/api/file" -> download(query, headers, output)
            path == "/api/thumb" -> thumbnail(query, output)
            path == "/api/zip" -> zipFolder(query, output)
            path == "/api/mkdir" && method == "POST" -> mkdir(input, headers, output)
            path == "/api/upload" && method == "POST" -> upload(query, headers, input, output)
            path == "/api/precheck" && method == "POST" -> precheck(headers, input, output)
            path == "/api/search" -> search(query, output)
            path == "/api/move" && method == "POST" -> move(headers, input, output)
            path == "/api/rename" && method == "POST" -> renameEntry(headers, input, output)
            path == "/api/delete" && method == "POST" -> deleteEntry(headers, input, output)
            else -> sendStatus(output, 404)
        }
    }

    // ------------------------------------------------------------------
    // Racine partagée et sécurité des chemins
    // ------------------------------------------------------------------

    /**
     * Volumes partagés, **toujours canoniques** : `/sdcard` est un lien symbolique vers
     * `/storage/emulated/0`, et comparer une forme à l'autre cassait le fil d'Ariane de
     * la page web.
     *
     * Il y en a **plusieurs** depuis la 2.3.0. Deux cas, et deux seulement :
     *  - un dossier a été choisi dans les réglages → c'est la seule racine, exactement
     *    comme avant : restreindre reste possible et rien ne s'élargit dans le dos de
     *    l'utilisateur ;
     *  - aucun dossier choisi (le défaut) → tous les volumes accessibles : stockage
     *    interne, carte SD, clé USB. C'est ce que montre déjà l'accueil de
     *    l'application ; la page web en donne maintenant la même vue.
     *
     * La racine « / » du système est **écartée** : elle donnerait au réseau tout ce que
     * l'utilisateur ne partage pas, et elle expose `/sdcard` en lien symbolique — le
     * chemin par lequel, en 1.2, une suppression aurait effacé tout le stockage interne.
     */
    private fun rootDirs(): List<File> {
        // `resolve()` est appelée sur chaque requête, vignettes comprises : une dizaine
        // de fois par affichage de dossier. Sans ce cache, chaque appel relancerait le
        // balayage des points de montage.
        rootsCache?.let { (at, dirs) ->
            if (System.currentTimeMillis() - at < ROOTS_TTL_MS) return dirs
        }
        val configured = hooks.config().rootPath
        val dirs = if (configured.isNotBlank()) {
            // Le dossier configuré est retenu **même s'il n'existe plus** : une carte SD
            // retirée ferait sinon repasser le partage sur tous les volumes, soit un
            // élargissement que personne n'a demandé. Ici la page répond « dossier
            // introuvable », ce qui est à la fois honnête et confiné.
            listOf(canonical(File(configured)))
        } else {
            val found = runCatching { StorageRepo.volumes(context) }.getOrDefault(emptyList())
                .filter { it.readable && it.path != "/" }
                .map { canonical(File(it.path)) }
                .filter { it.isDirectory }
                .distinctBy { it.absolutePath }
            // Repli : si la détection ne rend rien, on partage au moins le stockage
            // interne — un partage vide serait pris pour une panne.
            found.ifEmpty { listOf(canonical(android.os.Environment.getExternalStorageDirectory())) }
        }
        rootsCache = System.currentTimeMillis() to dirs
        return dirs
    }

    private fun acquireAwake() {
        if (awakeCount.getAndIncrement() > 0) return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val lock = wakeLock ?: pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "36fm:transfert"
            ).also { it.setReferenceCounted(false); wakeLock = it }
            // Délai de sûreté : un fil tué sans passer par son `finally` — le processus
            // abattu en pleine réception — ne doit pas laisser le processeur éveillé
            // jusqu'au prochain redémarrage.
            lock.acquire(WAKE_MAX_MS)
        }
    }

    private fun releaseAwake() {
        if (awakeCount.decrementAndGet() > 0) return
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
    }

    private fun canonical(f: File): File = runCatching { File(f.canonicalPath) }.getOrDefault(f)

    /** Le volume qui contient [file], ou null s'il est hors partage. */
    private fun ownerRoot(file: File): File? = rootDirs().firstOrNull { under(file, it) }

    private fun isSharedRoot(file: File): Boolean =
        rootDirs().any { it.absolutePath == file.absolutePath }

    private fun under(file: File, root: File): Boolean {
        val rc = root.absolutePath
        if (file.absolutePath == rc) return true
        // Le préfixe se termine par un séparateur, sinon « /sdcard/Photos2 » passerait
        // pour un enfant de « /sdcard/Photos ». Cas particulier d'une racine finissant
        // déjà par un séparateur, qui ne doit pas devenir « // ».
        val prefix = if (rc.endsWith(File.separator)) rc else rc + File.separator
        return file.absolutePath.startsWith(prefix)
    }

    /**
     * Résout un chemin demandé par le navigateur **à l'intérieur** des volumes
     * partagés. Sans cette barrière, `?path=../../data/data` exposerait le téléphone
     * entier à quiconque est sur le réseau. Le chemin est d'abord rendu canonique : un
     * lien symbolique qui sortirait du partage est donc refusé, et non suivi.
     */
    private fun resolve(path: String?): File? {
        val roots = rootDirs()
        if (path.isNullOrBlank()) return roots.firstOrNull()
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return null
        val target = File(canonical)
        return if (roots.any { under(target, it) }) target else null
    }

    /**
     * Les volumes partagés avec leur espace libre, relu à chaque appel : la page web
     * l'affiche en permanence, et une valeur mise en cache aurait vieilli sous les yeux
     * de l'utilisateur pendant qu'il dépose des fichiers.
     */
    private fun sharedVolumes(): List<StorageVolumeInfo> {
        val known = runCatching { StorageRepo.volumes(context) }.getOrDefault(emptyList())
        return rootDirs().map { root ->
            val match = known.firstOrNull { canonical(File(it.path)).absolutePath == root.absolutePath }
            if (match != null) match.copy(path = root.absolutePath)
            else {
                var total = 0L
                var free = 0L
                runCatching {
                    val st = android.os.StatFs(root.absolutePath)
                    total = st.blockCountLong * st.blockSizeLong
                    free = st.availableBlocksLong * st.blockSizeLong
                }
                StorageVolumeInfo(
                    label = volumeLabel(root), path = root.absolutePath,
                    isRemovable = false, isUsb = false, totalBytes = total, freeBytes = free,
                    readable = root.canRead(), writable = root.canWrite()
                )
            }
        }
    }

    private fun volumeLabel(root: File): String = when {
        root.absolutePath == canonical(android.os.Environment.getExternalStorageDirectory())
            .absolutePath -> "Stockage interne"
        root.name.isBlank() -> root.absolutePath
        else -> root.name
    }

    /**
     * Autorisations en cours, rappelées dans **chaque** listage.
     *
     * `/api/session` n'est interrogée qu'au chargement de la page : un interrupteur
     * basculé sur le téléphone pendant que le navigateur est ouvert restait sans effet
     * visible, et le bouton « Actualiser » n'y changeait rien. Deux booléens par réponse
     * coûtent moins que cette confusion.
     */
    private fun capabilitiesJson(): String {
        val c = hooks.config()
        return """"upload":${c.allowUpload},"manage":${c.allowManage},"""
    }

    private fun volumeJson(v: StorageVolumeInfo): String =
        """{"label":"${esc(v.label)}","path":"${esc(v.path)}","total":${v.totalBytes},""" +
            """"free":${v.freeBytes},"used":${v.usedBytes},"writable":${v.writable},""" +
            """"removable":${v.isRemovable},"usb":${v.isUsb}}"""

    // ------------------------------------------------------------------
    // Routes de la page web
    // ------------------------------------------------------------------

    private fun listing(query: Map<String, String>, output: OutputStream) {
        val roots = rootDirs()
        val volumes = sharedVolumes()

        // Sans chemin et avec plusieurs volumes : la vue « Stockage », qui remplace
        // l'ancienne arrivée directe dans le stockage interne. Avec un seul volume —
        // un dossier a été choisi dans les réglages — on entre dedans comme avant : une
        // vue à une seule carte serait un clic pour rien.
        if (query["path"].isNullOrBlank() && roots.size > 1) {
            val sb = StringBuilder()
            sb.append("""{"view":"volumes","path":"","name":"Stockage","parent":null,""")
            sb.append(capabilitiesJson())
            sb.append(""""crumbs":[{"name":"Stockage","path":""}],"entries":[],""")
            sb.append(""""volume":"","volumes":[""")
            volumes.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(volumeJson(v)) }
            sb.append("]}")
            return sendJson(output, 200, sb.toString())
        }

        val dir = resolve(query["path"]) ?: return sendJson(output, 403, """{"error":"Chemin refusé"}""")
        if (!dir.isDirectory) return sendJson(output, 404, """{"error":"Dossier introuvable"}""")
        val root = ownerRoot(dir) ?: return sendJson(output, 403, """{"error":"Chemin refusé"}""")
        val children = dir.listFiles()?.filterNot { it.name.startsWith(".") } ?: emptyList()
        val sorted = children.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        )

        val sb = StringBuilder()
        sb.append("""{"view":"dir","path":"${esc(dir.absolutePath)}","name":"${esc(dir.name)}",""")
        sb.append(capabilitiesJson())
        sb.append(""""root":"${esc(root.absolutePath)}",""")
        // Le volume courant et l'état de tous les volumes : la page les affiche en
        // permanence, quelle que soit la page où l'on se trouve.
        sb.append(""""volume":"${esc(root.absolutePath)}","volumes":[""")
        volumes.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(volumeJson(v)) }
        sb.append("],")
        val parent = dir.parentFile
        val parentOk = parent != null && resolve(parent.absolutePath) != null && dir.absolutePath != root.absolutePath
        sb.append(""""parent":${if (parentOk) "\"${esc(parent!!.absolutePath)}\"" else "null"},""")

        sb.append(""""crumbs":[""")
        sb.append(crumbsJson(dir, root, roots, volumes))
        sb.append("""],"entries":[""")
        sorted.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append(entryJson(f, null))
        }
        sb.append("]}")
        sendJson(output, 200, sb.toString())
    }

    /**
     * Fil d'Ariane, du volume jusqu'à [dir]. Avec plusieurs volumes, il commence par
     * « Stockage », qui ramène à la vue des volumes.
     */
    private fun crumbsJson(
        dir: File,
        root: File,
        roots: List<File>,
        volumes: List<StorageVolumeInfo>
    ): String {
        val sb = StringBuilder()
        if (roots.size > 1) sb.append("""{"name":"Stockage","path":""}""")
        val chain = mutableListOf<File>()
        var cur: File? = dir
        while (cur != null && under(cur, root)) {
            chain += cur
            if (cur.absolutePath == root.absolutePath) break
            cur = cur.parentFile
        }
        chain.reverse()
        chain.forEach { f ->
            if (sb.isNotEmpty()) sb.append(',')
            sb.append("""{"name":"${esc(crumbLabel(f, root, volumes))}","path":"${esc(f.absolutePath)}"}""")
        }
        return sb.toString()
    }

    private fun crumbLabel(f: File, root: File, volumes: List<StorageVolumeInfo>): String =
        if (f.absolutePath == root.absolutePath) {
            volumes.firstOrNull { it.path == root.absolutePath }?.label ?: "Téléphone"
        } else f.name

    /**
     * Une entrée de liste. [where] n'est renseigné que pour un résultat de recherche :
     * le dossier qui le contient, lisible (« Stockage interne › Documents »), sans
     * lequel deux « facture.pdf » trouvés à deux endroits seraient indiscernables.
     */
    private fun entryJson(f: File, where: String?): String {
        val kind = FileKinds.of(f.name, f.isDirectory).name.lowercase()
        return """{"name":"${esc(f.name)}","path":"${esc(f.absolutePath)}",""" +
            """"dir":${f.isDirectory},"size":${if (f.isDirectory) 0 else f.length()},""" +
            """"mtime":${f.lastModified()},"kind":"$kind"""" +
            (if (where != null) ""","where":"${esc(where)}"""" else "") + "}"
    }

    // ------------------------------------------------------------------
    // Recherche (2.5.0)
    // ------------------------------------------------------------------

    /**
     * Recherche par nom, dans un dossier et tous ses sous-dossiers, ou dans tous les
     * volumes partagés si aucun dossier n'est donné.
     *
     * Même moteur que l'application ([FileSearch]) : la page et le téléphone trouvent
     * les mêmes fichiers pour la même saisie. Deux bornes, parce qu'une recherche sur
     * tout le stockage peut parcourir des centaines de milliers d'entrées : un plafond
     * de résultats, et un délai au-delà duquel on rend ce qu'on a — en le disant.
     */
    private fun search(query: Map<String, String>, output: OutputStream) {
        val q = query["q"].orEmpty()
        if (q.length > MAX_SEARCH_CHARS) return sendJson(output, 400, """{"error":"Recherche trop longue"}""")
        if (FileSearch.terms(q).isEmpty()) return sendJson(output, 400, """{"error":"Saisissez un nom à rechercher"}""")

        val all = rootDirs()
        val scope = query["path"]
        val roots = if (scope.isNullOrBlank()) all
        else listOf(resolve(scope) ?: return sendJson(output, 403, """{"error":"Chemin refusé"}"""))
        if (roots.none { it.isDirectory }) return sendJson(output, 404, """{"error":"Dossier introuvable"}""")

        val volumes = sharedVolumes()
        val deadline = System.currentTimeMillis() + SEARCH_BUDGET_MS
        val results = mutableListOf<File>()
        val outcome = FileSearch.search(
            roots, q, includeHidden = false, maxResults = MAX_SEARCH_RESULTS,
            shouldStop = { System.currentTimeMillis() > deadline }
        ) { found ->
            // Filet de sécurité : `resolve` rend le chemin canonique et refuse tout ce
            // qui sort des volumes partagés — un lien symbolique listé par le moteur,
            // par exemple, qui pointerait ailleurs.
            resolve(found.absolutePath)?.let { results += it }
        }

        val sb = StringBuilder()
        val scopeDir = if (scope.isNullOrBlank()) null else roots.first()
        sb.append("""{"view":"search","q":"${esc(q)}","path":"${esc(scopeDir?.absolutePath ?: "")}",""")
        sb.append(capabilitiesJson())
        val scopeRoot = scopeDir?.let { ownerRoot(it) }
        sb.append(""""volume":"${esc(scopeRoot?.absolutePath ?: "")}","volumes":[""")
        volumes.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(volumeJson(v)) }
        sb.append("""],"crumbs":[""")
        if (scopeDir != null && scopeRoot != null) {
            sb.append(crumbsJson(scopeDir, scopeRoot, all, volumes))
        } else if (all.size > 1) {
            sb.append("""{"name":"Stockage","path":""}""")
        }
        sb.append("""],"truncated":${outcome.truncated},"stopped":${outcome.stopped},""")
        sb.append(""""scanned":${outcome.scanned},"entries":[""")
        results.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append(entryJson(f, whereOf(f, volumes)))
        }
        sb.append("]}")
        sendJson(output, 200, sb.toString())
    }

    /** « Carte SD › Musique › Albums » : le dossier qui contient [f], lisible. */
    private fun whereOf(f: File, volumes: List<StorageVolumeInfo>): String {
        val parent = f.parentFile ?: return ""
        val root = ownerRoot(parent) ?: return parent.absolutePath
        val label = volumes.firstOrNull { it.path == root.absolutePath }?.label ?: root.name
        val rest = parent.absolutePath.removePrefix(root.absolutePath).trim('/')
        return if (rest.isEmpty()) label else label + " › " + rest.replace("/", " › ")
    }

    // ------------------------------------------------------------------
    // Déplacer (2.5.0)
    // ------------------------------------------------------------------

    /**
     * Déplace une sélection dans un autre dossier, sur le même volume ou vers un autre.
     *
     * Soumis aux mêmes conditions que le renommage et la suppression (`manageRefusal`) :
     * un déplacement retire l'élément de là où il était, ce qui change le téléphone
     * autant qu'un renommage. Le travail est confié à `FileOperations.copyOrMove`, celui
     * du navigateur de l'application, avec tous ses garde-fous : rien n'est jamais
     * écrasé (« photo (1).jpg »), un dossier ne peut pas entrer dans lui-même, et un
     * déplacement vers un autre volume copie puis ne supprime qu'après une copie réussie.
     */
    private fun move(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        manageRefusal(headers)?.let { return sendJson(output, 403, """{"error":"${esc(it)}"}""") }
        if (bodyTooLarge(headers, 64 * 1024)) {
            return sendJson(output, 413, """{"error":"Trop d'éléments en une fois"}""")
        }
        val raw = String(readBody(input, headers, 64 * 1024), Charsets.UTF_8)
        val destRaw = parseAll(raw, "dest").firstOrNull()
            ?: return sendJson(output, 400, """{"error":"Destination manquante"}""")
        val dest = resolve(destRaw) ?: return sendJson(output, 403, """{"error":"Destination refusée"}""")
        if (!dest.isDirectory) return sendJson(output, 404, """{"error":"Dossier de destination introuvable"}""")

        val paths = parseAll(raw, "path")
        if (paths.isEmpty()) return sendJson(output, 400, """{"error":"Aucun élément à déplacer"}""")
        if (paths.size > MAX_DELETE) return sendJson(output, 413, """{"error":"Trop d'éléments en une fois"}""")

        val sources = mutableListOf<File>()
        val errors = mutableListOf<String>()
        for (p in paths) {
            val f = resolve(p)
            when {
                f == null -> errors += "Chemin refusé"
                !f.exists() -> errors += "${File(p).name} : introuvable"
                isSharedRoot(f) -> errors += "${f.name} : un volume ne se déplace pas"
                else -> sources += f
            }
        }
        // Annoncé avant le déplacement, sinon indétectable après : ces éléments
        // arriveront sous un nom numéroté pour ne rien écraser.
        val renamed = sources.count { src ->
            src.parentFile?.absolutePath != dest.absolutePath && File(dest, src.name).exists()
        }
        val result = FileOperations.copyOrMove(sources, dest, move = true)
        errors += result.failed

        val sb = StringBuilder()
        sb.append("""{"ok":true,"moved":${result.succeeded},"renamed":$renamed,""")
        sb.append(""""failed":${errors.size},"errors":[""")
        errors.take(5).forEachIndexed { i, e -> if (i > 0) sb.append(','); sb.append("\"${esc(e)}\"") }
        sb.append("]}")
        sendJson(output, 200, sb.toString())
    }

    private fun download(query: Map<String, String>, headers: Map<String, String>, output: OutputStream) {
        val file = resolve(query["path"]) ?: return sendStatus(output, 403)
        if (!file.isFile) return sendStatus(output, 404)
        val length = file.length()
        val mime = guessMime(file.name)
        // « Afficher dans le navigateur » n'est accordé qu'aux types que le navigateur
        // rend sans exécuter de code. Auparavant, n'importe quel fichier pouvait être
        // servi en `inline` : un .html ou un .svg déposé sur le téléphone s'exécutait
        // alors **sur l'origine du serveur**, lisait le cookie de session et pouvait
        // parcourir puis exfiltrer tout le stockage partagé.
        val inline = query["inline"] == "1" && isSafeToDisplay(mime)
        val disposition = if (inline) "inline" else "attachment"

        // Requête de plage : indispensable pour qu'une vidéo se lise dans le navigateur
        // et pour reprendre un téléchargement interrompu.
        val range = headers["range"]
        var start = 0L
        var end = length - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-').trim()
            // Les bornes ne sont adoptées qu'une fois l'analyse entièrement réussie :
            // avec une affectation au fil de l'eau, « bytes=100-abc » laissait start à
            // 100 sans passer partial à vrai, et le fichier repartait tronqué de 100
            // octets sous un « 200 OK » — une corruption parfaitement silencieuse.
            val parsed = runCatching {
                if (from.isNotEmpty()) {
                    val a = from.toLong()
                    val b = if (to.isNotEmpty()) to.toLong() else length - 1
                    a to b
                } else if (to.isNotEmpty()) {
                    // « bytes=-500 » : les 500 derniers octets.
                    (length - to.toLong()).coerceAtLeast(0) to (length - 1)
                } else null
            }.getOrNull()
            if (parsed == null) {
                return sendText(
                    output, 416, "Plage invalide",
                    extra = listOf("Content-Range: bytes */$length")
                )
            }
            start = parsed.first
            end = parsed.second
            partial = true
        }
        if (partial && (start < 0 || start >= length || start > end)) {
            return sendText(
                output, 416, "Plage invalide",
                extra = listOf("Content-Range: bytes */$length")
            )
        }
        end = end.coerceAtMost(length - 1)
        val count = end - start + 1

        val head = mutableListOf(
            "Content-Type: $mime",
            "Content-Disposition: $disposition; filename*=UTF-8''${urlEncode(file.name)}",
            "Accept-Ranges: bytes"
        )
        if (partial) head += "Content-Range: bytes $start-$end/$length"
        sendHeaders(output, if (partial) 206 else 200, count, head)

        FileInputStream(file).use { fis ->
            if (start > 0) fis.channel.position(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = count
            while (remaining > 0) {
                val read = fis.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        output.flush()
    }

    private fun downloadHead(query: Map<String, String>, output: OutputStream) {
        val file = resolve(query["path"]) ?: return sendStatus(output, 403)
        if (!file.isFile) return sendStatus(output, 404)
        sendHeaders(
            output, 200, file.length(),
            listOf("Content-Type: ${guessMime(file.name)}", "Accept-Ranges: bytes")
        )
        output.flush()
    }

    /**
     * Miniature carrée pour la grille du navigateur. Uniquement les images : une
     * vignette de vidéo demande `MediaMetadataRetriever`, trop lent pour être
     * produit à la volée sur plusieurs dizaines de fichiers.
     */
    private fun thumbnail(query: Map<String, String>, output: OutputStream) {
        val file = resolve(query["path"]) ?: return sendStatus(output, 403)
        if (!file.isFile || FileKinds.of(file.name, false) != FileKind.IMAGE) {
            return sendStatus(output, 404)
        }
        val bytes = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp: Bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            bmp.recycle()
            out.toByteArray()
        }.getOrNull() ?: return sendStatus(output, 404)

        sendHeaders(output, 200, bytes.size.toLong(), listOf(
            "Content-Type: image/jpeg",
            "Cache-Control: no-store"
        ))
        output.write(bytes)
        output.flush()
    }

    /** Télécharge un dossier entier sous forme d'archive ZIP, produite à la volée. */
    private fun zipFolder(query: Map<String, String>, output: OutputStream) {
        val dir = resolve(query["path"]) ?: return sendStatus(output, 403)
        if (!dir.isDirectory) return sendStatus(output, 404)
        // Taille inconnue à l'avance : on répond en « chunked », seule façon correcte
        // de diffuser sans connaître la longueur. Sans cela, le navigateur attendrait
        // indéfiniment la fin d'un corps annoncé mais jamais terminé.
        sendHeaders(
            output, 200, -1,
            listOf(
                "Content-Type: application/zip",
                "Content-Disposition: attachment; filename*=UTF-8''${urlEncode(dir.name + ".zip")}",
                "Transfer-Encoding: chunked"
            )
        )
        val chunked = ChunkedOutputStream(output)
        val ok = runCatching {
            ZipOutputStream(BufferedOutputStream(chunked, 64 * 1024)).use { zos ->
                val base = dir.absolutePath
                addToZip(zos, dir, base, 0)
                zos.finish()
            }
        }.isSuccess
        // En cas d'échec on ne clôt PAS le flux : la connexion se ferme brutalement et
        // le navigateur signale un téléchargement interrompu. Terminer proprement
        // livrerait une archive tronquée que rien ne distingue d'une archive complète.
        if (ok) {
            chunked.finish()
            output.flush()
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, base: String, depth: Int) {
        if (depth > 24) return
        // Pas de traversée de lien symbolique : /sdcard pointant sur lui-même
        // produirait une archive infinie.
        if (FileOperations.isSymlink(file)) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { addToZip(zos, it, base, depth + 1) }
        } else {
            val name = file.absolutePath.removePrefix(base).trimStart('/')
            if (name.isEmpty()) return
            runCatching {
                zos.putNextEntry(ZipEntry(name))
                FileInputStream(file).use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
            }
        }
    }

    private fun mkdir(input: InputStream, headers: Map<String, String>, output: OutputStream) {
        if (!hooks.config().allowUpload) return sendJson(output, 403, """{"error":"Écriture désactivée"}""")
        val body = parseQuery(String(readBody(input, headers, 8192), Charsets.UTF_8))
        val parent = resolve(body["path"]) ?: return sendJson(output, 403, """{"error":"Chemin refusé"}""")
        val name = sanitizeName(body["name"].orEmpty())
        if (name.isBlank()) return sendJson(output, 400, """{"error":"Nom invalide"}""")
        val dir = File(parent, name)
        return if (dir.exists()) sendJson(output, 409, """{"error":"Ce dossier existe déjà"}""")
        else if (dir.mkdirs()) sendJson(output, 200, """{"ok":true}""")
        else sendJson(output, 500, """{"error":"Création impossible"}""")
    }

    // ------------------------------------------------------------------
    // Renommer et supprimer depuis le navigateur (2.2.0)
    // ------------------------------------------------------------------

    /**
     * Les deux seules routes **irréversibles** du serveur. Elles exigent, en plus de
     * tout ce que traverse déjà une requête (barrières 1 à 6 du dossier de projet), un
     * réglage explicitement activé sur le téléphone et un en-tête que seule notre page
     * peut poser.
     *
     * @return le message d'erreur à renvoyer, ou null si la requête peut continuer.
     */
    private fun manageRefusal(headers: Map<String, String>): String? {
        if (!hooks.config().allowManage) {
            return "Renommer et supprimer est désactivé sur le téléphone."
        }
        // Un en-tête qui n'est pas dans la liste « sûre » du CORS oblige le navigateur à
        // demander d'abord la permission au serveur (requête préalable OPTIONS), à
        // laquelle nous ne répondons jamais : une page hostile ne peut donc pas fabriquer
        // cette requête, quels que soient les en-têtes d'origine qu'elle parvient à
        // imiter. Le contrôle d'origine reste la barrière principale ; celle-ci ne coûte
        // rien et ferme le cas des navigateurs anciens qui n'envoient ni
        // `Sec-Fetch-Site` ni `Origin`. Elle n'est exigée que sur ces deux routes :
        // ailleurs, refuser les clients non navigateurs casserait les gestionnaires de
        // téléchargement.
        if (headers["x-fm36"] != "1") {
            return "Requête refusée : elle ne vient pas de la page de partage."
        }
        return null
    }

    /**
     * `readBody` s'arrête au budget donné **sans le dire** : le corps revient tronqué.
     * Sur une route destructrice, c'est inacceptable — « /sdcard/Photos/vacances2024.jpg »
     * amputé de sa fin donne « /sdcard/Photos/vacances », qui peut exister et désigner
     * tout autre chose. Un corps trop long est donc refusé, jamais deviné.
     */
    private fun bodyTooLarge(headers: Map<String, String>, max: Int): Boolean =
        (headers["content-length"]?.toLongOrNull() ?: 0L) > max

    private fun renameEntry(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        manageRefusal(headers)?.let { return sendJson(output, 403, """{"error":"${esc(it)}"}""") }
        if (bodyTooLarge(headers, 8192)) return sendJson(output, 413, """{"error":"Requête trop longue"}""")
        val body = parseQuery(String(readBody(input, headers, 8192), Charsets.UTF_8))
        val target = resolve(body["path"]) ?: return sendJson(output, 403, """{"error":"Chemin refusé"}""")
        if (!target.exists()) return sendJson(output, 404, """{"error":"Introuvable"}""")
        if (isSharedRoot(target)) {
            return sendJson(output, 403, """{"error":"Un volume partagé lui-même ne peut pas être renommé"}""")
        }
        val name = sanitizeName(body["name"].orEmpty())
        if (name.isBlank()) return sendJson(output, 400, """{"error":"Nom invalide"}""")
        // Un nom commençant par un point est refusé, pas assaini : le listage masque les
        // fichiers cachés, si bien qu'un renommage accepté ferait disparaître l'entrée
        // de la page — indiscernable d'une suppression accidentelle.
        if (name.startsWith(".")) {
            return sendJson(output, 400, """{"error":"Un nom commençant par un point serait masqué"}""")
        }
        // `FileOperations.rename` refuse à nouveau le séparateur et la cible existante :
        // la même règle appliquée au navigateur et à l'application.
        return FileOperations.rename(target, name).fold(
            { renamed -> sendJson(output, 200, """{"ok":true,"name":"${esc(renamed.name)}"}""") },
            { e -> sendJson(output, 409, """{"error":"${esc(e.message ?: "Renommage impossible")}"}""") }
        )
    }

    /**
     * Suppression définitive : il n'y a pas de corbeille. Plusieurs `path=` sont acceptés
     * dans le même corps — la page en envoie autant que de lignes cochées, et une requête
     * par fichier rendait une sélection de deux cents entrées interminable.
     */
    private fun deleteEntry(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        manageRefusal(headers)?.let { return sendJson(output, 403, """{"error":"${esc(it)}"}""") }
        if (bodyTooLarge(headers, 64 * 1024)) {
            return sendJson(output, 413, """{"error":"Trop d'éléments en une fois"}""")
        }
        val raw = String(readBody(input, headers, 64 * 1024), Charsets.UTF_8)
        val paths = parseAll(raw, "path")
        if (paths.isEmpty()) return sendJson(output, 400, """{"error":"Aucun élément à supprimer"}""")
        if (paths.size > MAX_DELETE) {
            return sendJson(output, 413, """{"error":"Trop d'éléments en une fois"}""")
        }

        var deleted = 0
        var failed = 0
        for (p in paths) {
            val file = resolve(p)
            if (file == null || !file.exists() || isSharedRoot(file)) { failed++; continue }
            // `deleteRecursive` efface un lien symbolique sans jamais suivre sa cible :
            // supprimer « / sdcard » depuis un volume qui l'expose en lien aurait sinon
            // effacé tout le stockage interne.
            if (FileOperations.deleteRecursive(file)) deleted++ else failed++
        }
        sendJson(output, 200, """{"ok":true,"deleted":$deleted,"failed":$failed}""")
    }

    // ------------------------------------------------------------------
    // Dépôt de fichiers depuis l'ordinateur (multipart/form-data en flux)
    // ------------------------------------------------------------------

    private fun upload(
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream
    ) {
        if (!hooks.config().allowUpload) return sendJson(output, 403, """{"error":"Écriture désactivée"}""")
        val dir = resolve(query["path"]) ?: return sendJson(output, 403, """{"error":"Chemin refusé"}""")
        if (!dir.isDirectory) return sendJson(output, 404, """{"error":"Dossier introuvable"}""")

        val contentType = headers["content-type"].orEmpty()
        val boundary = Regex("boundary=\"?([^\";]+)\"?").find(contentType)?.groupValues?.get(1)
            ?: return sendJson(output, 400, """{"error":"Requête multipart invalide"}""")
        // La RFC 2046 plafonne la frontière à 70 caractères. Défense en profondeur : le
        // lecteur multipart cherche ce motif dans un tampon de 128 Kio, et une frontière
        // plus longue que le tampon le ferait boucler sans fin. Le budget d'en-têtes
        // (32 Kio) rend le cas inatteignable aujourd'hui — cette ligne le garantit
        // indépendamment de ce budget.
        if (boundary.length > MAX_BOUNDARY) {
            return sendJson(output, 400, """{"error":"Frontière multipart invalide"}""")
        }

        // Chemin relatif : l'envoi d'un dossier entier donne « photos/2026/img.jpg ».
        // Les dossiers intermédiaires sont créés, chaque segment assaini séparément.
        val rel = query["rel"].orEmpty()
        val mode = query["mode"].orEmpty()
        // La taille du fichier, annoncée par le navigateur. `Content-Length` porterait
        // sur la requête entière, enveloppe multipart comprise : l'écran de partage
        // afficherait « 2,3 Ko » pour un fichier de 2 Ko, et la barre de progression
        // n'atteindrait jamais tout à fait la fin.
        val announced = query["size"]?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L

        val saved = mutableListOf<String>()
        val result = runCatching {
            MultipartReader(input, boundary).forEachFilePart { filename, stream ->
                val target = uploadTarget(dir, rel, filename, mode)
                    ?: throw IllegalArgumentException("Nom de fichier refusé")
                // En « remplacer », le fichier n'est écrit sous son vrai nom qu'une fois
                // complet : une coupure au milieu détruirait la version du téléphone
                // sans rien livrer à la place.
                val temporary = mode == MODE_REPLACE && target.exists()
                val writing = if (temporary)
                    FileOperations.uniqueTarget(target.parentFile!!, "." + target.name + ".36fm-part")
                else target
                val id = hooks.onIncomingStart(target.name, announced, "Ordinateur")
                var written = 0L
                try {
                    FileOutputStream(writing).use { fos ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n <= 0) break
                            fos.write(buffer, 0, n)
                            written += n
                            hooks.onIncomingProgress(id, written)
                        }
                    }
                    if (temporary) {
                        if (!target.delete() || !writing.renameTo(target)) {
                            throw IllegalStateException("Remplacement impossible")
                        }
                    }
                    hooks.onIncomingDone(id, target, null)
                    saved += target.name
                } catch (e: Exception) {
                    runCatching { writing.delete() }
                    hooks.onIncomingDone(id, null, e.message)
                    throw e
                }
            }
        }
        if (result.isFailure) {
            return sendJson(output, 500, """{"error":"${esc(result.exceptionOrNull()?.message ?: "Envoi interrompu")}"}""")
        }
        sendJson(output, 200, """{"ok":true,"count":${saved.size}}""")
    }

    /**
     * Où écrire un fichier reçu : dossiers intermédiaires compris, et selon ce que
     * l'utilisateur a répondu si un fichier du même nom existe déjà.
     *
     * @param rel chemin relatif au dossier visé, vide pour un fichier isolé.
     * @param mode [MODE_REPLACE] pour écraser ; sinon un nom libre est choisi, ce qui
     * est le comportement historique et ne détruit jamais rien.
     * @return null si le chemin est refusé — l'appelant doit traiter cela comme une
     * erreur, jamais comme une invitation à écrire ailleurs.
     */
    private fun uploadTarget(dir: File, rel: String, filename: String, mode: String): File? {
        val segments = if (rel.isNotBlank()) SafeName.relative(rel)
        else listOf(sanitizeName(filename)).filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        val parent = if (segments.size == 1) dir
        else File(dir, segments.dropLast(1).joinToString(File.separator))
        // Ceinture et bretelles : `SafeName.relative` refuse déjà toute remontée, mais
        // le dossier calculé repasse quand même par le confinement.
        if (resolve(parent.absolutePath) == null) return null
        if (!parent.isDirectory && !parent.mkdirs()) return null

        val name = segments.last()
        val existing = File(parent, name)
        return when {
            // Un dossier porte déjà ce nom : il n'est pas question de l'écraser.
            existing.isDirectory -> FileOperations.uniqueTarget(parent, name)
            mode == MODE_REPLACE -> existing
            else -> FileOperations.uniqueTarget(parent, name)
        }
    }

    /**
     * Le fichier est-il déjà là, et identique ?
     *
     * Le navigateur pose la question **avant** d'envoyer quoi que ce soit : renvoyer
     * deux gigaoctets pour découvrir ensuite qu'ils étaient déjà sur le téléphone n'a
     * aucun intérêt, et c'est au propriétaire du téléphone de trancher quand les deux
     * versions diffèrent. La taille suffit à décider : comparer les octets demanderait
     * de les faire voyager, ce que cette route existe précisément pour éviter.
     */
    private fun precheck(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        if (!hooks.config().allowUpload) return sendJson(output, 403, """{"error":"Écriture désactivée"}""")
        if (bodyTooLarge(headers, 8192)) return sendJson(output, 413, """{"error":"Requête trop longue"}""")
        val body = parseQuery(String(readBody(input, headers, 8192), Charsets.UTF_8))
        val dir = resolve(body["path"]) ?: return sendJson(output, 403, """{"error":"Chemin refusé"}""")
        if (!dir.isDirectory) return sendJson(output, 404, """{"error":"Dossier introuvable"}""")

        val rel = body["rel"].orEmpty()
        val segments = if (rel.isNotBlank()) SafeName.relative(rel)
        else listOf(sanitizeName(body["name"].orEmpty())).filter { it.isNotBlank() }
        if (segments.isEmpty()) return sendJson(output, 400, """{"error":"Nom invalide"}""")

        val target = File(dir, segments.joinToString(File.separator))
        if (resolve(target.absolutePath) == null) return sendJson(output, 403, """{"error":"Chemin refusé"}""")
        val size = body["size"]?.toLongOrNull() ?: -1L

        val state = when {
            !target.exists() -> "absent"
            // Un dossier du même nom : rien à comparer, et rien à remplacer.
            target.isDirectory -> "directory"
            size >= 0 && target.length() == size -> "same"
            else -> "different"
        }
        // Musique et vidéo : deux tailles voisines ne disent pas si c'est le même morceau.
        // La durée, si — et c'est souvent elle qui tranche entre deux encodages d'un même
        // titre et deux titres différents portant le même nom. On ne la lit que lorsque la
        // question sera posée : un fichier identique ou absent n'ouvre aucune fenêtre.
        val kind = if (target.isFile) FileKinds.of(target.name, false) else null
        val media = kind == FileKind.AUDIO || kind == FileKind.VIDEO
        val duration = if (media && state == "different") mediaDurationMs(target) else -1L
        sendJson(
            output, 200,
            """{"state":"$state","size":${if (target.isFile) target.length() else -1},""" +
                """"mtime":${target.lastModified()},"media":$media,"duration":$duration}"""
        )
    }

    /** Durée d'un fichier audio ou vidéo, en millisecondes ; -1 si illisible. */
    private fun mediaDurationMs(file: File): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        } finally {
            // `release` déclare une IOException à partir d'Android 10.
            runCatching { retriever.release() }
        }
    }

    // ------------------------------------------------------------------
    // Transferts entre appareils Android
    // ------------------------------------------------------------------

    private fun pushOffer(
        input: InputStream,
        headers: Map<String, String>,
        output: OutputStream,
        remote: String
    ) {
        val body = parseQuery(String(readBody(input, headers, 64 * 1024), Charsets.UTF_8))
        val names = body["names"].orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            // Bornes sur des données venues du réseau : le nom de l'appareil et la
            // liste de fichiers sont affichés tels quels dans la boîte de dialogue.
            // Sans limite, un appareil hostile la rendait illisible — donc impossible
            // à refuser.
            .take(MAX_OFFER_FILES)
            // 300 caractères et non 120 : sanitizeName ramène ensuite les deux côtés
            // à 200 octets. Tronquer plus court ici faisait échouer la comparaison de
            // pushSend, et tout fichier au nom un peu long était refusé.
            .map { it.take(300) }
        if (names.isEmpty()) return sendJson(output, 400, """{"error":"Aucun fichier"}""")

        val offer = ShareOffer(
            id = java.util.UUID.randomUUID().toString(),
            fromName = body["from"].orEmpty().take(60).ifBlank { "Appareil inconnu" },
            fromAddress = remote,
            fileNames = names,
            totalBytes = (body["size"]?.toLongOrNull() ?: 0L).coerceIn(0, MAX_OFFER_BYTES),
            createdAt = System.currentTimeMillis()
        )
        if (!hooks.onOfferReceived(offer)) {
            return sendJson(output, 429, """{"error":"Trop de propositions en attente"}""")
        }
        sendJson(output, 200, """{"id":"${esc(offer.id)}"}""")
    }

    private fun pushState(query: Map<String, String>, output: OutputStream) {
        val offer = hooks.offerById(query["id"].orEmpty())
            ?: return sendJson(output, 404, """{"state":"expired"}""")
        sendJson(output, 200, """{"state":"${offer.answer.name.lowercase()}"}""")
    }

    /**
     * Réception effective d'un fichier proposé et accepté.
     *
     * Toutes les vérifications ci-dessous portent sur la même idée : l'utilisateur a
     * accepté **une liste précise de fichiers venue d'un appareil précis**, pas donné
     * un accès en écriture à son téléphone.
     */
    private fun pushSend(
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        remote: String
    ) {
        val offer = hooks.offerById(query["id"].orEmpty())
            ?: return sendJson(output, 403, """{"error":"Proposition inconnue ou expirée"}""")
        if (offer.answer != OfferAnswer.ACCEPTED) {
            return sendJson(output, 403, """{"error":"Transfert non accepté"}""")
        }
        // L'identifiant de proposition ne suffit pas : il doit venir de l'appareil qui
        // a fait la demande.
        if (remote != offer.fromAddress) {
            return sendJson(output, 403, """{"error":"Expéditeur inattendu"}""")
        }
        // parseQuery a déjà décodé : un second décodage transformait « a%41b.txt »
        // en « aAb.txt » sur le disque.
        val name = sanitizeName(query["name"].orEmpty())
        if (name.isBlank()) return sendJson(output, 400, """{"error":"Nom invalide"}""")
        // Le nom doit figurer dans la liste montrée à l'utilisateur.
        if (offer.fileNames.none { sanitizeName(it) == name }) {
            return sendJson(output, 403, """{"error":"Fichier non annoncé"}""")
        }
        val total = headers["content-length"]?.toLongOrNull() ?: -1L
        if (total < 0) return sendJson(output, 411, """{"error":"Longueur requise"}""")

        // Réservation atomique du quota AVANT d'écrire quoi que ce soit. Un simple
        // « vérifier puis incrémenter » laissait passer autant de requêtes qu'il y a
        // de fils si l'expéditeur les lançait en parallèle : une seule acceptation
        // aurait alors autorisé douze fois le volume annoncé.
        val budget = offer.totalBytes + 1024 * 1024
        if (!offer.reserve(total, budget)) {
            return sendJson(
                output, 413,
                """{"error":"Au-delà de ce qui a été annoncé et accepté"}"""
            )
        }

        val dir = inboxDir()
        if (!dir.exists() && !dir.mkdirs()) {
            // La réservation doit être rendue sur CHAQUE sortie anticipée : sans cela,
            // quelques échecs d'écriture consommaient le quota et un transfert
            // légitime finissait refusé pour dépassement.
            offer.release(total)
            return sendJson(output, 500, """{"error":"Dossier de réception inaccessible"}""")
        }
        val target = try {
            FileOperations.uniqueTarget(dir, name)
        } catch (e: Exception) {
            offer.release(total)
            return sendJson(output, 500, """{"error":"Destination inutilisable"}""")
        }
        val id = hooks.onIncomingStart(target.name, total, offer.fromName)
        try {
            FileOutputStream(target).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var remaining = total
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n <= 0) throw java.io.EOFException("Connexion interrompue")
                    fos.write(buffer, 0, n)
                    remaining -= n
                    hooks.onIncomingProgress(id, total - remaining)
                }
            }
            offer.touch()
            hooks.onIncomingDone(id, target, null)
            // Dernier fichier annoncé : la proposition ne sert plus à rien et ne doit
            // pas rester un droit d'écriture dormant.
            if (offer.isComplete) hooks.closeOffer(offer.id)
            sendJson(output, 200, """{"ok":true}""")
        } catch (e: Exception) {
            runCatching { target.delete() }
            offer.release(total)
            hooks.onIncomingDone(id, null, e.message)
            sendJson(output, 500, """{"error":"${esc(e.message ?: "Réception interrompue")}"}""")
        }
    }

    private fun inboxDir(): File = hooks.config().inboxDirectory()

    // ------------------------------------------------------------------
    // Primitives HTTP
    // ------------------------------------------------------------------

    /** Lit une ligne terminée par CRLF sans jamais lire au-delà — le corps doit rester intact. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                var s = buf.toString("UTF-8")
                if (s.endsWith("\r")) s = s.dropLast(1)
                return s
            }
            buf.write(b)
            if (buf.size() > 16 * 1024) return buf.toString("UTF-8")
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>, max: Int): ByteArray {
        // toLongOrNull : un corps de 2 Gio ou plus débordait un Int, donnait null,
        // et la requête passait pour vide au lieu d'être refusée.
        val length = headers["content-length"]?.toLongOrNull() ?: return ByteArray(0)
        if (length <= 0) return ByteArray(0)
        val size = length.coerceAtMost(max.toLong()).toInt()
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(out, read, size - read)
            if (n <= 0) break
            read += n
        }
        return if (read == size) out else out.copyOf(read)
    }

    private fun sendHeaders(
        output: OutputStream,
        status: Int,
        contentLength: Long,
        extra: List<String> = emptyList()
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status ${statusText(status)}\r\n")
        if (contentLength >= 0) sb.append("Content-Length: $contentLength\r\n")
        sb.append("Connection: close\r\n")
        // nosniff sur *toutes* les réponses : sans lui, un navigateur peut deviner le
        // type d'un fichier au lieu de croire l'en-tête, et exécuter comme du HTML un
        // fichier annoncé en application/octet-stream.
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("X-Frame-Options: DENY\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendText(output: OutputStream, status: Int, text: String, extra: List<String> = emptyList()) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sendHeaders(output, status, bytes.size.toLong(), extra + "Content-Type: text/plain; charset=utf-8")
        output.write(bytes)
        output.flush()
    }

    private fun sendJson(output: OutputStream, status: Int, json: String, extra: List<String> = emptyList()) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        sendHeaders(output, status, bytes.size.toLong(), extra + listOf(
            "Content-Type: application/json; charset=utf-8",
            "Cache-Control: no-store"
        ))
        output.write(bytes)
        output.flush()
    }

    private fun sendStatus(output: OutputStream, status: Int) = sendText(output, status, statusText(status))

    private fun sendAsset(output: OutputStream, name: String, mime: String) {
        val bytes = runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull()
            ?: return sendStatus(output, 404)
        sendHeaders(output, 200, bytes.size.toLong(), listOf(
            "Content-Type: $mime",
            "Cache-Control: no-store",
            // La page n'a besoin de rien d'extérieur : tout son style et tout son script
            // sont en ligne. Le verrouiller réduit à néant l'intérêt d'y faire entrer
            // quoi que ce soit, et interdit toute fuite vers un serveur tiers.
            "Content-Security-Policy: default-src 'none'; img-src 'self' data:; " +
                "style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; " +
                // blob: et rien d'autre : la page mesure la durée d'un fichier que
                // l'utilisateur dépose, en le confiant à un lecteur invisible. Une adresse
                // blob: ne peut être fabriquée que par la page elle-même, à partir d'un
                // fichier qu'elle détient déjà : aucune ressource extérieure n'entre.
                "media-src blob:; " +
                "form-action 'none'; base-uri 'none'; frame-ancestors 'none'"
        ))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 411 -> "Length Required"
        405 -> "Method Not Allowed"; 413 -> "Payload Too Large"
        416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"; else -> "Error"
    }

    private fun cookieToken(headers: Map<String, String>): String {
        val cookie = headers["cookie"] ?: return ""
        return cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("fm36=") }
            ?.removePrefix("fm36=") ?: ""
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else urlDecode(pair.substring(0, i)) to urlDecode(pair.substring(i + 1))
        }.toMap()
    }

    /**
     * Toutes les valeurs d'un même paramètre, dans l'ordre : `path=a&path=b` en donne
     * deux. [parseQuery] rend une `Map`, où la seconde occurrence écrase la première —
     * ce qui suffit partout ailleurs, mais perdrait la sélection multiple du navigateur.
     */
    private fun parseAll(raw: String, key: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else if (urlDecode(pair.substring(0, i)) != key) null
            else urlDecode(pair.substring(i + 1)).takeIf { it.isNotBlank() }
        }
    }

    private fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun urlEncode(s: String): String =
        runCatching { java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20") }.getOrDefault(s)

    /**
     * Neutralise les noms de fichiers venus du réseau : pas de chemin, pas de remontée.
     * Implémentation déplacée dans [SafeName] : trois autres chemins de code en avaient
     * besoin (téléchargement SMB, copie cloud) sans l'avoir.
     */
    private fun sanitizeName(raw: String): String = SafeName.of(raw)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    /**
     * La requête provient-elle bien de la page servie par ce serveur ?
     *
     * Trois signaux, du plus fiable au plus ancien :
     *  - `Sec-Fetch-Site` (tous les navigateurs à jour) : seuls `same-origin` et `none`
     *    — une adresse tapée à la main — sont acceptés ;
     *  - `Origin`, envoyé sur toute requête d'écriture : il doit désigner ce serveur ;
     *  - `Referer` en dernier recours.
     *
     * Une requête sans aucun de ces en-têtes est acceptée : ce sont les clients non
     * navigateurs (curl, gestionnaire de téléchargement, l'application elle-même), qui
     * ne peuvent pas être pilotés à distance par une page web — c'est précisément la
     * menace visée ici.
     */
    private fun originAllowed(headers: Map<String, String>, host: String): Boolean {
        when (headers["sec-fetch-site"]?.lowercase()) {
            "same-origin", "same-site", "none" -> return true
            "cross-site" -> return false
        }
        val origin = headers["origin"]
        if (!origin.isNullOrBlank()) {
            // « null » est l'origine opaque d'un document en bac à sable : à refuser.
            if (origin.equals("null", ignoreCase = true)) return false
            return hostOf(origin) == host
        }
        val referer = headers["referer"]
        if (!referer.isNullOrBlank()) return hostOf(referer) == host
        return true
    }

    /** Hôte d'une URL, sans schéma, sans port, sans crochets IPv6. */
    private fun hostOf(url: String): String =
        url.substringAfter("://", url)
            .substringBefore('/')
            .substringBeforeLast(':')
            .removePrefix("[").removeSuffix("]")

    /** L'hôte demandé est-il une adresse IP littérale (et non un nom de domaine) ? */
    private fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.contains(':')) return true // IPv6
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } }
    }

    private fun isLoginBlocked(remote: String): Boolean = synchronized(loginFails) {
        val entry = loginFails[remote] ?: return false
        System.currentTimeMillis() < entry.blockedUntil
    }

    private fun noteLogin(remote: String, success: Boolean) = synchronized(loginFails) {
        if (success) { loginFails.remove(remote); return }
        // Table bornée, mais on purge les entrées expirées plutôt que tout vider :
        // un attaquant disposant de plusieurs adresses locales pouvait sinon effacer
        // son propre blocage en saturant la table.
        if (loginFails.size > 200) {
            val now = System.currentTimeMillis()
            loginFails.entries.removeAll { it.value.blockedUntil < now }
            // Si tout est encore bloqué, on retire la plus ancienne entrée : un vidage
            // complet effacerait le blocage de celui qui cherche justement le code.
            while (loginFails.size > 400) {
                val oldest = loginFails.entries.minByOrNull { it.value.blockedUntil } ?: break
                loginFails.remove(oldest.key)
            }
        }
        val entry = loginFails.getOrPut(remote) { LoginAttempts() }
        entry.count++
        if (entry.count >= 6) {
            entry.blockedUntil = System.currentTimeMillis() + 60_000
            entry.count = 0
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /**
     * Types qu'un navigateur affiche sans jamais exécuter de script.
     *
     * `image/svg+xml` en est **exclu** malgré son préfixe : un SVG est un document XML
     * qui peut contenir du JavaScript, et s'ouvre avec les droits de l'origine.
     * `text/html` l'est évidemment aussi ; le reste du texte est forcé en brut.
     */
    private fun isSafeToDisplay(mime: String): Boolean {
        val m = mime.substringBefore(';').trim().lowercase()
        if (m == "image/svg+xml") return false
        return m == "application/pdf" || m == "text/plain" ||
            m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/")
    }

    private companion object {
        const val MAX_WORKERS = 12
        const val MAX_CONNECTIONS = 24
        const val MAX_PER_PEER = 8
        const val MAX_OFFER_FILES = 200
        const val MAX_OFFER_BYTES = 64L * 1024 * 1024 * 1024
        /** Durée de vie d'une session de navigateur, alignée sur le Max-Age du cookie. */
        const val SESSION_MS = 24L * 60 * 60 * 1000
        /** RFC 2046 : 70 caractères. On laisse une marge, sans plus. */
        const val MAX_BOUNDARY = 200
        /** Éléments supprimables en une requête ; au-delà, la page redécoupe. */
        const val MAX_DELETE = 500
        /** Durée de vie du cache des volumes : une carte SD insérée apparaît dans la seconde. */
        const val ROOTS_TTL_MS = 5_000L
        /** Attente maximale sur les en-têtes : borne contre les connexions dormantes. */
        const val HEADER_TIMEOUT_MS = 20_000
        /** Attente maximale sur un corps ou une réponse, écran éteint compris. */
        const val BODY_TIMEOUT_MS = 180_000
        /** Garde-fou du verrou de réveil, si un fil disparaît sans le relâcher. */
        const val WAKE_MAX_MS = 30L * 60 * 1000
        /** Réponse de l'utilisateur à un conflit de nom : écraser le fichier présent. */
        const val MODE_REPLACE = "replace"
        /** Résultats rendus par une recherche web ; au-delà, la page invite à préciser. */
        const val MAX_SEARCH_RESULTS = 500
        /** Temps accordé à une recherche web avant de rendre ce qui a été trouvé. */
        const val SEARCH_BUDGET_MS = 10_000L
        /** Longueur maximale d'une saisie de recherche. */
        const val MAX_SEARCH_CHARS = 200
    }
}

/** Encodage « chunked » pour les réponses de taille inconnue (archive ZIP à la volée). */
private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
    private var finished = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0 || finished) return
        out.write(Integer.toHexString(len).toByteArray(Charsets.US_ASCII))
        out.write(CRLF)
        out.write(b, off, len)
        out.write(CRLF)
    }

    override fun flush() = out.flush()

    fun finish() {
        if (finished) return
        finished = true
        out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
    }
}
