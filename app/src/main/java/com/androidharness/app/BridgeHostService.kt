package com.androidharness.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Owns the lifetime of the on-device remote bridge.
 *
 * The bridge executes against the existing AppContainer in this process; this
 * service does not create a second agent, provider stack, or workspace.
 */
class BridgeHostService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        LaptopConnectionActivity.ensureServerRunning(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LaptopConnectionActivity.stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // If Android reclaims the process, ask it to recreate the explicitly
        // user-started host service. The bridge remains opt-in: opening the
        // Remote Connection screen starts it; the notification can stop it.
        LaptopConnectionActivity.ensureServerRunning(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LaptopConnectionActivity.stopServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AndroidHarness remote access",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps the phone-hosted AndroidHarness runtime available to paired devices"
                },
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            OPEN_REQUEST,
            Intent(this, LaptopConnectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST,
            Intent(this, BridgeHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AndroidHarness remote host")
            .setContentText("Phone runtime is available to connected devices")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop remote access", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "androidharness_remote_host"
        private const val NOTIFICATION_ID = 7301
        private const val OPEN_REQUEST = 7302
        private const val STOP_REQUEST = 7303
        private const val ACTION_STOP = "com.androidharness.app.action.STOP_REMOTE_HOST"

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, BridgeHostService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }
    }
}
