package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raising [VmafPairScorer.MAX_COMPARE_PIXELS] to 4K-class only stays safe because queue depth is
 * derived from frame size instead of being a fixed count: a fixed 4-deep pair of queues holds
 * ~25 MB of decoded frames at 1080p but ~100 MB at 4K, on top of two live hardware decoders.
 *
 * These tests pin the two properties that matter — 1080p behavior is unchanged, and peak in-flight
 * bytes stay bounded at every scoreable geometry.
 */
class VmafPairScorerQueueBudgetTest {

    private fun frameBytes(width: Int, height: Int): Long =
        width.toLong() * height.toLong() * 3L / 2L

    /** Worst case in flight: both queues full. */
    private fun peakBytes(width: Int, height: Int): Long =
        2L * VmafPairScorer.queueCapacityFor(width, height) * frameBytes(width, height)

    @Test
    fun smallGeometriesKeepTheOriginalQueueDepth() {
        // Regression guard: the pre-existing 1080p-class path must behave exactly as before.
        assertEquals(4, VmafPairScorer.queueCapacityFor(1920, 1080))
        assertEquals(4, VmafPairScorer.queueCapacityFor(1920, 1088))
        assertEquals(4, VmafPairScorer.queueCapacityFor(1280, 720))
        assertEquals(4, VmafPairScorer.queueCapacityFor(640, 480))
    }

    @Test
    fun largeGeometriesShrinkTheQueueInsteadOfGrowingMemory() {
        // 4K frames are ~12.4 MB each, so the depth has to come down.
        assertTrue(VmafPairScorer.queueCapacityFor(3840, 2160) < 4)
        assertTrue(VmafPairScorer.queueCapacityFor(3840, 2176) < 4)
        // ...but never below a single frame, or the pipeline could not make progress at all.
        assertTrue(VmafPairScorer.queueCapacityFor(3840, 2176) >= 1)
        assertTrue(VmafPairScorer.queueCapacityFor(7680, 4320) >= 1)
    }

    @Test
    fun peakQueuedBytesStayBoundedAcrossEveryScoreableGeometry() {
        val geometries = listOf(
            640 to 480,
            1280 to 720,
            1920 to 1080,
            1920 to 1088,
            2560 to 1440,
            3840 to 2160,
            3840 to 2176,
            2160 to 3840 // portrait 4K
        )
        val budget = 64L * 1024 * 1024
        geometries.forEach { (w, h) ->
            assertTrue(
                "peak queued bytes for ${w}x$h must stay within the budget",
                peakBytes(w, h) <= budget
            )
        }
    }

    @Test
    fun everyScoreableGeometryIsAlsoBudgetable() {
        // The cap and the budget must agree: a geometry the scorer accepts must always yield a
        // usable queue depth, otherwise score() would allocate a zero-capacity ArrayBlockingQueue.
        listOf(1920 to 1080, 2560 to 1440, 3840 to 2160, 3840 to 2176).forEach { (w, h) ->
            assertTrue(QualityProbePolicy.isPixelScoreableGeometry(w, h))
            assertTrue(VmafPairScorer.queueCapacityFor(w, h) >= 1)
        }
    }

    @Test
    fun degenerateDimensionsNeverProduceAnInvalidQueue() {
        assertEquals(1, VmafPairScorer.queueCapacityFor(0, 0))
        assertEquals(1, VmafPairScorer.queueCapacityFor(-1, 1080))
    }
}
