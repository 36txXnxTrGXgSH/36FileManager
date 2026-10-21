package com.filemanager36.app.data

import android.content.Context
import com.filemanager36.app.util.NetworkUtil
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestration du partage réseau : serveur web pour l'ordinateur, découverte et
 * transferts entre appareils Android.
 *
 * Toutes les méthodes publiques sont **bloquantes ou non bloquantes selon leur nom** :
 * `start`/`stop` reviennent vite, `sendFiles` doit être appelée hors du fil principal.
 * Les rappels partent de fils secondaires : l'appelant les reporte lui-même sur le
 * fil principal.
 */
class ShareRepository(private val context: Context, private val prefs: Prefs) {

    // ---- Rappels vers l'interface -------------------------------------

    var onState: (ShareState, String?) -> Unit = { _, _ -> }
    var onTransfer: (ShareTransfer) -> Unit = {}
    var onOffer: (ShareOffer) -> Unit = {}
    var onPeers: (List<SharePeer>) -> Unit = {}
    var onWebClient: (String) -> Unit = {}

    // ---- État ---------------------------------------------------------

    @Volatile var state: ShareState = ShareState.STOPPED
        private set
    @Volatile var accessCode: String? = null
        private set
    @Volatile var boundPort: Int = ShareConfig.DEFAULT_PORT
        private set
    /** Adresse sur laquelle la socket est liée ; sert à détecter un changement de réseau. */
    @Volatile var boundAddress: String? = null
        private set

    var config: ShareConfig = prefs.shareConfig
        private set

    private val secureRandom = java.security.SecureRandom()
    private val offerChannelReady = AtomicBoolean(false)

    private val transfers = Collections.synchronizedMap(LinkedHashMap<String, ShareTransfer>())
    private val offers = Collections.synchronizedMap(HashMap<String, ShareOffer>())

    private val server = HttpShareServer(context, ServerHooks())

    private var discoveryThread: Thread? = null
    private val discoveryRunning = AtomicBoolean(false)
    private var discoverySocket: DatagramSocket? = null
    private val rebinding = AtomicBoolean(false)
    /** Le partage est arrêté faute de réseau et doit repartir de lui-même. */
    @Volatile private var suspendedByNetwork = false
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    // ------------------------------------------------------------------
    // Démarrage / arrêt
    // ------------------------------------------------------------------

    fun updateConfig(newConfig: ShareConfig) {
        config = newConfig
        prefs.shareConfig = newConfig
    }

    /**
     * @return null si tout va bien, sinon le message d'erreur à afficher.
     *
     * `@Synchronized` : sans cela, un changement de réseau et un changement de réglage
     * pouvaient s'entrelacer — l'un fermait la socket que l'autre venait d'ouvrir, et
     * le partage se déclarait actif sans que rien n'écoute.
     */
    @Synchronized
    fun start(): String? {
        if (state == ShareState.RUNNING) return null
        state = ShareState.STARTING
        onState(state, null)

        // Une seule lecture, réutilisée : deux appels séparés pouvaient renvoyer null
        // au second, et le serveur se liait alors à toutes les interfaces — ce que sa
        // documentation interdit explicitement.
        // `localAddress()` n'expose plus que les interfaces Wi-Fi, point d'accès et
        // Ethernet USB : lier la socket à l'interface des données mobiles rendrait le
        // téléphone joignable depuis Internet chez les opérateurs qui attribuent une
        // adresse routable. Le contrôle de transport ci-dessous est la seconde clé du
        // même verrou — `onLocalNetwork` existait depuis la 2.0 sans être appelée.
        val address = NetworkUtil.localAddress()
        if (address == null || !NetworkUtil.onLocalNetwork(context)) {
            state = ShareState.ERROR
            val msg = "Aucun réseau local détecté. Le partage exige le Wi-Fi ou un " +
                "câble Ethernet : il ne s'ouvre jamais sur les données mobiles."
            onState(state, msg)
            return msg
        }
        boundAddress = address

        accessCode = if (config.requireCode) newAccessCode() else null

        val result = server.start(config.port, address)
        return result.fold(
            { port ->
                boundPort = port
                startDiscoveryResponder()
                watchNetwork()
                state = ShareState.RUNNING
                onState(state, null)
                null
            },
            { e ->
                state = ShareState.ERROR
                val msg = e.message ?: "Démarrage impossible"
                onState(state, msg)
                msg
            }
        )
    }

    @Synchronized
    fun stop() {
        suspendedByNetwork = false
        unwatchNetwork()
        server.stop()
        stopDiscoveryResponder()
        accessCode = null
        boundAddress = null
        state = ShareState.STOPPED
        onState(state, null)
    }

    /**
     * Surveille le réseau : la socket est liée à une adresse précise, et cette adresse
     * disparaît à chaque coupure du Wi-Fi ou changement de bail DHCP. Sans réaction,
     * le serveur restait « actif » en écoutant une adresse qui n'existait plus, et
     * l'interface affichait une nouvelle adresse sur laquelle rien ne répondait.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(
                    network: android.net.Network,
                    props: android.net.LinkProperties
                ) = checkAddress()

                override fun onAvailable(network: android.net.Network) = checkAddress()
                override fun onLost(network: android.net.Network) = checkAddress()
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
    }

    private fun unwatchNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        }
    }

    /**
     * Relie le serveur si l'adresse locale a changé. Appelé depuis un fil système.
     *
     * L'état ERROR est accepté en entrée quand la suspension vient d'une perte de
     * réseau : sinon une coupure Wi-Fi de dix secondes condamnait le partage jusqu'à
     * ce que l'utilisateur le rallume à la main.
     */
    private fun checkAddress() {
        val resumable = state == ShareState.RUNNING ||
            (state == ShareState.ERROR && suspendedByNetwork)
        if (!resumable) return
        // Les rappels réseau arrivent en rafale (route, DNS, annonce de routeur) :
        // un seul fil de reprise à la fois.
        if (!rebinding.compareAndSet(false, true)) return
        Thread({
            try {
                synchronized(this) {
                    val stillResumable = state == ShareState.RUNNING ||
                        (state == ShareState.ERROR && suspendedByNetwork)
                    if (!stillResumable) return@synchronized
                    val fresh = NetworkUtil.localAddress()
                    if (fresh == null) {
                        if (state == ShareState.RUNNING) {
                            server.stop()
                            // L'adresse est effacée : continuer à l'afficher donnerait
                            // une URL parfaitement crédible et parfaitement morte.
                            boundAddress = null
                            suspendedByNetwork = true
                            state = ShareState.ERROR
                            onState(state, "Réseau perdu. Le partage reprendra au retour du Wi-Fi.")
                        }
                        return@synchronized
                    }
                    if (fresh == boundAddress && state == ShareState.RUNNING) return@synchronized
                    server.stop()
                    server.start(config.port, fresh).fold(
                        { port ->
                            boundPort = port
                            boundAddress = fresh
                            suspendedByNetwork = false
                            state = ShareState.RUNNING
                            onState(state, null)
                        },
                        { e ->
                            boundAddress = null
                            suspendedByNetwork = true
                            state = ShareState.ERROR
                            onState(state, e.message ?: "Reprise impossible après changement de réseau")
                        }
                    )
                }
            } finally {
                rebinding.set(false)
            }
        }, "36fm-rebind").apply { isDaemon = true }.start()
    }

    /**
     * Code à six chiffres tiré d'un générateur **cryptographique**.
     *
     * `(100000..999999).random()` s'appuie sur `kotlin.random.Random.Default`, un
     * générateur rapide mais prédictible : qui observe un code peut calculer les
     * suivants. Pour six chiffres qui gardent l'accès à tout le stockage, c'est le
     * mauvais outil.
     */
    private fun newAccessCode(): String =
        (100000 + secureRandom.nextInt(900000)).toString()

    /** Renouvelle le code et invalide les navigateurs déjà connectés. */
    fun renewCode(): String? {
        if (!config.requireCode) { accessCode = null; return null }
        accessCode = newAccessCode()
        server.revokeSessions()
        return accessCode
    }

    /**
     * Adresse à taper dans le navigateur. On renvoie celle sur laquelle la socket est
     * **réellement liée**, jamais l'adresse courante de l'appareil : afficher une
     * adresse plus récente que la liaison donnerait une URL confiante et morte.
     */
    fun url(): String? {
        val ip = boundAddress ?: return null
        return "http://$ip:$boundPort"
    }

    fun transferList(): List<ShareTransfer> = synchronized(transfers) { transfers.values.toList() }

    /**
     * Vide le journal des transferts terminés.
     *
     * Les transferts **en cours** sont épargnés : `updateTransfer` abandonne
     * silencieusement une progression dont l'identifiant a disparu de la table, si bien
     * qu'un envoi effacé en pleine course continuerait sans jamais réapparaître ni
     * signaler sa fin. Ce qui reste visible après l'effacement est donc exactement ce qui
     * travaille encore.
     *
     * @return le nombre de lignes retirées.
     */
    fun clearTransfers(): Int = synchronized(transfers) {
        val before = transfers.size
        transfers.entries.removeAll { (_, t) ->
            t.state != TransferState.RUNNING && t.state != TransferState.PENDING
        }
        before - transfers.size
    }

    // ------------------------------------------------------------------
    // Propositions reçues d'un autre appareil
    // ------------------------------------------------------------------

    fun answerOffer(id: String, accept: Boolean) {
        val offer = offers[id] ?: return
        offer.answer = if (accept) OfferAnswer.ACCEPTED else OfferAnswer.REFUSED
        if (!accept) offers.remove(id)
    }

    /** Propositions encore en attente de réponse, la plus ancienne d'abord. */
    fun pendingOffers(): List<ShareOffer> {
        sweepOffers()
        return synchronized(offers) {
            offers.values.filter { it.answer == OfferAnswer.PENDING }.sortedBy { it.createdAt }
        }
    }

    /**
     * Purge les propositions restées sans réponse plus de deux minutes.
     *
     * Une proposition **acceptée** est épargnée tant qu'elle sert : l'ancienne version
     * la supprimait au bout de deux minutes, si bien qu'un transfert de plusieurs
     * gigaoctets s'interrompait avec « proposition expirée » dès que l'utilisateur
     * revenait dans l'application.
     */
    private fun sweepOffers() {
        val now = System.currentTimeMillis()
        synchronized(offers) {
            offers.entries.removeAll { (_, offer) ->
                when (offer.answer) {
                    OfferAnswer.ACCEPTED -> now - offer.lastActivity > 30 * 60_000
                    else -> now - offer.createdAt > 120_000
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Découverte des autres appareils Android
    // ------------------------------------------------------------------

    @Synchronized
    private fun startDiscoveryResponder() {
        // compareAndSet et non get/set : deux redémarrages rapprochés lançaient deux
        // fils répondeurs, et les sondes arrivaient à l'un ou à l'autre au hasard.
        if (!discoveryRunning.compareAndSet(false, true)) return
        acquireMulticastLock()
        discoveryThread = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(ShareConfig.DISCOVERY_PORT))
                }
                discoverySocket = socket
                val buf = ByteArray(512)
                while (discoveryRunning.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: Exception) {
                        if (!discoveryRunning.get()) break else continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (!text.startsWith(PROBE)) continue
                    val reply = "$REPLY${NetworkUtil.deviceName()}|$boundPort|${deviceId()}"
                        .toByteArray(Charsets.UTF_8)
                    runCatching {
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            } catch (e: Exception) {
                // Le port de découverte peut être pris : le partage web fonctionne
                // quand même, seule la liste des appareils Android reste vide.
            } finally {
                runCatching { socket?.close() }
                discoverySocket = null
            }
        }, "36fm-discovery").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stopDiscoveryResponder() {
        discoveryRunning.set(false)
        runCatching { discoverySocket?.close() }
        discoverySocket = null
        // On attend la fin du fil avant de rendre la main : sans cette jointure, un
        // redémarrage immédiat pouvait recréer une socket pendant que l'ancienne
        // vivait encore.
        runCatching { discoveryThread?.join(600) }
        discoveryThread = null
        releaseMulticastLock()
    }

    /**
     * Le microprogramme Wi-Fi d'Android filtre les trames de diffusion pour économiser
     * la batterie, surtout écran éteint — exactement le moment où le service de
     * premier plan est censé maintenir le partage. Sans ce verrou, la découverte des
     * autres téléphones paraît capricieuse.
     */
    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        runCatching {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            multicastLock = wm.createMulticastLock("36fm:discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
        multicastLock = null
    }

    /**
     * Interroge le réseau et rassemble les appareils qui répondent.
     * Bloquant pendant [timeoutMs] : à appeler depuis un fil d'arrière-plan.
     */
    fun scanPeers(timeoutMs: Int = 2500): List<SharePeer> {
        sweepOffers()
        val found = LinkedHashMap<String, SharePeer>()
        val selfId = deviceId()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 350
                val probe = PROBE.toByteArray(Charsets.UTF_8)
                for (target in NetworkUtil.broadcastAddresses()) {
                    runCatching {
                        socket.send(
                            DatagramPacket(probe, probe.size, target, ShareConfig.DISCOVERY_PORT)
                        )
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(512)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (!text.startsWith(REPLY)) continue
                    val parts = text.removePrefix(REPLY).split('|')
                    if (parts.size < 3) continue
                    // Bornes sur des données venues du réseau : ces valeurs sont
                    // affichées telles quelles dans la liste des appareils. Sans limite,
                    // une réponse forgée de 500 caractères rendait l'écran illisible.
                    val id = parts[2].take(64)
                    // Notre propre réponse revient par la diffusion : on l'écarte.
                    if (id == selfId) continue
                    found[id] = SharePeer(
                        id = id,
                        name = parts[0].take(60).trim()
                            .ifBlank { packet.address.hostAddress.orEmpty() },
                        address = packet.address.hostAddress.orEmpty(),
                        port = parts[1].toIntOrNull() ?: ShareConfig.DEFAULT_PORT,
                        lastSeen = System.currentTimeMillis()
                    )
                }
            }
        }
        val list = found.values.sortedBy { it.name.lowercase() }
        onPeers(list)
        return list
    }

    // ------------------------------------------------------------------
    // Envoi vers un autre appareil Android
    // ------------------------------------------------------------------

    /**
     * Propose puis envoie des fichiers à un appareil. Bloquant : l'appelant doit être
     * sur un fil d'arrière-plan.
     *
     * @return null si tout est parti, sinon le message d'erreur.
     */
    fun sendFiles(peer: SharePeer, files: List<File>): String? {
        val real = files.filter { it.isFile }
        if (real.isEmpty()) return "Aucun fichier à envoyer (les dossiers ne sont pas gérés)"
        val total = real.sumOf { it.length() }
        val base = "http://${peer.address}:${peer.port}"

        // 1. Proposition — l'autre appareil demande à son utilisateur d'accepter.
        val offerId = runCatching {
            val body = buildString {
                append("from=").append(enc(NetworkUtil.deviceName()))
                append("&size=").append(total)
                append("&names=").append(enc(real.joinToString("\n") { it.name }))
            }
            val json = post("$base/api/push/offer", body)
            Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        }.getOrNull() ?: return "Appareil injoignable. Le partage est-il actif de son côté ?"

        // 2. Attente de la réponse, jusqu'à 90 s.
        val deadline = System.currentTimeMillis() + 90_000
        var accepted = false
        while (System.currentTimeMillis() < deadline) {
            val answer = runCatching {
                val json = get("$base/api/push/state?id=$offerId")
                Regex("\"state\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            }.getOrNull() ?: return "Contact perdu avec l'appareil"
            when (answer) {
                "accepted" -> { accepted = true }
                "refused" -> return "Transfert refusé par l'autre appareil"
                "expired" -> return "La proposition a expiré"
            }
            if (accepted) break
            Thread.sleep(600)
        }
        if (!accepted) return "Aucune réponse de l'autre appareil"

        // 3. Envoi effectif, fichier par fichier.
        for (file in real) {
            val id = newTransfer(file.name, peer.name, TransferDirection.OUTGOING, file.length())
            val error = runCatching {
                val url = URL("$base/api/push/send?id=$offerId&name=${enc(file.name)}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    setFixedLengthStreamingMode(file.length())
                    setRequestProperty("Content-Type", "application/octet-stream")
                }
                conn.outputStream.use { out ->
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            sent += n
                            updateTransfer(id) { it.copy(bytes = sent, state = TransferState.RUNNING) }
                        }
                    }
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..299) throw IllegalStateException("Refusé (code $code)")
            }.exceptionOrNull()

            if (error != null) {
                updateTransfer(id) {
                    it.copy(state = TransferState.FAILED, error = error.message)
                }
                return "Envoi de « ${file.name} » interrompu : ${error.message}"
            }
            updateTransfer(id) { it.copy(bytes = it.total, state = TransferState.DONE) }
        }
        return null
    }

    private fun post(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        return text
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 10_000
        }
        val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        return text
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------
    // Journal des transferts
    // ------------------------------------------------------------------

    private fun newTransfer(
        name: String,
        peer: String,
        direction: TransferDirection,
        total: Long
    ): String {
        val id = UUID.randomUUID().toString()
        val t = ShareTransfer(
            id = id, name = name, peer = peer, direction = direction,
            bytes = 0, total = total, state = TransferState.PENDING,
            startedAt = System.currentTimeMillis()
        )
        synchronized(transfers) {
            transfers[id] = t
            // Journal borné : sans cela, une longue session de partage ferait grossir
            // la liste — et la recomposition Compose — sans limite.
            while (transfers.size > 60) {
                val oldest = transfers.keys.firstOrNull() ?: break
                transfers.remove(oldest)
            }
        }
        onTransfer(t)
        return id
    }

    private var lastEmit = 0L

    private fun updateTransfer(id: String, edit: (ShareTransfer) -> ShareTransfer) {
        val updated = synchronized(transfers) {
            val current = transfers[id] ?: return
            val next = edit(current)
            transfers[id] = next
            next
        }
        // Les mises à jour de progression arrivent toutes les 64 Ko : sans limitation,
        // un fichier de 1 Go déclencherait 16 000 recompositions.
        val terminal = updated.state != TransferState.RUNNING
        val now = System.currentTimeMillis()
        if (terminal || now - lastEmit >= 120) {
            lastEmit = now
            onTransfer(updated)
        }
    }

    /**
     * Notification pour une proposition entrante.
     *
     * Sans elle, la boîte de dialogue n'existe que dans l'activité : téléphone dans la
     * poche, l'utilisateur ne voyait rien et l'expéditeur attendait quatre-vingt-dix
     * secondes avant d'obtenir « aucune réponse ».
     */
    private fun notifyOffer(offer: ShareOffer) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            // Le canal n'est créé qu'une fois : le recréer à chaque proposition était
            // sans effet visible, mais c'est un appel binder par notification.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                offerChannelReady.compareAndSet(false, true)
            ) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        OFFER_CHANNEL, "Transferts entrants",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            // Référence directe et non Class.forName : la réflexion ne survit pas à R8
            // et masquait au compilateur une dépendance parfaitement légitime.
            val open = android.app.PendingIntent.getActivity(
                context, 2,
                android.content.Intent(
                    context,
                    com.filemanager36.app.MainActivity::class.java
                ).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val builder =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    android.app.Notification.Builder(context, OFFER_CHANNEL)
                else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            nm.notify(
                3602 + (offer.id.hashCode() and 0x0f),
                builder
                    .setContentTitle("${offer.fromName} veut vous envoyer des fichiers")
                    .setContentText(
                        "${offer.fileNames.size} fichier(s) — ouvrez 36FileManager pour répondre"
                    )
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            )
        }
    }

    /**
     * Identifiant de cet appareil sur le réseau local.
     *
     * Jusqu'à la 2.0.2 : `(Build.MODEL + "|" + Build.DEVICE).hashCode()`. Deux
     * téléphones du même modèle recevaient donc le **même** identifiant, et comme
     * [scanPeers] écarte la réponse portant notre propre identifiant pour ne pas se
     * lister soi-même, deux appareils identiques ne se voyaient jamais. Il vient
     * maintenant d'un tirage aléatoire fait une fois à l'installation.
     */
    private fun deviceId(): String = prefs.installId

    // ------------------------------------------------------------------

    private inner class ServerHooks : HttpShareServer.Hooks {
        override fun config() = this@ShareRepository.config
        override fun accessCode() = this@ShareRepository.accessCode
        override fun onWebActivity(address: String) = onWebClient(address)

        override fun onOfferReceived(offer: ShareOffer): Boolean {
            sweepOffers()
            // File bornée : un appareil hostile ne doit pas pouvoir empiler des
            // propositions sans fin ni faire clignoter la boîte de dialogue.
            synchronized(offers) {
                // Seules les propositions réellement en attente comptent : un envoi
                // accepté puis abandonné en route bloquait sinon toute nouvelle
                // demande pendant des heures.
                val waiting = offers.values.count { it.answer == OfferAnswer.PENDING }
                if (waiting >= MAX_PENDING_OFFERS) return false
                offers[offer.id] = offer
            }
            notifyOffer(offer)
            onOffer(offer)
            return true
        }

        override fun offerById(id: String): ShareOffer? = offers[id]

        override fun closeOffer(id: String) { offers.remove(id) }

        override fun onIncomingStart(name: String, total: Long, peerName: String): String =
            newTransfer(name, peerName, TransferDirection.INCOMING, total)

        override fun onIncomingProgress(id: String, bytes: Long) {
            updateTransfer(id) { it.copy(bytes = bytes, state = TransferState.RUNNING) }
        }

        override fun onIncomingDone(id: String, saved: File?, error: String?) {
            updateTransfer(id) {
                if (error == null) it.copy(
                    // Un dépôt depuis le navigateur n'annonce pas sa taille : on la
                    // fixe a posteriori pour que la barre atteigne bien 100 %.
                    total = if (it.total <= 0) it.bytes else it.total,
                    state = TransferState.DONE
                )
                else it.copy(state = TransferState.FAILED, error = error)
            }
        }
    }

    companion object {
        private const val PROBE = "36FM?"
        private const val REPLY = "36FM!"
        private const val MAX_PENDING_OFFERS = 4
        private const val OFFER_CHANNEL = "offers"
    }
}
