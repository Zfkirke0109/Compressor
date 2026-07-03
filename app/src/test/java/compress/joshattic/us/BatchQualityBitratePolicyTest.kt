package compress.joshattic.us

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQualityBitratePolicyTest {
    private val s23HdrSource = BatchBitrateSource(
        originalSize = 1_500_000_000L,
        originalBitrate = 119_900_000,
        originalAudioBitrate = 256_015,
        originalHeight = 2160,
        originalFps = 60f,
        durationMs = 100_000L
    )

    @Test
    fun perceptuallyLosslessBitrateIsMoreConservativeThanCompressionModes() {
        val perceptual = BatchQualityBitratePolicy.videoBitrate(
            s23HdrSource,
            BatchQualityPreset.PERCEPTUALLY_LOSSLESS,
            MimeTypes.VIDEO_H265
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
        assertTrue(perceptual <= s23HdrSource.originalBitrate)
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
    fun perceptuallyLosslessFallsBackToRemuxWhenEncodeIsNotSmaller() {
        assertTrue(
            BatchQualityBitratePolicy.shouldUseRemuxFallbackForPerceptualOutput(
                quality = BatchQualityPreset.PERCEPTUALLY_LOSSLESS,
                originalSize = 1_000L,
                outputSize = 1_000L
            )
        )
        assertTrue(
            BatchQualityBitratePolicy.shouldUseRemuxFallbackForPerceptualOutput(
                quality = BatchQualityPreset.PERCEPTUALLY_LOSSLESS,
                originalSize = 1_000L,
                outputSize = 1_100L
            )
        )
    }

    @Test
    fun highQualityDoesNotUsePerceptualRemuxFallback() {
        assertFalse(
            BatchQualityBitratePolicy.shouldUseRemuxFallbackForPerceptualOutput(
                quality = BatchQualityPreset.HIGH_QUALITY,
                originalSize = 1_000L,
                outputSize = 1_100L
            )
        )
    }
}
