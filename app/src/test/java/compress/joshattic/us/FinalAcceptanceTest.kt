package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The job record's final verdict, kept apart from the structural verifier's. Reproduces the two
 * b169 records (job_478c2fa19100, job_c92a4ca7e1be) that read SKIPPED_WOULD_DEGRADE, outputSize=0,
 * pixelCertified=false, and also "Perceptually Lossless Verified", verified=true, replacementSafe=true.
 */
class FinalAcceptanceTest {

    private fun structural(verified: Boolean = true, replacementSafe: Boolean = true, verdict: String = OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED) =
        OutputVerificationReport(
            verdict = verdict, playability = "opens", video = "", fps = "", videoBitrate = "", videoCodec = "",
            audioCodec = "", audioDetails = "", audioBitrate = "", hdr = "", colorStandard = "", colorRange = "",
            mediaStoreDate = "", mp4Date = "", location = "", rotation = "", fileSize = "",
            replacementSafe = replacementSafe, verified = verified
        )

    @Test
    fun aCandidateDiscardedByCertificationIsRejected() {
        // job_478c2fa19100: 0.85 full encode, 15.1 MB candidate, third window 95.423 < 95.5.
        val a = FinalAcceptance.of(
            terminal = BatchTerminalResult.SKIPPED_WOULD_DEGRADE,
            structural = structural(),
            keptOutputBytes = 0L,
            candidateBytes = 15_123_456L
        )
        assertFalse(a.accepted)
        assertFalse(a.verified)
        assertFalse(a.replacementSafe)
        assertEquals("Rejected — ${BatchTerminalResult.SKIPPED_WOULD_DEGRADE.label}", a.verdict)
        assertEquals(0L, a.acceptedOutputBytes)
        // The evidence is kept, under its own name.
        assertEquals(15_123_456L, a.candidateBytes)
        assertEquals(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED, a.structuralVerdict)
        assertEquals(true, a.structuralVerified)
        assertEquals(true, a.structuralReplacementSafe)
    }

    @Test
    fun anUnverifiableRejectionIsAlsoRejected() {
        val a = FinalAcceptance.of(BatchTerminalResult.UNEXPECTED_REMUX, structural(), keptOutputBytes = 0L, candidateBytes = 9L)
        assertFalse(a.accepted)
        assertFalse(a.replacementSafe)
    }

    @Test
    fun aCertifiedSmallerOutputIsAcceptedAndReplaceable() {
        // job_d127463b57d6: 246,118,100 bytes kept.
        val a = FinalAcceptance.of(BatchTerminalResult.TRANSCODED_SMALLER, structural(), 246_118_100L, 246_118_100L)
        assertTrue(a.accepted)
        assertTrue(a.verified)
        assertTrue(a.replacementSafe)
        assertEquals(246_118_100L, a.acceptedOutputBytes)
        assertEquals(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED, a.verdict)
    }

    @Test
    fun aKeptOutputIsReplaceableOnlyWhenItsTerminalAllowsIt() {
        // A remux fallback kept a stream copy: an output, never a replacement.
        val remux = FinalAcceptance.of(BatchTerminalResult.UNEXPECTED_REMUX, structural(), 1_157_608L, 1_157_608L)
        assertTrue(remux.accepted)
        assertFalse(remux.replacementSafe)
        val noWin = FinalAcceptance.of(BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER, structural(), 100L, 100L)
        assertFalse(noWin.replacementSafe)
    }

    @Test
    fun aFailureTerminalMovesTheMeasuredSizeToCandidateBytes() {
        // finalizeItem used to record a failed output's size as outputSize.
        val a = FinalAcceptance.of(
            BatchTerminalResult.OUTPUT_VALIDATION_FAILED,
            structural(verified = false, replacementSafe = false, verdict = "Verification failed"),
            keptOutputBytes = 5_000_000L, candidateBytes = null
        )
        assertFalse(a.accepted)
        assertEquals(0L, a.acceptedOutputBytes)
        assertEquals(5_000_000L, a.candidateBytes)
    }

    @Test
    fun aRetainedOriginalIsNeverAnAcceptedOrReplaceableOutput() {
        val a = FinalAcceptance.of(
            BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED, structural = null, keptOutputBytes = 8_293_690L,
            candidateBytes = null, retainedVerdict = "Original retained", retainedReadable = true
        )
        assertFalse(a.accepted)
        assertFalse(a.replacementSafe)
        assertTrue(a.verified)
        assertNull(a.structuralVerdict)
    }

    @Test
    fun noVerificationMeansNothingToAccept() {
        val a = FinalAcceptance.of(BatchTerminalResult.CANCELLED, null, 0L, null)
        assertFalse(a.accepted)
        assertNull(a.verdict)
        assertFalse(a.replacementSafe)
    }

    @Test
    fun theSavedTotalCountsOnlyAcceptedOutputs() {
        val rejected = BatchTerminalAccountingEntry(BatchTerminalResult.SKIPPED_WOULD_DEGRADE, 16_753_345L, 0L)
        val won = BatchTerminalAccountingEntry(BatchTerminalResult.TRANSCODED_SMALLER, 322_388_270L, 246_118_100L)
        assertEquals(76_270_170L, BatchTerminalAccounting.summarize(listOf(rejected, won)).totalBytesSaved)
    }
}
