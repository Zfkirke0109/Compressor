package compress.joshattic.us.quality

import androidx.media3.common.MimeTypes
import compress.joshattic.us.VideoSourceInfo

/** HEVC failing its own measurements says nothing about a same-codec AVC encode. */
object PerceptualCodecFallbackPolicy {
    // One additional codec, at most three rungs, no downward refinement. The ladder's budget
    // is a soft bound (a running rung and the final safest-rung measurement can finish).
    const val PROBE_BUDGET_MS = 90_000L
    val CANDIDATE_RATIOS: List<Double> = listOf(0.85, 0.95, QualityProbePolicy.SAFEST_RATIO_CEILING)

    fun shouldProbeAvc(
        source: VideoSourceInfo,
        autoCodec: Boolean,
        exhaustive: Boolean,
        primaryMime: String,
        primaryMeasuredRejected: Boolean,
        primaryPredictedNotSmaller: Boolean,
        sourceParseFailed: Boolean
    ): Boolean = autoCodec && exhaustive &&
        source.videoMime == MimeTypes.VIDEO_H264 && primaryMime == MimeTypes.VIDEO_H265 &&
        !source.isHdr && QualityProbePolicy.isProbeLadderGeometry(source.width, source.height) &&
        !sourceParseFailed && (primaryMeasuredRejected || primaryPredictedNotSmaller)

    /** A fallback can run a full encode only after its OWN measured pass AND size gate. */
    fun canSelect(pixelProvenRatio: Double?, preferRemux: Boolean, skipReason: String?, sizeGateVerdict: String?): Boolean =
        pixelProvenRatio != null && pixelProvenRatio.isFinite() &&
            pixelProvenRatio in QualityProbePolicy.HARD_RATIO_FLOOR..QualityProbePolicy.SAFEST_RATIO_CEILING &&
            !preferRemux && skipReason == null && sizeGateVerdict == "encode"
}
