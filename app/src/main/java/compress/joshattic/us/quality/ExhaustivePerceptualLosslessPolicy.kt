package compress.joshattic.us.quality

/**
 * "Exhaustive" Perceptually Lossless: give every eligible file a MEASURED chance.
 *
 * Why it exists. In batch_1788273016134 (64 files) only 7 received a real VMAF verdict. The rest
 * left the Perceptually Lossless path before anything was measured — 47 as "Already efficient",
 * mostly through two shortcuts that are cost savers, not quality evidence:
 *
 *  - the bits-per-pixel gate: sources below [QualityProbePolicy.PROBE_MIN_SOURCE_BITS_PER_PIXEL]
 *    got no probe ladder at all, because a class-level prediction said they would fail;
 *  - the probe-skip ratchet: after two measured rejections for a device/content CLASS, the next
 *    three files of that class were not probed, however different their content.
 *
 * Exhaustive mode removes those shortcuts and nothing else. What it deliberately does NOT change:
 *
 *  - the acceptance bar. The same window thresholds decide a pass; a file is kept only on a
 *    measured pass, and the full output is still certified afterwards;
 *  - HDR. There is no validated PQ/HLG VMAF model, so HDR stays a stream copy;
 *  - codec downgrades, geometry above the ladder budget, and missing VMAF — none can be measured.
 *
 * It adds one safeguard of its own: a plan that overturned a heuristic "keep original" decision
 * must be certified by MEASURED full-output windows. Ordinarily a certification that cannot be
 * scored may still pass structurally when the ratio is at or above the codec default; that is
 * reasonable when the heuristic itself predicted headroom, but not when the heuristic predicted
 * visible loss and only sampled probe windows overruled it.
 *
 * Pure Kotlin, no Android dependencies, so every rule is unit-tested.
 */
object ExhaustivePerceptualLosslessPolicy {

    /**
     * Probe rungs for a source. Outside exhaustive mode this is exactly
     * [QualityProbePolicy.candidateRatiosForSource]. In exhaustive mode a source the bpp gate
     * would have skipped still gets ONE rung: the safest ratio the policy allows. That rung costs
     * the least quality headroom, so if anything passes on a starved source, it is this one.
     */
    fun candidateRatios(
        defaultRatio: Double,
        sourceBitsPerPixel: Double?,
        exhaustive: Boolean
    ): List<Double> {
        val normal = QualityProbePolicy.candidateRatiosForSource(defaultRatio, sourceBitsPerPixel)
        if (!exhaustive || normal.isNotEmpty()) return normal
        return listOf(QualityProbePolicy.SAFEST_RATIO_CEILING)
    }

    /** The class-level probe-skip ratchet never pre-empts a measurement in exhaustive mode. */
    fun honourProbeSkip(exhaustive: Boolean): Boolean = !exhaustive

    /**
     * Whether a pixel-proven ratio is worth encoding the full file for. Normally the PREDICTED
     * saving must clear file-size measurement noise (0.5% and 256 KiB). In exhaustive mode any
     * predicted saving at all qualifies: the prediction only decides whether to TRY, and the
     * post-encode checks still require the real output to be smaller and verified before
     * anything is kept.
     */
    fun worthEncoding(
        sourceBytes: Long,
        predictedBytes: Long,
        exhaustive: Boolean,
        meetsNoiseThreshold: Boolean
    ): Boolean {
        if (!exhaustive) return meetsNoiseThreshold
        if (sourceBytes <= 0L) return true
        return predictedBytes < sourceBytes
    }

    /**
     * Certification pass rule for a plan whose remux decision was overturned by probes in
     * exhaustive mode: only measured windows that pass. An unscoreable output is never accepted
     * on structure alone here.
     */
    fun measuredCertificationPasses(outcome: PairScoreOutcome): Boolean =
        outcome is PairScoreOutcome.Scored && QualityProbePolicy.windowsPass(outcome.windows)
}
