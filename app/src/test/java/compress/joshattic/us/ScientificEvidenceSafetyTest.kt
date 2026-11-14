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

    // ---- The two rules decided on November 12 --------------------------------------------------

    @Test fun aCandidateHoldingOnlyWhatTheSourcesEditListPresentsKeepsTheOriginal() {
        // Perceptually Lossless needs every video sample the platform's reader lists for the
        // source. A source can hold samples its edit list does not present (the synthetic file: 168
        // in its table, 166 presented), and Media3 hands on only the presented ones. On the S26 the
        // platform extractor lists all 168, so to a player built on it such a candidate is two
        // frames short, and frames a candidate does not hold were compared by nothing.
        val table = LongArray(168) { 97_488L + it * 41_708L }
        val presented = table.copyOf(166)
        // As Media3 writes it, the first timestamp moved to zero, and with that timestamp put back.
        for (candidate in listOf(presented.copyOf().also { it[0] = 0 }, presented)) {
            val timeline = MediaTimelineEvidence.compare(table, candidate)
            assertFalse(timeline.matches)
            assertEquals(168 to 166, timeline.sourceFrames to timeline.outputFrames)
            // Nothing to repair: a rewrite of the container restores no frame.
            assertNull(timeline.firstVideoPtsToRestoreUs)
            val report = OutputVerifier.verify(audioInput().copy(audioPacketsIdentical = true, videoTimeline = timeline))
            assertFalse(report.verified)
            assertFalse(report.replacementSafe)
            assertTrue("frameCountMatches" in report.failingChecks())
            assertEquals("complete video timeline not verified: frame count 168 -> 166", report.replacementBlockReason)
        }
        // With every sample carried the same input passes.
        val whole = MediaTimelineEvidence.compare(table, table)
        assertTrue(OutputVerifier.verify(audioInput().copy(audioPacketsIdentical = true, videoTimeline = whole)).verified)
    }

    /** The input as OutputVerifier.captureInput fills it from an audio comparison. */
    private fun withAudio(outcome: AudioTrackIdentity.Outcome) = audioInput().copy(
        audioPacketsIdentical = outcome.result == AudioTrackIdentity.Result.IDENTICAL,
        audioPacketsCompared = outcome.packets,
        audioPacketsDiffer = outcome.result == AudioTrackIdentity.Result.DIFFERENT,
        audioEvidence = outcome
    )

    @Test fun aCopyThatDiffersOnlyInItsEndPaddingIsNotIdenticalAudio() {
        // No tolerance. What the S26 read for Media3's copy of an AAC track: every packet the
        // source's, and the configuration the source's except 2 samples of end padding where the
        // source has 0. The verifier refuses it and says in what it differs. The pipeline rewrites
        // such a file once and judges the result by this same rule (CandidateContainerRepair,
        // AudioEndPaddingTest).
        val asc = byteArrayOf(0x11, 0x90.toByte(), 0x56, 0xe5.toByte(), 0x00)
        fun config(padding: Int) = AudioTrackIdentity.Config("audio/mp4a-latm", 2, 48_000, 1024, padding, listOf(asc, null, null))
        fun packets() = (0 until 329).map { AudioPacketEvidence.Packet(byteArrayOf(it.toByte(), 7), it * 21_333L, 1) }.iterator()
        val copy = AudioTrackIdentity.compareTimed(packets(), packets(), config(0), config(2), 0, 0)
        assertTrue(copy.differsOnlyInEndPadding)
        val report = OutputVerifier.verify(withAudio(copy))
        assertFalse(report.verified)
        assertFalse(report.replacementSafe)
        assertTrue("audioBitratePass" in report.failingChecks())
        assertEquals("audio packet, decoder configuration or A/V timing identity was not proven", report.replacementBlockReason)
        assertEquals(
            "audio preservation could not be verified (end padding differs (source 0, output 2 samples); " +
                "the packets and the rest of the configuration match)",
            report.audioBasis
        )
        val recorded = report.structuralEvidence["audioPacketEvidence"] as Map<*, *>
        assertEquals(true, recorded["differsOnlyInEndPadding"])
        assertEquals("decoder configuration differs", recorded["reason"])
        // The same packets under the source's own configuration are the source's audio.
        val same = AudioTrackIdentity.compareTimed(packets(), packets(), config(0), config(0), 0, 0)
        val passing = OutputVerifier.verify(withAudio(same))
        assertTrue(passing.verified)
        assertEquals(false, (passing.structuralEvidence["audioPacketEvidence"] as Map<*, *>)["differsOnlyInEndPadding"])
    }

}
