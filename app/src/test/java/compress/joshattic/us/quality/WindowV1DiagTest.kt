package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The v1 shadow summary must never invent a score from a broken or missing native result. */
class WindowV1DiagTest {

    @Test
    fun summarisesMeanP5AndMinLikeTheVerdictScore() {
        val perFrame = doubleArrayOf(99.0, 97.0, 95.0, 93.0, 91.0)
        val d = WindowV1Diag.fromPerFrame(perFrame)!!
        assertEquals(95.0, d.mean, 1e-9)
        assertEquals(91.0, d.min, 1e-9)
        assertEquals(91.0, d.p5, 1e-9)
        assertEquals("95.000/91.000/91.000", d.compact())
    }

    @Test
    fun anyFailedFrameVoidsTheShadowScoreRatherThanSkewingIt() {
        // Native code reports a per-frame failure as -1. Averaging it in would drag the shadow
        // mean down and look like a real quality signal.
        assertNull(WindowV1Diag.fromPerFrame(doubleArrayOf(99.0, -1.0, 98.0)))
        assertNull(WindowV1Diag.fromPerFrame(doubleArrayOf(99.0, Double.NaN)))
        assertNull(WindowV1Diag.fromPerFrame(doubleArrayOf()))
        assertNull(WindowV1Diag.fromPerFrame(null))
    }
}
