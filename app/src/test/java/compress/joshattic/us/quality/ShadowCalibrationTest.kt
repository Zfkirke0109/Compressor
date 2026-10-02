package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** b177 WP2: the v1 shadow is opt-in and budgeted, and never changes a v0 decision. */
class ShadowCalibrationTest {

    @Test
    fun offUnlessEnabledThenBoundedByGeometryAndBudget() {
        assertEquals(ShadowCalibration.Decision(false, "off"), ShadowCalibration.decide(false, 0, 3, 1920, 1080))
        assertTrue(ShadowCalibration.decide(true, 0, 3, 1920, 1080).shadow)
        assertEquals("above_1080p_class", ShadowCalibration.decide(true, 0, 3, 3840, 2160).reason)
        assertEquals("above_1080p_class", ShadowCalibration.decide(true, 0, 3, 2160, 3840).reason)
        assertTrue(ShadowCalibration.decide(true, ShadowCalibration.MAX_WINDOWS_PER_BATCH - 3, 3, 1080, 1920).shadow)
        assertEquals("batch_budget_spent", ShadowCalibration.decide(true, ShadowCalibration.MAX_WINDOWS_PER_BATCH - 2, 3, 1080, 1920).reason)
    }

    @Test
    fun v1DataNeverChangesTheVerdict() {
        // b177 job_458aa0663c3e window 2: v0 97.27/94.64/94.19, v1 94.095/90.986/90.179.
        val v0Only = WindowScore(36, 97.27, 94.64, 94.19)
        val withV1 = v0Only.copy(v1 = WindowV1Diag(94.095, 90.986, 90.179))
        // And a v1 score that would fail every bar if it were read.
        val v1Failing = v0Only.copy(v1 = WindowV1Diag(10.0, 5.0, 1.0))
        for (basis in CertificationGate.Basis.values()) {
            val expected = CertificationGate.evaluate(basis, 0.9, 0.9, PairScoreOutcome.Scored(List(3) { v0Only }))
            assertEquals(expected, CertificationGate.evaluate(basis, 0.9, 0.9, PairScoreOutcome.Scored(List(3) { withV1 })))
            assertEquals(expected, CertificationGate.evaluate(basis, 0.9, 0.9, PairScoreOutcome.Scored(List(3) { v1Failing })))
            assertTrue(expected.accepted)
        }
        assertFalse(ShadowCalibration.decide(false, 0, 3, 1920, 1080).shadow)
    }

    @Test
    fun cancellationStopsTheWindowLoopAndPropagates() {
        var scored = 0
        try {
            VmafPairScorer.collectWindows(listOf(1, 2, 3), null) {
                if (it == 2) VmafPairScorer.WindowOutcome.Cancelled
                else VmafPairScorer.WindowOutcome.Scored(WindowScore(36, 99.0, 98.0, 97.0)).also { scored++ }
            }
            throw AssertionError("cancellation was swallowed")
        } catch (e: java.util.concurrent.CancellationException) {
            assertEquals(1, scored) // window 3 never started
        }
    }

    @Test
    fun timingIsCompactAndComplete() {
        val t = WindowTiming(170_000, 1_200, 20_000, 300, 50, 140_000, 6_800, 9_000, 400_000, 30_000, 31_000, 44, 44, 3840, 2160, 4, 2)
        assertEquals(
            "wall=170000,queue=1200,v0=20000+300,cambi=50,v1=140000+6800,cpu[thread=9000,process=400000,refDec=30000,distDec=31000]," +
                "frames=44/44,3840x2160,threads=4/2",
            t.compact()
        )
    }
}
