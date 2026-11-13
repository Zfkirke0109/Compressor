package compress.joshattic.us

import compress.joshattic.us.quality.PairScoreOutcome
import compress.joshattic.us.quality.WindowScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Certification runs at two different sites and they answer different questions, so a capture has
 * to be able to tell them apart.
 *
 * Device-observed in batch_1786670702437: a job carried real measured windows
 * (`93.662/92.969/92.119;88.533/87.107/85.098;…`) alongside
 * `certificationStatus: "skipped_output_already_failed_verification"`. Certification had in fact
 * run — as the bitrate-floor recovery attempt — measured below the bar, and the encode fell back;
 * the skip reason was then evaluated afterwards and overwrote the truth. Populated scores beside a
 * status claiming nothing ran is the exact absent-vs-unexplained confusion this type exists to
 * prevent, and it hides the most useful datum: the numbers that rejected the encode.
 */
class CertificationStatusRecoveryTest {

    private val scored = PairScoreOutcome.Scored(
        listOf(WindowScore(comparedFrames = 35, mean = 88.533, p5 = 87.107, min = 85.098))
    )

    @Test
    fun aFailedRecoveryIsReportedAsHavingRunNotAsSkipped() {
        val status = CertificationStatus.forFailedRecoveryOutcome(scored)
        assertEquals(CertificationStatus.RECOVERY_SCORED_FAILED, status)
        assertFalse(
            "a recovery that measured windows must never read as skipped",
            CertificationStatus.didNotRun(status)
        )
    }

    @Test
    fun everyRecoveryOutcomeReadsAsHavingRun() {
        for (outcome in listOf(scored, PairScoreOutcome.Unavailable, PairScoreOutcome.MisalignmentRejected(null))) {
            val status = CertificationStatus.forFailedRecoveryOutcome(outcome)
            assertFalse(status, CertificationStatus.didNotRun(status))
            assertTrue(status, status.startsWith("ran_"))
        }
    }

    @Test
    fun recoveryStatusesAreDistinctFromFinalCertificationStatuses() {
        // Same scorer outcome, different question asked — the capture must not conflate them.
        for (outcome in listOf(scored, PairScoreOutcome.Unavailable, PairScoreOutcome.MisalignmentRejected(null))) {
            assertTrue(
                CertificationStatus.forOutcome(outcome) !=
                    CertificationStatus.forFailedRecoveryOutcome(outcome)
            )
        }
    }

    @Test
    fun theSkipReasonThatMislabelledItStillMeansSkipped() {
        // Unchanged behaviour for the case it genuinely describes: certification never started.
        assertTrue(CertificationStatus.didNotRun(CertificationStatus.SKIPPED_FELL_BACK_TO_REMUX))
    }
}
