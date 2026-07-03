package compress.joshattic.us

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQualityBitratePolicyTest {
    private val s23HevcOvershootFactor = 1.28

    private val s23HdrSource = BatchBitrateSource(
        originalSize = 1_500_000_000L,
        originalBitrate = 119_900_000,
        originalAudioBitrate = 256_015,
        originalHeight = 2160,
        originalFps = 60f,
        durationMs = 100_000L
    )

    @Test
    fun perceptuallyLosslessBitrateUsesMeasuredS23SafeBudget() {
        val perceptual = BatchQualityBitratePolicy.videoBitrate(
            s23HdrSource,
            BatchQualityPreset.PERCEPTUALLY_LOSSLESS,
            MimeTypes.VIDEO_H265,
            s23HevcOvershootFactor
        )
        val highQuality = BatchQualityBitratePolicy.videoBitrate(
            s23HdrSource,
            BatchQualityPreset.HIGH_QUALITY,
            MimeTypes.VIDEO_H265
        )
        val storageSaver = BatchQualityBitratePolicy.videoBitrate(
            s23HdrSource,
            BatchQualityPreset.STORAGE_SAVER,
            MimeTypes.VIDEO_H265
        )

        assertTrue(perceptual > highQuality)
        assertTrue(highQuality > storageSaver)
        assertTrue(perceptual >= 85_000_000)
        assertTrue(perceptual in 90_000_000..95_000_000)
        assertTrue(perceptual <= s23HdrSource.originalBitrate)
    }

    @Test
    fun perceptuallyLosslessKeepsVisualTargetWithoutMeasuredOvershoot() {
        val originalVideo = s23HdrSource.originalBitrate - s23HdrSource.originalAudioBitrate
        val decision = BatchQualityBitratePolicy.perceptualEncodeDecision(
            s23HdrSource,
            MimeTypes.VIDEO_H265
        )

        assertTrue(decision.shouldEncode)
        assertEquals((originalVideo * 0.88).toInt(), decision.targetBitrate)
        assertEquals(1.0, decision.overshootFactor, 0.0)
    }

    @Test
    fun perceptuallyLosslessSkipsEncodeWhenMeasuredSafeBudgetFallsBelowVisualFloor() {
        val alreadyEfficientSource = s23HdrSource.copy(originalSize = 900_000_000L)
        val decision = BatchQualityBitratePolicy.perceptualEncodeDecision(
            alreadyEfficientSource,
            MimeTypes.VIDEO_H265,
            s23HevcOvershootFactor
        )

        assertFalse(decision.shouldEncode)
        assertTrue(decision.targetBitrate < decision.floorBitrate)
        assertEquals(85_000_000, decision.floorBitrate)
    }

    @Test
    fun perceptuallyLosslessKeepsSourceResolutionAndAudioBitrate() {
        assertEquals(
            2160,
            BatchQualityBitratePolicy.targetHeight(s23HdrSource, BatchQualityPreset.PERCEPTUALLY_LOSSLESS)
        )
        assertEquals(
            256_015,
            BatchQualityBitratePolicy.audioBitrate(s23HdrSource, BatchQualityPreset.PERCEPTUALLY_LOSSLESS)
        )
    }

    @Test
    fun storageSaverCapsResolutionAndAudioBitrate() {
        assertEquals(
            720,
            BatchQualityBitratePolicy.targetHeight(s23HdrSource, BatchQualityPreset.STORAGE_SAVER)
        )
        assertEquals(
            128_000,
            BatchQualityBitratePolicy.audioBitrate(s23HdrSource, BatchQualityPreset.STORAGE_SAVER)
        )
    }

    @Test
    fun perceptuallyLosslessKeepsReencodeWhenSmallerThanSource() {
        assertFalse(
            BatchQualityBitratePolicy.shouldUseRemuxFallbackForPerceptualOutput(
                quality = BatchQualityPreset.PERCEPTUALLY_LOSSLESS,
                baselineSize = 1_000L,
                outputSize = 999L
            )
        )
    }

    @Test
    fun perceptuallyLosslessKeepsReencodeWhenWithinOversizeTolerance() {
        val sourceSize = 4_131_195_352L
        val tolerance = 16L * 1024L * 1024L

        assertEquals(
            tolerance,
            BatchQualityBitratePolicy.perceptualOversizeToleranceBytes(sourceSize)
        )
        assertFalse(
            BatchQualityBitratePolicy.shouldUseRemuxFallbackForPerceptualOutput(
                quality = BatchQualityPreset.PERCEPTUALLY_LOSSLESS,
                baselineSize = sourceSize,
                outputSize = sourceSize + tolerance
            )
        )
    }

    @Test
    fun perceptuallyLosslessFallsBackToRemuxWhenS23OutputExceedsTolerance() {
        assertTrue(
            BatchQualityBitratePolicy.shouldUseRemuxFallbackForPerceptualOutput(
                quality = BatchQualityPreset.PERCEPTUALLY_LOSSLESS,
                baselineSize = 4_131_195_352L,
                outputSize = 4_234_288_900L
            )
        )
    }

    @Test
    fun highQualityDoesNotUsePerceptualRemuxFallback() {
        assertFalse(
            BatchQualityBitratePolicy.shouldUseRemuxFallbackForPerceptualOutput(
                quality = BatchQualityPreset.HIGH_QUALITY,
                baselineSize = 1_000L,
                outputSize = 1_100L
            )
        )
    }

    @Test
    fun perceptualOversizeFallbackMessageIsExact() {
        assertEquals(
            "Source was already highly efficient; kept exact remux instead of larger re-encode.",
            BatchQualityBitratePolicy.PERCEPTUAL_OVERSIZE_REMUX_FALLBACK_MESSAGE
        )
    }
}
