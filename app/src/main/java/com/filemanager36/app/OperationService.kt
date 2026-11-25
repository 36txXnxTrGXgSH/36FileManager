package com.filemanager36.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Service de premier plan qui accompagne une opération longue — une extraction de
 * plusieurs gigaoctets dure de longues minutes.
 *
 * Sans lui, Android fige l'application dès que l'écran s'éteint ou que l'on passe à
 * autre chose : un processus sans composant visible est gelé en quelques secondes, et
 * l'extraction s'arrêtait net en silence. Le service ne fait aucun travail lui-même — le
 * ViewModel le fait — : il garde le processus vivant, tient le processeur éveillé, et
 * montre la progression dans une notification, avec un bouton « Annuler ».
 */
class OperationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground AVANT toute autre décision : lancé par startForegroundService,
        // le service doit passer au premier plan dans les secondes qui suivent, même s'il
        // s'arrête aussitôt — sinon Android tue le processus.
        runCatching { startForeground(NOTIF_ID, build(this, lastTitle, lastText, lastPercent)) }

        when (intent?.action) {
            ACTION_CANCEL -> { onCancel?.invoke(); return START_NOT_STICKY }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        if (wakeLock == null) {
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "36fm:operation").apply {
                    setReferenceCounted(false)
                    // Garde-fou : jamais plus de six heures, même si l'arrêt se perdait.
                    acquire(6L * 60 * 60 * 1000)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "operations"
        private const val NOTIF_ID = 3602
        private const val ACTION_CANCEL = "com.filemanager36.app.CANCEL_OPERATION"
        private const val ACTION_STOP = "com.filemanager36.app.STOP_OPERATION"

        /** Appelé par le bouton « Annuler » de la notification. */
        @Volatile var onCancel: (() -> Unit)? = null

        @Volatile private var lastTitle = "Opération en cours"
        @Volatile private var lastText = ""
        @Volatile private var lastPercent = -1

        fun start(context: Context, title: String) {
            lastTitle = title; lastText = ""; lastPercent = -1
            val intent = Intent(context, OperationService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        /** Met à jour la notification ; à appeler au plus une fois par seconde. */
        fun update(context: Context, title: String, text: String, percent: Int) {
            lastTitle = title; lastText = text; lastPercent = percent
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, build(context, title, text, percent))
            }
        }

        fun stop(context: Context) {
            onCancel = null
            runCatching { context.stopService(Intent(context, OperationService::class.java)) }
        }

        private fun build(context: Context, title: String, text: String, percent: Int): Notification {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Opérations en cours", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Extractions et autres opérations longues" }
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            val cancel = PendingIntent.getService(
                context, 2,
                Intent(context, OperationService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
            return builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setProgress(100, percent.coerceIn(0, 100), percent < 0)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                            context, android.R.drawable.ic_menu_close_clear_cancel
                        ),
                        "Annuler", cancel
                    ).build()
                )
                .build()
        }
    }
}
