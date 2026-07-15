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

    // The probe ladder may never target below this ratio no matter what windows say:
    // sampled windows are evidence, not proof, and the measured suite has nothing below
    // 0.60 that survived even partially.
    const val HARD_RATIO_FLOOR = 0.60

    // Probe fast path: sources this far below the transparency gate (0.08 bpp) failed
    // unanimously in both the 2026-07-14 PC suite and the on-device probe reruns (21/21
    // probe rejections in capture batch_1784095235729, worst measured bpp that ever came
    // close was 0.079). Skipping the ladder for them saves minutes per batch with no
    // evidence lost; they keep the conservative inference decision.
    const val PROBE_MIN_SOURCE_BITS_PER_PIXEL = 0.05

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
