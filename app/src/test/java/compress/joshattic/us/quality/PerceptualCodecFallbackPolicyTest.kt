package compress.joshattic.us.quality

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import compress.joshattic.us.VideoSourceInfo
import org.junit.Assert.*
import org.junit.Test

class PerceptualCodecFallbackPolicyTest {
    private val source = VideoSourceInfo(width = 1920, height = 1080, frameRate = 30f,
        videoMime = MimeTypes.VIDEO_H264, totalBitrate = 4_000_000)

    private fun eligible(s: VideoSourceInfo = source, auto: Boolean = true, exhaustive: Boolean = true,
        rejected: Boolean = true, notSmaller: Boolean = false, parseFailed: Boolean = false,
        primary: String = MimeTypes.VIDEO_H265) = PerceptualCodecFallbackPolicy.shouldProbeAvc(
            s, auto, exhaustive, primary, rejected, notSmaller, parseFailed)

    @Test fun aMeasuredHevcFailureOrSizeRejectionMayTryTheSourceCodec() {
        assertTrue(eligible())
        assertTrue(eligible(rejected = false, notSmaller = true))
        assertTrue(eligible(source.copy(width = 1080, height = 1920)))
        assertTrue(eligible(source.copy(totalBitrate = 200_000))) // No inherited bpp veto.
    }

    @Test fun aWinningOrUnmeasuredPrimaryDoesNotSpendAnotherLadder() {
        assertFalse(eligible(rejected = false))
        assertFalse(eligible(parseFailed = true))
    }

    @Test fun manualAndFastRequestsNeverChangeCodec() {
        assertFalse(eligible(auto = false))
        assertFalse(eligible(exhaustive = false))
        assertFalse(eligible(primary = MimeTypes.VIDEO_H264))
    }

    @Test fun unsupportedSourcesNeverExpandTheQualityClaim() {
        assertFalse(eligible(source.copy(videoMime = MimeTypes.VIDEO_H265)))
        assertFalse(eligible(source.copy(videoMime = MimeTypes.VIDEO_AV1)))
        assertFalse(eligible(source.copy(colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084)))
        assertFalse(eligible(source.copy(colorTransfer = MediaFormat.COLOR_TRANSFER_HLG)))
        assertFalse(eligible(source.copy(width = 3840, height = 2160)))
        assertFalse(eligible(source.copy(width = 0)))
    }

    @Test fun onlyAnOwnMeasuredPassingSmallerPlanCanBeSelected() {
        assertTrue(PerceptualCodecFallbackPolicy.canSelect(0.95, false, null, "encode"))
        assertFalse(PerceptualCodecFallbackPolicy.canSelect(null, false, null, "encode"))
        assertFalse(PerceptualCodecFallbackPolicy.canSelect(Double.NaN, false, null, "encode"))
        assertFalse(PerceptualCodecFallbackPolicy.canSelect(0.95, true, null, "encode"))
        assertFalse(PerceptualCodecFallbackPolicy.canSelect(0.95, false, "failed", "encode"))
        assertFalse(PerceptualCodecFallbackPolicy.canSelect(0.95, false, null, "keep_original"))
        assertFalse(PerceptualCodecFallbackPolicy.canSelect(1.0, false, null, "encode"))
    }

    @Test fun theAdditionalSearchHasAtMostThreeExistingBoundedRungs() {
        assertEquals(3, PerceptualCodecFallbackPolicy.CANDIDATE_RATIOS.size)
        assertEquals(PerceptualCodecFallbackPolicy.CANDIDATE_RATIOS.sorted().distinct(),
            PerceptualCodecFallbackPolicy.CANDIDATE_RATIOS)
        assertTrue(PerceptualCodecFallbackPolicy.CANDIDATE_RATIOS.all {
            it in QualityProbePolicy.HARD_RATIO_FLOOR..QualityProbePolicy.SAFEST_RATIO_CEILING
        })
        assertTrue(PerceptualCodecFallbackPolicy.PROBE_BUDGET_MS < ExhaustivePerceptualLosslessPolicy.PROBE_BUDGET_MS)
    }
}
