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

    private fun highQualityPortrait(outputDurationMs: Long = 64_834L) = OutputVerifier.verify(
        OutputVerifier.VerificationInput(
            mode = BatchQualityMode.HIGH_QUALITY,
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
    )

    @Test
    fun aVerifiedLossyOutputNamesItsDifferencesWithoutCallingThemFailures() {
        // The same HQ job: H.264 to HEVC, portrait coded as landscape. Its record carried
        // verified=true beside failedChecks=[videoCodec, rotation].
        val report = highQualityPortrait()
        assertEquals("High Quality (lossy mode)", report.verdict)
        assertTrue(report.verified)
        assertEquals(emptyList<String>(), report.failingChecks())
        assertTrue(report.unjudgedDifferences.toString(), report.unjudgedDifferences!!.containsAll(listOf("videoCodecMatches", "rotationMatches")))
        assertFalse(report.unjudgedDifferences!!.contains("playable"))
    }

    @Test
    fun aLossyOutputThatStopsEarlyMayNeverReplaceItsSource() {
        // Five seconds of a 64.8 s source: playable, and far smaller for the wrong reason. The
        // lossy verdict is unchanged, but it is not the whole video and must not overwrite it.
        val truncated = highQualityPortrait(outputDurationMs = 5_000L)
        assertTrue(truncated.verified)
        assertFalse(truncated.replacementSafe)
        assertEquals("output duration differs from the source (possible truncation)", truncated.replacementBlockReason)
        assertEquals(emptyList<String>(), truncated.failingChecks())
        assertTrue(truncated.unjudgedDifferences!!.contains("durationMatches"))
        val acceptance = FinalAcceptance.of(BatchTerminalResult.LOSSY_SMALLER, truncated, 9_000_000L, 9_000_000L)
        assertFalse(acceptance.replacementSafe)
        assertFalse(OutputDisposition.of(BatchTerminalResult.LOSSY_SMALLER, acceptance, originalRetained = false, pixelCertified = false).replacementEligible)
        // Inside the tolerance (the larger of 500 ms and 2 %) it is the whole video.
        for (ms in listOf(64_834L, 64_400L, 65_900L)) {
            val whole = highQualityPortrait(outputDurationMs = ms)
            assertTrue("$ms", whole.replacementSafe)
            assertEquals("$ms", null, whole.replacementBlockReason)
        }
        assertFalse(highQualityPortrait(outputDurationMs = 63_000L).replacementSafe)
    }

    @Test
    fun anUnplayableLossyOutputStillFailsOnAPredicateItIsJudgedOn() {
        val report = highQualityPortrait(outputDurationMs = 0L)
        assertFalse(report.verified)
        assertFalse(report.replacementSafe)
        assertEquals(listOf("playable"), report.failingChecks())
    }
}
