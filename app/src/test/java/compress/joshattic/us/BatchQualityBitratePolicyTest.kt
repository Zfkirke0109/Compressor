package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import compress.joshattic.us.quality.QualityProbePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQualityBitratePolicyTest {

    @Test
    fun highSpeedCaptureTagDoesNotSuppressThePlaybackRatesProbeLadder() {
        val source = VideoSourceInfo(width = 1920, height = 1080, totalBitrate = 10_000_000, audioPresent = false)
        fun probeRungs(fps: Float) = QualityProbePolicy.candidateRatiosForSource(
            0.90, BatchQualityBitratePolicy.sourceBitsPerPixelPerFrame(source.copy(frameRate = fps))
        )
        assertTrue(probeRungs(240f).isEmpty())
        assertEquals(30f, playbackFrameRate(30f, 240f)!!, 0f)
        assertTrue(probeRungs(playbackFrameRate(30f, 240f)!!).isNotEmpty())
        assertEquals(29.97f, playbackFrameRate(29.97f, null)!!, 0f)
        assertEquals(60f, playbackFrameRate(Float.NaN, 60f)!!, 0f)
        assertNull(playbackFrameRate(0f, Float.POSITIVE_INFINITY))
    }

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

    @Test
    fun portraitAndLandscapeUseTheSameEncoderRequestAndResolutionFloor() {
        fun check(width: Int, height: Int, bitrate: Int, expected: Int) {
            val source = lowBitrate1080pSource().copy(width = width, height = height, totalBitrate = bitrate)
            for (clip in listOf(source, source.copy(width = height, height = width))) {
                val plan = ResolvedEncodePlan.resolve(
                    clip, BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265, null, clip.height, null, null
                )
                assertEquals(expected, plan.requestedVideoBitrate)
                assertEquals(
                    plan.estimatedBytes,
                    BatchQualityBitratePolicy.estimateOutputSize(
                        clip, BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265, BatchFrameRateChoice.SOURCE
                    )
                )
                assertEquals(expected >= bitrate, BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                    clip, BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265
                ))
            }
        }
        check(1280, 720, 4_000_000, 2_880_000)
        check(1920, 1080, 7_000_000, 5_040_000)
        check(2560, 1440, 10_000_000, 8_000_000)
        check(3840, 2160, 15_000_000, 12_000_000)
        check(1920, 1080, 3_000_000, 3_000_000)
    }

    @Test
    fun highQualityBelowTheVideoFloorStillRunsWhenEncodingAudioCanSaveBytes() {
        val pcm = lowBitrate1080pSource().copy(totalBitrate = 4_536_000, audioPresent = true,
            audioMime = "audio/raw", audioBitrate = 1_536_000)
        fun skipped(source: VideoSourceInfo) = BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
            source, BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265
        )
        assertEquals(3_000_000, BatchQualityBitratePolicy.calculateVideoBitrate(
            pcm, BatchQualityMode.HIGH_QUALITY, MimeTypes.VIDEO_H265
        ))
        assertFalse(skipped(pcm))
        assertFalse(skipped(pcm.copy(totalBitrate = 3_000_000, audioBitrate = 0)))
        assertTrue(skipped(pcm.copy(audioMime = "audio/mp4a-latm")))
        assertTrue(skipped(pcm.copy(totalBitrate = 3_128_000, audioBitrate = 128_000)))
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
