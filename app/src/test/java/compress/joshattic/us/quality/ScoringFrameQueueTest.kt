package compress.joshattic.us.quality

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringFrameQueueTest {
    @Test
    fun stoppedConsumerReleasesProducerBlockedPublishingItsEndMarker() {
        val queue = ScoringFrameQueue<String>(1)
        assertTrue(queue.send("first"))
        val sendingSecond = CountDownLatch(1)
        val producer = Thread {
            try {
                sendingSecond.countDown()
                queue.send("second")
                queue.send("END")
            } catch (_: InterruptedException) {
                // Test cleanup for the old, permanently blocked behavior.
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(sendingSecond.await(1, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (producer.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING) && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertTrue(producer.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING))

            queue.stop() // The scorer has stopped consuming after an error.
            producer.join(500)
            assertFalse("decoder must not wait for an abandoned full queue", producer.isAlive)
        } finally {
            producer.interrupt()
        }
    }

    @Test
    fun emptyQueueReceiveNoticesCancellationWithoutWaitingForFrameTimeout() {
        val queue = ScoringFrameQueue<String>(1)
        val cancelled = AtomicBoolean(false)
        val polling = CountDownLatch(1)
        val consumer = Thread {
            try {
                queue.receive(5_000) {
                    polling.countDown()
                    cancelled.get()
                }
            } catch (_: InterruptedException) {
                // Test cleanup for the old, unresponsive poll.
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue("receive must check cancellation while the queue is empty", polling.await(500, TimeUnit.MILLISECONDS))
            cancelled.set(true)
            consumer.join(500)
            assertFalse("cancelled scoring must not wait for the frame timeout", consumer.isAlive)
        } finally {
            consumer.interrupt()
        }
    }

    @Test
    fun liveQueueKeepsFrameOrderThroughEndMarker() {
        val queue = ScoringFrameQueue<String>(2)
        assertTrue(queue.send("frame"))
        assertTrue(queue.send("END"))
        assertEquals("frame", queue.receive(100) { false })
        assertEquals("END", queue.receive(100) { false })
    }
}
