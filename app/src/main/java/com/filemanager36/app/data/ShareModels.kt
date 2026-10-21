package com.filemanager36.app.data

import kotlinx.serialization.Serializable
import java.io.File

/** Réglages du partage réseau, persistés dans les préférences. */
@Serializable
data class ShareConfig(
    /** Port d'écoute du serveur web. 8036 par défaut. */
    val port: Int = DEFAULT_PORT,
    /** Dossier racine visible depuis le navigateur du PC. */
    val rootPath: String = "",
    /** Le navigateur doit-il saisir le code d'accès ? */
    val requireCode: Boolean = true,
    /** L'ordinateur peut-il déposer des fichiers sur le téléphone ? */
    val allowUpload: Boolean = true,
    /**
     * L'ordinateur peut-il **renommer et supprimer** ?
     *
     * Réglage distinct de [allowUpload], parce que le pouvoir accordé n'est pas du même
     * ordre : un dépôt ajoute un fichier — jamais il n'en écrase un,
     * `FileOperations.uniqueTarget` s'en charge — alors qu'une suppression est
     * irréversible et qu'il n'y a pas de corbeille.
     *
     * **Ouvert par défaut depuis la 2.3.1.** Il était fermé en 2.2.0, par prudence : la
     * conséquence, non anticipée, est que la fonction demandée n'apparaissait nulle part
     * dans la page web et passait pour absente. Un réglage qu'il faut deviner pour que la
     * fonction existe n'est pas un garde-fou, c'est un piège. Le garde-fou utile reste
     * l'interrupteur, bien visible sur l'écran de partage, et la demande de confirmation
     * du navigateur avant chaque suppression.
     */
    val allowManage: Boolean = true,
    /** Dossier où atterrissent les fichiers reçus. Vide = Download/36FileManager. */
    val inboxPath: String = ""
) {
    /**
     * Dossier où atterrissent les fichiers reçus.
     *
     * Défini ici et nulle part ailleurs : le serveur et l'écran « Fichiers reçus »
     * calculaient chacun le leur, et un réglage lu d'un côté mais pas de l'autre aurait
     * envoyé l'utilisateur dans un dossier vide.
     */
    fun inboxDirectory(): File {
        if (inboxPath.isNotBlank()) {
            val f = File(inboxPath)
            if (f.isDirectory || f.mkdirs()) return f
        }
        return File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "36FileManager"
        )
    }

    companion object {
        const val DEFAULT_PORT = 8036
        /** Port de découverte des autres appareils Android, en diffusion UDP. */
        const val DISCOVERY_PORT = 8037
    }
}

/** État global du partage, observé par l'interface. */
enum class ShareState { STOPPED, STARTING, RUNNING, ERROR }

/** Un autre appareil Android découvert sur le réseau local. */
data class SharePeer(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val lastSeen: Long
)

/** Sens d'un transfert, du point de vue de cet appareil. */
enum class TransferDirection { INCOMING, OUTGOING }

enum class TransferState { PENDING, RUNNING, DONE, FAILED, REFUSED }

/** Une ligne du journal des transferts. */
data class ShareTransfer(
    val id: String,
    val name: String,
    val peer: String,
    val direction: TransferDirection,
    val bytes: Long,
    val total: Long,
    val state: TransferState,
    val startedAt: Long,
    val error: String? = null
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Proposition d'envoi reçue d'un autre appareil Android, en attente de la réponse
 * de l'utilisateur. L'expéditeur interroge son état jusqu'à la réponse.
 */
data class ShareOffer(
    val id: String,
    val fromName: String,
    val fromAddress: String,
    val fileNames: List<String>,
    val totalBytes: Long,
    val createdAt: Long,
    @Volatile var answer: OfferAnswer = OfferAnswer.PENDING
) {
    /**
     * Ce qui a été réservé puis reçu. Une proposition acceptée n'est pas un droit
     * d'écriture illimité : sans ces compteurs, l'expéditeur pouvait envoyer autant
     * de fichiers qu'il voulait, de n'importe quelle taille, après une acceptation.
     *
     * `AtomicLong` et non `@Volatile` : « lire, comparer, incrémenter » n'est pas
     * atomique, et des envois parallèles franchissaient tous la vérification.
     */
    private val count = java.util.concurrent.atomic.AtomicInteger(0)
    private val bytes = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile var lastActivity: Long = createdAt
        private set

    val receivedCount: Int get() = count.get()
    val receivedBytes: Long get() = bytes.get()
    val isComplete: Boolean get() = count.get() >= fileNames.size

    /** Réserve la place d'un fichier. @return false si le quota est dépassé. */
    fun reserve(size: Long, budget: Long): Boolean {
        while (true) {
            val n = count.get()
            if (n >= fileNames.size) return false
            val b = bytes.get()
            if (b + size > budget) return false
            if (!count.compareAndSet(n, n + 1)) continue
            if (bytes.compareAndSet(b, b + size)) { lastActivity = System.currentTimeMillis(); return true }
            // Le volume a bougé entre-temps : on rend la place et on recommence.
            count.decrementAndGet()
        }
    }

    fun release(size: Long) {
        count.decrementAndGet()
        bytes.addAndGet(-size)
    }

    fun touch() { lastActivity = System.currentTimeMillis() }
}

enum class OfferAnswer { PENDING, ACCEPTED, REFUSED, EXPIRED }
