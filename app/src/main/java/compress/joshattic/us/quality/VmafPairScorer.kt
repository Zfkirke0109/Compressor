package compress.joshattic.us.quality

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Aggregated per-window VMAF result. */
data class WindowScore(
    val comparedFrames: Int,
    val mean: Double,
    val p5: Double,
    val min: Double
)

/** A comparison window in the REFERENCE file's timeline. */
data class ScoreWindow(val startUs: Long, val endUs: Long, val distStartUs: Long = startUs)

/**
 * Streams display-normalized frames of two files through libvmaf for a set of short windows
 * and returns per-window aggregate scores. Frames are paired in decode order after window
 * alignment; a frame-count mismatch beyond a small tolerance fails the window (fail-closed:
 * misalignment must never be scored as quality).
 *
 * Memory is bounded by two small queues (~8 frames total). One window at a time, one native
 * VMAF session per window, everything confined to the calling (background) thread plus two
 * decoder threads.
 */
object VmafPairScorer {
    private const val TAG = "VmafPairScorer"
    private const val QUEUE_CAPACITY = 4
    private const val QUEUE_POLL_TIMEOUT_S = 30L
    private const val FRAME_COUNT_TOLERANCE = 2
    const val MAX_COMPARE_PIXELS = 1920 * 1088 // pixel scoring capped at 1080p-class for memory/time

    private val END = I420Frame(ByteArray(0), 0, 0, Long.MIN_VALUE)

    fun isSupportedGeometry(context: Context, ref: Uri, dist: Uri): Boolean {
        val rg = YuvFrameReader.displayGeometry(context, ref) ?: return false
        val dg = YuvFrameReader.displayGeometry(context, dist) ?: return false
        if (rg.first != dg.first || rg.second != dg.second) return false
        return rg.first * rg.second <= MAX_COMPARE_PIXELS
    }

    /**
     * Scores [windows]; returns null if pixel evidence could not be produced (native lib missing,
     * geometry mismatch, decoder failure, frame-count mismatch). Never throws.
     */
    fun score(context: Context, ref: Uri, dist: Uri, windows: List<ScoreWindow>): List<WindowScore>? {
        if (!VmafNative.isAvailable) return null
        val refGeom = YuvFrameReader.displayGeometry(context, ref) ?: return null
        val distGeom = YuvFrameReader.displayGeometry(context, dist) ?: return null
        if (refGeom.first != distGeom.first || refGeom.second != distGeom.second) {
            Log.w(TAG, "display geometry mismatch ${refGeom.first}x${refGeom.second} vs ${distGeom.first}x${distGeom.second}")
            return null
        }
        val width = refGeom.first
        val height = refGeom.second
        if (width * height > MAX_COMPARE_PIXELS) {
            Log.i(TAG, "geometry ${width}x$height above pixel-scoring cap; skipping")
            return null
        }

        val results = mutableListOf<WindowScore>()
        for (window in windows) {
            val score = scoreWindow(context, ref, dist, window, width, height) ?: return null
            results += score
        }
        return results
    }

    private fun scoreWindow(
        context: Context,
        ref: Uri,
        dist: Uri,
        window: ScoreWindow,
        width: Int,
        height: Int
    ): WindowScore? {
        // Plain vmaf_v0.6.1 (no phone transform): every threshold in QualityProbePolicy was
        // calibrated against the PC harness's default-model scores, and mixing models would
        // silently loosen the bar (the phone transform maps scores upward).
        val handle = VmafNative.open(width, height, phoneModel = false, threads = 2)
        if (handle == 0L) return null
        val refQueue = ArrayBlockingQueue<I420Frame>(QUEUE_CAPACITY)
        val distQueue = ArrayBlockingQueue<I420Frame>(QUEUE_CAPACITY)
        val error = AtomicReference<String?>(null)
        val windowLenUs = window.endUs - window.startUs

        fun reader(uri: Uri, startUs: Long, queue: ArrayBlockingQueue<I420Frame>, label: String) =
            thread(name = "vmaf-$label") {
                try {
                    YuvFrameReader(context, uri, startUs, startUs + windowLenUs) { frame ->
                        queue.put(frame)
                        error.get() == null
                    }.run()
                } catch (t: Throwable) {
                    error.compareAndSet(null, "$label decode failed: ${t.message}")
                } finally {
                    runCatching { queue.put(END) }
                }
            }

        val refThread = reader(ref, window.startUs, refQueue, "ref")
        val distThread = reader(dist, window.distStartUs, distQueue, "dist")

        var fed = 0
        var refEnded = false
        var distEnded = false
        var refExtra = 0
        var distExtra = 0
        try {
            while (true) {
                val r = if (!refEnded) refQueue.poll(QUEUE_POLL_TIMEOUT_S, TimeUnit.SECONDS) else END
                val d = if (!distEnded) distQueue.poll(QUEUE_POLL_TIMEOUT_S, TimeUnit.SECONDS) else END
                if (r == null || d == null) {
                    error.compareAndSet(null, "frame queue timeout")
                    break
                }
                if (r === END) refEnded = true
                if (d === END) distEnded = true
                if (refEnded && distEnded) break
                if (refEnded != distEnded) {
                    // one stream has leftover frames; count them for the tolerance check
                    if (refEnded) distExtra++ else refExtra++
                    if (refExtra + distExtra > FRAME_COUNT_TOLERANCE) {
                        error.compareAndSet(null, "frame count mismatch beyond tolerance")
                        break
                    }
                    continue
                }
                if (r.width != width || r.height != height || d.width != width || d.height != height) {
                    error.compareAndSet(null, "frame geometry drift")
                    break
                }
                val rc = VmafNative.readFrames(handle, r.data, d.data, width, height)
                if (rc < 0) {
                    error.compareAndSet(null, "vmaf read_frames error $rc")
                    break
                }
                fed++
            }
        } catch (t: Throwable) {
            error.compareAndSet(null, "scorer failed: ${t.message}")
        } finally {
            // Unblock producers and wait for them.
            error.compareAndSet(null, null)
            if (error.get() != null) {
                refQueue.clear(); distQueue.clear()
            }
            runCatching { refThread.join(10_000) }
            runCatching { distThread.join(10_000) }
        }

        val err = error.get()
        if (err != null || fed == 0) {
            Log.w(TAG, "window [${window.startUs}..${window.endUs}] failed: ${err ?: "no frames"}")
            VmafNative.close(handle)
            return null
        }
        val perFrame = VmafNative.flush(handle)
        VmafNative.close(handle)
        if (perFrame == null || perFrame.isEmpty() || perFrame.any { it < 0 }) {
            Log.w(TAG, "vmaf flush failed for window")
            return null
        }
        val sorted = perFrame.sortedArray()
        val p5Index = ((sorted.size - 1) * 0.05).toInt()
        val result = WindowScore(
            comparedFrames = perFrame.size,
            mean = perFrame.average(),
            p5 = sorted[p5Index],
            min = sorted.first()
        )
        Log.i(
            TAG,
            "window [${window.startUs / 1000}ms..${window.endUs / 1000}ms] frames=${result.comparedFrames} " +
                "mean=%.2f p5=%.2f min=%.2f".format(result.mean, result.p5, result.min)
        )
        return result
    }
}
