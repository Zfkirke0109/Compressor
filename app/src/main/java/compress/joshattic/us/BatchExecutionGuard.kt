package compress.joshattic.us

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The OS-level side effects a running batch needs to stay alive. Extracted behind an interface so
 * [BatchExecutionGuard]'s idempotent lifecycle state machine is unit-testable on the JVM without
 * Android. Real behavior lives in [AndroidBatchExecutionSink].
 */
interface BatchExecutionSink {
    fun startForegroundProtection()
    fun stopForegroundProtection()
    fun acquireCpuWakeLock()
    fun releaseCpuWakeLock()
}

/**
 * Idempotent coordinator that brackets a running batch with foreground protection and a CPU wake
 * lock. [begin] and [end] are each safe to call repeatedly and in any order: the sink effects fire
 * exactly once per active interval (compareAndSet gates them), so a double-start can never create
 * duplicate notifications/locks and a double-stop can never double-release.
 *
 * This owns NO business logic and starts NO compression — it only reflects "a batch is running"
 * into OS priority. The single caller (the batch coroutine) is the sole owner of the work itself.
 */
class BatchExecutionGuard(private val sink: BatchExecutionSink) {
    private val active = AtomicBoolean(false)

    /** Enter the protected interval. No-op when already active. */
    fun begin() {
        if (!active.compareAndSet(false, true)) return
        sink.startForegroundProtection()
        sink.acquireCpuWakeLock()
    }

    /** Leave the protected interval, releasing in reverse acquisition order. No-op when inactive. */
    fun end() {
        if (!active.compareAndSet(true, false)) return
        sink.releaseCpuWakeLock()
        sink.stopForegroundProtection()
    }

    val isActive: Boolean get() = active.get()
}

/**
 * Android-backed sink: the batch foreground service plus a bounded PARTIAL wake lock (CPU stays on;
 * the screen is NEVER forced on). The FGS alone prevents process death but not Doze CPU suspension
 * while the screen is off, so encodes could stall between items; the partial lock keeps them
 * progressing. The timeout is only a last-resort leak guard — [releaseCpuWakeLock] releases
 * deterministically, and release is idempotent (guarded by isHeld). Holds application context only.
 */
class AndroidBatchExecutionSink(context: Context) : BatchExecutionSink {
    private val appContext = context.applicationContext

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    override fun startForegroundProtection() {
        runCatching { BatchForegroundService.start(appContext) }
            .onFailure { Log.w(TAG, "foreground start failed: ${it.message}") }
    }

    override fun stopForegroundProtection() {
        runCatching { BatchForegroundService.stop(appContext) }
    }

    override fun acquireCpuWakeLock() {
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            wakeLock = lock
            Log.i(TAG, "batch wake lock acquired (bounded ${WAKELOCK_TIMEOUT_MS / 60_000L} min)")
        }.onFailure { Log.w(TAG, "wake lock acquire failed: ${it.message}") }
    }

    override fun releaseCpuWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            .onFailure { Log.w(TAG, "wake lock release failed: ${it.message}") }
        wakeLock = null
        Log.i(TAG, "batch wake lock released")
    }

    companion object {
        private const val TAG = "BatchExecGuard"
        private const val WAKELOCK_TAG = "galaxycompressor:batch"

        // Leak-guard bound ONLY; end() releases deterministically long before this fires. Sized
        // above any plausible single batch and within the same hours-scale envelope as the FGS.
        private const val WAKELOCK_TIMEOUT_MS = 3L * 60L * 60L * 1000L
    }
}
