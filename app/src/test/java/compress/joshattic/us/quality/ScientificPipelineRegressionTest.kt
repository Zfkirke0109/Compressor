package compress.joshattic.us.quality

import org.junit.Assert.*
import org.junit.Test

class ScientificPipelineRegressionTest {
    @Test fun inaccessibleHardWindowsCannotCollapseOntoTheOpeningScene() {
        val index = object : ProbeWindowPlanner.SyncSampleIndex {
            override fun previousSyncUs(targetUs: Long) = 0L
            override fun nextSyncUs(targetUs: Long): Long? = null
        }
        val plan = ProbeWindowPlanner.plan(1_800_000_000L, index)
        assertTrue(plan.windows.isEmpty())
        assertEquals(3, plan.unplaceable.size)
    }

    @Test fun aDistantNextKeyframeDoesNotReplaceTheRequestedContent() {
        val index = object : ProbeWindowPlanner.SyncSampleIndex {
            override fun previousSyncUs(targetUs: Long) = 0L
            override fun nextSyncUs(targetUs: Long) = 400_000_000L
        }
        assertNull(ProbeWindowPlanner.place(100_000_000L, 600_000_000L, 1_200_000L, index))
    }

    @Test fun invalidShadowScoresAreAbsentDiagnostics() {
        assertNull(WindowV1Diag.fromPerFrame(doubleArrayOf(99.0, Double.POSITIVE_INFINITY)))
    }

    @Test fun emptySelfCheckCannotReportIdentity() {
        assertTrue(SelfCheckVerdict.identity(emptyList()).startsWith("UNPROVEN"))
    }
}
