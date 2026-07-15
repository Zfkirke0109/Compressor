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
}
