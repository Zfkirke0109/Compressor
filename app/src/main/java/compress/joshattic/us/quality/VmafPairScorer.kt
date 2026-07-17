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
    val min: Double,
    // Pairing diagnostics for the window (null on legacy/synthetic scores). Telemetry only:
    // never consulted by any pass/fail decision.
    val pairing: WindowPairingDiag? = null
)

/**
 * Per-window frame-pairing diagnostics. Frames are paired in decode order; the skew of pair i
 * is (refPts_i - windowStart) - (distPts_i - distStart) — 0 for a perfectly aligned pair. A
 * large or drifting skew means the two streams' frames were compared out of time: the score
 * measured misalignment, not quality. Recorded so captures can tell those apart.
 */
data class WindowPairingDiag(
    val refFrames: Int,
    val distFrames: Int,
    val refExtra: Int,
    val distExtra: Int,
    val skewFirstUs: Long,
    val skewMaxAbsUs: Long,
    val skewMeanAbsUs: Long,
    // Frames the aligner dropped to re-establish timestamp alignment before scoring
    // (measured misalignment, e.g. the Transformer clip's off-by-one window start).
    val refAlignDrops: Int = 0,
    val distAlignDrops: Int = 0
) {
    /**
     * Compact capture form: "ref=50,dist=52,extra=0/2,skewMs=first/maxAbs/meanAbs,drop=a/b".
     * Locale-pinned: a comma-decimal device locale must not corrupt the comma-separated field.
     */
    fun compact(): String =
        "ref=%d,dist=%d,extra=%d/%d,skewMs=%.1f/%.1f/%.1f,drop=%d/%d".format(
            java.util.Locale.US,
            refFrames, distFrames, refExtra, distExtra,
            skewFirstUs / 1000.0, skewMaxAbsUs / 1000.0, skewMeanAbsUs / 1000.0,
            refAlignDrops, distAlignDrops
        )
}

/** A comparison window in the REFERENCE file's timeline. */
data class ScoreWindow(val startUs: Long, val endUs: Long, val distStartUs: Long = startUs)

/**
 * Tri-state scoring result. The distinction between the two failure cases is load-bearing:
 * [Unavailable] means evidence could not be produced (native lib missing, geometry mismatch,
 * decoder failure) — legacy "no evidence" semantics, eligible for the structural default-ratio
 * certification fallback. [MisalignmentRejected] is POSITIVE evidence that the two streams'
 * frames are not temporally comparable (frame loss or retiming) — it must always fail closed
 * and is NEVER eligible for any structural fallback.
 */
sealed interface PairScoreOutcome {
    data class Scored(val windows: List<WindowScore>) : PairScoreOutcome
    object Unavailable : PairScoreOutcome
    object MisalignmentRejected : PairScoreOutcome
}

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
     * Scores [windows]. [PairScoreOutcome.Unavailable] when pixel evidence could not be
     * produced (native lib missing, geometry mismatch, decoder failure, frame-count mismatch);
     * [PairScoreOutcome.MisalignmentRejected] when the streams were measurably NOT temporally
     * comparable (fail closed — never scored, never structurally certifiable). Never throws.
     */
    fun score(context: Context, ref: Uri, dist: Uri, windows: List<ScoreWindow>): PairScoreOutcome {
        if (!VmafNative.isAvailable) return PairScoreOutcome.Unavailable
        val refGeom = YuvFrameReader.displayGeometry(context, ref) ?: return PairScoreOutcome.Unavailable
        val distGeom = YuvFrameReader.displayGeometry(context, dist) ?: return PairScoreOutcome.Unavailable
        if (refGeom.first != distGeom.first || refGeom.second != distGeom.second) {
            Log.w(TAG, "display geometry mismatch ${refGeom.first}x${refGeom.second} vs ${distGeom.first}x${distGeom.second}")
            return PairScoreOutcome.Unavailable
        }
        val width = refGeom.first
        val height = refGeom.second
        if (width * height > MAX_COMPARE_PIXELS) {
            Log.i(TAG, "geometry ${width}x$height above pixel-scoring cap; skipping")
            return PairScoreOutcome.Unavailable
        }

        val results = mutableListOf<WindowScore>()
        for (window in windows) {
            when (val outcome = scoreWindow(context, ref, dist, window, width, height)) {
                is WindowOutcome.Scored -> results += outcome.score
                WindowOutcome.Unavailable -> return PairScoreOutcome.Unavailable
                WindowOutcome.Misaligned -> return PairScoreOutcome.MisalignmentRejected
            }
        }
        return PairScoreOutcome.Scored(results)
    }

    private sealed interface WindowOutcome {
        data class Scored(val score: WindowScore) : WindowOutcome
        object Unavailable : WindowOutcome
        object Misaligned : WindowOutcome
    }

    private fun scoreWindow(
        context: Context,
        ref: Uri,
        dist: Uri,
        window: ScoreWindow,
        width: Int,
        height: Int
    ): WindowOutcome {
        // Plain vmaf_v0.6.1 (no phone transform): every threshold in QualityProbePolicy was
        // calibrated against the PC harness's default-model scores, and mixing models would
        // silently loosen the bar (the phone transform maps scores upward).
        val handle = VmafNative.open(width, height, phoneModel = false, threads = 2)
        if (handle == 0L) return WindowOutcome.Unavailable
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
        // Frames are paired by TIMESTAMP, not blind decode order: the Transformer probe clip
        // and the reference window reader can disagree by one frame about where the window
        // starts (measured: constant 33.2 ms skew at 29.97 fps, capture batch_20260716_185345),
        // and decode-order pairing then scores inter-frame motion as if it were encode quality.
        // The aligner drops the earlier head (budgeted) until the heads agree within half a
        // frame interval; unalignable windows FAIL — misalignment is never scored.
        val aligner = PtsAligner()
        // Pairing telemetry accumulators (diagnostics only — no influence on scoring).
        var refSeen = 0
        var distSeen = 0
        var skewFirstUs = 0L
        var skewMaxAbsUs = 0L
        var skewAbsSumUs = 0L
        var pendingRef: I420Frame? = null
        var pendingDist: I420Frame? = null
        var misaligned = false
        try {
            while (true) {
                if (pendingRef == null && !refEnded) {
                    val r = refQueue.poll(QUEUE_POLL_TIMEOUT_S, TimeUnit.SECONDS)
                    if (r == null) {
                        error.compareAndSet(null, "frame queue timeout")
                        break
                    }
                    if (r === END) {
                        refEnded = true
                    } else {
                        refSeen++
                        aligner.onRefFrame(r.ptsUs - window.startUs)
                        pendingRef = r
                    }
                }
                if (pendingDist == null && !distEnded) {
                    val d = distQueue.poll(QUEUE_POLL_TIMEOUT_S, TimeUnit.SECONDS)
                    if (d == null) {
                        error.compareAndSet(null, "frame queue timeout")
                        break
                    }
                    if (d === END) {
                        distEnded = true
                    } else {
                        distSeen++
                        aligner.onDistFrame(d.ptsUs - window.distStartUs)
                        pendingDist = d
                    }
                }
                val r = pendingRef
                val d = pendingDist
                if (r == null && d == null) break // both streams fully consumed
                if (r == null || d == null) {
                    // One stream ended with the other still producing: trailing extras.
                    if (r == null) {
                        distExtra++
                        pendingDist = null
                    } else {
                        refExtra++
                        pendingRef = null
                    }
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
                when (aligner.decide(r.ptsUs - window.startUs, d.ptsUs - window.distStartUs)) {
                    PtsAligner.Action.PAIR -> {
                        val skewUs = (r.ptsUs - window.startUs) - (d.ptsUs - window.distStartUs)
                        if (fed == 0) skewFirstUs = skewUs
                        val absSkew = kotlin.math.abs(skewUs)
                        if (absSkew > skewMaxAbsUs) skewMaxAbsUs = absSkew
                        skewAbsSumUs += absSkew
                        val rc = VmafNative.readFrames(handle, r.data, d.data, width, height)
                        if (rc < 0) {
                            error.compareAndSet(null, "vmaf read_frames error $rc")
                            break
                        }
                        fed++
                        pendingRef = null
                        pendingDist = null
                    }
                    PtsAligner.Action.DROP_REF -> pendingRef = null
                    PtsAligner.Action.DROP_DIST -> pendingDist = null
                    PtsAligner.Action.FAIL -> {
                        misaligned = true
                        error.compareAndSet(
                            null,
                            "frame pairing rejected: ${aligner.failureReason ?: "misaligned"}"
                        )
                        break
                    }
                }
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
            // Measured misalignment is positive evidence, not mere absence of evidence.
            return if (misaligned) WindowOutcome.Misaligned else WindowOutcome.Unavailable
        }
        val perFrame = VmafNative.flush(handle)
        VmafNative.close(handle)
        if (perFrame == null || perFrame.isEmpty() || perFrame.any { it < 0 }) {
            Log.w(TAG, "vmaf flush failed for window")
            return WindowOutcome.Unavailable
        }
        val sorted = perFrame.sortedArray()
        val p5Index = ((sorted.size - 1) * 0.05).toInt()
        val pairing = WindowPairingDiag(
            refFrames = refSeen,
            distFrames = distSeen,
            refExtra = refExtra,
            distExtra = distExtra,
            skewFirstUs = skewFirstUs,
            skewMaxAbsUs = skewMaxAbsUs,
            skewMeanAbsUs = if (fed > 0) skewAbsSumUs / fed else 0L,
            refAlignDrops = aligner.refDropped,
            distAlignDrops = aligner.distDropped
        )
        val result = WindowScore(
            comparedFrames = perFrame.size,
            mean = perFrame.average(),
            p5 = sorted[p5Index],
            min = sorted.first(),
            pairing = pairing
        )
        Log.i(
            TAG,
            "window [${window.startUs / 1000}ms..${window.endUs / 1000}ms] frames=${result.comparedFrames} " +
                "mean=%.2f p5=%.2f min=%.2f".format(java.util.Locale.US, result.mean, result.p5, result.min) +
                " pairing[${pairing.compact()}]"
        )
        return WindowOutcome.Scored(result)
    }
}
