package compress.joshattic.us.quality

import java.security.MessageDigest
import java.util.Locale

/**
 * The encoder request a probe or a full encode was made with, as a short stable hash. Two
 * encodes with the same identity asked the encoder for the same thing: output codec, rate-control
 * mode, keyframe interval, B-frame request and video bitrate. A probe is evidence about a full
 * encode only when the two identities match (b177 F2: the retry's record joined .85 probe windows
 * to a .90 encode).
 *
 * The identity describes the REQUEST. Whether the encoder honoured it is recorded separately
 * (EncoderConfigDelta); an identity match is necessary for a join, never proof of equal output.
 */
object EncodeConfigIdentity {
    fun canonical(outputMime: String, bitrateMode: String, iFrameIntervalSeconds: Float, maxBFrames: Int, videoBitrate: Int): String =
        String.format(
            Locale.US, "mime=%s;mode=%s;gop=%.3f;bframes=%d;vbr=%d",
            outputMime, bitrateMode, iFrameIntervalSeconds, maxBFrames, videoBitrate
        )

    fun of(outputMime: String, bitrateMode: String, iFrameIntervalSeconds: Float, maxBFrames: Int, videoBitrate: Int): String =
        hash(canonical(outputMime, bitrateMode, iFrameIntervalSeconds, maxBFrames, videoBitrate))

    fun of(outputMime: String, shape: ProbeEncodeShape, videoBitrate: Int): String =
        of(outputMime, if (shape.isCbr) "CBR" else "VBR", shape.iFrameIntervalSeconds, shape.maxBFrames, videoBitrate)

    /** First 12 hex digits of SHA-256: enough to tell configurations apart within a capture. */
    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it) }
            .substring(0, 12)
}

/** One planned window on the SOURCE timeline. Its id joins probe and certification windows. */
data class WindowRef(val startUs: Long, val endUs: Long) {
    val id: String get() = "${startUs}-${endUs}"
}

/**
 * Everything one probe rung produced, frozen when the rung finished (b177 F2). The plan keeps one
 * per rung the ladder tried, in order, so a later attempt at another ratio (the safer-rung retry)
 * carries the evidence OF THAT RATIO instead of the proven rung's with a changed ratio.
 *
 * [windows] are the planned windows the rung attempted, in order; [scores] are aligned with the
 * first `scores.size` of them (a failing window stops the rung early). Scores are full precision.
 */
data class RungEvidence(
    val ratio: Double,
    /** "measured", "misaligned" or "unavailable". */
    val outcome: String,
    /** QualityProbePolicy.RungVerdict name (lower case) when measured; null otherwise. */
    val verdict: String?,
    val requestedVideoBitrate: Int,
    val configId: String,
    val windows: List<WindowRef>,
    val scores: List<WindowScore>,
    val rateFactors: List<Double>,
    val rateDiag: String?,
    /** Wall time the rung took, monotonic clock. */
    val elapsedMs: Long,
    val reason: String? = null,
    val encoderNames: List<String> = emptyList()
) {
    val id: String get() = String.format(Locale.US, "%.2f@%s", ratio, configId)

    val measured: Boolean get() = outcome == MEASURED

    /** The ids of the windows that were actually scored, aligned with [scores]. */
    val scoredWindowIds: List<String> get() = windows.take(scores.size).map { it.id }

    companion object {
        const val MEASURED = "measured"
        const val MISALIGNED = "misaligned"
        const val UNAVAILABLE = "unavailable"

        /** The last measured rung at exactly [ratio], or null when none was measured there. */
        fun measuredAt(rungs: List<RungEvidence>, ratio: Double): RungEvidence? =
            rungs.lastOrNull { it.measured && kotlin.math.abs(it.ratio - ratio) < 1e-9 }
    }
}
