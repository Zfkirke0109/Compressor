package compress.joshattic.us

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Pure rules for turning a platform process-exit record into a report. No Android types, so it
 * is unit-testable.
 */
object ProcessExitPlan {

    /** Names for ApplicationExitInfo.REASON_* codes (stable platform values, API 30+). */
    fun reasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    /**
     * Readable runs from a trace. An ANR trace is plain text. A native-crash tombstone is protobuf
     * on Android 12+, where the signal, the abort message, library names and backtrace symbols
     * survive as plain ASCII runs. Those runs are what identifies the crashing library.
     */
    fun printableRuns(bytes: ByteArray, minRun: Int = 6, maxChars: Int = 64 * 1024): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= minRun && out.length < maxChars) out.append(run).append('\n')
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c == 0x09) run.append(c.toChar()) else flush()
        }
        flush()
        return if (out.length > maxChars) out.substring(0, maxChars) else out.toString()
    }

    /**
     * Whether an exit is a failure worth a crash report (and a place in its limited history):
     * a Java or native crash, an ANR, or a failure to start. Every other exit (low memory, user
     * stop or swipe, update, background kill) is recorded as an exit record instead.
     */
    fun isFailure(reason: Int): Boolean = reason in setOf(4, 5, 6, 7)

    /** Only records newer than the last one already written, oldest first. */
    fun <T> unseen(records: List<T>, timestampOf: (T) -> Long, lastSeenTimestamp: Long): List<T> =
        records.filter { timestampOf(it) > lastSeenTimestamp }.sortedBy(timestampOf)
}

/**
 * Records why this app's previous processes ended, into the same crash-report folder the log
 * export already includes.
 *
 * [CrashRecorder] catches Java and Kotlin exceptions only. It cannot see a native crash (a
 * SIGSEGV in libvmaf or a codec library), an ANR, a low-memory kill or a user force-stop. All of
 * these end a batch with no record. batch_1790262412832 (b161) stopped mid-probe at 10:22 with no
 * session summary, no cancel record and no crash report, and nothing on the device could say
 * which it was. Android 11+ keeps the answer: ApplicationExitInfo, with the tombstone for native
 * crashes and the trace for ANRs. This reads it at the next start.
 */
object ProcessExitRecorder {

    private const val TAG = "CompressorCrash"
    private const val MARKER = ".last-exit-timestamp"
    private const val MAX_TRACE_BYTES = 512 * 1024

    fun recordPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { record(context.applicationContext) }
            .onFailure { Log.w(TAG, "could not read process exit reasons: ${it.message}") }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun record(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val dir = File(context.filesDir, "diagnostics/${CrashReportPlan.DIRECTORY}").apply { mkdirs() }
        val marker = File(dir, MARKER)
        val lastSeen = runCatching { marker.readText().trim().toLong() }.getOrDefault(0L)
        val fresh = ProcessExitPlan.unseen(am.getHistoricalProcessExitReasons(null, 0, 16), { it.timestamp }, lastSeen)
        if (fresh.isEmpty()) return
        for (info in fresh) {
            val failure = ProcessExitPlan.isFailure(info.reason)
            val name = if (failure) CrashReportPlan.fileName(info.timestamp) else CrashReportPlan.exitRecordName(info.timestamp)
            File(dir, name).writeText(render(info))
            if (failure) {
                Log.e(TAG, "previous process failed: ${ProcessExitPlan.reasonName(info.reason)}")
            } else {
                Log.i(TAG, "previous process exit recorded: ${ProcessExitPlan.reasonName(info.reason)}")
            }
        }
        marker.writeText(fresh.maxOf { it.timestamp }.toString())
        dir.list()?.let { names ->
            (CrashReportPlan.reportsToPrune(names.toList()) + CrashReportPlan.exitRecordsToPrune(names.toList()))
                .forEach { File(dir, it).delete() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun render(info: ApplicationExitInfo): String = buildString {
        appendLine("Compressor process exit record")
        appendLine("reason: ${ProcessExitPlan.reasonName(info.reason)}")
        appendLine("description: ${info.description ?: "-"}")
        appendLine("status: ${info.status}")
        appendLine("importance: ${info.importance}")
        appendLine("epochMs: ${info.timestamp}")
        appendLine("pid: ${info.pid}")
        appendLine("process: ${info.processName}")
        appendLine("pssKb: ${info.pss}  rssKb: ${info.rss}")
        appendLine("appVersionName (reading build): ${BuildConfig.VERSION_NAME}")
        val trace = runCatching {
            info.traceInputStream?.use { input ->
                val buffer = ByteArray(MAX_TRACE_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
                buffer.copyOf(total)
            }
        }.getOrNull()
        if (trace != null && trace.isNotEmpty()) {
            appendLine()
            appendLine("trace (${trace.size} bytes; readable text only):")
            append(ProcessExitPlan.printableRuns(trace))
        }
    }
}
