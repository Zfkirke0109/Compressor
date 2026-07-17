package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityProbePolicyTest {

    private fun good(frames: Int = 30) = WindowScore(frames, mean = 97.0, p5 = 93.0, min = 88.0)

    @Test
    fun candidateLadderIsLowestFirstClampedToHardFloor() {
        assertEquals(listOf(0.70, 0.80, 0.90), QualityProbePolicy.candidateRatios(0.90, allowBelowDefault = true))
        // Ladder can never go below the hard floor even for high defaults.
        assertEquals(
            listOf(0.60, 0.65, 0.75),
            QualityProbePolicy.candidateRatios(0.75, allowBelowDefault = true)
        )
        assertEquals(listOf(0.90), QualityProbePolicy.candidateRatios(0.90, allowBelowDefault = false))
        assertTrue(QualityProbePolicy.candidateRatios(0.62, true).all { it >= QualityProbePolicy.HARD_RATIO_FLOOR })
    }

    @Test
    fun windowsPassRequiresEveryWindowAboveEveryThreshold() {
        assertTrue(QualityProbePolicy.windowsPass(listOf(good(), good(), good())))
        assertFalse(QualityProbePolicy.windowsPass(null))
        assertFalse(QualityProbePolicy.windowsPass(emptyList()))
        // One weak window rejects the whole ladder step.
        assertFalse(QualityProbePolicy.windowsPass(listOf(good(), good().copy(p5 = 89.9))))
        assertFalse(QualityProbePolicy.windowsPass(listOf(good().copy(mean = 95.0))))
        assertFalse(QualityProbePolicy.windowsPass(listOf(good().copy(min = 80.0))))
        // Too few compared frames = no evidence, not weak evidence.
        assertFalse(QualityProbePolicy.windowsPass(listOf(good(frames = 5))))
    }

    @Test
    fun certificationFailsClosedOnlyForSubDefaultRatios() {
        val passing = listOf(good(), good())
        val failing = listOf(good(), good().copy(min = 60.0))

        // Measured results always decide, regardless of ratio.
        assertTrue(QualityProbePolicy.certificationPasses(0.70, 0.90, passing))
        assertFalse(QualityProbePolicy.certificationPasses(0.70, 0.90, failing))
        assertFalse(QualityProbePolicy.certificationPasses(0.90, 0.90, failing))

        // Unmeasurable: the legacy default-ratio path keeps its structural verdict...
        assertTrue(QualityProbePolicy.certificationPasses(0.90, 0.90, null))
        // ...but a sub-default encode's only justification WAS pixel evidence -> fail closed.
        assertFalse(QualityProbePolicy.certificationPasses(0.70, 0.90, null))
    }

    @Test
    fun measuredMisalignmentNeverCertifiesNotEvenStructurally() {
        val passing = PairScoreOutcome.Scored(listOf(good(), good()))
        val failing = PairScoreOutcome.Scored(listOf(good(), good().copy(min = 60.0)))

        // Scored outcomes decide exactly like the legacy list path.
        assertTrue(QualityProbePolicy.certificationOutcomePasses(0.90, 0.90, passing))
        assertFalse(QualityProbePolicy.certificationOutcomePasses(0.90, 0.90, failing))

        // Unavailable evidence keeps the legacy structural fallback semantics.
        assertTrue(QualityProbePolicy.certificationOutcomePasses(0.90, 0.90, PairScoreOutcome.Unavailable))
        assertFalse(QualityProbePolicy.certificationOutcomePasses(0.70, 0.90, PairScoreOutcome.Unavailable))

        // Measured misalignment is evidence AGAINST the output (frame loss/retiming):
        // it must fail even at the default ratio, where mere unavailability would pass.
        assertFalse(QualityProbePolicy.certificationOutcomePasses(0.90, 0.90, PairScoreOutcome.MisalignmentRejected))
        assertFalse(QualityProbePolicy.certificationOutcomePasses(0.70, 0.90, PairScoreOutcome.MisalignmentRejected))
    }

    @Test
    fun probeWindowsSampleAwayFromClipEdges() {
        // Too short to probe honestly.
        assertTrue(QualityProbePolicy.probeWindows(1_500_000L).isEmpty())

        // Short clip: one centered window.
        val single = QualityProbePolicy.probeWindows(6_000_000L)
        assertEquals(1, single.size)
        assertTrue(single[0].startUs > 0 && single[0].endUs < 6_000_000L)

        // Long clip: three windows at 20/50/80%, all inside the clip.
        val durationUs = 60_000_000L
        val triple = QualityProbePolicy.probeWindows(durationUs)
        assertEquals(3, triple.size)
        triple.forEach {
            assertTrue(it.startUs >= 0)
            assertTrue(it.endUs <= durationUs)
            assertEquals(1_200_000L, it.endUs - it.startUs)
        }
        assertTrue(triple[0].startUs < triple[1].startUs && triple[1].startUs < triple[2].startUs)
    }

    @Test
    fun probeFastPathFloorSitsBelowTheTransparencyGate() {
        // The fast path may only skip probing for sources ALREADY excluded by evidence margin;
        // it must never eat into the gray band the ladder is meant to test (0.05..0.08 bpp).
        assertTrue(
            QualityProbePolicy.PROBE_MIN_SOURCE_BITS_PER_PIXEL <
                compress.joshattic.us.BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MIN_SOURCE_BITS_PER_PIXEL
        )
    }

    @Test
    fun windowThresholdsSitAboveTheFullClipSuiteThresholds() {
        // The margin is the whole point: sampled windows must look BETTER than the full-clip
        // bar (mean 95 / 1%-low 90 / min 80) before a lower ratio is trusted.
        assertTrue(QualityProbePolicy.WINDOW_MEAN_MIN > 95.0)
        assertTrue(QualityProbePolicy.WINDOW_P5_MIN > 90.0)
        assertTrue(QualityProbePolicy.WINDOW_MIN_MIN > 80.0)
        assertNull(null) // keep junit4 import shape stable
    }

    @Test
    fun bppClassedLadderShapesMatchTheEvidence() {
        // Healthy sources: full downward ladder PLUS one safer retreat rung above the default.
        assertEquals(
            listOf(0.70, 0.80, 0.90, 0.95),
            QualityProbePolicy.candidateRatiosForSource(0.90, sourceBitsPerPixel = 0.15)
        )
        // The retreat rung is capped at the safest useful ceiling.
        assertEquals(
            listOf(0.75, 0.85, 0.95, 0.97),
            QualityProbePolicy.candidateRatiosForSource(0.95, sourceBitsPerPixel = 0.20)
        )
        // Starved-but-probeable sources get safest-biased rungs only: their low rungs are
        // already disproven, but the safer rungs have never been measured — these are their
        // first-ever trial encodes instead of a prediction-only rejection.
        assertEquals(
            listOf(0.90, 0.95),
            QualityProbePolicy.candidateRatiosForSource(0.90, sourceBitsPerPixel = 0.05)
        )
        // Below the noise line: no ladder; inference stands.
        assertTrue(QualityProbePolicy.candidateRatiosForSource(0.90, sourceBitsPerPixel = 0.02).isEmpty())
        assertTrue(QualityProbePolicy.candidateRatiosForSource(0.90, sourceBitsPerPixel = null).isEmpty())
        // Ladder never drops below the hard floor.
        assertTrue(
            QualityProbePolicy.candidateRatiosForSource(0.70, sourceBitsPerPixel = 0.30)
                .all { it >= QualityProbePolicy.HARD_RATIO_FLOOR }
        )
    }

    @Test
    fun refinementBisectsOnlyWhenTheGapIsWorthAnEncode() {
        // Pass at 0.80 after failing 0.70: midpoint 0.75 is worth one probe.
        assertEquals(0.75, QualityProbePolicy.refinementCandidate(0.80, 0.70)!!, 1e-9)
        // Narrow gap: not worth an extra encode.
        assertNull(QualityProbePolicy.refinementCandidate(0.75, 0.70))
        // Nothing failed below: bisect toward the hard floor when the gap allows.
        assertEquals(0.65, QualityProbePolicy.refinementCandidate(0.70, null)!!, 1e-9)
        // Passing at the floor itself leaves nothing to refine.
        assertNull(QualityProbePolicy.refinementCandidate(QualityProbePolicy.HARD_RATIO_FLOOR, null))
        // The refinement may never dip below the hard floor.
        val refined = QualityProbePolicy.refinementCandidate(0.90, null)
        assertTrue(refined == null || refined >= QualityProbePolicy.HARD_RATIO_FLOOR)
    }
}
