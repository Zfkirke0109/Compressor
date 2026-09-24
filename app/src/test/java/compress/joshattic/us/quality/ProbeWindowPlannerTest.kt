package compress.joshattic.us.quality

import compress.joshattic.us.quality.ProbeWindowPlanner.Anchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe clips must start at a keyframe and score only after a lead-in; see ProbeWindowPlanner
 * for the b163 evidence (identical minimum scores across bitrates; probe-vs-full gap of ~6).
 */
class ProbeWindowPlannerTest {

    /** A source with keyframes every [gapUs]. */
    private fun regular(gapUs: Long) = object : ProbeWindowPlanner.SyncSampleIndex {
        override fun previousSyncUs(targetUs: Long) = (targetUs / gapUs) * gapUs
        override fun nextSyncUs(targetUs: Long) = ((targetUs + gapUs - 1) / gapUs) * gapUs
    }

    /** A source with a single keyframe at 0 (a screen recording or stream capture). */
    private val singleKeyframe = object : ProbeWindowPlanner.SyncSampleIndex {
        override fun previousSyncUs(targetUs: Long) = 0L
        override fun nextSyncUs(targetUs: Long): Long? = null
    }

    private val minute = 60_000_000L

    @Test
    fun aShortGopSourceKeepsItsWantedPositionsWithAtLeastTwoSecondsOfLeadIn() {
        val plan = ProbeWindowPlanner.plan(durationUs = 2 * minute, index = regular(1_000_000L))
        assertEquals(3, plan.windows.size)
        assertTrue(plan.unplaceable.isEmpty())
        for (w in plan.windows) {
            assertEquals(Anchor.PREVIOUS_KEYFRAME, w.anchor)
            assertTrue("lead-in ${w.leadInUs}", w.leadInUs >= ProbeWindowPlanner.MIN_LEAD_IN_US)
            assertTrue(w.leadInUs <= ProbeWindowPlanner.MAX_LEAD_IN_US)
            assertEquals(0L, w.clipStartUs % 1_000_000L) // on a keyframe
            assertEquals(1_200_000L, w.endUs - w.startUs)
        }
        // The window did not drift away from where it was wanted.
        val wanted = QualityProbePolicy.probeWindows(2 * minute).map { it.startUs }
        plan.windows.zip(wanted).forEach { (w, want) -> assertTrue(kotlin.math.abs(w.startUs - want) < 1_000_000L) }
    }

    @Test
    fun aLongGopSourceMovesTheWindowToJustAfterTheNextKeyframe() {
        // Keyframes every 60 s: the previous one is up to a minute back, beyond MAX_LEAD_IN_US.
        val plan = ProbeWindowPlanner.plan(durationUs = 10 * minute, index = regular(minute))
        assertTrue(plan.windows.isNotEmpty())
        for (w in plan.windows) {
            assertEquals(0L, w.clipStartUs % minute)
            assertTrue(w.leadInUs in ProbeWindowPlanner.MIN_LEAD_IN_US..ProbeWindowPlanner.MAX_LEAD_IN_US)
        }
    }

    @Test
    fun aSingleKeyframeSourceGetsOneWindowNearTheStartInsteadOfNothing() {
        // The b163 30-minute files: any window away from the start means decoding from 0.
        val plan = ProbeWindowPlanner.plan(durationUs = 30 * minute, index = singleKeyframe)
        assertEquals(1, plan.windows.size)
        val w = plan.windows.single()
        assertEquals(0L, w.clipStartUs)
        assertEquals(ProbeWindowPlanner.MIN_LEAD_IN_US, w.startUs)
    }

    @Test
    fun withoutAnIndexTheClipStillStartsALeadInBeforeTheWindow() {
        val plan = ProbeWindowPlanner.plan(durationUs = 2 * minute, index = null)
        assertEquals(3, plan.windows.size)
        for (w in plan.windows) {
            assertEquals(Anchor.UNINDEXED, w.anchor)
            assertEquals(ProbeWindowPlanner.MIN_LEAD_IN_US, w.leadInUs)
        }
    }

    @Test
    fun aVeryShortClipTakesWhateverLeadInFits() {
        // 3 s clip, one centred window: the lead-in is whatever room there is before it.
        val plan = ProbeWindowPlanner.plan(durationUs = 3_000_000L, index = regular(1_000_000L))
        assertEquals(1, plan.windows.size)
        val w = plan.windows.single()
        assertTrue(w.endUs <= 3_000_000L)
        assertTrue(w.leadInUs in 0L..ProbeWindowPlanner.MIN_LEAD_IN_US)
    }

    @Test
    fun probeAndCertificationScoreTheSameFrames() {
        val w = ProbeWindowPlanner.plan(2 * minute, regular(1_000_000L)).windows.first()
        val probe = w.scoreWindowForProbeClip()
        val cert = w.scoreWindowForCertification()
        assertEquals(cert.startUs, probe.startUs)
        assertEquals(cert.endUs, probe.endUs)
        assertEquals(0L, probe.distStartUs)
        assertTrue(probe.alignFirstFrames)
        assertEquals(w.leadInUs, probe.leadInUs)
        assertEquals(0L, cert.leadInUs)
    }
}
