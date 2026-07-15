package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pixel-proven ratio (on-device VMAF probe evidence) may target below the class-level
 * inference floors — but only through the explicit override parameters, never by default,
 * and verification honors the proven floor only when it is explicitly provided.
 */
class PixelProvenFloorTest {

    private fun sdr1080p30(totalBitrate: Int = 20_000_000) = VideoSourceInfo(
        width = 1920,
        height = 1080,
        frameRate = 30f,
        durationMs = 60_000,
        totalBitrate = totalBitrate,
        audioBitrate = 256_000,
        videoMime = MimeTypes.VIDEO_H264,
        audioMime = MimeTypes.AUDIO_AAC,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709
    )

    @Test
    fun pixelProvenRatioTargetsBelowTheClassFloor() {
        val source = sdr1080p30()
        val video = source.videoBitrate

        // Without evidence: a 0.70 learned ratio is clamped up to the 0.85 class floor.
        val clamped = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265,
            learnedTargetRatio = 0.70
        )
        assertTrue(clamped >= (video * 0.85).toInt())

        // With pixel-proven evidence at 0.70: the target is exactly the proven ratio.
        val proven = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265,
            learnedTargetRatio = 0.70,
            pixelProvenRatioFloor = 0.70
        )
        assertEquals((video * 0.70).toInt(), proven)
    }

    @Test
    fun defaultBehaviorIsByteIdenticalWhenNoEvidenceIsSupplied() {
        val source = sdr1080p30()
        val withoutParam = BatchQualityBitratePolicy.calculateVideoBitrate(
            source, BatchQualityMode.PERCEPTUAL_LOSSLESS, MimeTypes.VIDEO_H265
        )
        val withNull = BatchQualityBitratePolicy.calculateVideoBitrate(
            source, BatchQualityMode.PERCEPTUAL_LOSSLESS, MimeTypes.VIDEO_H265,
            learnedTargetRatio = null, pixelProvenRatioFloor = null
        )
        assertEquals(withoutParam, withNull)
    }

    @Test
    fun verifierUsesProvenFloorOnlyWhenProvided() {
        val source = sdr1080p30()
        val video = source.videoBitrate
        val sourceTracks = OutputVerifier.TrackProbe(
            videoCodec = MimeTypes.VIDEO_H264,
            audioCodec = MimeTypes.AUDIO_AAC,
            videoBitrate = video,
            audioBitrate = 256_000,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            audioChannelCount = 2,
            audioSampleRate = 48_000,
            videoFrameRate = 30f
        )
        // Output delivered ~0.68x of the source video bitrate.
        val deliveredVideo = (video * 0.68).toInt()
        val outputTracks = sourceTracks.copy(
            videoCodec = MimeTypes.VIDEO_H265,
            videoBitrate = deliveredVideo
        )
        fun input(provenFloor: Int?) = OutputVerifier.VerificationInput(
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            source = source,
            outputFileProbe = OutputVerifier.FileProbe(1920, 1080, 30f, 60_000, 0),
            sourceTrackProbe = sourceTracks,
            outputTrackProbe = outputTracks,
            sourceMetadata = VideoMetadataSnapshot(rotationDegrees = 0),
            outputMetadata = VideoMetadataSnapshot(rotationDegrees = 0),
            sourceSize = 150_000_000L,
            outputSize = 105_000_000L,
            privacyMode = MetadataPrivacyMode.PRESERVE_ALL,
            pixelProvenVideoBitrateFloor = provenFloor
        )

        // Classic behavior: 0.68x delivery is far below the 0.85 class floor -> fail.
        val classic = OutputVerifier.verify(input(provenFloor = null))
        assertFalse(classic.verified)
        assertTrue(classic.replacementBlockReason.orEmpty().contains("bitrate"))

        // Pixel-proven 0.70 encode with undershoot tolerance: floor (0.70-0.06)*video -> pass.
        val provenFloor = ((0.70 - 0.06) * video).toInt()
        val proven = OutputVerifier.verify(input(provenFloor = provenFloor))
        assertTrue(proven.verified)
        assertTrue(proven.replacementSafe)
    }
}
