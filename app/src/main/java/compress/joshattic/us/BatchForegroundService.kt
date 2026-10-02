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
 * stopped in the batch's finally block. If that stop arrives before the service has called
 * startForeground, [ForegroundStartStopGate] defers it to the service itself. Start failures are swallowed by the caller so the batch can
 * still proceed (just without the priority boost) rather than crash.
 */
class BatchForegroundService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        try {
            if (ForegroundServiceTypePolicy.requiresTypedStartForeground(Build.VERSION.SDK_INT)) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ForegroundServiceTypePolicy.typeForSdk(Build.VERSION.SDK_INT)
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException if the app left the foreground before
            // the service attached. The batch continues without the priority boost.
            Log.w(TAG, "startForeground failed: ${t.message}")
            gate.onForegroundFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        if (gate.onForegroundEntered()) {
            // The batch ended before this service got to startForeground (a keep-original item can
            // finish in 30 ms). stop() deferred to us instead of calling stopService, because
            // stopping first is what kills the app. startForeground has run now, so the
            // contract is met and the service can leave.
            Log.i(TAG, "batch ended before the service reached the foreground; stopping now that it has")
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        // The batch state lives in the ViewModel, not here: if the process is killed there is nothing
        // for a restarted service to resume, so never recreate it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        gate.onServiceDestroyed()
        super.onDestroy()
    }

    /**
     * API 34+ foreground-service timeout (mediaProcessing/dataSync have a cumulative daily cap). The
     * platform calls this when the cap is hit and REQUIRES a prompt clean stop or it crashes the app.
     * We stop the service; the batch (owned by the ViewModel) keeps running without the priority
     * boost, which is honest degradation rather than a false completion or a crash. Never called on
     * pre-34 devices. A real batch finishes far inside the cap, so this is a safety backstop only.
     */
    override fun onTimeout(startId: Int) = handleTimeout()

    // API 35+ delivers the timeout through the two-arg overload; the one-arg (API 34) is never
    // called on 35+, so BOTH must be handled or a 35+ dataSync/mediaProcessing timeout would hit the
    // no-op super and the system would kill the process with a fatal RemoteServiceException.
    override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

    private fun handleTimeout() {
        Log.w(TAG, "foreground-service timeout reached; stopping cleanly (batch continues unprotected)")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) } // STOP_FOREGROUND_REMOVE is API 24 = minSdk
        stopSelf()
    }

    companion object {
        private const val TAG = "BatchFgs"
        private const val CHANNEL_ID = "batch_compression"
        private const val NOTIFICATION_ID = 4711

        /**
         * Orders stop against start. See [ForegroundStartStopGate] for the crash this prevents:
         * build pr44-b158 on Android 17, where a 42 ms batch called stopService before the service
         * had called startForeground.
         */
        private val gate = ForegroundStartStopGate()

        /** Starts the batch foreground service. Safe to call when already running (no-op restart). */
        fun start(context: Context) {
            val app = context.applicationContext
            gate.onStartRequested()
            try {
                ContextCompat.startForegroundService(app, Intent(app, BatchForegroundService::class.java))
            } catch (t: Throwable) {
                gate.onStartFailed()
                throw t
            }
        }

        /**
         * Stops the batch foreground service. Safe to call when not running, and safe to call
         * before the service has started: in that case the service stops itself as soon as it has
         * called startForeground, instead of this call crashing the app.
         */
        fun stop(context: Context) {
            val app = context.applicationContext
            when (gate.onStopRequested()) {
                ForegroundStartStopGate.StopAction.STOP_NOW ->
                    app.stopService(Intent(app, BatchForegroundService::class.java))
                ForegroundStartStopGate.StopAction.DEFER_UNTIL_FOREGROUND ->
                    Log.i(TAG, "stop requested before startForeground; the service will stop itself once it has")
                ForegroundStartStopGate.StopAction.NONE -> Unit
            }
        }

        // The platform-level -> FGS-type decision lives in ForegroundServiceTypePolicy so it is pure
        // and unit-testable (ForegroundServiceTypePolicyTest pins the API-35 boundary). Getting it
        // wrong fails silently at startForeground on real devices rather than at build time.

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
