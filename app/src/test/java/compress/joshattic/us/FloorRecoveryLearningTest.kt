package compress.joshattic.us

import compress.joshattic.us.quality.CertificationDecision
import compress.joshattic.us.quality.PairScoreOutcome
import compress.joshattic.us.quality.VmafPairScorer
import compress.joshattic.us.quality.WindowScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b177 F6: what a discarded encode whose only structural failure was the video bitrate floor
 * teaches, given what the floor-recovery pixels were evidence of.
 *
 * The chain is the one `verifyStructurally` + `applyPerceptualVerdict` run: the recovery outcome
 * (from the scorer's window loop), its [CertificationDecision], then
 * [LearningEvidencePolicy.applyVerificationFailure] against a real engine. The original is kept
 * in every case here; only the learned-state delta differs.
 *
 * Policy (documented in LearningEvidencePolicy): the structural floor failure alone teaches the
 * legacy step-up only when no pixel was measured (no recovery, or a recovery that scored nothing).
 * Measured pixels below the bar teach it too. Pixels that were measured and did NOT fail — too few
 * frames, a partial sample, or a pass — leave the learned state unchanged.
 */
class FloorRecoveryLearningTest {

    private val key = SmartPerceptualProfileEngine.EncodeProfileKey(
        "samsung", "sm-s918u1", 37, "video/hevc", "video/avc", "720p", "10", "unknown", "lt10m", "mp4a-latm-lt160k"
    )
    private val floorOnly = listOf("videoBitratePass")

    private fun w(frames: Int, mean: Double = 99.2, p5: Double = 97.3, min: Double = 97.3) =
        WindowScore(comparedFrames = frames, mean = mean, p5 = p5, min = min)

    private fun recovery(vararg windows: WindowScore?): PairScoreOutcome =
        VmafPairScorer.collectWindows(windows.toList(), null) { window ->
            if (window == null) VmafPairScorer.WindowOutcome.Unavailable else VmafPairScorer.WindowOutcome.Scored(window)
        }

    /** Applies the rule to a fresh engine; returns the kind and the store's contents afterwards. */
    private fun apply(recoveryDecision: CertificationDecision?): Pair<LearningEvidencePolicy.Kind, Map<String, String>> {
        val store = SmartPerceptualProfileEngine.InMemoryProfileStore()
        val kind = LearningEvidencePolicy.applyVerificationFailure(
            engine = SmartPerceptualProfileEngine(store),
            failingChecks = floorOnly,
            floorRecovery = recoveryDecision,
            key = key, usedRatio = 0.85, reason = "video bitrate below floor", floorRatio = 0.60, measuredOvershoot = 0.93
        )
        return kind to store.snapshot()
    }

    @Test
    fun highScoresOnElevenFramesAreUndecidedAndTeachNothing() {
        // b168 job_478c2fa19100's shape: every window far above the bar, one holding 11 frames.
        val outcome = recovery(w(36), w(36), w(11))
        val decision = CertificationDecision.of(outcome)
        assertEquals(CertificationDecision.INSUFFICIENT_EVIDENCE, decision)
        val (kind, store) = apply(decision)
        assertEquals(LearningEvidencePolicy.Kind.UNDECIDED, kind)
        assertTrue("an undecided recovery changed the learned state: $store", store.isEmpty())
    }

    @Test
    fun aPartialPassingRecoveryTeachesNothing() {
        val decision = CertificationDecision.of(recovery(w(36), null, w(36)))
        assertEquals(CertificationDecision.PARTIAL, decision)
        val (kind, store) = apply(decision)
        assertEquals(LearningEvidencePolicy.Kind.UNDECIDED, kind)
        assertTrue(store.isEmpty())
    }

    @Test
    fun aMeasuredRecoveryFailureStepsTheRatioUp() {
        val decision = CertificationDecision.of(recovery(w(36, mean = 94.0), null, w(36)))
        assertEquals(CertificationDecision.MEASURED_FAILURE, decision)
        val (kind, store) = apply(decision)
        assertEquals(LearningEvidencePolicy.Kind.QUALITY, kind)
        val learned = SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(store.getValue(key.asKey()))
        assertNotNull(learned)
        assertEquals(0.85 + SmartPerceptualProfileEngine.FAILURE_STEP_UP, learned!!.nextTargetRatio!!, 1e-9)
    }

    @Test
    fun withNoPixelMeasuredTheStructuralFloorTeachesNothing() {
        for (decision in listOf(null, CertificationDecision.of(recovery(null, null, null)))) {
            val (kind, store) = apply(decision)
            assertEquals("$decision", LearningEvidencePolicy.Kind.UNDECIDED, kind)
            assertTrue(store.isEmpty())
        }
    }

    @Test
    fun theLegacyOneArgumentRuleIsUnchanged() {
        assertEquals(LearningEvidencePolicy.Kind.UNDECIDED, LearningEvidencePolicy.classifyVerificationFailure(floorOnly))
        assertEquals(LearningEvidencePolicy.Kind.PIPELINE, LearningEvidencePolicy.classifyVerificationFailure(listOf("playable")))
    }
}
