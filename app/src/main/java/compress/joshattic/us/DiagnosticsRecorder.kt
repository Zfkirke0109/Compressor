package compress.joshattic.us

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Structured, privacy-safe batch diagnostics.
 *
 * Every record is emitted as one compact JSON line to the `CompressorDiag` logcat tag AND appended
 * to an on-device session file. Logcat is the canonical transport here because the app runs inside
 * Samsung Secure Folder, whose private files directory cannot be pulled over adb (cross-user); the
 * system log crosses that boundary, so `adb logcat -s CompressorDiag` reconstructs the full dataset
 * even for a Secure Folder run. When the files dir IS reachable (main-profile runs) the same records
 * also land in files/diagnostics/<batchId>/ for direct inspection.
 *
 * Privacy: NO video frames, NO source bytes, NO Secure Folder paths, NO raw display names or content
 * URIs ever enter a record. Sources are identified by a stable salted hash so every job can be
 * correlated across the before/after runs without exposing the private name.
 */
class DiagnosticsRecorder private constructor(
    private val batchId: String,
    private val sessionFile: File?
) {
    private val outcomes = mutableListOf<BatchTerminalAccountingEntry>()

    fun jobId(sourceKey: String): String = redactedJobId(sourceKey)

    /** One record. [type] names the record; [fields] must already be privacy-safe. */
    fun record(type: String, fields: Map<String, Any?>) {
        val obj = JSONObject()
        obj.put("batchId", batchId)
        obj.put("type", type)
        obj.put("timestampMs", System.currentTimeMillis())
        for ((k, v) in fields) obj.put(k, v ?: JSONObject.NULL)
        val line = obj.toString()
        Log.i(TAG, line)
        runCatching { sessionFile?.appendText(line + "\n") }
    }

    fun sessionStart(mode: String, selectedCount: Int, appVersion: String, buildType: String) {
        record(
            "session_start",
            mapOf(
                "schema" to SCHEMA_VERSION,
                "appVersion" to appVersion,
                "buildType" to buildType,
                "gitAppId" to APP_ID,
                "deviceModel" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "androidRelease" to Build.VERSION.RELEASE,
                "sdkInt" to Build.VERSION.SDK_INT,
                "mode" to mode,
                "selectedCount" to selectedCount,
                "privacy" to "redacted-hashes"
            )
        )
    }

    /** Full per-job record: identity + probe + plan + operation + verification + terminal. */
    fun job(
        sourceKey: String,
        displayNameForHashOnly: String,
        sourceMime: String?,
        width: Int,
        height: Int,
        fps: Float,
        durationMs: Long,
        sourceSize: Long,
        sourceTotalBitrate: Int,
        bitrateWasMeasured: Boolean,
        hdr: Boolean,
        audioMime: String?,
        audioBitrate: Int,
        requestedMode: String,
        effectiveMode: String,
        plannedOutputMime: String?,
        plannedTargetRatio: Double?,
        plannedTargetVideoBitrate: Int?,
        plannedDecisionReason: String?,
        wasStreamCopy: Boolean,
        verdict: String?,
        verified: Boolean,
        replacementSafe: Boolean,
        blockReason: String?,
        outputSize: Long,
        terminal: BatchTerminalResult,
        elapsedMs: Long
    ) {
        val accountingEntry = BatchTerminalAccountingEntry(terminal, sourceSize, outputSize)
        outcomes += accountingEntry
        val rawByteDelta = sourceSize - outputSize
        val savedBytes = BatchTerminalAccounting.savedBytes(accountingEntry)
        val savedPct = if (sourceSize > 0 && terminal.countsAsRealCompression) {
            savedBytes.toDouble() / sourceSize.toDouble()
        } else {
            0.0
        }
        record(
            "job",
            mapOf(
                "id" to jobId(sourceKey),
                "nameHash" to redactedNameHash(displayNameForHashOnly),
                "ext" to (displayNameForHashOnly.substringAfterLast('.', "none").lowercase()),
                "sourceMime" to sourceMime,
                "w" to width, "h" to height, "fps" to fps, "durationMs" to durationMs,
                "sourceSize" to sourceSize,
                "sourceTotalBitrate" to sourceTotalBitrate,
                "bitrateMeasuredFromSize" to bitrateWasMeasured,
                "hdr" to hdr,
                "audioMime" to audioMime, "audioBitrate" to audioBitrate,
                "requestedMode" to requestedMode,
                "effectiveMode" to effectiveMode,
                "plannedOutputMime" to plannedOutputMime,
                "plannedTargetRatio" to plannedTargetRatio,
                "plannedTargetVideoBitrate" to plannedTargetVideoBitrate,
                "plannedDecisionReason" to plannedDecisionReason,
                "wasStreamCopy" to wasStreamCopy,
                "verdict" to verdict,
                "verified" to verified,
                "replacementSafe" to replacementSafe,
                "blockReason" to blockReason,
                "outputSize" to outputSize,
                "rawByteDelta" to rawByteDelta,
                "savedBytes" to savedBytes,
                "savedPct" to savedPct,
                "terminal" to terminal.name,
                "countsAsRealCompression" to terminal.countsAsRealCompression,
                "elapsedMs" to elapsedMs
            )
        )
    }

    fun sessionSummary(totalElapsedMs: Long) {
        val summary = BatchTerminalAccounting.summarize(outcomes)
        record(
            "session_summary",
            mapOf(
                "processed" to summary.processedCount,
                "failed" to summary.failedCount,
                "skipped" to summary.skippedCount,
                "cancelled" to summary.cancelledCount,
                "realCompressions" to summary.realCompressionCount,
                "nonCompressions" to summary.nonCompressionCount,
                "realCompressionInputBytes" to summary.realCompressionInputBytes,
                "realCompressionOutputBytes" to summary.realCompressionOutputBytes,
                "totalBytesSaved" to summary.totalBytesSaved,
                "totalElapsedMs" to totalElapsedMs
            )
        )
    }

    companion object {
        const val TAG = "CompressorDiag"
        const val SCHEMA_VERSION = 1
        private const val APP_ID = "io.github.zfkirke0109.galaxycompressor"
        // Per-process random-free salt: a fixed app salt keeps hashes stable across the before/after
        // runs (so jobs correlate) while still not being a reversible identifier.
        private const val SALT = "galaxycompressor-diag-v1"

        internal fun redactedJobId(sourceKey: String): String = "job_" + stableHash(sourceKey)
        internal fun redactedNameHash(displayName: String): String = stableHash(displayName)

        private fun stableHash(value: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.update(SALT.toByteArray())
            md.update(value.toByteArray(Charsets.UTF_8))
            return md.digest().joinToString("") { "%02x".format(it) }.substring(0, 12)
        }

        fun start(
            context: Context,
            batchId: String,
            mode: String,
            selectedCount: Int,
            appVersion: String,
            buildType: String
        ): DiagnosticsRecorder {
            val file = runCatching {
                val dir = File(context.filesDir, "diagnostics/$batchId").apply { mkdirs() }
                File(dir, "session.jsonl")
            }.getOrNull()
            return DiagnosticsRecorder(batchId, file).also {
                it.sessionStart(mode, selectedCount, appVersion, buildType)
            }
        }
    }
}
