package compress.joshattic.us

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a diagnostic line to logcat AND to a file the app owns.
 *
 * The structured `session.jsonl` has survived every capture intact, because the recorder writes it
 * to `filesDir`. The human-readable decision lines — which probe rung failed and why, what the
 * aligner saw, what the encoder was asked for — went only to logcat, and logcat is a small
 * device-wide ring buffer. That difference has now cost three consecutive rounds of evidence:
 *
 *   2026-09-01 01:44  a crash rotated the pid, and the export's `--pid` filter hid every record
 *                     the dead process had written.
 *   2026-09-01 03:49  captured, but tracing was off, so the stall had no component detail.
 *   2026-09-01 08:08  batch_1788273016134 ran 07:30-08:0x; by the 08:08 export the buffer held
 *                     nothing older than 08:07:57. All 64 jobs survived in session.jsonl; every
 *                     decision line was gone.
 *
 * The fix is not a bigger buffer or a faster export — those are races we keep losing. It is to stop
 * depending on a buffer we do not own for evidence we need. Lines still go to logcat (live `adb`
 * watching stays useful); they are additionally appended to `diagnostics/<batchId>/decisions.log`,
 * which is bounded, exported alongside the JSONL, and cannot be evicted by another app's logging.
 *
 * [attach] is called when a batch starts and [detach] when it ends. Before attach — and if the file
 * cannot be opened at all — this degrades to plain logcat, so no call site has to handle failure.
 */
object DiagLog {

    /**
     * Cap per batch. A 219-file run's decision lines measured well under this; the bound exists so
     * a pathological run cannot fill the user's storage, and it is enforced by refusing further
     * writes rather than truncating, so what IS captured stays a contiguous prefix of the run.
     */
    const val MAX_BYTES = 8L * 1024 * 1024

    @Volatile
    private var file: File? = null

    @Volatile
    private var written = 0L

    @Volatile
    private var capped = false

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Begin mirroring to `diagnostics/<batchId>/decisions.log`. Idempotent per batch. */
    @Synchronized
    fun attach(context: Context, batchId: String) {
        file = runCatching {
            val dir = File(context.filesDir, "diagnostics/$batchId").apply { mkdirs() }
            File(dir, "decisions.log")
        }.getOrNull()
        written = file?.takeIf { it.exists() }?.length() ?: 0L
        capped = false
    }

    /** Stop mirroring. Lines logged outside a batch still reach logcat. */
    @Synchronized
    fun detach() {
        file = null
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append('I', tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append('W', tag, message)
    }

    fun w(tag: String, message: String, t: Throwable) {
        Log.w(tag, message, t)
        append('W', tag, "$message: ${t.javaClass.simpleName}: ${t.message}")
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
        append('E', tag, if (t == null) message else "$message\n${Log.getStackTraceString(t)}")
    }

    @Synchronized
    private fun append(level: Char, tag: String, message: String) {
        val target = file ?: return
        if (capped) return
        val line = "${stamp.format(Date())} $level $tag: $message\n"
        val bytes = line.toByteArray()
        if (written + bytes.size > MAX_BYTES) {
            capped = true
            runCatching {
                target.appendText(
                    "${stamp.format(Date())} W DiagLog: decision log capped at $MAX_BYTES bytes;" +
                        " further lines are in logcat only\n"
                )
            }
            return
        }
        runCatching {
            target.appendText(line)
            written += bytes.size
        }
    }
}
