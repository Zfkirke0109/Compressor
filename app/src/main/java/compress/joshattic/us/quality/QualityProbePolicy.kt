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
     * True only when a source of these display dimensions can actually be pixel-scored by
     * [VmafPairScorer] (its geometry is at or below the 1080p-class scoring cap). Above the cap the
     * scorer returns Unavailable AFTER the probe clip has already been encoded — so probing such a
     * source burns full-resolution encodes for zero pixel evidence and then falls back to structural
     * verification anyway. Gating probe eligibility on this predicate skips that doomed work with no
     * change to the eventual acceptance decision. Pure so it is unit-testable without a device.
     */
    fun isPixelScoreableGeometry(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= VmafPairScorer.MAX_COMPARE_PIXELS

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

    /** True when every window individually clears the acceptance thresholds. */
    fun windowsPass(scores: List<WindowScore>?): Boolean {
        if (scores.isNullOrEmpty()) return false
        return scores.all {
            it.comparedFrames >= MIN_COMPARED_FRAMES_PER_WINDOW &&
                it.mean >= WINDOW_MEAN_MIN &&
                it.p5 >= WINDOW_P5_MIN &&
                it.min >= WINDOW_MIN_MIN
        }
    }

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
