package compress.joshattic.us

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Minimal foreground service that keeps a running batch alive while the app is backgrounded or the
 * screen is off. Without it the batch lives only in the ViewModel's coroutine scope, so Android can
 * kill the process mid-encode (Doze / background execution limits) and silently stop the batch.
 *
 * This service does NOT perform any compression — it only raises process priority and shows the
 * required ongoing notification for the batch's duration. It is started while the app is in the
 * foreground (the user taps Start, satisfying the Android 12+ background-start restriction) and
 * stopped in the batch's finally block. Start failures are swallowed by the caller so the batch can
 * still proceed (just without the priority boost) rather than crash.
 */
class BatchForegroundService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceType())
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException if the app left the foreground before
            // the service attached. The batch continues without the priority boost.
            Log.w(TAG, "startForeground failed: ${t.message}")
            stopSelf()
        }
        // The batch state lives in the ViewModel, not here: if the process is killed there is nothing
        // for a restarted service to resume, so never recreate it.
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "BatchFgs"
        private const val CHANNEL_ID = "batch_compression"
        private const val NOTIFICATION_ID = 4711

        /** Starts the batch foreground service. Safe to call when already running (no-op restart). */
        fun start(context: Context) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(app, Intent(app, BatchForegroundService::class.java))
        }

        /** Stops the batch foreground service. Safe to call when not running. */
        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, BatchForegroundService::class.java))
        }

        /**
         * The declared FGS type for the running platform. mediaProcessing is the semantically correct
         * type but only exists on API 34+; API 29-33 use dataSync (a general long-running type valid
         * there); below API 29 no type is passed. compileSdk (36) makes the 34+ constant available at
         * compile time; it is only referenced when SDK_INT >= 34, so older devices never touch it.
         */
        private fun foregroundServiceType(): Int = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }

        private fun buildNotification(context: Context): Notification {
            ensureChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.batch_fgs_title))
                .setContentText(context.getString(R.string.batch_fgs_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            context.getString(R.string.batch_fgs_channel),
                            NotificationManager.IMPORTANCE_LOW
                        )
                    )
                }
            }
        }
    }
}
