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
 *  - codec downgrades, geometry above the 4K scoring cap, and missing VMAF — none can be measured.
 *
 * Above 1080p it runs a SHORT ladder ([probeLadderAllowed], [trimToShortLadder]). The full ladder
 * does not fit the prober's budget at 4K, which is why sources above 1080p were never probed. Every
 * 4K HEVC SDR clip from the S23 was therefore kept as-is on an inference, the same-codec rule,
 * without a single measurement. Camera footage can carry far more bits per pixel than the 0.092 bpp
 * source whose same-codec re-encode failed calibration. The 4K60 HEVC file in batch_1790259773632
 * ran at ~120 Mbps, about 0.24 bpp. That file was HDR, so it stays excluded, and no capture has yet
 * measured an SDR 4K camera clip. Whether such clips compress transparently is an empirical
 * question, and the short ladder is how it gets asked.
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
     * Wall-clock budget of a full probe ladder (up to 1080p). Was 150 s. The selection margin
     * (QualityProbePolicy.PROBE_SELECTION) sends a marginal rung on to the next one, so ladders
     * now measure more rungs; in b166 two 1080p ladders ran out of budget part-way.
     */
    const val PROBE_BUDGET_MS = 240_000L

    enum class OverBudget { SKIP_TO_SAFEST, PROBE_SAFEST, STOP }

    /**
     * What the ladder does with [ratio] once [elapsedMs] has passed [budgetMs].
     *
     * It used to stop and fall back to the plan, which in exhaustive mode meant a full encode at
     * the default ratio with no measurement behind it. In b166 three files went that way after
     * measured failures on the lower rungs, and all three failed certification after full encodes
     * of up to 8.6 minutes of video. So an over-budget ladder skips the rungs in between and still
     * measures the safest one: a measured pass encodes with proof, and a measured failure skips the
     * file. The safest rung may run up to twice the budget; past that the ladder stops.
     */
    fun overBudgetAction(ratio: Double, highestCandidate: Double?, elapsedMs: Long, budgetMs: Long): OverBudget = when {
        highestCandidate == null || elapsedMs > 2 * budgetMs -> OverBudget.STOP
        ratio < highestCandidate - 1e-9 -> OverBudget.SKIP_TO_SAFEST
        else -> OverBudget.PROBE_SAFEST
    }

    /**
     * Wall-clock budget of the short ladder. A 4K rung costs about three minutes on the S23 Ultra:
     * in b165, job_965e925705f2 (2160x3840, 250 s) spent 180 s on its single rung, then hit
     * "probe budget exhausted" with the retreat rung never tried, although the failing window had
     * missed the mean by one point. Three rungs need ten minutes, which is still less than the
     * full encode of a long 4K file that a wrong guess would waste.
     */
    const val SHORT_LADDER_PROBE_BUDGET_MS = 600_000L

    fun probeBudgetMs(shortLadder: Boolean): Long =
        if (shortLadder) SHORT_LADDER_PROBE_BUDGET_MS else PROBE_BUDGET_MS

    /**
     * Probe rungs for a source. Outside exhaustive mode this is exactly
     * [QualityProbePolicy.candidateRatiosForSource]. In exhaustive mode a source the bpp gate
     * would have skipped still gets ONE rung: the safest ratio the policy allows. That rung costs
     * the least quality headroom, so if anything passes on a starved source, it is this one.
     */
    fun candidateRatios(
        defaultRatio: Double,
        sourceBitsPerPixel: Double?,
        exhaustive: Boolean,
        shortLadder: Boolean = false
    ): List<Double> {
        val normal = QualityProbePolicy.candidateRatiosForSource(defaultRatio, sourceBitsPerPixel)
        val rungs = if (!exhaustive || normal.isNotEmpty()) normal else listOf(QualityProbePolicy.SAFEST_RATIO_CEILING)
        return if (shortLadder) trimToShortLadder(rungs) else rungs
    }

    /**
     * Whether a source of these display dimensions may run a probe ladder at all.
     *
     * Up to 1080p: always, as before. Above 1080p: only in exhaustive mode, and only up to the 4K
     * scoring cap ([QualityProbePolicy.isPixelScoreableGeometry]). 8K can never be scored.
     */
    fun probeLadderAllowed(width: Int, height: Int, exhaustive: Boolean): Boolean =
        QualityProbePolicy.isProbeLadderGeometry(width, height) ||
            (exhaustive && QualityProbePolicy.isPixelScoreableGeometry(width, height))

    /** True for geometry that is scoreable but too large for the full ladder: above 1080p, up to 4K. */
    fun needsShortLadder(width: Int, height: Int): Boolean =
        QualityProbePolicy.isPixelScoreableGeometry(width, height) &&
            !QualityProbePolicy.isProbeLadderGeometry(width, height)

    /**
     * The short ladder: at most three rungs, namely the most aggressive rung, the codec default
     * and the retreat rung. The in-between rung is dropped, and the caller also skips the downward
     * bisection after a pass.
     *
     * Why those three. A failing rung is cheap, because the prober stops at the first window below
     * the bar, while a passing rung scores every window. So the aggressive rung costs little to try
     * and is where a high-bit-density camera clip could save the most. The default and retreat
     * rungs keep exactly the top of the normal ladder. That matters because a measured rejection
     * at the HIGHEST rung is what lets the batch skip a file as "would visibly lose quality", and
     * that claim must mean the same thing at 4K as it does at 1080p. Ladders already at three
     * rungs or fewer (starved and very-starved sources) are left unchanged.
     */
    fun trimToShortLadder(rungs: List<Double>): List<Double> =
        if (rungs.size <= 3) rungs else (listOf(rungs.first()) + rungs.takeLast(2)).distinct().sorted()

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
