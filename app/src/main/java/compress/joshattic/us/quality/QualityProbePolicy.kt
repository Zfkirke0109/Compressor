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

    // ---- Window duration ------------------------------------------------------------------------
    //
    // A window must hold both the calibrated amount of time (1.2 s) and enough frames for its p5 and
    // minimum to mean anything (MIN_COMPARED_FRAMES_PER_WINDOW). At 30 fps 1.2 s is 36 frames; at
    // 10 fps it is 12 at best, and b168 job_478c2fa19100 (1356x760, 10 fps) scored 11 in one window
    // while every window cleared the bar and the selection margin (0.95: 99.24/97.25/97.25,
    // 98.19/95.43/95.43, 97.51/95.08/95.08). The frame count, not the pixels, rejected it, and the
    // ladder then reported that as "no candidate ratio passed" and the file as "would visibly lose
    // quality". The requirement is now met by the window's length: below ~11.7 fps it grows to hold
    // MIN_COMPARED_FRAMES_PER_WINDOW plus two frames of headroom (edge frames and VFR jitter).

    /** The calibrated window length, and the one used whenever the frame rate is unknown. */
    const val BASE_WINDOW_US = 1_200_000L

    /** Frames added above the minimum when a window is sized from the frame rate. */
    const val WINDOW_FRAME_HEADROOM = 2

    /** Longest window: a 3.5 fps time-lapse still gets 14 frames; slower material stays undecided. */
    const val MAX_WINDOW_US = 4_000_000L

    /** Window length for a source of [fps] frames per second; [BASE_WINDOW_US] when unknown. */
    fun windowDurationUs(fps: Double): Long {
        if (!fps.isFinite() || fps <= 0.0) return BASE_WINDOW_US
        val needed = Math.ceil((MIN_COMPARED_FRAMES_PER_WINDOW + WINDOW_FRAME_HEADROOM) * 1_000_000.0 / fps).toLong()
        return needed.coerceIn(BASE_WINDOW_US, MAX_WINDOW_US)
    }

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

    // ---- Probe selection margin ---------------------------------------------------------------
    //
    // How far above the certification bar a PROBE must land before the ladder selects its ratio.
    //
    // Calibrated from b165 (batch_1790280254600, S23 Ultra): 63 windows were scored twice on the
    // same frames, once on the probe clip and once on the finished full encode at the same
    // requested ratio. The full encode landed below the probe by (10th percentile / worst):
    // mean 0.52 / 1.16, 5th percentile 1.25 / 1.76, minimum 0.89 / 3.62 points. Of 23 certified
    // encodes 7 failed, and every one of them had passed its probe by under 0.5 points on some
    // gate, interleaved with passes down to 0.01. A probe pass inside the drift is a coin toss,
    // and a lost toss costs a full encode of a 20-minute file and leaves the original untouched.
    //
    // The margins are the 10th-percentile drift. They are NOT a change to the acceptance bar:
    // certification still judges the real output against PERCEPTUAL_LOSSLESS, unchanged. They
    // change which rung the ladder SELECTS: a rung that clears the bar but not the margin is
    // "marginal", the ladder moves on to the next, safer rung, and only when no rung clears the
    // margin is the highest marginal rung attempted, labelled as such, for certification to decide.
    const val PROBE_SELECTION_MARGIN_MEAN = 0.5
    const val PROBE_SELECTION_MARGIN_P5 = 1.25
    const val PROBE_SELECTION_MARGIN_MIN = 1.0

    val PROBE_SELECTION = QualityBar(
        meanMin = WINDOW_MEAN_MIN + PROBE_SELECTION_MARGIN_MEAN,
        p5Min = WINDOW_P5_MIN + PROBE_SELECTION_MARGIN_P5,
        minMin = WINDOW_MIN_MIN + PROBE_SELECTION_MARGIN_MIN,
        label = "Perceptually Lossless, probe selection margin"
    )

    /**
     * [INSUFFICIENT]: no window with enough frames failed the bar, but at least one window had
     * too few frames to decide. It is not a measured rejection, and never a pass.
     */
    enum class RungVerdict { UNMEASURED, INSUFFICIENT, FAILED, MARGINAL, PASSED }

    private fun clears(w: WindowScore, bar: QualityBar) = w.mean >= bar.meanMin && w.p5 >= bar.p5Min && w.min >= bar.minMin

    /** How a measured rung stands: clears bar and margin, clears only the bar, or fails the bar. */
    fun rungVerdict(scores: List<WindowScore>?): RungVerdict = when {
        scores.isNullOrEmpty() -> RungVerdict.UNMEASURED
        // A window with enough frames below the bar is a measurement, whatever the others hold.
        scores.any { it.comparedFrames >= MIN_COMPARED_FRAMES_PER_WINDOW && !clears(it, PERCEPTUAL_LOSSLESS) } ->
            RungVerdict.FAILED
        scores.any { it.comparedFrames < MIN_COMPARED_FRAMES_PER_WINDOW } -> RungVerdict.INSUFFICIENT
        windowsPass(scores, PROBE_SELECTION) -> RungVerdict.PASSED
        windowsPass(scores) -> RungVerdict.MARGINAL
        else -> RungVerdict.FAILED
    }

    /**
     * The smallest amount, over every window and gate, by which the scores clear the
     * Perceptually Lossless bar. Negative when a gate fails. For log lines: "passed by 0.31".
     */
    fun barMargin(scores: List<WindowScore>): Double = scores.minOf { w ->
        minOf(w.mean - WINDOW_MEAN_MIN, w.p5 - WINDOW_P5_MIN, w.min - WINDOW_MIN_MIN)
    }

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
    // higher-quality rung) may close the gap.
    //
    // Re-estimated from b166, b167 and b168 (2.5 until then). A failing 0.95 was retried at 0.97
    // 122 times and passed 0 times; 94 of those started more than 0.5 short. Near the ceiling the
    // mean gains a median 0.04 VMAF per +0.01 of ratio (90th percentile 0.12), so 0.95 -> 0.97 buys
    // about 0.08 and rarely more than 0.25. Every 0.97 pass in those batches came from a 0.95 that
    // had already passed (the marginal path, which this does not govern). 0.5 keeps the retries a
    // steep file could still close and skips the ~38 per batch that none did. Search cost only: the
    // acceptance bar is untouched.
    const val NEAR_MISS_UPWARD_MARGIN = 0.5

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
    /**
     * When the safest probed rung was MARGINAL (cleared the bar, not the selection margin), one
     * probe at [SAFEST_RATIO_CEILING] is the best chance of a pass that clears the margin too.
     * Null when the rung is already at the ceiling.
     */
    fun upwardMarginCandidate(highestMarginalRatio: Double): Double? =
        if (highestMarginalRatio >= SAFEST_RATIO_CEILING - 1e-9) null else SAFEST_RATIO_CEILING

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
            is PairScoreOutcome.MisalignmentRejected -> false
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
            is PairScoreOutcome.MisalignmentRejected -> false
        }

    /**
     * How to describe a ladder that ended without a proven ratio.
     *
     * The distinction is load-bearing and was previously lost. "no candidate ratio passed" asserts
     * that every rung WAS measured and rejected — positive pixel evidence that the clip cannot be
     * re-encoded transparently. When no rung produced a single scored window, that sentence is
     * false, and a reader (or a threshold calibrated from captures) takes measurement failure for
     * evidence of incompressibility. Across the five 219-file S23 Ultra captures, 84 of 425 ladder
     * runs (19.8%) reported "no candidate ratio passed" having measured nothing at all.
     *
     * Only the wording changes here; no acceptance decision reads this string. The gate that
     * matters — [ProbeDecision.highestCandidateMeasuredRejected], which feeds the probe-skip
     * ratchet — already required a measured rung and is untouched.
     */
    fun ladderExhaustedDetail(
        measured: Int,
        misaligned: Int,
        unavailable: Int,
        unavailableReasons: Map<String, Int> = emptyMap(),
        misalignedReasons: Map<String, Int> = emptyMap()
    ): String {
        // "unmeasurable" alone was still too coarse to act on. The first captures from the fixed
        // 4 ms tolerance showed 28 unavailable rungs, and the count could not distinguish an
        // export that timed out on a 52-minute source from a decoder that failed on a 90-second
        // one — which point at completely different fixes. Naming them costs nothing and is the
        // difference between a capture that poses a question and one that answers it.
        val why = unavailableReasons.entries
            .sortedByDescending { it.value }
            .joinToString("; ") { "${it.value}x ${it.key}" }
        val unavailableDetail = if (why.isEmpty()) "$unavailable unmeasurable" else "$unavailable unmeasurable [$why]"
        // Misalignment needs the same treatment, and for a sharper reason: its two causes are
        // opposite diagnoses. "leading offset not aligned" means the probe pipeline never lined
        // the streams up and the clip was never fairly measured; "internal frame misalignment"
        // means frames really are missing or retimed inside the window. A bare count reads as the
        // second when it may be entirely the first — 13 of 27 ladders in batch_1788254475481
        // ended here with no way to tell.
        val misWhy = misalignedReasons.entries
            .sortedByDescending { it.value }
            .joinToString("; ") { "${it.value}x ${it.key}" }
        val misalignedDetail =
            if (misWhy.isEmpty()) "$misaligned not time-alignable" else "$misaligned not time-alignable [$misWhy]"
        return when {
            measured > 0 && (misaligned + unavailable) == 0 -> "no candidate ratio passed"
            measured > 0 -> "no candidate ratio passed (of ${measured + misaligned + unavailable} rungs, " +
                "$measured measured; $misalignedDetail, $unavailableDetail)"
            (misaligned + unavailable) == 0 -> "no candidate ratio passed"
            else -> "no probe rung could be measured ($misalignedDetail, " +
                "$unavailableDetail) — nothing was scored, so this is NOT evidence the clip " +
                "resists re-encoding"
        }
    }

    /**
     * Probe windows for a clip of [durationUs]: up to three short windows away from the very
     * start/end (codec warm-up and tail padding are unrepresentative). Short clips get one
     * centered window. Returns an empty list when the clip is too short to sample honestly.
     */
    fun probeWindows(durationUs: Long, windowUs: Long = BASE_WINDOW_US): List<ScoreWindow> {
        if (durationUs < 2_000_000L) return emptyList()
        // A frame-rate-sized window (windowDurationUs) can be longer than a short clip.
        if (windowUs >= durationUs) return emptyList()
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
