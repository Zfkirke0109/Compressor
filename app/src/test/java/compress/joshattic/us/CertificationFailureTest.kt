package compress.joshattic.us

import androidx.media3.common.MimeTypes
import compress.joshattic.us.quality.CertificationDecision
import compress.joshattic.us.quality.ExhaustivePerceptualLosslessPolicy
import compress.joshattic.us.quality.PairScoreOutcome
import compress.joshattic.us.quality.QualityProbePolicy
import compress.joshattic.us.quality.WindowScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a certification outcome is evidence of, and what it does to the item and the learned
 * profile. Acceptance never changes: the pass rules are asserted unchanged alongside each case.
 */
class CertificationFailureTest {

    private fun w(frames: Int, mean: Double = 99.0, p5: Double = 98.0, min: Double = 97.0) =
        WindowScore(comparedFrames = frames, mean = mean, p5 = p5, min = min)

    private fun scored(vararg windows: WindowScore) = PairScoreOutcome.Scored(windows.toList())

    private val key = SmartPerceptualProfileEngine.profileKeyFor(
        source = VideoSourceInfo(
            width = 1356, height = 760, frameRate = 10f, durationMs = 128_104L,
            totalBitrate = 1_046_230, audioBitrate = 128_000,
            videoMime = MimeTypes.VIDEO_H264, audioMime = MimeTypes.AUDIO_AAC
        ),
        encoderMime = MimeTypes.VIDEO_H265, manufacturer = "samsung", model = "SM-S918U1", sdkInt = 37
    )

    /** Runs the rule on a fresh engine and returns its whole store afterwards. */
    private fun learnedAfter(decision: CertificationDecision): Map<String, String> {
        val store = SmartPerceptualProfileEngine.InMemoryProfileStore()
        val engine = SmartPerceptualProfileEngine(store)
        CertificationFailure.learn(engine, decision, key, 0.85, "reason", 0.60, 1.1)
        return store.snapshot()
    }

    @Test
    fun emptyAndUnavailableAreNotMeasurements() {
        assertEquals(CertificationDecision.UNAVAILABLE, CertificationDecision.of(PairScoreOutcome.Unavailable))
        assertEquals(CertificationDecision.UNAVAILABLE, CertificationDecision.of(PairScoreOutcome.Scored(emptyList())))
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, CertificationFailure.terminalFor(CertificationDecision.UNAVAILABLE))
        assertTrue(learnedAfter(CertificationDecision.UNAVAILABLE).isEmpty())
    }

    @Test
    fun elevenHighScoringFramesAreInsufficientNotAQualityFailure() {
        val outcome = scored(w(36), w(36), w(11))
        assertEquals(CertificationDecision.INSUFFICIENT_EVIDENCE, CertificationDecision.of(outcome))
        assertEquals(11, CertificationDecision.fewestFrames(outcome))
        // Not accepted, by any of the three certification rules; exactly as before.
        assertFalse(QualityProbePolicy.windowsPass(outcome.windows))
        assertFalse(ExhaustivePerceptualLosslessPolicy.measuredCertificationPasses(outcome))
        assertFalse(QualityProbePolicy.certificationOutcomePasses(0.85, 0.90, outcome))
        assertFalse(QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(outcome))
        assertFalse(QualityProbePolicy.isPixelCertified(false, outcome))
        // The item keeps its original without "would visibly lose quality", and nothing is learned.
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, CertificationFailure.terminalFor(CertificationDecision.INSUFFICIENT_EVIDENCE))
        assertTrue(learnedAfter(CertificationDecision.INSUFFICIENT_EVIDENCE).isEmpty())
        assertEquals(CertificationStatus.SCORED_INSUFFICIENT, CertificationStatus.forOutcome(outcome))
    }

    @Test
    fun twelveHighScoringFramesPass() {
        val outcome = scored(w(12), w(12), w(12))
        assertEquals(CertificationDecision.PASSED, CertificationDecision.of(outcome))
        assertTrue(QualityProbePolicy.windowsPass(outcome.windows))
        assertEquals(CertificationStatus.SCORED, CertificationStatus.forOutcome(outcome))
    }

    @Test
    fun anAdequateFailingWindowOutranksAnInsufficientOne() {
        // 36 frames below the mean bar, plus an 11-frame window: measured negative evidence.
        val outcome = scored(w(36, mean = 95.0), w(11))
        assertEquals(CertificationDecision.MEASURED_FAILURE, CertificationDecision.of(outcome))
        assertEquals(BatchTerminalResult.SKIPPED_WOULD_DEGRADE, CertificationFailure.terminalFor(CertificationDecision.MEASURED_FAILURE))
        assertEquals(1, learnedAfter(CertificationDecision.MEASURED_FAILURE).size)
        assertEquals(CertificationStatus.SCORED, CertificationStatus.forOutcome(outcome))
    }

    @Test
    fun b169s478cCertificationIsAMeasuredFailure() {
        // certWindowScores 98.975/96.746/96.746; 97.643/94.889/94.889; 95.423/92.856/92.856, 1.4 s
        // windows at 10 fps (14 frames). The third mean is 0.077 below 95.5.
        val outcome = scored(w(14, 98.975, 96.746, 96.746), w(14, 97.643, 94.889, 94.889), w(14, 95.423, 92.856, 92.856))
        assertEquals(CertificationDecision.MEASURED_FAILURE, CertificationDecision.of(outcome))
        val learned = learnedAfter(CertificationDecision.MEASURED_FAILURE)
        val profile = SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(learned.values.single())!!
        assertEquals(1, profile.failureCount)
    }

    @Test
    fun misalignmentStaysMeasuredAndDistinctFromUnavailability() {
        val outcome = PairScoreOutcome.MisalignmentRejected("internal frame misalignment after 2 leading drops")
        assertEquals(CertificationDecision.MISALIGNED, CertificationDecision.of(outcome))
        assertNotEquals(CertificationDecision.of(outcome), CertificationDecision.of(PairScoreOutcome.Unavailable))
        assertEquals(BatchTerminalResult.SKIPPED_WOULD_DEGRADE, CertificationFailure.terminalFor(CertificationDecision.MISALIGNED))
        assertEquals(1, learnedAfter(CertificationDecision.MISALIGNED).size)
    }

    @Test
    fun aMarginalCertificationStillPassesTheUnchangedBar() {
        // Probe selection's margin is not applied to certification: 95.6 clears 95.5.
        val outcome = scored(w(36, 95.6, 91.1, 84.1))
        assertEquals(CertificationDecision.PASSED, CertificationDecision.of(outcome))
        assertTrue(QualityProbePolicy.windowsPass(outcome.windows))
    }

    @Test
    fun floorRecoveryStatusesKeepTheSameDistinction() {
        assertEquals(CertificationStatus.RECOVERY_INSUFFICIENT, CertificationStatus.forFailedRecoveryOutcome(scored(w(11))))
        assertEquals(CertificationStatus.RECOVERY_SCORED_FAILED, CertificationStatus.forFailedRecoveryOutcome(scored(w(20, mean = 90.0))))
        assertEquals(CertificationStatus.RECOVERY_MISALIGNED, CertificationStatus.forFailedRecoveryOutcome(PairScoreOutcome.MisalignmentRejected(null)))
        assertEquals(CertificationStatus.RECOVERY_UNAVAILABLE, CertificationStatus.forFailedRecoveryOutcome(PairScoreOutcome.Unavailable))
    }

    @Test
    fun onlyMeasuredNegativesTeach() {
        for (d in CertificationDecision.entries) {
            val learned = learnedAfter(d)
            if (d == CertificationDecision.MEASURED_FAILURE || d == CertificationDecision.MISALIGNED) {
                assertEquals(d.name, 1, learned.size)
            } else {
                assertTrue(d.name, learned.isEmpty())
            }
        }
        assertNull(CertificationFailure.learn(
            SmartPerceptualProfileEngine(SmartPerceptualProfileEngine.InMemoryProfileStore()),
            CertificationDecision.INSUFFICIENT_EVIDENCE, key, 0.85, "r", 0.6, null
        ))
    }
}
