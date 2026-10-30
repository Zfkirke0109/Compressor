package compress.joshattic.us.quality

/**
 * How far apart the encoder places its keyframes, derived from the source.
 *
 * Media3's DefaultEncoderFactory requests an I-frame every second unless told otherwise
 * (VideoEncoderSettings.DEFAULT_I_FRAME_INTERVAL_SECONDS = 1.0). Every source in the b165 capture
 * (batch_1790280254600, 221 files) carried a 3 s keyframe interval: each structure line read
 * `maxGapMs=3003`, and the two full 30-minute traces showed the same. So every re-encode spent
 * three times the source's share of bits on intra frames. An intra frame costs several P-frames'
 * worth of bits at the same quality, which at 30 fps means a 1 s interval puts roughly 15-20% more
 * of the budget into keyframes than a 3 s one. The P-frames lack those bits, and that is where the
 * Perceptually Lossless gates look: 353 of the 371 failing probe windows in b165 failed on the
 * window mean, at a median bit density of 0.050 bpp where every bit counts.
 *
 * The rule: match the source's typical keyframe interval, never finer than Media3's default and
 * never coarser than [MAX_SECONDS]. Matching keeps the output's seek granularity no worse than the
 * source's. The cap keeps a source with 10 s GOPs (some screen recorders) from producing an output
 * that scrubs badly and that certification would have to decode ten seconds of lead-in for.
 *
 * Probes and full encodes use the same value for the same source, so a probe stays evidence about
 * the encode it vouches for. Pure so the rule is unit-tested.
 */
object KeyframeIntervalPolicy {

    /** Media3's default; never place keyframes closer than this. */
    const val MIN_SECONDS = 1.0f

    /** Upper bound, for sources whose own keyframes are rarer than this. */
    const val MAX_SECONDS = 5.0f

    /** When the source's keyframes could not be indexed: the streaming industry's usual 2 s. */
    const val WHEN_UNKNOWN_SECONDS = 2.0f

    /** The I-frame interval to request for a source whose typical keyframe gap is [typicalSourceGapUs]. */
    fun iFrameIntervalSeconds(typicalSourceGapUs: Long?): Float {
        if (typicalSourceGapUs == null || typicalSourceGapUs <= 0L) return WHEN_UNKNOWN_SECONDS
        val seconds = typicalSourceGapUs / 1_000_000f
        // Tenths of a second, so the common 3003 ms GOP (3 s at 29.97 fps) is asked for as 3.0.
        val rounded = Math.round(seconds * 10f) / 10f
        return rounded.coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    /**
     * The typical gap between consecutive keyframes: the median, so a scene-cut keyframe inside a
     * fixed GOP does not shorten the answer.
     */
    fun typicalGapUs(gapsUs: List<Long>): Long? {
        val positive = gapsUs.filter { it > 0L }.sorted()
        if (positive.isEmpty()) return null
        return positive[positive.size / 2]
    }

    fun describe(seconds: Float): String = "%.1fs".format(java.util.Locale.US, seconds)
}
