package compress.joshattic.us.quality

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Bounded decoder-to-scorer handoff. Stop ends future publication, including a reader waiting
 * to publish END; receive keeps the full timeout while checking cancellation between short polls.
 */
internal class ScoringFrameQueue<T>(capacity: Int) {
    private companion object {
        const val CHECK_INTERVAL_MS = 50L
    }

    private val queue = ArrayBlockingQueue<T>(capacity)
    @Volatile private var stopped = false

    fun send(item: T): Boolean {
        while (!stopped) {
            if (queue.offer(item, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)) return !stopped
        }
        return false
    }

    fun receive(timeoutMs: Long, cancelled: () -> Boolean): T? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!cancelled()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return null
            val item = queue.poll(
                minOf(remaining, TimeUnit.MILLISECONDS.toNanos(CHECK_INTERVAL_MS)),
                TimeUnit.NANOSECONDS
            )
            if (item != null) return item
        }
        return null
    }

    /** The scorer has stopped consuming; discard queued frames and release waiting producers. */
    fun stop() {
        stopped = true
        queue.clear()
    }
}
