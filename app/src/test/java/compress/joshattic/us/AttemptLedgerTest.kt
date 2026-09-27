package compress.joshattic.us

import compress.joshattic.us.quality.CertificationDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b177 F3: every started attempt is counted once, whatever ended it. Each case follows the order
 * in which BatchCompressorViewModel calls the ledger: start in encodeReadingSource, then exactly
 * one of fallBackAfterEncodeFailure (export), applyPerceptualVerdict (structural),
 * certifyPixels (certification), finalizeItem (accepted / not accepted), handleItemFailure or the
 * cancellation handler (closeOpen).
 */
class AttemptLedgerTest {

    private fun firstAttemptFailsCertification(): AttemptLedger = AttemptLedger().apply {
        start(token = 260, ratio = 0.85, probeRungId = "0.85@aaa", configId = "aaa", atMs = 1_000)
        assertTrue(finish(CertificationDecision.MEASURED_FAILURE.wire, 14_928_138, 7_680))
    }

    @Test
    fun measuredFailureThenRetryExportFailureCountsTwoStartedAttempts() {
        val ledger = firstAttemptFailsCertification()
        ledger.start(token = 261, ratio = 0.90, probeRungId = "0.90@bbb", configId = "bbb", atMs = 9_000)
        assertTrue(ledger.finish(AttemptLedger.EXPORT_FAILED, 0, 3_000))
        // The item then retains its original: the terminal path's closeOpen finds nothing open.
        assertFalse(ledger.closeOpen(AttemptLedger.FAILED))
        assertEquals(2, ledger.started)
        assertEquals(0, ledger.accepted())
        assertEquals(listOf("measured_below_bar", "export_failed"), ledger.all.map { it.outcome })
        assertEquals(listOf(1, 2), ledger.all.map { it.index })
        assertEquals(listOf(260, 261), ledger.all.map { it.token })
    }

    @Test
    fun measuredFailureThenRetryStructuralFailure() {
        val ledger = firstAttemptFailsCertification()
        ledger.start(261, 0.90, "0.90@bbb", "bbb", 9_000)
        ledger.finish(AttemptLedger.STRUCTURAL_FAILED, 15_000_000, 8_000)
        // finalizeItem after a stream-copy fallback must not overwrite the structural outcome.
        assertFalse(ledger.finish(AttemptLedger.notAccepted(BatchTerminalResult.UNEXPECTED_REMUX), 16_000_000, 0))
        assertEquals("structural_failed", ledger.all.last().outcome)
        assertEquals(15_000_000, ledger.all.last().candidateBytes)
        assertEquals(2, ledger.started)
    }

    @Test
    fun measuredFailureThenCancellationDuringTheRetry() {
        val ledger = firstAttemptFailsCertification()
        ledger.start(261, 0.90, "0.90@bbb", "bbb", 9_000)
        assertTrue(ledger.closeOpen(AttemptLedger.CANCELLED))
        assertEquals(listOf("measured_below_bar", "cancelled"), ledger.all.map { it.outcome })
        assertEquals(2, ledger.started)
    }

    @Test
    fun measuredFailureThenAcceptedRetry() {
        // b177 PL-B job_478c2fa19100, as the ledger records it.
        val ledger = firstAttemptFailsCertification()
        ledger.start(261, 0.90, "0.90@bbb", "bbb", 9_000)
        ledger.finish(AttemptLedger.ACCEPTED, 15_658_828, 8_249)
        assertEquals(2, ledger.started)
        assertEquals(1, ledger.accepted())
        assertEquals(
            listOf(
                "0.85:measured_below_bar:cand=14928138:encodeMs=7680:n=1:token=260:rung=0.85@aaa:cfg=aaa",
                "0.90:accepted:cand=15658828:encodeMs=8249:n=2:token=261:rung=0.90@bbb:cfg=bbb"
            ),
            ledger.wire()
        )
    }

    @Test
    fun anUnexpectedExceptionClosesTheOpenAttemptAsFailed() {
        val ledger = AttemptLedger()
        ledger.start(5, 0.9, null, "c", 0)
        assertTrue(ledger.closeOpen(AttemptLedger.FAILED))
        assertEquals("failed", ledger.all.single().outcome)
    }

    @Test
    fun anAttemptIsNeverLostWhenANewOneStarts() {
        val ledger = AttemptLedger()
        ledger.start(1, 0.85, null, "a", 0)
        ledger.start(2, 0.90, null, "b", 10)
        assertEquals(listOf("superseded", "open"), ledger.all.map { it.outcome ?: "open" })
        assertEquals(2, ledger.started)
    }

    @Test
    fun theIndexIsPerJobAndIndependentOfTheGlobalToken() {
        val a = AttemptLedger().apply { start(315, 0.9, null, "x", 0) }
        assertEquals(1, a.currentIndex)
        assertEquals(315, a.current!!.token)
        assertEquals(0, AttemptLedger().currentIndex)
    }
}
