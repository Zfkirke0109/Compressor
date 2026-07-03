package compress.joshattic.us

import org.junit.Assert.assertTrue
import org.junit.Test

class OutputVerificationReportTest {
    @Test
    fun remuxKeptReportIncludesExactFallbackMessage() {
        val report = OutputVerificationReport(
            playability = "opens",
            encoderMode = "not exposed",
            video = "2160x3840 -> 2160x3840 ok",
            fps = "60 -> 60 ok",
            videoCodec = "HEVC -> HEVC ok",
            audioCodec = "AAC -> AAC ok",
            audioBitrate = "256 kbps -> 256 kbps",
            hdr = "HDR PQ -> HDR PQ ok",
            galleryDate = "restore requested where Android allows",
            mp4CreationTime = "MP4 creation_time differs",
            location = "verified",
            rotation = "90deg -> 90deg",
            fileSize = "1.3 GB -> 1.3 GB",
            replacementSafe = true,
            perceptualStatus = PerceptualLosslessStatus.REMUX_KEPT.reportLabel,
            perceptualReason = BatchQualityBitratePolicy.PERCEPTUAL_OVERSIZE_REMUX_FALLBACK_MESSAGE
        )

        assertTrue(report.summaryLines.contains("PL status: Remux Kept"))
        assertTrue(
            report.summaryLines.contains(
                "PL reason: Source was already highly efficient; kept exact remux instead of larger re-encode."
            )
        )
    }
}
