package compress.joshattic.us.quality

/**
 * What the size gate assessed and what it decided, as values the record, the row text and the
 * audit all read.
 *
 * Three things meet at this gate and must not be folded into one word:
 *  - the QUALITY evidence is one probe rung: sampled windows measured against the Perceptually
 *    Lossless bar. A rung that clears the bar and the selection margin is "passed"; one that clears
 *    only the bar is "marginal" (QualityProbePolicy.RungVerdict). The ladder hands the gate a
 *    marginal rung only when no rung cleared the margin;
 *  - the SIZE is a prediction from the requested bitrate and an overshoot factor, never a
 *    measurement of an output;
 *  - nothing here is a full-file result. No encode has run, so there is no candidate, no
 *    certificate and no saving, whichever way the gate decides.
 *
 * b287 regular job_8227cd6d6a43: the gate assessed a MARGINAL 0.97 rung measured on one planned
 * window (95.70 mean, 0.20 above the bar) and kept the original on predicted size (114,912,600 B
 * against 102,508,481 B). The basis read "pixel probes passed at 0.97" and the `size_gate` record
 * carried `provenRatio=null`, so nothing said which rung had been assessed or how it stood. b287
 * Secure Folder job_31e5faeaf665 was the other case: a clean 0.90 pass on three of three windows,
 * also kept on predicted size, written with the same words.
 */
data class SizeGateEvidence(
    /** [ENCODE] or [KEEP_ORIGINAL]. */
    val verdict: String,
    /** The ratio of the rung the gate assessed, whether or not the plan went on to adopt it. */
    val assessedRatio: Double,
    /** RungEvidence.id of that rung; null when the ladder's record of it could not be found. */
    val rungId: String?,
    /** That rung's QualityProbePolicy.RungVerdict, lower case ("passed" or "marginal"); null when unknown. */
    val rungVerdict: String?,
    /** Windows the rung scored, and windows its ladder planned. */
    val measuredWindows: Int,
    val plannedWindows: Int,
    val predictedBytes: Long,
    val sourceBytes: Long,
    /** The overshoot the prediction multiplied the request by, before its clamp. */
    val overshoot: Double,
    /** True when [overshoot] came from this file's probe clips; false when the class's learned value stood in. */
    val overshootMeasured: Boolean
) {
    val keepsOriginal: Boolean get() = verdict == KEEP_ORIGINAL

    /** The rung cleared the selection margin, not only the bar. False for a marginal or unknown rung. */
    val clearedSelectionMargin: Boolean get() = rungVerdict == PASSED

    /** How much of its plan the assessed rung measured. */
    val scope: String
        get() = when {
            plannedWindows <= 0 -> SCOPE_UNKNOWN
            measuredWindows >= plannedWindows -> SCOPE_COMPLETE
            else -> SCOPE_PARTIAL
        }

    /** The decision named for what it is: a prediction about size, in either direction. */
    val disposition: String get() = if (keepsOriginal) PREDICTED_NOT_SMALLER else PREDICTED_SMALLER

    /** Privacy-safe fields for the `size_gate` stage record. */
    fun fields(): Map<String, Any?> = linkedMapOf(
        "assessedRatio" to assessedRatio,
        "assessedRungId" to rungId,
        "rungVerdict" to rungVerdict,
        "clearedSelectionMargin" to clearedSelectionMargin,
        "measuredWindows" to measuredWindows,
        "plannedWindows" to plannedWindows,
        "scope" to scope,
        "overshootMeasured" to overshootMeasured,
        "qualityBasis" to QUALITY_BASIS,
        "sizeBasis" to SIZE_BASIS,
        "disposition" to disposition
    )

    companion object {
        const val ENCODE = "encode"
        const val KEEP_ORIGINAL = "keep_original"
        const val PASSED = "passed"
        const val MARGINAL = "marginal"
        const val SCOPE_COMPLETE = "complete"
        const val SCOPE_PARTIAL = "partial"
        const val SCOPE_UNKNOWN = "unknown"
        const val PREDICTED_SMALLER = "predicted_smaller"
        const val PREDICTED_NOT_SMALLER = "predicted_not_smaller"
        const val QUALITY_BASIS = "sampled_probe_windows"
        const val SIZE_BASIS = "predicted"

        /** The gate's evidence for [rung], the ladder's own record of the ratio it assessed. */
        fun of(
            keepOriginal: Boolean,
            assessedRatio: Double,
            rung: RungEvidence?,
            predictedBytes: Long,
            sourceBytes: Long,
            overshoot: Double,
            overshootMeasured: Boolean
        ) = SizeGateEvidence(
            verdict = if (keepOriginal) KEEP_ORIGINAL else ENCODE,
            assessedRatio = assessedRatio,
            rungId = rung?.id,
            rungVerdict = rung?.verdict,
            measuredWindows = rung?.scores?.size ?: 0,
            plannedWindows = rung?.windows?.size ?: 0,
            predictedBytes = predictedBytes,
            sourceBytes = sourceBytes,
            overshoot = overshoot,
            overshootMeasured = overshootMeasured
        )
    }
}
