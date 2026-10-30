package compress.joshattic.us.quality

import androidx.media3.common.MimeTypes
import compress.joshattic.us.BatchTerminalResult
import compress.joshattic.us.CertificationFailure
import compress.joshattic.us.CertificationStatus
import compress.joshattic.us.SmartPerceptualProfileEngine
import compress.joshattic.us.VideoSourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b177 F1: a window that cannot be scored must not erase the windows scored before it.
 *
 * Each case runs the scorer's own window loop ([VmafPairScorer.collectWindows], the code
 * `VmafPairScorer.score` runs) with scripted per-window outcomes, then the certification gate
 * `certifyPixels` uses ([CertificationGate]) on every basis a plan can have, then the rejection
 * rule ([CertificationFailure]) against a real learning engine. Thresholds are the production
 * ones; nothing here sets a bar.
 */
class PartialScoringTest {

    private fun w(frames: Int = 36, mean: Double = 99.0, p5: Double = 98.0, min: Double = 97.0) =
        WindowScore(comparedFrames = frames, mean = mean, p5 = p5, min = min)

    private val unavailable = VmafPairScorer.WindowOutcome.Unavailable
    private fun scored(score: WindowScore) = VmafPairScorer.WindowOutcome.Scored(score)

    /** The scorer's loop over three planned windows, each answering with the scripted outcome. */
    private fun loop(vararg outcomes: VmafPairScorer.WindowOutcome): Pair<PairScoreOutcome, List<Pair<Int, Int>>> {
        val progress = mutableListOf<Pair<Int, Int>>()
        val planned = outcomes.indices.toList()
        val result = VmafPairScorer.collectWindows(planned, { d, t -> progress += d to t }) { outcomes[it] }
        return result to progress
    }

    private val key = SmartPerceptualProfileEngine.profileKeyFor(
        source = VideoSourceInfo(
            width = 1920, height = 1080, frameRate = 30f, durationMs = 60_000L,
            totalBitrate = 20_000_000, audioBitrate = 128_000,
            videoMime = MimeTypes.VIDEO_H264, audioMime = MimeTypes.AUDIO_AAC
        ),
        encoderMime = MimeTypes.VIDEO_H265, manufacturer = "samsung", model = "SM-S918U1", sdkInt = 37
    )

    private fun learnedAfter(decision: CertificationDecision): Map<String, String> {
        val store = SmartPerceptualProfileEngine.InMemoryProfileStore()
        CertificationFailure.learn(SmartPerceptualProfileEngine(store), decision, key, 0.90, "r", 0.60, 1.0)
        return store.snapshot()
    }

    /** Every basis a plan can certify under, at the codec default ratio (the legacy fallback's home). */
    private val bases = listOf(
        CertificationGate.Basis.MEASURED_REQUIRED,
        CertificationGate.Basis.PROBE_BASIS,
        CertificationGate.Basis.NO_PROBE_BASIS
    )

    private fun gate(basis: CertificationGate.Basis, outcome: PairScoreOutcome) =
        CertificationGate.evaluate(basis, usedRatio = 0.90, defaultRatio = 0.90, outcome = outcome)

    @Test
    fun anAdequateFailureFollowedByAnUnavailableWindowIsAMeasuredFailureOnEveryBasis() {
        val (outcome, progress) = loop(scored(w(mean = 90.0)), unavailable, scored(w()))
        // The later windows are still attempted; the failure is kept, not replaced by "unavailable".
        assertTrue(outcome is PairScoreOutcome.Incomplete)
        assertEquals(listOf(w(mean = 90.0), w()), outcome.scoredWindows)
        assertEquals(listOf(1 to 3, 2 to 3), progress)
        assertEquals(CertificationDecision.MEASURED_FAILURE, CertificationDecision.of(outcome))
        for (basis in bases) {
            val verdict = gate(basis, outcome)
            assertFalse("$basis accepted a measured failure", verdict.accepted)
            assertFalse(verdict.pixelCertified)
        }
        // Rejected as a measured degradation, taught as one, nothing kept.
        assertEquals(BatchTerminalResult.SKIPPED_WOULD_DEGRADE, CertificationFailure.terminalFor(CertificationDecision.MEASURED_FAILURE))
        assertEquals(1, learnedAfter(CertificationDecision.MEASURED_FAILURE).size)
        // The record says the sample was partial, and the decision says what it proved.
        assertEquals(CertificationStatus.SCORED_PARTIAL, CertificationStatus.forOutcome(outcome))
    }

    @Test
    fun beforeTheFixTheSameSequenceWasAcceptedStructurally() {
        // The pre-fix outcome for this sequence was bare Unavailable. It is still accepted
        // structurally at the default ratio (probe and no-probe bases): that is the legacy rule
        // for real absence of evidence, and why losing the first window was dangerous.
        assertFalse(gate(CertificationGate.Basis.PROBE_BASIS, PairScoreOutcome.Unavailable).accepted)
        assertFalse(gate(CertificationGate.Basis.NO_PROBE_BASIS, PairScoreOutcome.Unavailable).accepted)
        assertFalse(gate(CertificationGate.Basis.MEASURED_REQUIRED, PairScoreOutcome.Unavailable).accepted)
    }

    @Test
    fun aPassingPrefixFollowedByAnUnavailableWindowIsNeverPixelCertification() {
        val (outcome, _) = loop(scored(w()), scored(w()), unavailable)
        assertTrue(outcome is PairScoreOutcome.Incomplete)
        assertEquals(CertificationDecision.PARTIAL, CertificationDecision.of(outcome))
        assertFalse(CertificationDecision.PARTIAL.isMeasuredNegative)
        // Where the plan needed measured proof, a partial sample is not proof.
        assertFalse(gate(CertificationGate.Basis.MEASURED_REQUIRED, outcome).accepted)
        // Where missing evidence was already tolerated structurally, the partial positive sample is
        // tolerated the same way, and it is still not pixel certification.
        for (basis in bases) assertFalse(gate(basis, outcome).pixelCertified)
        assertFalse(gate(CertificationGate.Basis.PROBE_BASIS, outcome).accepted)
        assertFalse(gate(CertificationGate.Basis.NO_PROBE_BASIS, outcome).accepted)
        // Below the default ratio a sub-default target rested on pixels alone: fails closed.
        assertFalse(CertificationGate.evaluate(CertificationGate.Basis.PROBE_BASIS, 0.85, 0.90, outcome).accepted)
        // Not a measured failure: kept original without "would degrade", and nothing learned.
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, CertificationFailure.terminalFor(CertificationDecision.PARTIAL))
        assertTrue(learnedAfter(CertificationDecision.PARTIAL).isEmpty())
        assertEquals(CertificationStatus.SCORED_PARTIAL, CertificationStatus.forOutcome(outcome))
    }

    @Test
    fun anInadequatePrefixFollowedByAnUnavailableWindowIsUndecidedAndNeverAccepted() {
        val (outcome, _) = loop(scored(w(frames = 11)), unavailable, unavailable)
        assertEquals(CertificationDecision.INSUFFICIENT_EVIDENCE, CertificationDecision.of(outcome))
        // Same as an all-scored set with an 11-frame window: fails on every basis, teaches nothing.
        for (basis in bases) assertFalse("$basis", gate(basis, outcome).accepted)
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, CertificationFailure.terminalFor(CertificationDecision.INSUFFICIENT_EVIDENCE))
        assertTrue(learnedAfter(CertificationDecision.INSUFFICIENT_EVIDENCE).isEmpty())
    }

    @Test
    fun anAdequateFailureOutranksAnInadequateWindow() {
        val (outcome, _) = loop(scored(w(frames = 11)), scored(w(p5 = 88.0)), scored(w()))
        assertTrue(outcome is PairScoreOutcome.Scored)
        assertEquals(CertificationDecision.MEASURED_FAILURE, CertificationDecision.of(outcome))
        for (basis in bases) assertFalse(gate(basis, outcome).accepted)
        assertEquals(1, learnedAfter(CertificationDecision.MEASURED_FAILURE).size)
    }

    @Test
    fun allWindowsPassingIsPixelCertifiedOnEveryBasis() {
        val (outcome, progress) = loop(scored(w()), scored(w()), scored(w()))
        assertTrue(outcome is PairScoreOutcome.Scored)
        assertEquals(CertificationDecision.PASSED, CertificationDecision.of(outcome))
        for (basis in bases) {
            val verdict = gate(basis, outcome)
            assertTrue(verdict.accepted)
            assertTrue(verdict.pixelCertified)
        }
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun nothingScoredIsStillPlainUnavailable() {
        val (outcome, progress) = loop(unavailable, unavailable, unavailable)
        assertEquals(PairScoreOutcome.Unavailable, outcome)
        assertNull(outcome.scoredWindows)
        assertTrue(progress.isEmpty())
    }

    @Test
    fun misalignmentStillFailsClosedAndKeepsWhatWasScored() {
        val (outcome, _) = loop(
            scored(w(mean = 90.0)), VmafPairScorer.WindowOutcome.Misaligned("internal frame misalignment"), scored(w())
        )
        assertTrue(outcome is PairScoreOutcome.MisalignmentRejected)
        assertEquals(listOf(w(mean = 90.0)), outcome.scoredWindows)
        assertEquals(CertificationDecision.MISALIGNED, CertificationDecision.of(outcome))
        for (basis in bases) assertFalse(gate(basis, outcome).accepted)
    }
}
