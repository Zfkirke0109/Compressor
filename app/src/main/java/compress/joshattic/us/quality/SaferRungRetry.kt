package compress.joshattic.us.quality

/**
 * Opt-in experiment: one full-encode retry at a higher, already-measured ratio after a measured
 * certification failure. Pure: the caller supplies every fact and runs the encode.
 *
 * Motivating case, b169 `job_478c2fa19100` (1356x760, 10 fps): the ladder measured 0.70, 0.80,
 * 0.90 (passed) and refined down to 0.85 (passed). The 0.85 full encode's third certification
 * window scored mean 95.423 against the unchanged 95.5 bar, and the original was kept. The 0.90
 * probe pass was discarded when refinement succeeded. Whether a 0.90 full encode would certify is
 * NOT known: it was never encoded. This lets a device run find out, at a bounded cost.
 *
 * It never lowers a bar or skips a check: the retry is a new encode that must pass structural
 * verification, audio, metadata, the actual-size rule and the same pixel certification. It is
 * never offered without a previously measured, passing, higher rung (b169 `job_c92a4ca7e1be`
 * failed at 0.97 with nothing measured above it, and is not retried).
 */
object SaferRungRetry {

    /** At most one retry per file. */
    const val MAX_RETRIES_PER_ITEM = 1

    /** At most this many retries in one batch, whatever they recover. */
    const val MAX_RETRIES_PER_BATCH = 5

    /** No retry once the item has run this long; a retry costs about one more full encode. */
    const val ITEM_BUDGET_MS = 20 * 60_000L

    /** PowerManager.THERMAL_STATUS_SEVERE: at or above it, no extra full encode. */
    const val THERMAL_STATUS_LIMIT = 3

    /** Free space needed beyond the expected output: the same margin the input normaliser keeps. */
    const val FREE_SPACE_MARGIN_BYTES = 1L shl 30

    data class Input(
        val enabled: Boolean,
        val decision: CertificationDecision,
        val usedRatio: Double,
        /** The higher rung the same ladder measured as passing, or null. */
        val saferPassingRatio: Double?,
        /** The encode read the original source (not a platform-normalised copy) with the planned codec. */
        val sameSourceAndConfig: Boolean,
        val retriesThisItem: Int,
        val retriesThisBatch: Int,
        /** Fresh size check at [saferPassingRatio]: the size gate's own rule with that rung's evidence. */
        val predictedBytes: Long,
        val worthEncoding: Boolean,
        val itemElapsedMs: Long,
        /** How long the failed full encode took, as the estimate of the retry's cost. */
        val lastEncodeMs: Long,
        /** PowerManager thermal status, or null when unavailable. */
        val thermalStatus: Int?,
        /** Usable bytes where the output is written, or null when unknown. */
        val freeBytes: Long?,
        val sourceBytes: Long
    )

    sealed interface Decision {
        data class Allowed(val ratio: Double) : Decision
        data class Denied(val reasonCode: String) : Decision
    }

    fun decide(i: Input): Decision {
        fun deny(code: String) = Decision.Denied(code)
        val safer = i.saferPassingRatio
        return when {
            !i.enabled -> deny("experiment_off")
            // Only an adequately measured below-bar result is a reason to try more bits. Misalignment
            // is temporal, not rate; insufficient/unavailable evidence is not a measurement.
            i.decision != CertificationDecision.MEASURED_FAILURE -> deny("not_a_measured_quality_failure")
            safer == null || !safer.isFinite() -> deny("no_measured_safer_rung")
            safer <= i.usedRatio -> deny("safer_rung_not_higher")
            !i.sameSourceAndConfig -> deny("source_or_config_changed")
            i.retriesThisItem >= MAX_RETRIES_PER_ITEM -> deny("item_retry_limit")
            i.retriesThisBatch >= MAX_RETRIES_PER_BATCH -> deny("batch_retry_limit")
            !i.worthEncoding -> deny("size_check_predicts_no_saving")
            i.itemElapsedMs + i.lastEncodeMs > ITEM_BUDGET_MS -> deny("item_time_budget")
            i.thermalStatus != null && i.thermalStatus >= THERMAL_STATUS_LIMIT -> deny("thermal")
            i.freeBytes != null && i.freeBytes < i.sourceBytes + FREE_SPACE_MARGIN_BYTES -> deny("storage")
            else -> Decision.Allowed(safer)
        }
    }
}
