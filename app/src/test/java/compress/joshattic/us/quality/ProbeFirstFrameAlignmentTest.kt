package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays batch_1790263711162 (b161). Probe clips have their first frame at 0 (715 of 715), while
 * the source's first frame sits `lead` after the requested start. Normalising the source by the
 * requested start and the clip by 0 either failed to align ("leading offset not aligned", 142 of
 * 201 ladders measured nothing) or paired source frame k with clip frame k+1 (all 34 such windows
 * scored mean VMAF 11-86 and were recorded as MEASURED rejections).
 */
class ProbeFirstFrameAlignmentTest {

    private val frameUs = 33_333L          // 30 fps
    private val startUs = 4_050_500L       // a real requested window start from the capture

    /** Source frames at/after the window start, first one [leadUs] into the window. */
    private fun sourceFrames(leadUs: Long, n: Int) = List(n) { startUs + leadUs + it * frameUs }

    /** The probe clip: the same frames, rewritten to start at 0. */
    private fun clipFrames(n: Int) = List(n) { it * frameUs }

    /**
     * Runs the scorer's pairing loop: returns the (sourceIndex, clipIndex) pairs, or null when the
     * aligner fails the window.
     */
    private fun pair(src: List<Long>, clip: List<Long>, refOrigin: StreamOrigin, distOrigin: StreamOrigin): List<Pair<Int, Int>>? {
        val aligner = PtsAligner()
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < src.size && j < clip.size) {
            val r = refOrigin.normalize(src[i])
            val d = distOrigin.normalize(clip[j])
            when (aligner.decide(r, d)) {
                PtsAligner.Action.PAIR -> { pairs += i to j; i++; j++ }
                PtsAligner.Action.DROP_REF -> i++
                PtsAligner.Action.DROP_DIST -> j++
                PtsAligner.Action.FAIL -> return null
            }
        }
        return pairs
    }

    @Test
    fun oldNormalisationFailsASubFrameLead() {
        // 16 ms lead: the exact "unclosed skew 16ms" of the first window in the capture.
        val result = pair(sourceFrames(16_000, 36), clipFrames(36), StreamOrigin(startUs), StreamOrigin(0L))
        assertEquals(null, result)
    }

    @Test
    fun oldNormalisationPairsTheWrongFramesWhenTheLeadIsNearlyAFrame() {
        // 31 ms lead: the aligner "closes" the skew by dropping clip frame 0, so every pair
        // compares source frame k with clip frame k+1, which is a different picture.
        val result = pair(sourceFrames(31_000, 36), clipFrames(36), StreamOrigin(startUs), StreamOrigin(0L))!!
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { (s, c) -> c == s + 1 })
    }

    @Test
    fun firstFrameOriginsPairEveryFrameWithItself() {
        for (lead in listOf(0L, 1_500L, 4_000L, 16_000L, 22_000L, 31_000L, 33_000L)) {
            val result = pair(sourceFrames(lead, 36), clipFrames(36), StreamOrigin(null), StreamOrigin(null))!!
            assertEquals("lead=$lead", 36, result.size)
            assertTrue("lead=$lead", result.all { (s, c) -> s == c })
        }
    }

    @Test
    fun aFrameMissingInsideTheClipStillFailsTheWindow() {
        // First-frame alignment only fixes the ORIGIN. Real frame loss after it must still fail.
        val clip = clipFrames(36).toMutableList().apply { removeAt(10) }
        val result = pair(sourceFrames(16_000, 36), clip, StreamOrigin(null), StreamOrigin(null))
        assertEquals(null, result)
    }

    @Test
    fun aFixedOriginIsUnchanged() {
        val origin = StreamOrigin(1_000L)
        assertEquals(500L, origin.normalize(1_500L))
        assertEquals(1_000L, origin.originUs)
    }

    @Test
    fun aFirstFrameOriginIsFixedByTheFirstFrameOnly() {
        val origin = StreamOrigin(null)
        assertEquals(0L, origin.normalize(4_066_500L))
        assertEquals(33_333L, origin.normalize(4_099_833L))
        assertEquals(4_066_500L, origin.originUs)
    }
}
