package compress.joshattic.us.quality

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The b166 self-check: the source against itself scored 97.43 on the first frame of every window
 * and 100 on the rest. MotionContext feeds one pre-window pair and drops its score.
 */
class MotionContextTest {

    @Test
    fun theContextScoreIsDroppedAndTheRestKept() {
        val raw = doubleArrayOf(97.43, 100.0, 100.0, 99.5)
        assertArrayEquals(doubleArrayOf(100.0, 100.0, 99.5), MotionContext.scoredSpan(raw, 1), 0.0)
    }

    @Test
    fun withoutAContextFrameNothingIsDropped() {
        val raw = doubleArrayOf(97.43, 100.0)
        assertArrayEquals(raw, MotionContext.scoredSpan(raw, 0), 0.0)
    }

    @Test
    fun onlyAContextFrameMeansNoScoredFrames() {
        assertEquals(0, MotionContext.scoredSpan(doubleArrayOf(97.43), 1)!!.size)
        assertNull(MotionContext.scoredSpan(null, 1))
    }

    @Test
    fun certificationWindowsDecodeContextAndProbeWindowsUseTheirLeadIn() {
        val w = ProbeWindowPlanner.PlannedWindow(clipStartUs = 1_000_000L, startUs = 3_000_000L, endUs = 4_200_000L,
            anchor = ProbeWindowPlanner.Anchor.PREVIOUS_KEYFRAME)
        assertEquals(MotionContext.CERTIFICATION_CONTEXT_US, w.scoreWindowForCertification().contextUs)
        assertEquals(0L, w.scoreWindowForProbeClip().contextUs)
        assertTrue(w.scoreWindowForProbeClip().leadInUs > 0L)
        // At least one frame precedes the window down to 4 fps.
        assertTrue(MotionContext.CERTIFICATION_CONTEXT_US >= 250_000L)
    }
}
