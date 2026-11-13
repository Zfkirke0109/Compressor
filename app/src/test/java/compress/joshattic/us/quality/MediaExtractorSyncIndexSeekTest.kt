package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync index's seek target when the video track records no duration. It used to clamp every
 * target into [0, 0], so on fragmented MP4s (screen recorders) and some MKV/WebM files every
 * probe and certification window snapped to the first keyframe, and a long file could be
 * certified on its opening seconds alone.
 */
class MediaExtractorSyncIndexSeekTest {

    private val minute = 60_000_000L

    /** An index that seeks the way MediaExtractorSyncIndex does, over keyframes every second. */
    private fun indexWithTrackDuration(trackDurationUs: Long) = object : ProbeWindowPlanner.SyncSampleIndex {
        private val gap = 1_000_000L
        private fun at(t: Long) = MediaExtractorSyncIndex.seekTargetUs(t, trackDurationUs)
        override fun previousSyncUs(targetUs: Long) = (at(targetUs) / gap) * gap
        override fun nextSyncUs(targetUs: Long) = ((at(targetUs) + gap - 1) / gap) * gap
    }

    @Test
    fun anUnknownTrackDurationDoesNotPullEveryTargetToZero() {
        assertEquals(90 * 1_000_000L, MediaExtractorSyncIndex.seekTargetUs(90 * 1_000_000L, 0L))
        assertEquals(0L, MediaExtractorSyncIndex.seekTargetUs(-5L, 0L))
    }

    @Test
    fun aKnownTrackDurationStillBoundsTheTarget() {
        assertEquals(minute, MediaExtractorSyncIndex.seekTargetUs(2 * minute, minute))
        assertEquals(30 * 1_000_000L, MediaExtractorSyncIndex.seekTargetUs(30 * 1_000_000L, minute))
    }

    @Test
    fun windowsOfAFileWithoutTrackDurationSpreadAcrossTheWholeFile() {
        val duration = 10 * minute
        val plan = ProbeWindowPlanner.plan(durationUs = duration, index = indexWithTrackDuration(0L))
        assertEquals(3, plan.windows.size)
        // Same placement as a file whose track does record its duration.
        val reference = ProbeWindowPlanner.plan(durationUs = duration, index = indexWithTrackDuration(duration))
        assertEquals(reference.windows, plan.windows)
        // The last window is in the last third of the file, not the first twelve seconds.
        assertTrue("last window at ${plan.windows.last().startUs}", plan.windows.last().startUs > duration / 2)
    }
}
