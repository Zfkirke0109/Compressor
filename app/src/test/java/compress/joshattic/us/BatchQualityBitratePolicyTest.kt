package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQualityBitratePolicyTest {

    // A 1080p source already below the lossy resolution floors: 3 Mbps video is under both the
    // High Quality 1080p floor (5 Mbps) and the Storage Saver 1080p floor (3.5 Mbps). Before the
    // floor-clamp fix, calculateVideoBitrate called coerceIn(floor, source) with min > max on these
    // branches and threw IllegalArgumentException. Now an already-below-floor source resolves to
    // "no shrink" (target == source), matching the Perceptually Lossless branch's existing guard.
    private fun lowBitrate1080pSource() = VideoSourceInfo(
        width = 1920,
        height = 1080,
        frameRate = 30f,
        durationMs = 60_000,
        totalBitrate = 3_000_000,
        audioPresent = false,
        videoMime = MimeTypes.VIDEO_H264,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709
    )

    @Test
    fun highQualityBelowFloorSourceResolvesToNoShrinkInsteadOfThrowing() {
        val source = lowBitrate1080pSource()
        assertEquals(3_000_000, source.videoBitrate)

        // Must not throw (min > max on coerceIn) and must never target above the source bitrate.
        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.HIGH_QUALITY,
            outputMimeType = MimeTypes.VIDEO_H265
        )

        assertTrue("target must not exceed source video bitrate", target <= source.videoBitrate)
        // Already below the floor -> no shrink: target clamps back up to the source bitrate.
        assertEquals(source.videoBitrate, target)
    }

    @Test
    fun storageSaverBelowFloorSourceResolvesToNoShrinkInsteadOfThrowing() {
        val source = lowBitrate1080pSource()
        assertEquals(3_000_000, source.videoBitrate)

        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.STORAGE_SAVER,
            outputMimeType = MimeTypes.VIDEO_H265
        )

        assertTrue("target must not exceed source video bitrate", target <= source.videoBitrate)
        assertEquals(source.videoBitrate, target)
    }

    // A healthy 1080p source with real headroom: 10 Mbps is well above the 5 Mbps HQ floor, so
    // the 0.72 ratio survives and the encode can genuinely shrink it.
    private fun healthy1080pSource() = lowBitrate1080pSource().copy(totalBitrate = 10_000_000)

    @Test
    fun doomedLossyEncodesAreDetectedWithoutSuppressingWinnableOnes() {
        val starved = lowBitrate1080pSource()
        val healthy = healthy1080pSource()

        // Below-floor source: target clamps to the source bitrate -> the encode cannot win.
        // These are exactly the clips that produced BIGGER output on the 2026-07-31 S23 batch.
        assertTrue(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                starved, BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265
            )
        )
        assertTrue(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                starved, BatchQualityMode.STORAGE_SAVER, MimeTypes.VIDEO_H265
            )
        )
        // Healthy source keeps real headroom -> must NOT be skipped.
        assertFalse(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                healthy, BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265
            )
        )
    }

    @Test
    fun theDoomedEncodeGuardNeverDivertsLosslessModesAndFailsOpenOnUnknownBitrate() {
        val starved = lowBitrate1080pSource()

        // Perceptually Lossless and Remux Only own their probe / stream-copy decisions: the lossy
        // guard must never return true for them, even on a source it would skip in High Quality.
        assertFalse(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                starved, BatchQualityMode.PERCEPTUAL_LOSSLESS, MimeTypes.VIDEO_H265
            )
        )
        assertFalse(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                starved, BatchQualityMode.REMUX_ONLY, MimeTypes.VIDEO_H265
            )
        )

        // Unknown source bitrate FAILS OPEN: with no honest estimate the encode still runs and the
        // real measured result decides, rather than blind-skipping a possible win.
        val unknown = starved.copy(totalBitrate = 0)
        assertEquals(0, unknown.videoBitrate)
        assertFalse(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                unknown, BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265
            )
        )
    }

    // Adversarial-review regression: a REQUESTED transform must never be silently denied. Storage
    // Saver exists to produce a smaller-resolution / lower-FPS file; that output is the point, not
    // merely a smaller byte count. A below-floor source asked to downscale or drop FPS must still
    // encode, even though the bitrate alone has no headroom.
    @Test
    fun aRequestedFpsOrResolutionChangeIsNeverSkipped() {
        val starved = lowBitrate1080pSource()
        // Bitrate alone has no headroom (baseline: the guard fires with no transform requested).
        assertTrue(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                starved, BatchQualityMode.STORAGE_SAVER, MimeTypes.VIDEO_H265
            )
        )
        // ...but the user asked for 30fps -> must run.
        assertFalse(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                starved, BatchQualityMode.STORAGE_SAVER, MimeTypes.VIDEO_H265, outputFps = 30
            )
        )
        // ...or asked for a resolution reduction -> must run.
        assertFalse(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                starved, BatchQualityMode.STORAGE_SAVER, MimeTypes.VIDEO_H265, outputHeight = 720
            )
        )
    }

    // Adversarial-review regression: the lossy floor tables key off raw source.height, which
    // mis-tiers PORTRAIT clips (1080x1920 is 1080p-class, but height 1920 selects the 1440p tier
    // and its higher 8 Mbps floor). A 1080x1920 clip at 7 Mbps really has High Quality headroom
    // (0.72 x 7 = 5.04 Mbps), so the orientation-normalized re-check must refuse to skip it.
    @Test
    fun portraitClipsAreNotSkippedByAnOrientationMisTieredFloor() {
        val portrait = lowBitrate1080pSource().copy(width = 1080, height = 1920, totalBitrate = 7_000_000)
        assertEquals(7_000_000, portrait.videoBitrate)
        assertFalse(
            "a portrait 1080p-class clip with real headroom must never be skipped",
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                portrait, BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265
            )
        )
        // Landscape 1080p at the same bitrate is genuinely above its floor too -> also not skipped.
        assertFalse(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                portrait.copy(width = 1920, height = 1080), BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265
            )
        )
    }

    @Test
    fun storageSaverResolutionReductionStillCountsAsRealHeadroom() {
        // A 4K source at 20 Mbps that Storage Saver will downscale to 1080p: the height scale pulls
        // the target far below the source, so this is a genuine win and must never be skipped.
        val fourK = lowBitrate1080pSource().copy(width = 3840, height = 2160, totalBitrate = 20_000_000)
        assertFalse(
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                source = fourK,
                mode = BatchQualityMode.STORAGE_SAVER,
                outputMimeType = MimeTypes.VIDEO_H265,
                outputHeight = BatchQualityBitratePolicy.targetHeightFor(2160, BatchQualityMode.STORAGE_SAVER)
            )
        )
    }
}
