package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQualitySafetyTest {
    @Test
    fun perceptuallyLossless4k60HdrKeepsConservativeBitrateFloor() {
        val source = VideoSourceInfo(
            width = 3840,
            height = 2160,
            frameRate = 60f,
            durationMs = 60_000,
            totalBitrate = 119_900_000,
            audioBitrate = 320_000,
            videoMime = MimeTypes.VIDEO_H265,
            audioMime = MimeTypes.AUDIO_AAC,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED
        )

        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265
        )

        assertTrue(target >= 95_000_000)
        assertTrue(target > 64_000_000)
    }

    @Test
    fun perceptuallyLosslessPreserves120FpsUnlessUserExplicitlyCapsIt() {
        assertEquals(null, BatchQualityBitratePolicy.outputFpsFor(120f, BatchFrameRateChoice.SOURCE))
        assertEquals(60, BatchQualityBitratePolicy.outputFpsFor(120f, BatchFrameRateChoice.FPS60))
        assertEquals(120, BatchQualityBitratePolicy.outputFpsFor(120f, BatchFrameRateChoice.FPS120))
    }

    @Test
    fun s23LikeProfilePrefersHevcForHdrCameraSources() {
        val profile = DeviceCapabilityProfile(
            name = "Test S23",
            isGalaxyS23Ultra = true,
            preferHevcForDefaultCompression = true,
            avoidAv1EncodingByDefault = true,
            recommendedBatchParallelism = 1
        )
        val source = VideoSourceInfo(
            width = 3840,
            height = 2160,
            frameRate = 60f,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084
        )

        val chosen = profile.chooseDefaultVideoCodec(
            listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1),
            source
        )

        assertEquals(MimeTypes.VIDEO_H265, chosen)
    }

    @Test
    fun zeroDimensionsAreNotTreatedAsSafeMatch() {
        assertFalse(OutputVerifier.sameDimensions(3840, 2160, 0, 0))
    }

    @Test
    fun perceptuallyLosslessFailsVerificationOnHdrAndBitrateLoss() {
        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
                source = VideoSourceInfo(
                    width = 3840,
                    height = 2160,
                    frameRate = 120f,
                    durationMs = 60_000,
                    totalBitrate = 119_900_000,
                    audioBitrate = 256_000,
                    videoMime = MimeTypes.VIDEO_H265,
                    audioMime = MimeTypes.AUDIO_AAC,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                outputFileProbe = OutputVerifier.FileProbe(3840, 2160, 60f, 60_000, 90),
                sourceTrackProbe = OutputVerifier.TrackProbe(
                    videoCodec = MimeTypes.VIDEO_H265,
                    audioCodec = MimeTypes.AUDIO_AAC,
                    videoBitrate = 119_000_000,
                    audioBitrate = 256_000,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                outputTrackProbe = OutputVerifier.TrackProbe(
                    videoCodec = MimeTypes.VIDEO_H264,
                    audioCodec = MimeTypes.AUDIO_AAC,
                    videoBitrate = 64_000_000,
                    audioBitrate = 128_000,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                sourceMetadata = VideoMetadataSnapshot(
                    rawDateTag = "2026-07-08T00:00:00Z",
                    dateSource = "MediaStore date",
                    latitude = 1.0,
                    longitude = 2.0,
                    rotationDegrees = 90
                ),
                outputMetadata = VideoMetadataSnapshot(rotationDegrees = 90),
                sourceSize = 900L * 1024L * 1024L,
                outputSize = 700L * 1024L * 1024L,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertFalse(report.verified)
        assertFalse(report.replacementSafe)
        assertTrue(report.verdict.contains("Failed") || report.verdict.contains("Unverified"))
    }

    @Test
    fun remuxOnlyRequiresCompleteVerificationBeforeReplacement() {
        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.REMUX_ONLY,
                source = VideoSourceInfo(
                    width = 3840,
                    height = 2160,
                    frameRate = 60f,
                    durationMs = 60_000,
                    totalBitrate = 80_000_000,
                    audioBitrate = 256_000,
                    videoMime = MimeTypes.VIDEO_H265,
                    audioMime = MimeTypes.AUDIO_AAC,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                outputFileProbe = OutputVerifier.FileProbe(3840, 2160, 60f, 60_000, 90),
                sourceTrackProbe = OutputVerifier.TrackProbe(
                    MimeTypes.VIDEO_H265,
                    MimeTypes.AUDIO_AAC,
                    79_500_000,
                    256_000,
                    MediaFormat.COLOR_TRANSFER_HLG,
                    MediaFormat.COLOR_STANDARD_BT2020,
                    MediaFormat.COLOR_RANGE_LIMITED,
                    2,
                    48_000
                ),
                outputTrackProbe = OutputVerifier.TrackProbe(
                    MimeTypes.VIDEO_H265,
                    MimeTypes.AUDIO_AAC,
                    79_000_000,
                    256_000,
                    MediaFormat.COLOR_TRANSFER_HLG,
                    MediaFormat.COLOR_STANDARD_BT2020,
                    MediaFormat.COLOR_RANGE_LIMITED,
                    2,
                    48_000
                ),
                sourceMetadata = VideoMetadataSnapshot(
                    rawDateTag = "2026-07-08T00:00:00Z",
                    dateSource = "MediaStore date",
                    latitude = 1.0,
                    longitude = 2.0,
                    rotationDegrees = 90
                ),
                outputMetadata = VideoMetadataSnapshot(
                    rawDateTag = "2026-07-08T00:00:00Z",
                    dateSource = "MediaStore date",
                    latitude = 1.0,
                    longitude = 2.0,
                    rotationDegrees = 90
                ),
                sourceSize = 500L * 1024L * 1024L,
                outputSize = 501L * 1024L * 1024L,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertTrue(report.verified)
        assertTrue(report.replacementSafe)
        assertEquals("verified", report.mediaStoreDate)
        assertEquals("verified", report.mp4Date)
    }

    @Test
    fun missingCriticalFieldsMakePerceptualLosslessUnverified() {
        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
                source = VideoSourceInfo(width = 3840, height = 2160, frameRate = 60f, durationMs = 1_000, totalBitrate = 80_000_000, audioBitrate = 256_000),
                outputFileProbe = OutputVerifier.FileProbe(3840, 2160, 0f, 1_000, 0),
                sourceTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC, 79_000_000, 256_000, null, null, null, 2, 48_000),
                outputTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC, 79_000_000, 256_000, null, null, null, null, null),
                sourceMetadata = VideoMetadataSnapshot(),
                outputMetadata = VideoMetadataSnapshot(),
                sourceSize = 100,
                outputSize = 100,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertEquals("Perceptually Lossless Unverified", report.verdict)
        assertFalse(report.replacementSafe)
    }

    @Test
    fun dateWordingSeparatesMediaStoreAndMp4Metadata() {
        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.HIGH_QUALITY,
                source = VideoSourceInfo(width = 1920, height = 1080, frameRate = 30f, durationMs = 1_000, totalBitrate = 10_000_000, audioBitrate = 128_000),
                outputFileProbe = OutputVerifier.FileProbe(1920, 1080, 30f, 1_000, 0),
                sourceTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC, 9_000_000, 128_000, null, null, null, 2, 48_000),
                outputTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC, 7_000_000, 128_000, null, null, null, 2, 48_000),
                sourceMetadata = VideoMetadataSnapshot(rawDateTag = "tag", dateSource = "MediaStore date"),
                outputMetadata = VideoMetadataSnapshot(rawDateTag = "tag"),
                sourceSize = 100,
                outputSize = 90,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertEquals("unverified", report.mediaStoreDate)
        assertEquals("verified", report.mp4Date)
    }

    @Test
    fun perceptualLosslessFallbackTriggersWhenVerificationIsUnverified() {
        val report = OutputVerificationReport(
            verdict = "Perceptually Lossless Unverified",
            playability = "opens",
            video = "",
            fps = "",
            videoBitrate = "",
            videoCodec = "",
            audioCodec = "",
            audioDetails = "",
            audioBitrate = "",
            hdr = "",
            colorStandard = "",
            colorRange = "",
            mediaStoreDate = "unverified",
            mp4Date = "verified",
            location = "unverified",
            rotation = "",
            fileSize = "",
            replacementSafe = false,
            replacementBlockReason = "critical fields missing",
            criticalFieldsComplete = false,
            verified = false
        )

        assertTrue(PerceptualLosslessVerifier.shouldFallbackToRemux(report, 100L, 100L))
    }
}
