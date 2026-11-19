package compress.joshattic.us

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generated, verified, kept, counted and replaceable are separate states of a job's output. The
 * cases are the b287 records whose verdict strings a reader had to reconcile by hand.
 */
class OutputDispositionTest {

    private fun structural(
        verdict: String, verified: Boolean = true, replacementSafe: Boolean = true, pixelCertified: Boolean = false
    ) = OutputVerificationReport(
        verdict = verdict, playability = "opens", video = "", fps = "", videoBitrate = "", videoCodec = "",
        audioCodec = "", audioDetails = "", audioBitrate = "", hdr = "", colorStandard = "", colorRange = "",
        mediaStoreDate = "", mp4Date = "", location = "", rotation = "", fileSize = "",
        replacementSafe = replacementSafe, verified = verified, pixelCertified = pixelCertified
    )

    private fun disposition(
        terminal: BatchTerminalResult, report: OutputVerificationReport?, kept: Long, candidate: Long?,
        retainedVerdict: String? = null, retainedReadable: Boolean? = null
    ): OutputDisposition {
        val acceptance = FinalAcceptance.of(terminal, report, kept, candidate, retainedVerdict, retainedReadable)
        return OutputDisposition.of(
            terminal, acceptance, originalRetained = retainedVerdict != null || retainedReadable != null,
            pixelCertified = report?.pixelCertified == true
        )
    }

    @Test
    fun aVerifiedLossyOutputWithNoSizeWinIsKeptForCopyAndCountsForNothing() {
        // b287 Secure Folder HQ job_31e5faeaf665: 122,130,776 B from 123,783,185 B. finalAccepted
        // was true, the accept event "accepted", the attempt ledger "not_accepted:...".
        val d = disposition(
            BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER, structural("High Quality (lossy mode)"),
            kept = 122_130_776L, candidate = 122_130_776L
        )
        assertTrue(d.candidateGenerated)
        assertEquals(true, d.structurallyVerified)
        assertEquals(OutputDisposition.KEPT_FOR_COPY, d.output)
        assertFalse(d.candidateRejected)
        assertFalse(d.qualifyingSavings)
        assertFalse(d.replacementEligible)
        assertFalse(d.pixelCertified)
        assertEquals(
            listOf("candidateGenerated", "structurallyVerified", "pixelCertified", "output", "candidateRejected",
                "qualifyingSavings", "replacementEligible"),
            d.fields().keys.toList()
        )
    }

    @Test
    fun aLossyOutputThatShrankCountsAndMayReplaceButCarriesNoCertificate() {
        val d = disposition(BatchTerminalResult.LOSSY_SMALLER, structural("High Quality (lossy mode)"), 60_000_000L, 60_000_000L)
        assertEquals(OutputDisposition.KEPT_FOR_COPY, d.output)
        assertTrue(d.qualifyingSavings)
        assertTrue(d.replacementEligible)
        assertFalse(d.pixelCertified)
    }

    @Test
    fun aCertifiedPerceptuallyLosslessOutputIsEveryStateAtOnce() {
        val d = disposition(
            BatchTerminalResult.TRANSCODED_SMALLER,
            structural(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED, pixelCertified = true),
            246_118_100L, 246_118_100L
        )
        assertTrue(d.candidateGenerated)
        assertEquals(true, d.structurallyVerified)
        assertTrue(d.pixelCertified)
        assertEquals(OutputDisposition.KEPT_FOR_COPY, d.output)
        assertTrue(d.qualifyingSavings)
        // Eligible is a property of the output. Whether the batch asked for the write is the `replace` event.
        assertTrue(d.replacementEligible)
    }

    @Test
    fun aVerifiedOutputThatIsNotSmallerIsACopyOnly() {
        val d = disposition(
            BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER,
            structural(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED, replacementSafe = false, pixelCertified = true),
            100_000_000L, 100_000_000L
        )
        assertEquals(OutputDisposition.KEPT_FOR_COPY, d.output)
        assertFalse(d.qualifyingSavings)
        assertFalse(d.replacementEligible)
    }

    @Test
    fun aSizeGateKeepGeneratedNothingSoNothingWasRejected() {
        // b287 job_8227cd6d6a43 and job_31e5faeaf665 (PL): kept on predicted size before any
        // encode. Their accept events read "rejected".
        val d = disposition(
            BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED, null, kept = 102_508_481L, candidate = null,
            retainedVerdict = "Original Retained (source readable; no copy written; not output-verified)", retainedReadable = true
        )
        assertFalse(d.candidateGenerated)
        assertFalse(d.candidateRejected)
        assertNull(d.structurallyVerified)
        assertFalse(d.pixelCertified)
        assertEquals(OutputDisposition.ORIGINAL_RETAINED, d.output)
        assertFalse(d.qualifyingSavings)
        assertFalse(d.replacementEligible)
    }

    @Test
    fun aCandidateDiscardedByCertificationIsGeneratedVerifiedAndRejected() {
        val d = disposition(
            BatchTerminalResult.SKIPPED_WOULD_DEGRADE, structural(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED),
            kept = 0L, candidate = 15_123_456L
        )
        assertTrue(d.candidateGenerated)
        assertEquals(true, d.structurallyVerified)
        assertTrue(d.candidateRejected)
        assertEquals(OutputDisposition.OUTPUT_NONE, d.output)
        assertFalse(d.qualifyingSavings)
        assertFalse(d.replacementEligible)
    }

    @Test
    fun anOriginalRetainedAfterADiscardedAttemptRecordsBoth() {
        val d = disposition(
            BatchTerminalResult.UNEXPECTED_REMUX, null, kept = 44_000_000L, candidate = 40_000_000L,
            retainedVerdict = "Original Retained (source readable; no copy written; not output-verified)", retainedReadable = true
        )
        assertTrue(d.candidateGenerated)
        assertTrue(d.candidateRejected)
        assertEquals(OutputDisposition.ORIGINAL_RETAINED, d.output)
        assertFalse(d.replacementEligible)
    }

    @Test
    fun anUnreadableRetainedSourceOffersNothing() {
        val d = disposition(
            BatchTerminalResult.OUTPUT_VALIDATION_FAILED, null, kept = 0L, candidate = null,
            retainedVerdict = "Original Retention Failed (source not readable)", retainedReadable = false
        )
        assertEquals(OutputDisposition.OUTPUT_NONE, d.output)
        assertFalse(d.candidateGenerated)
    }

    @Test
    fun aRemuxIsKeptAndReplaceableButNeverASaving() {
        val d = disposition(BatchTerminalResult.EXPLICIT_REMUX, structural("Remux Verified"), 99_000_000L, 99_000_000L)
        assertEquals(OutputDisposition.KEPT_FOR_COPY, d.output)
        assertFalse(d.qualifyingSavings)
        assertTrue(d.replacementEligible)
    }

    @Test
    fun aFailedOrSkippedJobHasNoOutput() {
        val failed = disposition(BatchTerminalResult.OUTPUT_VALIDATION_FAILED, structural("Remux Verification Failed", verified = false, replacementSafe = false), 5L, 5L)
        assertEquals(OutputDisposition.OUTPUT_NONE, failed.output)
        assertTrue(failed.candidateRejected)
        assertEquals(false, failed.structurallyVerified)
        val skipped = disposition(BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED, null, 0L, null)
        assertEquals(OutputDisposition.OUTPUT_NONE, skipped.output)
        assertFalse(skipped.candidateGenerated)
        assertFalse(skipped.candidateRejected)
    }

    private fun portraitInput(outputDurationMs: Long = 64_834L, mode: BatchQualityMode = BatchQualityMode.HIGH_QUALITY) =
        OutputVerifier.VerificationInput(
            mode = mode,
            source = VideoSourceInfo(width = 2160, height = 3840, frameRate = 30f, durationMs = 64_834, totalBitrate = 15_273_860, audioBitrate = 128_039),
            // Media3 codes a portrait source as landscape with a quarter-turn hint: the display size
            // is the source's, the rotation tag is not.
            outputFileProbe = OutputVerifier.FileProbe(2160, 3840, 30f, outputDurationMs, 90),
            sourceTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC, 15_145_821, 128_039, null, null, null, 2, 48_000),
            outputTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC, 14_900_287, 128_039, null, null, null, 2, 48_000),
            sourceMetadata = VideoMetadataSnapshot(rotationDegrees = 0),
            outputMetadata = VideoMetadataSnapshot(),
            sourceSize = 123_783_185L,
            outputSize = 122_130_776L,
            privacyMode = MetadataPrivacyMode.PRESERVE_ALL
        )

    @Test
    fun aVerifiedLossyOutputNamesItsDifferencesWithoutCallingThemFailures() {
        // The same HQ job: H.264 to HEVC, portrait coded as landscape. Its record carried
        // verified=true beside failedChecks=[videoCodec, rotation].
        val report = OutputVerifier.verify(portraitInput())
        assertEquals("High Quality (lossy mode)", report.verdict)
        assertTrue(report.verified)
        assertEquals(emptyList<String>(), report.failingChecks())
        assertTrue(report.unjudgedDifferences.toString(), report.unjudgedDifferences!!.containsAll(listOf("videoCodecMatches", "rotationMatches")))
        assertFalse(report.unjudgedDifferences!!.contains("playable"))
    }

    @Test
    fun aHardwareDownscaleCannotCountAsHighQualitySavings() {
        val input = portraitInput()
        val downscaled = input.copy(outputFileProbe = input.outputFileProbe.copy(width = 1080, height = 1920))
        assertRejected(downscaled, "videoMatches")
        assertTrue(OutputVerifier.verify(downscaled.copy(mode = BatchQualityMode.STORAGE_SAVER)).verified)
    }

    @Test
    fun aTruncatedLossyOutputDoesNotCountAsSavingsOrProduceACopy() {
        for (mode in listOf(BatchQualityMode.HIGH_QUALITY, BatchQualityMode.STORAGE_SAVER)) {
            val input = portraitInput(outputDurationMs = 5_000L, mode = mode).copy(outputSize = 9_000_000L)
            assertRejected(input, "durationMatches")
            assertRejected(input.copy(source = input.source.copy(durationMs = 0)), "durationMatches")
        }
        // Preserve the existing duration tolerance at either boundary.
        for (ms in listOf(64_834L, 64_400L, 65_900L)) {
            val whole = OutputVerifier.verify(portraitInput(outputDurationMs = ms))
            assertTrue("$ms", whole.replacementSafe)
            assertEquals("$ms", null, whole.replacementBlockReason)
        }
        assertRejected(portraitInput(outputDurationMs = 63_000L), "durationMatches")
    }

    @Test
    fun missingAudioOrAnUnreadableSourceInventoryCannotPassOnFileDuration() {
        for (mode in listOf(BatchQualityMode.HIGH_QUALITY, BatchQualityMode.STORAGE_SAVER)) {
            val input = portraitInput(mode = mode)
            val silentOutput = input.outputTrackProbe.copy(audioCodec = null, audioBitrate = 0, audioChannelCount = null, audioSampleRate = null)
            assertRejected(input.copy(outputTrackProbe = silentOutput), "contentTracksMatch")
            val silentSource = input.sourceTrackProbe.copy(audioCodec = null, audioBitrate = 0, audioChannelCount = null, audioSampleRate = null)
            assertTrue(OutputVerifier.verify(input.copy(sourceTrackProbe = silentSource, outputTrackProbe = silentOutput)).verified)
            assertRejected(input.copy(sourceTrackProbe = silentSource.copy(videoCodec = null), outputTrackProbe = silentOutput), "contentTracksMatch")
            val twoSoundtracks = input.sourceTrackProbe.contentTracks + OutputVerifier.ContentTrack("audio")
            assertRejected(input.copy(sourceTrackProbe = input.sourceTrackProbe.copy(extractedContentTracks = twoSoundtracks)), "contentTracksMatch")
        }
    }

    @Test
    fun differentSourceTrackDurationsAreValidButOneTruncatedTrackCannotHideBehindTheOther() {
        val input = portraitInput()
        val source = listOf(OutputVerifier.ContentTrack("video", 64_834L), OutputVerifier.ContentTrack("audio", 55_000L))
        val checked = input.copy(sourceTrackProbe = input.sourceTrackProbe.copy(extractedContentTracks = source))
        assertTrue(OutputVerifier.verify(checked.copy(outputTrackProbe = input.outputTrackProbe.copy(extractedContentTracks = source))).verified)
        for (kind in listOf("video", "audio")) {
            val truncated = source.map { if (it.kind == kind) it.copy(durationMs = 5_000L) else it }
            assertRejected(checked.copy(outputTrackProbe = input.outputTrackProbe.copy(extractedContentTracks = truncated)), "durationMatches")
        }
    }

    @Test
    fun completeLossyOutputCanChangeFrameRateAudioCodecAndStorageSaverResolution() {
        for (mode in listOf(BatchQualityMode.HIGH_QUALITY, BatchQualityMode.STORAGE_SAVER)) {
            val input = portraitInput(mode = mode)
            val output = input.outputFileProbe.copy(fps = 24f, frameCount = 1556)
            val report = OutputVerifier.verify(input.copy(
                outputFileProbe = if (mode == BatchQualityMode.STORAGE_SAVER) output.copy(width = 608, height = 1080) else output,
                sourceFrameCount = 1945,
                sourceTrackProbe = input.sourceTrackProbe.copy(audioCodec = "audio/mpeg")
            ))
            assertTrue(report.verified)
            assertTrue(report.replacementSafe)
            assertEquals(emptyList<String>(), report.failingChecks())
            assertNull(report.replacementBlockReason)
            assertFalse(report.pixelCertified)
        }
    }

    @Test
    fun droppingAnEmbeddedSubtitleTrackIsNotAValidOutputInAnyMode() {
        val input = portraitInput()
        val captions = requireNotNull(OutputVerifier.ContentTrack.from("application/x-quicktime-tx3g"))
        assertNull(OutputVerifier.ContentTrack.from("application/id3"))
        val withCaptions = input.sourceTrackProbe.copy(extractedContentTracks = input.sourceTrackProbe.contentTracks + captions)
        for (mode in BatchQualityMode.entries) {
            val sameStreams = input.copy(
                mode = mode,
                outputFileProbe = input.outputFileProbe.copy(rotationDegrees = 0),
                sourceTrackProbe = withCaptions,
                outputTrackProbe = withCaptions,
                videoTimeline = MediaTimelineEvidence.Result(true, 1945, 1945, 0, 0),
                audioPacketsIdentical = true
            )
            assertTrue(mode.name, OutputVerifier.verify(sameStreams).verified)
            assertRejected(sameStreams.copy(outputTrackProbe = input.sourceTrackProbe), "contentTracksMatch")
        }
    }

    @Test
    fun locationRemovalChecksTheActualOutputInEveryMode() {
        val input = portraitInput()
        val gps = VideoMetadataSnapshot(latitude = 42.25, longitude = -71.0, rotationDegrees = 0)
        val complete = input.copy(
            outputFileProbe = input.outputFileProbe.copy(rotationDegrees = 0),
            outputTrackProbe = input.sourceTrackProbe,
            sourceMetadata = gps, outputMetadata = gps,
            videoTimeline = MediaTimelineEvidence.Result(true, 1945, 1945, 0, 0),
            audioPacketsIdentical = true
        )
        for (mode in BatchQualityMode.entries) {
            val preserved = complete.copy(mode = mode)
            assertTrue(mode.name, OutputVerifier.verify(preserved).verified)
            val removing = preserved.copy(privacyMode = MetadataPrivacyMode.REMOVE_LOCATION)
            assertRejected(removing.copy(embeddedLocationDetected = true), "locationRemovalPass")
            val stripped = removing.copy(outputMetadata = VideoMetadataSnapshot(), embeddedLocationDetected = false)
            assertTrue(mode.name, OutputVerifier.verify(stripped).verified)
            assertEquals("no embedded location detected", OutputVerifier.verify(stripped).location)
            assertRejected(stripped.copy(embeddedLocationDetected = null), "locationRemovalPass")
            assertRejected(stripped.copy(outputMetadata = VideoMetadataSnapshot(rawLocationTag = "tag")), "locationRemovalPass")
        }
    }

    @Test
    fun anUnplayableLossyOutputReportsBothFailedIntegrityChecks() {
        val report = OutputVerifier.verify(portraitInput(outputDurationMs = 0L))
        assertEquals(listOf("playable", "durationMatches"), report.failingChecks())
    }

    private fun assertRejected(input: OutputVerifier.VerificationInput, failedCheck: String) {
        val report = OutputVerifier.verify(input)
        assertFalse(report.verified)
        assertFalse(report.replacementSafe)
        assertEquals(listOf(failedCheck), report.failingChecks())
        val terminal = BatchTerminalClassifier.classify(BatchTerminalInput(
            requestedMode = input.mode, effectiveMode = input.mode, wasStreamCopy = input.mode == BatchQualityMode.REMUX_ONLY,
            verified = report.verified, replacementSafe = report.replacementSafe, sourceSize = input.sourceSize, outputSize = input.outputSize
        ))
        assertEquals(BatchTerminalResult.OUTPUT_VALIDATION_FAILED, terminal)
        assertEquals(0L, BatchTerminalAccounting.savedBytes(BatchTerminalAccountingEntry(terminal, input.sourceSize, input.outputSize)))
        val d = disposition(terminal, report, kept = input.outputSize, candidate = input.outputSize)
        assertEquals(OutputDisposition.OUTPUT_NONE, d.output)
        assertTrue(d.candidateRejected)
        assertFalse(d.qualifyingSavings)
        assertFalse(d.replacementEligible)
    }
}
