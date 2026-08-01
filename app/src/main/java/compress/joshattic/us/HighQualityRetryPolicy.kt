package compress.joshattic.us

/**
 * Pure decision logic for the post-batch "shrink these with High Quality?" offer.
 *
 * After a Perceptually Lossless batch, many videos are honestly kept at their original bytes —
 * either already efficient, remux-preferred by evidence, or re-encoded without a meaningful win.
 * High Quality is the honest lossy lever that CAN make those smaller (it targets a lower bitrate
 * at the same resolution and frame rate). But HQ has resolution-based bitrate floors, so an
 * already low-bitrate source cannot actually shrink under HQ either. Offering "shrink this" on a
 * file HQ physically cannot shrink would be a false promise.
 *
 * This object decides which kept-original items are worth offering an HQ retry: the terminal must
 * be a genuine keep-original (not a failure, cancel, explicit remux choice, or one of our own
 * outputs), AND HQ's computed target bitrate must sit meaningfully below the source. Both checks
 * are pure and unit-testable with no Android or device dependencies.
 *
 * The HQ target formula mirrors [BatchQualityBitratePolicy.calculateVideoBitrate]'s HIGH_QUALITY
 * branch for the retry's shape (keep resolution + source FPS, so the fps/height scales are 1).
 * A cross-codec HQ encode (e.g. H.264 -> HEVC) only shrinks MORE than this same-codec estimate,
 * so gating on the same-codec target is deliberately conservative: it never over-promises.
 */
object HighQualityRetryPolicy {

    // The nominal High Quality bitrate ratio (see BatchQualityBitratePolicy HIGH_QUALITY branch).
    const val HIGH_QUALITY_RATIO = 0.72

    // Minimum fractional video-bitrate reduction for an HQ retry to be worth offering. HQ must
    // target at or below (1 - this) * source video bitrate, or its floor has eaten the saving and
    // the file would not get meaningfully smaller. 0.15 keeps the offer to clearly worthwhile wins.
    const val MIN_HEADROOM_FRACTION = 0.15

    // Large-file escape hatch. A modest RELATIVE reduction on a big file is still a big win, and
    // the 0.15 bar alone silently dropped those. Measured on the 2026-07-31 S23 batch: a 471.9 MB
    // 4K clip (13.49 Mbps video, floor-clamped target 12 Mbps = 89.0% of source) fell just under
    // the relative bar at 11.0% predicted, yet really saved 50.7 MB. So an offer also qualifies
    // when the PREDICTED absolute saving is substantial and the relative cut is still non-trivial.
    //
    // These are OFFER-POLICY knobs only: they decide which already-kept files we SUGGEST re-running
    // in High Quality. They do not move any bitrate floor, ratio, or encode target — a file that is
    // offered still encodes under exactly the same quality policy as before.
    const val MIN_ABSOLUTE_SAVING_BYTES = 25L * 1024L * 1024L
    // Floor for the absolute path: never trade real quality + minutes of encoding for a trivial
    // relative cut just because the source file happens to be huge.
    const val MIN_HEADROOM_FRACTION_FOR_LARGE_FILES = 0.10

    /**
     * High Quality's target video bitrate for a same-resolution, same-FPS retry, in bits/sec.
     * Mirrors the encoder's HIGH_QUALITY branch but GUARDS the floor against exceeding the source
     * (the production coerceIn(floor, source) throws when floor > source; here that simply yields
     * the source bitrate, i.e. "no shrink"). Returns 0 when the source video bitrate is unknown.
     */
    fun highQualityTargetVideoBitrate(sourceVideoBitrate: Int, sourceHeight: Int): Int {
        if (sourceVideoBitrate <= 0) return 0
        val floor = when {
            sourceHeight >= 2160 -> 12_000_000
            sourceHeight >= 1440 -> 8_000_000
            sourceHeight >= 1080 -> 5_000_000
            sourceHeight >= 720 -> 2_500_000
            else -> 1_000_000
        }
        val target = (sourceVideoBitrate * HIGH_QUALITY_RATIO).toInt()
        val effectiveFloor = minOf(floor, sourceVideoBitrate)
        return target.coerceIn(effectiveFloor, sourceVideoBitrate)
    }

    /**
     * The fraction of the source video bitrate High Quality would cut for a same-resolution,
     * same-FPS retry: 0.0 when the floor has eaten the entire saving (target == source), up to
     * ~0.28 for healthy sources. Returns 0.0 when the source bitrate is unknown.
     */
    fun predictedSavingFraction(sourceVideoBitrate: Int, sourceHeight: Int): Double {
        if (sourceVideoBitrate <= 0) return 0.0
        val target = highQualityTargetVideoBitrate(sourceVideoBitrate, sourceHeight)
        if (target <= 0) return 0.0
        return (1.0 - target.toDouble() / sourceVideoBitrate.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * True when High Quality would actually make this source meaningfully smaller. Qualifies two
     * ways: a clearly worthwhile RELATIVE cut ([MIN_HEADROOM_FRACTION]), or a still-non-trivial cut
     * ([MIN_HEADROOM_FRACTION_FOR_LARGE_FILES]) that yields a substantial ABSOLUTE saving
     * ([MIN_ABSOLUTE_SAVING_BYTES]) because the file is large.
     *
     * Note both paths key off the same predicted fraction, so a source whose floor leaves NO
     * headroom (fraction 0.0 — exactly the case that produces bigger-than-source output) can never
     * qualify through either path, no matter how large the file is. Unknown source bitrate or size
     * fails closed: without an honest estimate we do not promise a shrink.
     */
    fun highQualityWouldShrink(sourceVideoBitrate: Int, sourceHeight: Int, sourceSizeBytes: Long): Boolean {
        val fraction = predictedSavingFraction(sourceVideoBitrate, sourceHeight)
        if (fraction <= 0.0) return false
        if (fraction >= MIN_HEADROOM_FRACTION) return true
        if (sourceSizeBytes <= 0L) return false
        val predictedSaving = sourceSizeBytes * fraction
        return fraction >= MIN_HEADROOM_FRACTION_FOR_LARGE_FILES &&
            predictedSaving >= MIN_ABSOLUTE_SAVING_BYTES
    }

    /**
     * True only for terminals that mean "kept the original, and a High Quality retry is a sensible
     * offer": no real compression happened, it wasn't a failure, a cancel, an explicit Remux Only
     * choice, or one of our own prior outputs. SKIPPED_WOULD_DEGRADE is deliberately EXCLUDED — the
     * app specifically measured visible quality loss there, so auto-suggesting a lossy re-encode on
     * exactly those files works against the honesty ethos (the user can still pick them manually).
     */
    fun terminalIsRetryCandidate(terminal: BatchTerminalResult?): Boolean {
        if (terminal == null) return false
        if (terminal.countsAsRealCompression) return false
        if (terminal.isFailure) return false
        return when (terminal) {
            BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER,
            BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED,
            BatchTerminalResult.REMUX_PREFERRED_BY_EVIDENCE,
            BatchTerminalResult.UNEXPECTED_REMUX -> true
            else -> false
        }
    }

    /**
     * The full eligibility gate for one item's post-batch HQ retry offer. Only fires when the batch
     * ran in Perceptually Lossless (offering HQ after HQ or a lossy mode makes no sense), the
     * terminal is a genuine keep-original candidate, and HQ would actually shrink the source.
     */
    fun isEligible(
        batchWasPerceptualLossless: Boolean,
        terminal: BatchTerminalResult?,
        sourceVideoBitrate: Int,
        sourceHeight: Int,
        sourceSizeBytes: Long
    ): Boolean =
        batchWasPerceptualLossless &&
            terminalIsRetryCandidate(terminal) &&
            highQualityWouldShrink(sourceVideoBitrate, sourceHeight, sourceSizeBytes)
}
