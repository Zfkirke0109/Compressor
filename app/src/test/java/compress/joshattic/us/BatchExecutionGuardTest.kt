package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard is the single point that decides "a batch is running" -> OS priority. Its idempotency
 * is the safety property: a double begin must not create duplicate notifications/wake locks, and a
 * double end must not double-release. These pin that contract without Android.
 */
class BatchExecutionGuardTest {

    /** Counting fake sink: records how many times each effect fired and enforces balanced pairing. */
    private class FakeSink : BatchExecutionSink {
        var starts = 0; private set
        var stops = 0; private set
        var acquires = 0; private set
        var releases = 0; private set
        val events = mutableListOf<String>()

        override fun startForegroundProtection() { starts++; events += "start" }
        override fun stopForegroundProtection() { stops++; events += "stop" }
        override fun acquireCpuWakeLock() { acquires++; events += "acquire" }
        override fun releaseCpuWakeLock() { releases++; events += "release" }
    }

    @Test
    fun beginFiresEffectsExactlyOnceAndIsIdempotent() {
        val sink = FakeSink()
        val guard = BatchExecutionGuard(sink)

        guard.begin()
        guard.begin()
        guard.begin()

        assertTrue(guard.isActive)
        assertEquals(1, sink.starts)   // no duplicate foreground notification
        assertEquals(1, sink.acquires) // no duplicate wake lock
    }

    @Test
    fun endFiresReleaseExactlyOnceAndIsIdempotent() {
        val sink = FakeSink()
        val guard = BatchExecutionGuard(sink)

        guard.begin()
        guard.end()
        guard.end()
        guard.end()

        assertFalse(guard.isActive)
        assertEquals(1, sink.releases) // no double-release
        assertEquals(1, sink.stops)
    }

    @Test
    fun endBeforeBeginIsANoOp() {
        val sink = FakeSink()
        val guard = BatchExecutionGuard(sink)

        guard.end()

        assertFalse(guard.isActive)
        assertEquals(0, sink.releases)
        assertEquals(0, sink.stops)
    }

    @Test
    fun reuseAcrossRunsBracketsEachRunOnceInReverseOrder() {
        val sink = FakeSink()
        val guard = BatchExecutionGuard(sink)

        guard.begin(); guard.end()   // run 1
        guard.begin(); guard.end()   // run 2

        assertEquals(2, sink.starts)
        assertEquals(2, sink.acquires)
        assertEquals(2, sink.releases)
        assertEquals(2, sink.stops)
        // Each run acquires foreground then wake lock, and releases wake lock then foreground.
        assertEquals(
            listOf("start", "acquire", "release", "stop", "start", "acquire", "release", "stop"),
            sink.events
        )
    }

    @Test
    fun releaseHappensBeforeStoppingForegroundWithinAnEnd() {
        val sink = FakeSink()
        val guard = BatchExecutionGuard(sink)
        guard.begin()
        sink.events.clear()

        guard.end()

        assertEquals(listOf("release", "stop"), sink.events)
    }
}
