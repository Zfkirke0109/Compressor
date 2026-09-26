package compress.joshattic.us

import java.security.MessageDigest
import java.util.Locale

/**
 * A stage event (schema v3): one stage of one attempt at one job.
 *
 * [sourceKey] is the raw source key; [DiagnosticsRecorder] hashes it before anything is written.
 * [fields] must be privacy-safe values (numbers, codes, hashes). Numbers are full precision:
 * the job record's compact score strings round to three decimals, which is not enough to replay
 * a decision that sits within 0.001 of a threshold.
 */
data class StageEvent(
    val sourceKey: String,
    val attempt: Int,
    val stage: String,
    val reasonCode: String,
    val elapsedMs: Long? = null,
    val fields: Map<String, Any?> = emptyMap()
) {
    object Stage {
        const val PLAN = "plan"
        const val PROBE_RUNG = "probe_rung"
        const val SIZE_GATE = "size_gate"
        const val ENCODE = "encode"
        const val FINALIZE = "finalize"
        const val VERIFY = "verify"
        const val CERTIFY = "certify"
        const val RETRY = "retry"
        const val ACCEPT = "accept"
    }

    /** Stable reason codes. Never renamed: captures are compared across builds by these strings. */
    object Reason {
        const val PLAN_RESOLVED = "plan_resolved"
        const val GATE_ENCODE = "gate_encode"
        const val GATE_KEEP_ORIGINAL = "gate_keep_original"
        const val ENCODE_COMPLETED = "encode_completed"
        const val ENCODE_FAILED = "encode_failed"
        const val CANDIDATE_FINALIZED = "candidate_finalized"
        const val STRUCTURAL_PASSED = "structural_passed"
        const val STRUCTURAL_FAILED = "structural_failed"
        const val CERT_PASSED = "cert_passed"
        const val CERT_MEASURED_FAILURE = "cert_measured_failure"
        const val CERT_INSUFFICIENT = "cert_insufficient_frames"
        const val CERT_UNAVAILABLE = "cert_unavailable"
        const val CERT_MISALIGNED = "cert_misaligned"
        const val RETRY_ALLOWED = "retry_allowed"
        const val RETRY_DENIED = "retry_denied"
        const val ACCEPTED = "accepted"
        const val REJECTED = "rejected"
    }

    companion object {
        /** Full-precision window evidence: mean/p5/min exactly as compared, frames, and window bounds. */
        fun windowFields(prefix: String, windows: List<compress.joshattic.us.quality.WindowScore>?): Map<String, Any?> {
            if (windows.isNullOrEmpty()) return mapOf("${prefix}Windows" to 0)
            return mapOf(
                "${prefix}Windows" to windows.size,
                "${prefix}Mean" to windows.joinToString(";") { full(it.mean) },
                "${prefix}P5" to windows.joinToString(";") { full(it.p5) },
                "${prefix}Min" to windows.joinToString(";") { full(it.min) },
                "${prefix}Frames" to windows.joinToString(";") { it.comparedFrames.toString() }
            )
        }

        /** Round-trippable decimal: enough digits that parsing it gives back the same double. */
        fun full(v: Double): String = if (v.isFinite()) v.toString() else "nan"
    }
}

/**
 * The learned state a batch starts from, with a SHA-256 over its canonical form (entries sorted
 * by key, `key=value` lines). Two batches whose hashes match started from byte-identical state.
 */
data class LearnedStateSnapshot(val entries: Map<String, String>, val sha256: String) {
    companion object {
        fun of(entries: Map<String, String>): LearnedStateSnapshot {
            val sorted = entries.toSortedMap()
            val canonical = sorted.entries.joinToString("\n") { "${it.key}=${it.value}" }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return LearnedStateSnapshot(sorted, digest.joinToString("") { String.format(Locale.US, "%02x", it) })
        }
    }
}

/**
 * A [SmartPerceptualProfileEngine.ProfileStore] that reports every write, in order, with the value
 * it replaced. With the batch-start [LearnedStateSnapshot] this makes the learned state of any
 * point in a batch reconstructible from the capture. Reads and clears pass straight through.
 */
class RecordingProfileStore(
    private val delegate: SmartPerceptualProfileEngine.ProfileStore,
    private val onWrite: (key: String, before: String?, after: String) -> Unit
) : SmartPerceptualProfileEngine.ProfileStore {
    override fun read(key: String): String? = delegate.read(key)
    override fun write(key: String, value: String) {
        val before = delegate.read(key)
        delegate.write(key, value)
        if (before != value) runCatching { onWrite(key, before, value) }
    }
    override fun snapshot(): Map<String, String> = delegate.snapshot()
    override fun clear() = delegate.clear()
}
