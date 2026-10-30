package compress.joshattic.us

import compress.joshattic.us.quality.PairScoreOutcome
import compress.joshattic.us.quality.WindowScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two 219-job captures recorded null certWindowScores and null certBandingDiag for all 438 jobs
 * with nothing explaining why. A null measurement field is ambiguous — it cannot distinguish
 * "certification correctly did not apply" from "certification silently broke" — so every job now
 * records which gate closed.
 */
class CertificationStatusTest {

    @Test
    fun anOpenPathHasNoBlockReason() {
        assertNull(
            CertificationStatus.blockReasonFor(
                isHdr = false, codecDowngrade = false,
                vmafAvailable = true, geometryScoreable = true
            )
        )
    }

    @Test
    fun eachClosedGateIsNamed() {
        assertEquals(
            CertificationStatus.SKIPPED_HDR,
            CertificationStatus.blockReasonFor(true, false, true, true)
        )
        assertEquals(
            CertificationStatus.SKIPPED_CODEC_DOWNGRADE,
            CertificationStatus.blockReasonFor(false, true, true, true)
        )
        assertEquals(
            CertificationStatus.SKIPPED_VMAF_UNAVAILABLE,
            CertificationStatus.blockReasonFor(false, false, false, true)
        )
        assertEquals(
            CertificationStatus.SKIPPED_GEOMETRY_ABOVE_CAP,
            CertificationStatus.blockReasonFor(false, false, true, false)
        )
    }

    @Test
    fun theReportedReasonIsStableWhenSeveralGatesAreClosed() {
        // Grouping a capture by reason requires a deterministic answer, not an arbitrary one.
        repeat(5) {
            assertEquals(
                CertificationStatus.SKIPPED_HDR,
                CertificationStatus.blockReasonFor(true, true, false, false)
            )
        }
    }

    @Test
    fun everyRunOutcomeMapsToADistinctStatus() {
        val scored = PairScoreOutcome.Scored(listOf(WindowScore(30, 97.0, 93.0, 88.0)))
        assertEquals(CertificationStatus.SCORED, CertificationStatus.forOutcome(scored))
        assertEquals(
            CertificationStatus.UNAVAILABLE,
            CertificationStatus.forOutcome(PairScoreOutcome.Unavailable)
        )
        assertEquals(
            CertificationStatus.MISALIGNED,
            CertificationStatus.forOutcome(PairScoreOutcome.MisalignmentRejected(null))
        )
        // The three must stay distinguishable: "measured and misaligned" is evidence AGAINST,
        // while "unavailable" is merely absent evidence, and they drive different decisions.
        val all = setOf(
            CertificationStatus.forOutcome(scored),
            CertificationStatus.forOutcome(PairScoreOutcome.Unavailable),
            CertificationStatus.forOutcome(PairScoreOutcome.MisalignmentRejected(null))
        )
        assertEquals(3, all.size)
    }

    @Test
    fun ranAndSkippedAreDistinguishable() {
        assertFalse(CertificationStatus.didNotRun(CertificationStatus.SCORED))
        assertFalse(CertificationStatus.didNotRun(CertificationStatus.UNAVAILABLE))
        assertFalse(CertificationStatus.didNotRun(CertificationStatus.MISALIGNED))
        assertTrue(CertificationStatus.didNotRun(CertificationStatus.SKIPPED_HDR))
        assertTrue(CertificationStatus.didNotRun(CertificationStatus.SKIPPED_FELL_BACK_TO_REMUX))
        assertTrue(CertificationStatus.didNotRun(CertificationStatus.SKIPPED_GEOMETRY_ABOVE_CAP))
        // Absent status is not a claim either way.
        assertFalse(CertificationStatus.didNotRun(null))
    }

    @Test
    fun theCaptureExplanationForTheObservedRunsIsRepresentable() {
        // Both captures ended every generated output in a failed verification, which skips
        // certification. That specific cause must be nameable, or the 438 nulls stay a mystery.
        assertTrue(
            CertificationStatus.didNotRun(CertificationStatus.SKIPPED_FELL_BACK_TO_REMUX)
        )
    }
}
