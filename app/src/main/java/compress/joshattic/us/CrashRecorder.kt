package compress.joshattic.us

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure rules for crash reports: where they go, what they are named, what they contain, and how
 * many are kept. There are no Android dependencies, so all of it is unit-testable.
 *
 * Why this exists: until now, every crash in this project had to be inferred from a pid change in
 * an export. The report for build pr44-b158 ("it crashed when attempting to compress") contained a
 * complete, clean session (session_summary: processed 1, failed 0) followed about 2 s later by a new
 * pid. There was no exception and no stack. The app never wrote one down, and its log export did not
 * collect the platform's AndroidRuntime tag. The cause had to be reconstructed from timing. The
 * next crash should arrive with its stack trace attached.
 */
object CrashReportPlan {

    /** Under `filesDir/diagnostics/`. No session.jsonl or decisions.log lives here, so session exports skip it. */
    const val DIRECTORY = "crashes"

    /**
     * Newest reports kept. A crash loop must not fill storage, and anything older than the last
     * few runs has either been exported already or is no longer useful.
     */
    const val MAX_REPORTS = 20

    private const val PREFIX = "crash-"
    private const val SUFFIX = ".log"

    /**
     * `crash-<epochMs>.log`. Epoch milliseconds, zero-padded to 13 digits, so names sort in time
     * order as plain strings and never depend on the device's time zone or locale.
     */
    fun fileName(epochMs: Long): String {
        require(epochMs >= 0) { "epochMs must be non-negative, was $epochMs" }
        return PREFIX + epochMs.toString().padStart(13, '0') + SUFFIX
    }

    fun isReportName(name: String): Boolean = hasEpochName(name, PREFIX)

    /** Report names past [keep], oldest first. Names that are not crash reports are never returned. */
    fun reportsToPrune(names: Collection<String>, keep: Int = MAX_REPORTS): List<String> =
        prune(names.filter(::isReportName), keep)

    // Routine process exits (low-memory kills, user stops, updates, background kills): recorded,
    // but under their own name and cap, so they can never push a real crash's stack trace out
    // of the MAX_REPORTS window (review of PR 44: every exit used to be a crash-*.log).
    const val MAX_EXIT_RECORDS = 20
    private const val EXIT_PREFIX = "exit-"

    fun exitRecordName(epochMs: Long): String {
        require(epochMs >= 0) { "epochMs must be non-negative, was $epochMs" }
        return EXIT_PREFIX + epochMs.toString().padStart(13, '0') + SUFFIX
    }

    fun isExitRecordName(name: String): Boolean = hasEpochName(name, EXIT_PREFIX)

    fun exitRecordsToPrune(names: Collection<String>, keep: Int = MAX_EXIT_RECORDS): List<String> =
        prune(names.filter(::isExitRecordName), keep)

    /** Epoch of a crash report or exit record name, or -1. */
    fun epochOf(name: String): Long =
        name.removePrefix(PREFIX).removePrefix(EXIT_PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: -1L

    private fun hasEpochName(name: String, prefix: String): Boolean =
        name.startsWith(prefix) && name.endsWith(SUFFIX) &&
            name.removePrefix(prefix).removeSuffix(SUFFIX).let { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun prune(matching: List<String>, keep: Int): List<String> {
        require(keep >= 0) { "keep must be non-negative, was $keep" }
        val sorted = matching.sorted()
        return if (sorted.size <= keep) emptyList() else sorted.take(sorted.size - keep)
    }

    /**
     * The report body. The environment lines come first so a report found on its own still says
     * which build and platform produced it. That is the same identity every session record carries.
     */
    fun render(environment: Map<String, String>, threadName: String, stackTrace: String): String =
        buildString {
            appendLine("Compressor crash report")
            environment.forEach { (key, value) -> appendLine("$key: $value") }
            appendLine("thread: $threadName")
            appendLine()
            append(stackTrace)
            if (!stackTrace.endsWith("\n")) appendLine()
        }
}

/**
 * Writes an uncaught exception to `filesDir/diagnostics/crashes/` before the process dies. The
 * diagnostics log export then includes it (see [DiagnosticsExporter.exportLogcat]).
 *
 * This only records the crash. The previous handler still runs afterwards, so the platform shows
 * its usual crash handling and kills the process as before. Nothing is suppressed or recovered,
 * and a crash is never turned into a quiet failure.
 */
object CrashRecorder {

    private const val TAG = "CompressorCrash"
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Never let the recorder stop the crash from being handled normally.
            runCatching { record(appContext, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** Every recorded crash report, newest first. */
    fun recordedReports(context: Context): List<File> {
        val dir = File(context.filesDir, "diagnostics/${CrashReportPlan.DIRECTORY}")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && CrashReportPlan.isReportName(it.name) }
            .sortedByDescending { it.name }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val dir = File(context.filesDir, "diagnostics/${CrashReportPlan.DIRECTORY}").apply { mkdirs() }
        val environment = linkedMapOf(
            "epochMs" to now.toString(),
            "appVersionName" to BuildConfig.VERSION_NAME,
            "appVersionCode" to BuildConfig.VERSION_CODE.toString(),
            "buildTag" to BuildConfig.BUILD_TAG,
            "buildCommit" to BuildConfig.GIT_COMMIT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidRelease" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT.toString(),
            "pid" to android.os.Process.myPid().toString()
        )
        File(dir, CrashReportPlan.fileName(now)).writeText(
            // stackTraceToString, not Log.getStackTraceString: the latter returns "" for any chain
            // containing an UnknownHostException, which would leave an empty report.
            CrashReportPlan.render(environment, thread.name, throwable.stackTraceToString())
        )
        dir.list()?.let { names ->
            CrashReportPlan.reportsToPrune(names.toList()).forEach { File(dir, it).delete() }
        }
        Log.e(TAG, "crash recorded to ${dir.name}/${CrashReportPlan.fileName(now)}")
    }
}
