package compress.joshattic.us.quality

/**
 * Pure decision logic for pixel-evidence-based Perceptually Lossless targeting.
 *
 * Calibration comes from the 2026-07-14 VMAF suite (validation\vmaf_analysis\
 * PIXEL_QUALITY_REPORT.md). Full-clip PL thresholds are VMAF mean >= 95 / 1%-low >= 90 /
 * min >= 80. Probe windows sample only part of a clip, so the window thresholds carry a
 * safety margin above the full-clip thresholds: a clip must look BETTER than the bar inside
 * its sampled windows before the ladder trusts a lower ratio.
 *
 * No side effects, no Android dependencies: fully unit-testable.
 */
object QualityProbePolicy {

    // Window-level acceptance thresholds (stricter than the full-clip suite thresholds).
    const val WINDOW_MEAN_MIN = 95.5
    const val WINDOW_P5_MIN = 91.0
    const val WINDOW_MIN_MIN = 84.0
    const val MIN_COMPARED_FRAMES_PER_WINDOW = 12

    /**
     * A per-window acceptance bar: the three VMAF thresholds a window must clear.
     *
     * Exists so the probe/certification machinery can serve more than one quality target. The
     * numbers themselves are NOT interchangeable — see the two instances below. Every existing
     * caller keeps using [PERCEPTUAL_LOSSLESS], whose values are unchanged.
     */
    data class QualityBar(
        val meanMin: Double,
        val p5Min: Double,
        val minMin: Double,
        val label: String
    )

    /**
     * The transparency bar. Calibrated against the 2026-07-14 VMAF suite and confirmed by the v2
     * study (`research/perceptual_calibration`), which found current production sits inside the
     * cross-fold consensus box and stays tied-optimal in 100% of bootstrap resamples.
     *
     * Do not move these without a fresh calibration round. They are the meaning of the word
     * "lossless" in this app's user-facing labels.
     */
    val PERCEPTUAL_LOSSLESS = QualityBar(
        meanMin = WINDOW_MEAN_MIN,
        p5Min = WINDOW_P5_MIN,
        minMin = WINDOW_MIN_MIN,
        label = "Perceptually Lossless"
    )

    /**
     * The High Quality bar: an explicitly LOSSY target, deliberately below transparency.
     *
     * Why it exists: HQ currently targets a flat 0.72 bitrate ratio with no quality measurement at
     * all, so "High Quality" names a bitrate cut rather than a quality level. A static talking head
     * and a 4K60 handheld motion clip get the same ratio and land nowhere near each other. Giving
     * HQ a measurable bar lets it mean "the smallest file that still clears this", which is
     * consistent in quality instead of consistent in ratio.
     *
     * **These three numbers are provisional and NOT yet calibrated.** They are offset from the
     * transparency bar by roughly the same margin that bar carries over the full-clip suite
     * thresholds, which is a reasoned starting point and nothing more. They must be validated
     * against subjective checks before HQ's user-facing wording leans on them. Crucially, nothing
     * about this bar may ever be described as lossless or transparent: HQ stays labelled lossy
     * whatever it measures.
     */
    val HIGH_QUALITY = QualityBar(
        meanMin = 93.0,
        p5Min = 88.0,
        minMin = 80.0,
        label = "High Quality"
    )

    /**
     * True only when a source of these display dimensions can actually be pixel-scored by
     * [VmafPairScorer] (its geometry is at or below the 4K-class scoring cap). Above the cap the
     * scorer returns Unavailable, so the output can only ever be accepted structurally and can
     * never earn the pixel-certified label. Pure so it is unit-testable without a device.
     */
    fun isPixelScoreableGeometry(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= VmafPairScorer.MAX_COMPARE_PIXELS

    /**
     * Geometry ceiling for spending PROBE LADDER encodes, as distinct from scoring pixels once.
     *
     * Post-encode certification scores three ~1.2 s windows of an output that already exists. The
     * ladder additionally *encodes* a real clip per rung — up to four rungs x three windows — and
     * scores every one of them. At 4K that work does not fit inside
     * `PerceptualQualityProber.TOTAL_BUDGET_MS`, so the ladder would time out mid-search and return
     * "no evidence" after having burned the battery anyway.
     *
     * So above this bar the plan keeps its codec-default/learned ratio and skips the ladder, but
     * still runs certification — which is what actually unlocks the pixel-proven label.
     */
    const val PROBE_LADDER_MAX_PIXELS = 1920 * 1088

    /** True when a probe ladder is both scoreable and affordable at these dimensions. */
    fun isProbeLadderGeometry(width: Int, height: Int): Boolean =
        isPixelScoreableGeometry(width, height) &&
            width.toLong() * height.toLong() <= PROBE_LADDER_MAX_PIXELS

    // The probe ladder may never target below this ratio no matter what windows say:
    // sampled windows are evidence, not proof, and the measured suite has nothing below
    // 0.60 that survived even partially.
    const val HARD_RATIO_FLOOR = 0.60

    // The safest rung the ladder may retreat to after the default ratio fails its windows.
    // Above ~0.97 the video-bitrate saving disappears into container/measurement noise, so a
    // "pass" there would not produce a genuinely smaller file on same-codec encodes (cross-
    // codec encodes still gain from codec efficiency, which the bitrate policy accounts for).
    const val SAFEST_RATIO_CEILING = 0.97

    // Probe fast path: sources below this video bits-per-pixel never earn probe encodes.
    // Measured basis: every pair below 0.05 bpp failed unanimously at ratios <= 0.90 (21/21
    // rejections in capture batch_1784095235729 plus the 2026-07-14 PC suite). Between 0.03
    // and 0.08 the ladder now retreats to safer-than-default rungs first (those were never
    // measured before), so the hard cut-off moves down to 0.03: below that, even a 0.97-ratio
    // pass would save less than file-size measurement noise on such starved sources.
    const val PROBE_MIN_SOURCE_BITS_PER_PIXEL = 0.03

    // Sources at or above this bpp carry plausible transparency headroom (the policy gate
    // value in BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MIN_SOURCE_BITS_PER_PIXEL);
    // they earn the full downward ladder. Below it the ladder is safest-rungs-only.
    const val PROBE_HEALTHY_SOURCE_BITS_PER_PIXEL = 0.08

    // One bounded bisection refinement between the lowest passing rung and the rung below it
    // is only worth an extra probe encode when the gap is at least this wide.
    const val REFINEMENT_MIN_GAP = 0.06

    // Upward near-miss refinement: when the SAFEST probed rung fails but its worst window is within
    // this many VMAF points of the bars, one more probe at [SAFEST_RATIO_CEILING] (a higher, safer,
    // higher-quality rung) may close the gap. Kept small so the extra encode is spent only when a
    // pass is plausible — a rung that failed by a lot will not be rescued by a slightly higher one.
    const val NEAR_MISS_UPWARD_MARGIN = 2.5

    // Full-encode certification uses the same window thresholds; a certified sub-default
    // encode must PROVE its windows, an unmeasurable certification fails closed only when
    // the ratio was below the codec default (pixel evidence was the sole justification).

    /** Candidate ratios for the probe ladder, lowest (most savings) first. */
    fun candidateRatios(defaultRatio: Double, allowBelowDefault: Boolean): List<Double> {
        if (!allowBelowDefault) return listOf(defaultRatio)
        val ladder = listOf(
            (defaultRatio - 0.20).coerceAtLeast(HARD_RATIO_FLOOR),
            (defaultRatio - 0.10).coerceAtLeast(HARD_RATIO_FLOOR),
            defaultRatio
        )
        return ladder.distinct().sorted()
    }

    /**
     * Bpp-aware probe ladder, lowest (most savings) first. Healthy sources (bpp >=
     * [PROBE_HEALTHY_SOURCE_BITS_PER_PIXEL]) get the full downward ladder PLUS one
     * safer-than-default retreat rung, so a clip that fails at the default ratio can still
     * earn a small verified saving instead of being abandoned. Starved-but-probeable sources
     * (bpp in [PROBE_MIN_SOURCE_BITS_PER_PIXEL, healthy)) get safest-biased rungs only —
     * the measured evidence says their low rungs always fail, so probing them wastes encodes.
     * Sources below [PROBE_MIN_SOURCE_BITS_PER_PIXEL] get no ladder (empty list).
     */
    fun candidateRatiosForSource(defaultRatio: Double, sourceBitsPerPixel: Double?): List<Double> {
        val bpp = sourceBitsPerPixel ?: return emptyList()
        if (bpp < PROBE_MIN_SOURCE_BITS_PER_PIXEL) return emptyList()
        // Rungs are rounded to 2 decimals: IEEE drift (0.90 + 0.05 = 0.9500000000000001)
        // otherwise leaks into logs, structured records, and dedup comparisons.
        fun rung(v: Double) = Math.round(v * 100.0) / 100.0
        val retreatRung = rung((defaultRatio + 0.05).coerceAtMost(SAFEST_RATIO_CEILING))
        if (bpp < PROBE_HEALTHY_SOURCE_BITS_PER_PIXEL) {
            // First-ever measured rungs for this class: default plus the safest retreat.
            return listOf(rung(defaultRatio), retreatRung).distinct().sorted()
        }
        val ladder = listOf(
            rung((defaultRatio - 0.20).coerceAtLeast(HARD_RATIO_FLOOR)),
            rung((defaultRatio - 0.10).coerceAtLeast(HARD_RATIO_FLOOR)),
            rung(defaultRatio),
            retreatRung
        )
        return ladder.distinct().sorted()
    }

    /**
     * One bounded bisection refinement: when the lowest passing rung sits at least
     * [REFINEMENT_MIN_GAP] above the rung that failed below it (or above the hard floor when
     * nothing below was tried), the midpoint is worth one extra probe. Returns null when the
     * gap is too narrow to matter. Never returns a value below [HARD_RATIO_FLOOR].
     */
    fun refinementCandidate(lowestPassingRatio: Double, highestFailedBelow: Double?): Double? {
        val lowerBound = (highestFailedBelow ?: HARD_RATIO_FLOOR).coerceAtLeast(HARD_RATIO_FLOOR)
        if (lowestPassingRatio - lowerBound < REFINEMENT_MIN_GAP) return null
        val midpoint = (lowestPassingRatio + lowerBound) / 2.0
        return (Math.round(midpoint * 100.0) / 100.0).coerceAtLeast(HARD_RATIO_FLOOR)
    }

    /**
     * How far the WORST window falls below the acceptance bars, in VMAF points: > 0 means it fails
     * by that margin, <= 0 means every window passes. Null when there is nothing measured. Used to
     * decide whether a near-miss is worth one more (higher, safer) probe.
     */
    fun worstWindowShortfall(scores: List<WindowScore>?): Double? {
        if (scores.isNullOrEmpty()) return null
        return scores.maxOf {
            maxOf(WINDOW_MEAN_MIN - it.mean, WINDOW_P5_MIN - it.p5, WINDOW_MIN_MIN - it.min)
        }
    }

    /**
     * When the safest probed rung FAILED but only just, one more probe at [SAFEST_RATIO_CEILING] may
     * close the gap — a higher ratio keeps more bitrate and buys a few VMAF points. Returns that
     * ceiling ratio, or null when: the rung is already at/above the ceiling (nowhere higher to go),
     * it actually passed (nothing to refine), or it failed by more than [NEAR_MISS_UPWARD_MARGIN]
     * (a slightly higher rung will not rescue it, so the extra encode would be wasted).
     *
     * This is honest: it only ever tries a MORE conservative, higher-quality rung. Any pass is still
     * gated downstream by the strictly-smaller and minimum-savings checks, so it can never turn a
     * non-saving or degrading re-encode into a claimed win — only recover a genuine near-transparent
     * saving (typically a cross-codec H.264 -> HEVC clip) that the fixed ladder stopped just short of.
     */
    fun upwardRefinementCandidate(highestFailedRatio: Double, highestFailedScores: List<WindowScore>?): Double? {
        if (highestFailedRatio >= SAFEST_RATIO_CEILING - 1e-9) return null
        val shortfall = worstWindowShortfall(highestFailedScores) ?: return null
        if (shortfall <= 0.0) return null
        if (shortfall > NEAR_MISS_UPWARD_MARGIN) return null
        return SAFEST_RATIO_CEILING
    }

    /**
     * True when every window individually clears [bar].
     *
     * The no-bar overload below keeps every existing caller on [PERCEPTUAL_LOSSLESS], so adding a
     * second bar cannot silently move the transparency decision.
     */
    fun windowsPass(scores: List<WindowScore>?, bar: QualityBar): Boolean {
        if (scores.isNullOrEmpty()) return false
        return scores.all {
            it.comparedFrames >= MIN_COMPARED_FRAMES_PER_WINDOW &&
                it.mean >= bar.meanMin &&
                it.p5 >= bar.p5Min &&
                it.min >= bar.minMin
        }
    }

    /** True when every window clears the Perceptually Lossless bar. */
    fun windowsPass(scores: List<WindowScore>?): Boolean = windowsPass(scores, PERCEPTUAL_LOSSLESS)

    /**
     * Certification verdict for a completed full encode.
     *
     * @param usedRatio ratio the encode actually used
     * @param defaultRatio the codec-appropriate default ratio
     * @param scores sampled window scores of the final output vs the source, or null when
     *   measurement was not possible
     * @return true when the PL verdict may stand
     *
     * Rules:
     *  - measured and passing -> certified
     *  - measured and failing -> NOT certified (regardless of ratio)
     *  - unmeasurable at the default ratio -> certified structurally (pre-pixel behavior;
     *    pixel scoring is an upgrade, not a new requirement for the legacy path)
     *  - unmeasurable below the default ratio -> NOT certified (the sub-default target was
     *    justified only by pixel evidence, so its absence fails closed)
     */
    fun certificationPasses(usedRatio: Double, defaultRatio: Double, scores: List<WindowScore>?): Boolean {
        if (scores != null) return windowsPass(scores)
        return usedRatio >= defaultRatio - 1e-9
    }

    /**
     * Certification verdict for a tri-state scoring outcome. Measured misalignment is
     * POSITIVE evidence the output's frames are not temporally comparable to the source
     * (frame loss or retiming) — it always fails, and is never eligible for the structural
     * default-ratio fallback that covers merely-unavailable evidence.
     */
    /**
     * True ONLY when sampled pixels actually certified this output.
     *
     * Deliberately NOT the same thing as "certification passed": [certificationOutcomePasses]
     * returns true for [PairScoreOutcome.Unavailable] at or above the default ratio via the
     * structural fallback, which is an honest ACCEPTANCE but is NOT pixel evidence. It is also not
     * the same as probe eligibility — an eligible item whose certification produced no measured
     * windows was still accepted structurally. Only a passing [PairScoreOutcome.Scored] means real
     * measured windows backed the result, so only that may wear the full perceptual label (QUAL-001).
     */
    fun isPixelCertified(certificationPassed: Boolean, outcome: PairScoreOutcome): Boolean =
        certificationPassed && outcome is PairScoreOutcome.Scored

    fun certificationOutcomePasses(usedRatio: Double, defaultRatio: Double, outcome: PairScoreOutcome): Boolean =
        when (outcome) {
            is PairScoreOutcome.Scored -> windowsPass(outcome.windows)
            PairScoreOutcome.Unavailable -> certificationPasses(usedRatio, defaultRatio, null)
            PairScoreOutcome.MisalignmentRejected -> false
        }

    /**
     * Certification verdict for an encode whose target ratio was NOT justified by probe pixels —
     * the case for sources above [PROBE_LADDER_MAX_PIXELS], where no ladder ever runs and the
     * target comes from the codec default plus the learning engine's floor-clamped ratio.
     *
     * Measured evidence still rules in the negative direction, exactly as everywhere else:
     *  - measured and passing -> certified (and, being [PairScoreOutcome.Scored], pixel-proven)
     *  - measured and failing -> NOT certified
     *  - measured misalignment -> NOT certified
     *  - unmeasurable -> the structural verdict stands, because no part of this encode's target
     *    depended on pixel evidence in the first place. This is the pre-existing behavior for
     *    these sources, so enabling certification for them can only ADD proof, never withdraw an
     *    acceptance the app already grants today.
     *
     * The ratio-aware [certificationOutcomePasses] must keep being used wherever a ladder DID run:
     * there a sub-default target was justified by pixels alone, so their absence has to fail closed.
     */
    fun certificationOutcomePassesWithoutProbeBasis(outcome: PairScoreOutcome): Boolean =
        when (outcome) {
            is PairScoreOutcome.Scored -> windowsPass(outcome.windows)
            PairScoreOutcome.Unavailable -> true
            PairScoreOutcome.MisalignmentRejected -> false
        }

    /**
     * Probe windows for a clip of [durationUs]: up to three short windows away from the very
     * start/end (codec warm-up and tail padding are unrepresentative). Short clips get one
     * centered window. Returns an empty list when the clip is too short to sample honestly.
     */
    fun probeWindows(durationUs: Long, windowUs: Long = 1_200_000L): List<ScoreWindow> {
        if (durationUs < 2_000_000L) return emptyList()
        if (durationUs < 10_000_000L) {
            val start = (durationUs - windowUs) / 2
            return listOf(ScoreWindow(start, start + windowUs))
        }
        return listOf(0.20, 0.50, 0.80).map { fraction ->
            val start = (durationUs * fraction).toLong().coerceIn(0L, durationUs - windowUs)
            ScoreWindow(start, start + windowUs)
        }
    }
}
