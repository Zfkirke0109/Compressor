package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * batch_1790263711162 (b161): 13 encodes were discarded with "Audio bitrate: 128 kbps -> 128 kbps
 * warn". The AAC track had been copied bit-exactly, but the verifier still applied the old PL
 * re-encode rule (>= max(source, 256 kbps) less 10%).
 */
class AudioPassThroughVerificationTest {

    private val source = VideoSourceInfo(
        width = 1356, height = 760, frameRate = 10f, durationMs = 128_104,
        totalBitrate = 1_050_000, audioBitrate = 128_000,
        videoMime = MimeTypes.VIDEO_H264, audioMime = MimeTypes.AUDIO_AAC,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
        colorRange = MediaFormat.COLOR_RANGE_LIMITED,
        audioChannelCount = 2, audioSampleRate = 44_100, audioPresent = true
    )
    private val sourceTracks = OutputVerifier.TrackProbe(
        videoCodec = MimeTypes.VIDEO_H264, audioCodec = MimeTypes.AUDIO_AAC,
        videoBitrate = 914_000, audioBitrate = 128_000,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
        colorRange = MediaFormat.COLOR_RANGE_LIMITED,
        audioChannelCount = 2, audioSampleRate = 44_100, videoFrameRate = 10f
    )
    // The muxer exposes the copied track's bitrate (128 kbps), exactly as on device.
    private val outputTracks = sourceTracks.copy(videoCodec = MimeTypes.VIDEO_H265, videoBitrate = 800_000)

    private fun verify(packetsIdentical: Boolean) = OutputVerifier.verify(
        OutputVerifier.VerificationInput(
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            source = source,
            outputFileProbe = OutputVerifier.FileProbe(1356, 760, 10f, 128_105, 0),
            sourceTrackProbe = sourceTracks,
            outputTrackProbe = outputTracks,
            sourceMetadata = VideoMetadataSnapshot(rotationDegrees = 0),
            outputMetadata = VideoMetadataSnapshot(rotationDegrees = 0),
            sourceSize = 16_753_345L,
            outputSize = 14_872_572L,
            privacyMode = MetadataPrivacyMode.PRESERVE_ALL,
            audioPacketsIdentical = packetsIdentical
        )
    )

    @Test
    fun aProvenBitExactCopyPassesTheAudioCheck() {
        val report = verify(packetsIdentical = true)
        assertFalse("audioBitratePass" in report.failingChecks())
        assertTrue(report.audioBitrate.contains("stream copied"))
    }

    @Test
    fun withoutPacketProofTheOldRuleStillApplies() {
        // Nothing is loosened for a track that was not proven identical: a 128 kbps track that
        // might be a re-encode still has to meet the re-encode rule.
        assertTrue("audioBitratePass" in verify(packetsIdentical = false).failingChecks())
    }

    @Test
    fun packetComparisonNeedsTheSameCountAndBytes() {
        fun seq(vararg p: String) = p.map { it.toByteArray() }.iterator()
        assertEquals(AudioTrackIdentity.Result.IDENTICAL, AudioTrackIdentity.compare(seq("a", "bc"), seq("a", "bc")))
        assertEquals(AudioTrackIdentity.Result.DIFFERENT, AudioTrackIdentity.compare(seq("a", "bc"), seq("a", "bd")))
        assertEquals(AudioTrackIdentity.Result.DIFFERENT, AudioTrackIdentity.compare(seq("a", "bc"), seq("a")))
        assertEquals(AudioTrackIdentity.Result.DIFFERENT, AudioTrackIdentity.compare(seq("a"), seq("a", "bc")))
        assertEquals(AudioTrackIdentity.Result.UNAVAILABLE, AudioTrackIdentity.compare(seq(), seq()))
    }
}
