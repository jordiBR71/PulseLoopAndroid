package com.pulseloop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pulseloop.AppServices
import com.pulseloop.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Opt-in "keep connected": holds the app process in the foreground so the ring link survives the
 * app being closed. Without it the UI drops the link 45 s after backgrounding and only the
 * 30-minute [RingSyncWorker] reconnects, so live steps freeze and the log shows a reconnect every
 * few minutes.
 *
 * It only keeps alive the link the UI already set up ([AppServices]); it never builds its own ring
 * stack. If the process is killed anyway, the service is not restarted ([START_NOT_STICKY]): a
 * fresh process without the UI's startup wiring would hold a link nothing listens to. The worker
 * covers that case and the next app launch starts the service again.
 */
class KeepConnectedService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdog: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        if (watchdog?.isActive != true) {
            watchdog = scope.launch {
                while (isActive) {
                    delay(WATCHDOG_MS)
                    // Re-establish a link the OS dropped (Doze, out of range); a no-op when live.
                    AppServices.peek()?.bleClient?.reconnectIfNeeded()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "keep_connected"
        private const val NOTIFICATION_ID = 4711
        private const val WATCHDOG_MS = 60_000L
        private const val PREFS = "keep_connected"
        private const val KEY_ENABLED = "enabled"

        @Volatile var isRunning = false
            private set

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) start(context) else stop(context)
        }

        /** Starts the service if the user turned the option on. Must be called from the foreground. */
        fun startIfEnabled(context: Context) {
            if (isEnabled(context)) start(context)
        }

        private fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, KeepConnectedService::class.java))
            } catch (_: Exception) {
                // Android refuses a foreground start from the background; the next app launch retries.
            }
        }

        private fun stop(context: Context) {
            context.stopService(Intent(context, KeepConnectedService::class.java))
        }

        private fun buildNotification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Anillo conectado", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Mantiene el anillo conectado con la app cerrada"
                        setShowBadge(false)
                    }
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("PulseLoop")
                .setContentText("Manteniendo el anillo conectado")
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}
