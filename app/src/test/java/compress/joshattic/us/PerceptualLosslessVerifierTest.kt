package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class PerceptualLosslessVerifierTest {
    @Test
    fun statusLabelsMatchReportContract() {
        assertEquals("PL Verified", PerceptualLosslessStatus.PL_VERIFIED.reportLabel)
        assertEquals("PL Unverified", PerceptualLosslessStatus.PL_UNVERIFIED.reportLabel)
        assertEquals("Remux Kept", PerceptualLosslessStatus.REMUX_KEPT.reportLabel)
    }

    @Test
    fun fourKSixtyHdrOutputBelowNinetyPercentBitrateIsUnverified() {
        val result = PerceptualLosslessVerifier.verify(
            validInput().copy(
                sourceVideoBitrate = 120_000_000,
                outputVideoBitrate = 100_000_000
            )
        )

        assertEquals(PerceptualLosslessStatus.PL_UNVERIFIED, result.status)
        assertEquals(
            "4K60 HDR output bitrate dropped below 90% of source video bitrate",
            result.reason
        )
    }

    @Test
    fun fourKSixtyHdrOutputAtNinetyPercentBitrateIsVerified() {
        val result = PerceptualLosslessVerifier.verify(
            validInput().copy(
                sourceVideoBitrate = 120_000_000,
                outputVideoBitrate = 108_000_000
            )
        )

        assertEquals(PerceptualLosslessStatus.PL_VERIFIED, result.status)
    }

    @Test
    fun missingOutputFpsIsUnverifiedForPerceptuallyLossless() {
        val result = PerceptualLosslessVerifier.verify(validInput().copy(outputFps = 0f))

        assertEquals(PerceptualLosslessStatus.PL_UNVERIFIED, result.status)
        assertEquals("FPS was not preserved", result.reason)
    }

    @Test
    fun changedHdrTransferIsUnverifiedForPerceptuallyLossless() {
        val result = PerceptualLosslessVerifier.verify(
            validInput().copy(outputColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        )

        assertEquals(PerceptualLosslessStatus.PL_UNVERIFIED, result.status)
        assertEquals("HDR/color metadata was not preserved", result.reason)
    }

    @Test
    fun changedAudioTrackIsUnverifiedForPerceptuallyLossless() {
        val result = PerceptualLosslessVerifier.verify(
            validInput().copy(outputAudioCodec = MimeTypes.AUDIO_OPUS)
        )

        assertEquals(PerceptualLosslessStatus.PL_UNVERIFIED, result.status)
        assertEquals("audio track was not preserved", result.reason)
    }

    private fun validInput(): PerceptualLosslessVerificationInput {
        return PerceptualLosslessVerificationInput(
            playable = true,
            sourceWidth = 2160,
            sourceHeight = 3840,
            outputWidth = 2160,
            outputHeight = 3840,
            sourceFps = 60f,
            outputFps = 60f,
            sourceVideoBitrate = 120_000_000,
            outputVideoBitrate = 115_000_000,
            sourceHdrLike = true,
            sourceColorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            outputColorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            sourceColorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            outputColorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            sourceRotationDegrees = 90,
            outputRotationDegrees = 90,
            sourceAudioCodec = MimeTypes.AUDIO_AAC,
            outputAudioCodec = MimeTypes.AUDIO_AAC,
            fourKSixtyHdr = true
        )
    }
}
