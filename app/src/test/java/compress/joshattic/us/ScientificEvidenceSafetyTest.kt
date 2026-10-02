package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import compress.joshattic.us.quality.*
import org.junit.Assert.*
import org.junit.Test

/** Adversarial cases found by the October 1 review; no numeric acceptance gate changes. */
class ScientificEvidenceSafetyTest {
    @Test fun absentPixelsCannotAuthorizeAPlTranscodeAtAnyRatio() {
        for (basis in CertificationGate.Basis.values()) for (ratio in listOf(0.85, 0.90, 0.97)) {
            assertFalse("$basis / $ratio", CertificationGate.evaluate(basis, ratio, 0.90, PairScoreOutcome.Unavailable).accepted)
        }
    }

    @Test fun passingPrefixDoesNotProveTheMissingWindow() {
        val partial = PairScoreOutcome.Incomplete(listOf(WindowScore(36, 99.0, 98.0, 97.0)), 3, 2)
        for (basis in CertificationGate.Basis.values()) {
            assertFalse(CertificationGate.evaluate(basis, 0.97, 0.90, partial).accepted)
        }
    }

    @Test fun nonFiniteScoresAreUnavailableEvidence() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val windows = listOf(WindowScore(36, bad, 98.0, 97.0))
            assertFalse(QualityProbePolicy.windowsPass(windows))
            assertEquals(CertificationDecision.UNAVAILABLE, CertificationDecision.of(PairScoreOutcome.Scored(windows)))
        }
    }

    @Test fun pipelineFailureAlongsideBitrateFailureMustNotTrainQuality() {
        assertEquals(LearningEvidencePolicy.Kind.PIPELINE,
            LearningEvidencePolicy.classifyVerificationFailure(listOf("videoBitratePass", "audioBitratePass")))
    }

    @Test fun inferredBitrateFloorIsNotMeasuredVisualEvidence() {
        assertEquals(LearningEvidencePolicy.Kind.UNDECIDED,
            LearningEvidencePolicy.classifyVerificationFailure(listOf("videoBitratePass")))
    }

    @Test fun misalignmentDoesNotProveBitrateStarvation() {
        assertFalse(CertificationDecision.MISALIGNED.isMeasuredNegative)
    }

    private fun audioInput(): OutputVerifier.VerificationInput {
        val tracks = OutputVerifier.TrackProbe(videoCodec = MimeTypes.VIDEO_H264,
            audioCodec = MimeTypes.AUDIO_AAC, videoBitrate = 3_000_000, audioBitrate = 320_000,
            audioChannelCount = 2, audioSampleRate = 48000, videoFrameRate = 30f,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED, colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        return OutputVerifier.VerificationInput(
            videoTimeline = MediaTimelineEvidence.Result(true, 300, 300, 0, 0),
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            source = VideoSourceInfo(width = 1280, height = 720, frameRate = 30f, durationMs = 10_000,
                totalBitrate = 3_320_000, audioBitrate = 320_000, videoMime = MimeTypes.VIDEO_H264,
                audioMime = MimeTypes.AUDIO_AAC, audioPresent = true),
            outputFileProbe = OutputVerifier.FileProbe(1280, 720, 30f, 10_000, 0),
            sourceTrackProbe = tracks, outputTrackProbe = tracks.copy(videoBitrate = 2_900_000),
            sourceMetadata = VideoMetadataSnapshot(rotationDegrees = 0),
            outputMetadata = VideoMetadataSnapshot(rotationDegrees = 0),
            sourceSize = 4_200_000, outputSize = 4_100_000,
            privacyMode = MetadataPrivacyMode.PRESERVE_ALL)
    }

    @Test fun highBitrateDoesNotMakeDifferentAudioPacketsTransparent() {
        assertTrue("audioBitratePass" in OutputVerifier.verify(audioInput().copy(audioPacketsDiffer = true)).failingChecks())
    }

    @Test fun unavailableAudioComparisonIsNotProofOfACopy() {
        val input = audioInput()
        assertTrue("audioBitratePass" in OutputVerifier.verify(input.copy(
            outputTrackProbe = input.outputTrackProbe.copy(audioBitrate = 0))).failingChecks())
    }

    @Test fun differentBt601PrimariesNeedDecodedColorEvidence() {
        val input = audioInput()
        val source = input.sourceTrackProbe.copy(colorStandard = MediaFormat.COLOR_STANDARD_BT601_NTSC)
        val output = input.outputTrackProbe.copy(colorStandard = MediaFormat.COLOR_STANDARD_BT601_PAL)
        assertFalse(OutputVerifier.compareColorTransition(BatchQualityMode.PERCEPTUAL_LOSSLESS, source, output).matches)
    }
    @Test fun absentOrFailedWholeVideoTimelineCannotPassPlStructure() {
        val input = audioInput().copy(audioPacketsIdentical = true)
        assertTrue(OutputVerifier.verify(input).verified)
        for (timeline in listOf(null, MediaTimelineEvidence.Result(false, 300, 299, reason = "missing frame"))) {
            val report = OutputVerifier.verify(input.copy(videoTimeline = timeline))
            assertFalse(report.verified)
            assertTrue("frameCountMatches" in report.failingChecks())
            assertFalse(report.failedOnlyOnVideoBitrateFloor)
        }
    }

}
