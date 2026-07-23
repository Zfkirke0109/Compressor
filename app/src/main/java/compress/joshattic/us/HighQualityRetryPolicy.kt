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
     * True when High Quality would actually make this source meaningfully smaller — its target
     * video bitrate clears the [MIN_HEADROOM_FRACTION] margin below the source. Unknown source
     * bitrate fails closed (no honest estimate is possible).
     */
    fun highQualityWouldShrink(sourceVideoBitrate: Int, sourceHeight: Int): Boolean {
        if (sourceVideoBitrate <= 0) return false
        val target = highQualityTargetVideoBitrate(sourceVideoBitrate, sourceHeight)
        if (target <= 0) return false
        return target <= sourceVideoBitrate * (1.0 - MIN_HEADROOM_FRACTION)
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
        sourceHeight: Int
    ): Boolean =
        batchWasPerceptualLossless &&
            terminalIsRetryCandidate(terminal) &&
            highQualityWouldShrink(sourceVideoBitrate, sourceHeight)
}
