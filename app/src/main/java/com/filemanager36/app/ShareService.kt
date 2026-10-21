package com.filemanager36.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.filemanager36.app.data.ShareState

/**
 * Service de premier plan qui maintient le partage réseau en vie.
 *
 * Sans lui, Android suspend le processus dès que l'écran s'éteint ou que
 * l'utilisateur quitte l'application : le serveur cesse de répondre au milieu d'un
 * téléchargement, et le PC voit la connexion tomber sans explication. Le service ne
 * fait rien d'autre — le serveur lui-même appartient à [FmApplication.share].
 *
 * Il tient aussi un `WifiLock` : le Wi-Fi passe en veille sur beaucoup d'appareils
 * quelques minutes après l'extinction de l'écran, ce qui rend le téléphone
 * injoignable alors que le partage est toujours actif.
 */
class ShareService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val share = (application as? FmApplication)?.share

        // startForeground AVANT toute autre décision, sans exception.
        // Le service est lancé par startForegroundService() : si l'on appelle
        // stopSelf() sans être passé en premier plan — ce qui arrive quand
        // l'utilisateur coupe le partage dans les millisecondes qui suivent — Android
        // tue le processus avec ForegroundServiceDidNotStartInTimeException.
        runCatching { startForeground(NOTIF_ID, buildNotification(share?.url())) }

        if (intent?.action == ACTION_STOP) {
            share?.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        if (share == null || share.state != ShareState.RUNNING) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWifiLock()
        // START_NOT_STICKY : au redémarrage du système, rien ne doit repartir tout
        // seul — le partage réseau s'active toujours à la demande explicite.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWifiLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // L'utilisateur a balayé l'application hors des tâches récentes : on coupe le
        // partage plutôt que de laisser un serveur ouvert sur le réseau à son insu.
        (application as? FmApplication)?.share?.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "36fm:share").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
    }

    private fun buildNotification(url: String?): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "Partage réseau", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Indique que le partage réseau est actif" }
            nm.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("Partage réseau actif")
            .setContentText(url ?: "En attente du réseau")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                // Icône réelle : un null est toléré par les versions récentes, mais
                // fait planter notify() sur certaines surcouches d'Android 8 à 9.
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_menu_close_clear_cancel
                    ),
                    "Arrêter", stop
                ).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL = "share"
        private const val NOTIF_ID = 3601
        const val ACTION_STOP = "com.filemanager36.app.STOP_SHARE"

        fun start(context: Context) {
            val intent = Intent(context, ShareService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        /** Réémet la notification, par exemple après un changement d'adresse IP. */
        fun refresh(context: Context) = start(context)

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ShareService::class.java)) }
        }
    }
}
