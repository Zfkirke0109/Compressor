package compress.joshattic.us

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One resolved plan for the encoder request, the estimate and the plan log. The numbers are b169
 * `job_d127463b57d6` (the recorded row 110): 1080x1080, 30 fps, 843,418 ms, 3,057,920 bps total,
 * 128 kbps AAC, H.264 -> HEVC at the pixel-proven 0.75.
 */
class ResolvedEncodePlanTest {

    private val row110 = VideoSourceInfo(
        width = 1080, height = 1080, frameRate = 30f, durationMs = 843_418L,
        totalBitrate = 3_057_920, audioBitrate = 128_000,
        videoMime = MimeTypes.VIDEO_H264, audioMime = MimeTypes.AUDIO_AAC
    )

    private fun resolve(overshoot: Double? = 0.885, ratio: Double = 0.75, provisional: Boolean = false) =
        ResolvedEncodePlan.resolve(
            source = row110,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMime = MimeTypes.VIDEO_H265,
            outputFps = null,
            outputHeight = 1080,
            targetRatio = ratio,
            pixelProvenRatioFloor = ratio,
            overshootPreClamp = overshoot,
            provisional = provisional
        )

    @Test
    fun theRequestIsTheOneTheEncoderLogged() {
        // encodeResult: requestedVideoBitrate=2197440.
        assertEquals(2_197_440, resolve().requestedVideoBitrate)
    }

    @Test
    fun theOldPlanLineLeftOutTheProvenFloor() {
        // The b169 CompressorEncoderPlan line said 2,490,432: the same call without the floor.
        val withoutFloor = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = row110, mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265, learnedTargetRatio = 0.75
        )
        assertEquals(2_490_432, withoutFloor)
        assertTrue(resolve().describe().contains("requestedVideoBitrate=2197440"))
    }

    @Test
    fun aacAudioIsCopiedAndCountedAtTheSourceBitrate() {
        // "audio; ... sourceBitrate=128000; action=passthrough", not the 256 kbps target the plan line printed.
        assertEquals(ResolvedEncodePlan.AudioPlan.Copy(128_000), resolve().audio)
        assertEquals(128_000, resolve().audio.estimateBitrate)
    }

    @Test
    fun theEstimateIsTheSizeGatePredictionWithTheClampStated() {
        val plan = resolve()
        // "size gate; ... predictedBytes=247616391; ... used=0.885" — the gate's own number.
        assertEquals(247_616_391L, plan.estimatedBytes)
        assertEquals(ResolvedEncodePlan.EstimateBasis.Kind.SIZE_GATE_PREDICTION, plan.estimate.kind)
        // The log said "used=0.885"; the prediction multiplied by 1.0.
        assertEquals(0.885, plan.estimate.overshootPreClamp!!, 1e-12)
        assertEquals(1.0, plan.estimate.overshootUsed, 1e-12)
        // The real output was 246,118,100 bytes: within 0.7 % of the estimate, where the row showed 302.5 MiB.
        assertEquals(246_118_100.0, plan.estimatedBytes.toDouble(), 246_118_100 * 0.007)
    }

    @Test
    fun theEstimateTheRowShowedWasAnotherPlan() {
        // BatchQualityBitratePolicy.estimateOutputSize: codec default ratio, 256 kbps audio, 4 % padding.
        val shown = BatchQualityBitratePolicy.estimateOutputSize(
            source = row110, mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265, frameRateChoice = BatchFrameRateChoice.SOURCE
        )
        assertEquals(302.5, shown / (1024.0 * 1024.0), 0.05)
        assertTrue(shown > resolve().estimatedBytes + 50_000_000L)
    }

    @Test
    fun beforeTheProbesTheEstimateIsLabelledProvisional() {
        val plan = resolve(overshoot = null, provisional = true)
        assertTrue(plan.isProvisional)
        assertEquals(ResolvedEncodePlan.EstimateBasis.Kind.PROVISIONAL, plan.estimate.kind)
        assertFalse(resolve().isProvisional)
    }

    @Test
    fun withoutAGateTheEstimateIsRequestedBitratesTimesDuration() {
        val plan = resolve(overshoot = null)
        val expected = ((2_197_440.0 + 128_000) * 843.418 * ResolvedEncodePlan.PL_PREDICTION_CONTAINER_FACTOR / 8.0).toLong()
        assertEquals(expected, plan.estimatedBytes)
        assertEquals(ResolvedEncodePlan.EstimateBasis.Kind.REQUESTED_BITRATES, plan.estimate.kind)
    }

    @Test
    fun noAudioTrackIsNoAudio() {
        val silent = row110.copy(audioMime = null, audioBitrate = 0, audioPresent = false)
        val plan = ResolvedEncodePlan.resolve(
            silent, BatchQualityMode.PERCEPTUAL_LOSSLESS, MimeTypes.VIDEO_H265, null, 1080, 0.75, 0.75
        )
        assertEquals(ResolvedEncodePlan.AudioPlan.None, plan.audio)
    }

    @Test
    fun storageSaverReencodesAudioAtItsTarget() {
        val plan = ResolvedEncodePlan.resolve(
            row110.copy(audioBitrate = 256_000), BatchQualityMode.STORAGE_SAVER, MimeTypes.VIDEO_H265, null, 1080, null, null
        )
        assertEquals(ResolvedEncodePlan.AudioPlan.Reencode(160_000), plan.audio)
    }

    @Test
    fun sizesAreLabelledInTheUnitsTheyAreComputedIn() {
        assertEquals("234.7 MiB", formatFileSize(246_118_100L))
        assertEquals("0 MiB", formatFileSize(0L))
        assertEquals("1.0 GiB", formatFileSize(1L shl 30))
    }
}
