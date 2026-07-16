package compress.joshattic.us

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/**
 * Structured, privacy-safe batch diagnostics (schema v2).
 *
 * Every record is emitted as one compact JSON line to the `CompressorDiag` logcat tag AND appended
 * to an on-device session file. Logcat is the canonical transport here because the app runs inside
 * Samsung Secure Folder, whose private files directory cannot be pulled over adb (cross-user); the
 * system log crosses that boundary, so `adb logcat -s CompressorDiag` reconstructs the full dataset
 * even for a Secure Folder run. When the files dir IS reachable (main-profile runs) the same records
 * also land in files/diagnostics/<batchId>/ for direct inspection.
 *
 * Schema v2 envelope (on every record): schemaVersion, batchId, eventId, sequence, eventType (also
 * mirrored as `type` for v1 readers), jobId, timestampMs, androidUserId, profileKind. `sequence` is a
 * thread-safe monotonic counter per batch; `eventId` is a fresh unique token per logical event, so a
 * reconnect replay of the same emitted line carries the same eventId (deduped downstream) while two
 * genuinely different events never collide.
 *
 * Privacy: NO video frames, NO source bytes, NO Secure Folder paths, NO raw display names or content
 * URIs ever enter a record. Sources are identified by a stable salted hash so every job can be
 * correlated across the before/after runs without exposing the private name. androidUserId is the
 * app's own user id (public via Process.myUid()); profileKind is a truthful "normal"/"secondary_profile"
 * classification — it never claims Secure Folder specifically because that cannot be proven from a
 * public API.
 */
class DiagnosticsRecorder private constructor(
    private val batchId: String,
    private val sessionFile: File?,
    private val identity: SessionIdentity
) {
    private val outcomes = mutableListOf<BatchTerminalAccountingEntry>()
    private val sequence = AtomicInteger(0)

    fun jobId(sourceKey: String): String = redactedJobId(sourceKey)

    /**
     * One record. [type] names the event; [jobId] correlates per-item events (null for session
     * events); [fields] must already be privacy-safe. The v2 envelope is added here so no caller can
     * forget it, and the sequence/eventId are generated atomically per event.
     */
    fun record(type: String, jobId: String? = null, fields: Map<String, Any?>) {
        val obj = JSONObject()
        obj.put("schemaVersion", SCHEMA_VERSION)
        obj.put("batchId", batchId)
        obj.put("eventId", newEventId())
        obj.put("sequence", sequence.getAndIncrement())
        obj.put("eventType", type)
        // Mirror the v1 field name so older parsers keep working during migration.
        obj.put("type", type)
        obj.put("jobId", jobId ?: JSONObject.NULL)
        obj.put("timestampMs", System.currentTimeMillis())
        obj.put("androidUserId", identity.androidUserId)
        obj.put("profileKind", identity.profileKind)
        for ((k, v) in fields) obj.put(k, v ?: JSONObject.NULL)
        val line = obj.toString()
        Log.i(TAG, line)
        runCatching { sessionFile?.appendText(line + "\n") }
    }

    fun sessionStart(mode: String, selectedCount: Int) {
        record(
            "session_start",
            fields = mapOf(
                "schema" to SCHEMA_VERSION,
                "packageName" to identity.packageName,
                "appVersionName" to identity.appVersionName,
                "appVersionCode" to identity.appVersionCode,
                "buildCommit" to identity.buildCommit,
                "buildType" to identity.buildType,
                "gitAppId" to APP_ID,
                "androidUserId" to identity.androidUserId,
                "profileKind" to identity.profileKind,
                "processUid" to identity.processUid,
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
        elapsedMs: Long,
        // When a Perceptually Lossless encode was attempted and then discarded in favour of a remux,
        // these preserve WHY in the structured record itself — the per-field verification block reason
        // and the discarded encode's measured video bitrate. Without them the remux re-verification
        // overwrites verdict/blockReason and an UNEXPECTED_REMUX is opaque in a privacy-mode capture.
        fallbackReason: String? = null,
        // Probe-ladder trace (schema-additive): the exact ratios attempted ("0.70,0.80,0.90"),
        // the pixel-proven winner, and the prober's decision detail. Together these prove
        // whether a trial encode happened for this job and what it measured — the difference
        // between "rejected by prediction" and "rejected by evidence" in every capture.
        probedRatios: String? = null,
        pixelProvenRatio: Double? = null,
        probeDetail: String? = null,
        // Raw evidence behind the probe/certification decisions ("mean/p5/min;…" per window)
        // and the job's thermal bracket — enough to recalibrate window thresholds and correlate
        // throughput vs thermal state from a privacy-mode capture alone.
        probeWindowScores: String? = null,
        certWindowScores: String? = null,
        thermalStart: String? = null,
        thermalEnd: String? = null,
        // Inter-item handoff: thermal cooldown (ms) applied after the previous item, before this
        // one. 0 when the previous item ran no full encode or was skipped. Timing telemetry only.
        precedingCooldownMs: Long? = null,
        // Keep-original fast path: REUSED_SOURCE (original surfaced, no copy) vs GENERATED_FILE;
        // the guard that blocked reuse; and the bytes whose stream-copy was avoided.
        materializationMode: String? = null,
        originalReuseBlockReason: String? = null,
        copyAvoidedBytes: Long? = null,
        discardedVideoBitrate: Int? = null
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
        val id = jobId(sourceKey)
        record(
            "job",
            jobId = id,
            fields = mapOf(
                "id" to id,
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
                "fallbackReason" to fallbackReason,
                "discardedVideoBitrate" to discardedVideoBitrate,
                "probedRatios" to probedRatios,
                "pixelProvenRatio" to pixelProvenRatio,
                "probeDetail" to probeDetail,
                "probeWindowScores" to probeWindowScores,
                "certWindowScores" to certWindowScores,
                "thermalStart" to thermalStart,
                "thermalEnd" to thermalEnd,
                "precedingCooldownMs" to precedingCooldownMs,
                "materializationMode" to materializationMode,
                "originalReuseBlockReason" to originalReuseBlockReason,
                "copyAvoidedBytes" to copyAvoidedBytes,
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
            fields = mapOf(
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

    /** Terminal record for a user-cancelled batch. Still one session terminal event. */
    fun sessionCancelled(totalElapsedMs: Long, reason: String) {
        val summary = BatchTerminalAccounting.summarize(outcomes)
        record(
            "session_cancelled",
            fields = mapOf(
                "processed" to summary.processedCount,
                "cancelled" to summary.cancelledCount,
                "realCompressions" to summary.realCompressionCount,
                "totalBytesSaved" to summary.totalBytesSaved,
                "totalElapsedMs" to totalElapsedMs,
                "reason" to reason
            )
        )
    }

    /** Terminal record for a batch that aborted with an unrecovered error. */
    fun sessionFailed(totalElapsedMs: Long, reason: String) {
        val summary = BatchTerminalAccounting.summarize(outcomes)
        record(
            "session_failed",
            fields = mapOf(
                "processed" to summary.processedCount,
                "failed" to summary.failedCount,
                "realCompressions" to summary.realCompressionCount,
                "totalElapsedMs" to totalElapsedMs,
                "reason" to reason
            )
        )
    }

    private fun newEventId(): String = "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    private data class SessionIdentity(
        val packageName: String,
        val appVersionName: String,
        val appVersionCode: Long,
        val buildCommit: String,
        val buildType: String,
        val androidUserId: Int,
        val profileKind: String,
        val processUid: Int
    )

    companion object {
        const val TAG = "CompressorDiag"
        const val SCHEMA_VERSION = 2
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

        // UID layout is userId * PER_USER_RANGE + appId; PER_USER_RANGE is 100000. Process.myUid()
        // is public, so this yields the app's own Android user id (0 normal, >0 secondary profile
        // such as Secure Folder) without any cross-user permission.
        private const val PER_USER_RANGE = 100_000

        private fun buildIdentity(context: Context): SessionIdentity {
            val uid = Process.myUid()
            val userId = uid / PER_USER_RANGE
            val info = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            @Suppress("DEPRECATION")
            val versionCode = info?.let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
            } ?: -1L
            val debuggable = context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            val buildCommit = runCatching { BuildConfig.GIT_COMMIT }.getOrDefault("unknown")
            return SessionIdentity(
                packageName = context.packageName,
                appVersionName = info?.versionName ?: "unknown",
                appVersionCode = versionCode,
                buildCommit = buildCommit,
                buildType = if (debuggable) "debug" else "release",
                androidUserId = userId,
                profileKind = if (userId == 0) "normal" else "secondary_profile",
                processUid = uid
            )
        }

        fun start(
            context: Context,
            batchId: String,
            mode: String,
            selectedCount: Int
        ): DiagnosticsRecorder {
            val file = runCatching {
                val dir = File(context.filesDir, "diagnostics/$batchId").apply { mkdirs() }
                File(dir, "session.jsonl")
            }.getOrNull()
            return DiagnosticsRecorder(batchId, file, buildIdentity(context)).also {
                it.sessionStart(mode, selectedCount)
            }
        }

        /**
         * Emit a self-contained, privacy-safe synthetic session (session_start + one synthetic job +
         * session_summary) so the autonomous recorder can be validated in normal Android and Secure
         * Folder WITHOUT running a real video batch. Touches no media, replaces no files, exposes no
         * personal data. batchId carries a "selftest" marker and a caller token so repeated runs are
         * distinguishable in the log buffer.
         */
        fun runSelfTest(context: Context, token: String): String {
            val batchId = "selftest_$token"
            val recorder = start(context, batchId, mode = "self_test", selectedCount = 1)
            recorder.job(
                sourceKey = "selftest://synthetic/$token",
                displayNameForHashOnly = "selftest_synthetic.mp4",
                sourceMime = "video/avc",
                width = 1080, height = 1920, fps = 30f, durationMs = 5_000L,
                sourceSize = 10_000_000L,
                sourceTotalBitrate = 16_000_000,
                bitrateWasMeasured = false,
                hdr = false,
                audioMime = "audio/mp4a-latm", audioBitrate = 128_000,
                requestedMode = "Perceptually Lossless",
                effectiveMode = "Perceptually Lossless",
                plannedOutputMime = "video/hevc",
                plannedTargetRatio = 0.85,
                plannedTargetVideoBitrate = 13_600_000,
                plannedDecisionReason = null,
                wasStreamCopy = false,
                verdict = "Perceptually Lossless Verified (self-test synthetic)",
                verified = true,
                replacementSafe = false,
                blockReason = null,
                outputSize = 7_000_000L,
                terminal = BatchTerminalResult.TRANSCODED_SMALLER,
                elapsedMs = 1_234L
            )
            recorder.sessionSummary(totalElapsedMs = 1_500L)
            return batchId
        }
    }
}
