package compress.joshattic.us.quality

import compress.joshattic.us.DiagLog
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
    val pairing: WindowPairingDiag? = null,
    // Banding diagnostics for the window (null when banding was not collected, or when the
    // native feature could not be registered). Telemetry only: never consulted by any pass/fail
    // decision — see [WindowBandingDiag] for why it is not a gate yet.
    val banding: WindowBandingDiag? = null,
    // VMAF v1 shadow score for the same frame pairs (see VmafNativeV1). Telemetry only — never
    // consulted by any pass/fail decision; the verdict is mean/p5/min above (vmaf_v0.6.1).
    val v1: WindowV1Diag? = null,
    // Where the low scores sit (see WindowFrameDiag). Telemetry only.
    val frameDiag: WindowFrameDiag? = null
)

/** VMAF v1 shadow scores for one window. See [VmafNativeV1]: evidence, never a gate. */
data class WindowV1Diag(val mean: Double, val p5: Double, val min: Double) {
    fun compact(): String = "%.3f/%.3f/%.3f".format(java.util.Locale.US, mean, p5, min)

    companion object {
        fun fromPerFrame(perFrame: DoubleArray?): WindowV1Diag? {
            if (perFrame == null || perFrame.isEmpty() || perFrame.any { it < 0 || it.isNaN() }) return null
            val sorted = perFrame.sortedArray()
            return WindowV1Diag(
                mean = perFrame.average(),
                p5 = sorted[((sorted.size - 1) * 0.05).toInt()],
                min = sorted.first()
            )
        }
    }
}

/**
 * Per-window CAMBI (Contrast Aware Multiscale Banding Index) summary of the DISTORTED frames.
 *
 * Why this exists: VMAF is known to under-weight contrast banding, and banding is the signature
 * artifact of re-encoding smooth gradients — skies, walls, fades — at exactly the conservative
 * bitrates Smart Perceptually Lossless targets. A clip can clear every VMAF window threshold and
 * still show visible banding, which means the transparency claim currently has a blind spot.
 *
 * Why it is NOT a gate: this codebase's window thresholds came from a calibrated VMAF suite. No
 * equivalent calibration exists for CAMBI on this content, and inventing a cut-off would gate a
 * user-facing "Perceptually Lossless" claim on a number nobody measured — the exact failure the
 * truth rules exist to prevent. So this ships as recorded evidence first; a separate, calibrated
 * change may later turn it into an acceptance criterion.
 *
 * Polarity is the opposite of VMAF: **higher means more banding**, and a clean frame scores near 0.
 * [p95] and [max] are therefore the worst-case ends of the window, not the best.
 *
 * Calibration caveat for whoever turns this into a gate: CAMBI's default window size is
 * 4K-referenced (libvmaf documents 63 as "~1 degree at 4k"), so absolute values are not
 * necessarily comparable across source resolutions. Any future threshold has to be established
 * per resolution class, or with the window size pinned — not by picking one number from a paper.
 */
data class WindowBandingDiag(
    val frames: Int,
    val mean: Double,
    val p95: Double,
    val max: Double
) {
    /** Compact capture form: "cambi=mean/p95/max". Locale-pinned like [WindowPairingDiag.compact]. */
    fun compact(): String =
        "cambi=%.4f/%.4f/%.4f".format(java.util.Locale.US, mean, p95, max)
}

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
    val distAlignDrops: Int = 0,
    // Probe windows only (ScoreWindow.alignFirstFrames): how far after the requested window
    // start the source's first frame sat. Null for windows normalised by the requested start.
    val leadUs: Long? = null,
    // Pairs consumed in the lead-in before the window and not scored (ScoreWindow.leadInUs).
    val leadInPairsSkipped: Int = 0
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
        ) + (leadUs?.let { ",leadMs=%.1f".format(java.util.Locale.US, it / 1000.0) } ?: "") +
            (if (leadInPairsSkipped > 0) ",leadIn=$leadInPairsSkipped" else "")
}

/**
 * WHERE the low scores sit inside a scored window. Recorded so a capture can tell a warm-up or
 * boundary artefact (minimum at the first or last scored frame) from a genuinely hard frame in
 * the middle. The b163 capture could not: it carried only mean/p5/min, and 140 of 175 windows had
 * a minimum that was identical across bitrates with no way to say which frame it was.
 */
data class WindowFrameDiag(
    val frames: Int,
    val minIndex: Int,
    val first: Double,
    val last: Double,
    /** The three lowest (index, score) pairs, lowest first. */
    val lowest: List<Pair<Int, Double>>
) {
    /** Compact capture form: "n=36,minAt=0,first=77.5,last=97.2,low=0:77.5|1:79.0|35:88.2". */
    fun compact(): String =
        "n=%d,minAt=%d,first=%.1f,last=%.1f,low=%s".format(
            java.util.Locale.US, frames, minIndex, first, last,
            lowest.joinToString("|") { "%d:%.1f".format(java.util.Locale.US, it.first, it.second) }
        )

    companion object {
        fun fromPerFrame(perFrame: DoubleArray): WindowFrameDiag? {
            if (perFrame.isEmpty()) return null
            val indexed = perFrame.withIndex().sortedBy { it.value }
            return WindowFrameDiag(
                frames = perFrame.size,
                minIndex = indexed.first().index,
                first = perFrame.first(),
                last = perFrame.last(),
                lowest = indexed.take(3).map { it.index to it.value }
            )
        }
    }
}

/**
 * A comparison window in the REFERENCE file's timeline.
 *
 * [alignFirstFrames] is for probe clips, and only for them. A probe clip is exported with
 * `setStartPositionUs(startUs)`, and its first frame is the source's first frame at or after
 * `startUs`. The clip's single-track MP4 is written with that frame at time 0, so its timeline
 * starts at the frame, not at the requested instant. The reference reader's first frame
 * sits `lead` = (first frame pts - startUs) into the window, anywhere from 0 to one frame
 * interval. Normalising the reference by `startUs` and the clip by 0 therefore leaves every
 * pair `lead` apart.
 *
 * batch_1790263711162 (b161, 221 files) measured exactly that. All 715 probe clips reported
 * firstSample=0 (ProbeClipGeometry). The aligner's 4 ms tolerance cannot absorb a sub-frame
 * lead, and dropping whole frames cannot close one, so windows with lead > 4 ms failed as
 * "leading offset not aligned": 142 of 201 ladders measured nothing. Windows with lead near
 * one frame interval were worse. The aligner dropped one clip frame, closed the skew and paired
 * source frame k with clip frame k+1. All 34 such windows scored mean VMAF 11.2-86.3, and they
 * were recorded as MEASURED rejections: 34 of that batch's 58 "would visibly lose quality" skips
 * rested on at least one of them. All 27 directly paired windows (lead <= 4 ms) scored 77.1-99.7.
 * That split is the evidence that clip frame 0 IS the source's first frame at or after startUs,
 * so the two first frames are the origins.
 *
 * Frames after the first are still paired by timestamp under the same 4 ms tolerance, so a
 * frame dropped or retimed inside the clip still fails the window.
 */
data class ScoreWindow(
    val startUs: Long,
    val endUs: Long,
    val distStartUs: Long = startUs,
    val alignFirstFrames: Boolean = false,
    /**
     * Frames decoded and PAIRED before [startUs] but never scored. The probe clip begins
     * `leadInUs` before the window so its scored frames come from the encoder's steady state;
     * see ProbeWindowPlanner. Pairing (and first-frame alignment) covers the lead-in too, so a
     * frame dropped anywhere in the clip still fails the window.
     */
    val leadInUs: Long = 0L,
    /**
     * Extra decode before the scored span on BOTH streams, for motion context only (see
     * [MotionContext]). Probe windows get their context from the lead-in and leave this 0;
     * certification windows, which have no lead-in, decode this much earlier.
     */
    val contextUs: Long = 0L
)

/**
 * VMAF's motion feature for a frame is its difference from the PREVIOUS frame, and libvmaf gives
 * the first frame of a session a motion of 0. `vmaf_v0.6.1` scores a frame with zero motion lower
 * than the same frame with its real motion: identical frames score 97.43 instead of 100.
 *
 * Every window opens a fresh session, so every window's first frame carried that artefact. The
 * b166 self-check showed it directly: the source against itself scored 97.43 on frame 0 of each
 * window and 100 on every other frame. It was also the minimum of 413 of the 568 probe windows in
 * b165 and 262 of the 332 in b166, 2.1-2.6 points below the next-lowest frame at the median. The
 * offline calibration behind the thresholds scored whole clips, where only one frame per clip is
 * affected, so the per-window artefact made the device stricter than the calibration it claims.
 *
 * The fix gives VMAF one aligned pair from before the window, then drops that pair's score. The
 * first scored frame gets its real motion, as it would in a whole-clip run. Nothing about the
 * thresholds changes.
 */
internal object MotionContext {
    /** Decode this much before a certification window so at least one frame precedes it (>= 4 fps). */
    const val CERTIFICATION_CONTEXT_US = 250_000L

    /** Per-frame scores of the scored span: [perFrame] without the [contextFrames] leading scores. */
    fun scoredSpan(perFrame: DoubleArray?, contextFrames: Int): DoubleArray? {
        if (perFrame == null) return null
        if (contextFrames <= 0) return perFrame
        if (perFrame.size <= contextFrames) return DoubleArray(0)
        return perFrame.copyOfRange(contextFrames, perFrame.size)
    }
}

/**
 * The origin one stream's timestamps are normalised against: fixed (a requested window start),
 * or the stream's own first frame when [fixedUs] is null. See [ScoreWindow.alignFirstFrames].
 */
internal class StreamOrigin(fixedUs: Long?) {
    var originUs: Long? = fixedUs
        private set

    /** [ptsUs] relative to the origin. The first call fixes a first-frame origin. */
    fun normalize(ptsUs: Long): Long {
        val origin = originUs ?: ptsUs.also { originUs = it }
        return ptsUs - origin
    }
}

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

    /**
     * [reason] names WHICH of PtsAligner's two failures occurred, because they are different
     * diagnoses and only one of them is about the source:
     *
     *  - "leading offset not aligned within N drops" — the two streams were never lined up at the
     *    window's start. That is an addressing/seek problem in the probe pipeline, and the clip
     *    was never given a fair measurement.
     *  - "internal frame misalignment after N leading drops" — a frame is missing or retimed
     *    INSIDE the window. That is real temporal degradation in the encode.
     *
     * Both fail closed, so the reason changes no decision. It exists because 13 of the 27 ladders
     * in batch_1788254475481 ended here and the capture could not say which had happened: the
     * reason was written to logcat under a tag the in-app export did not collect, and the outcome
     * carried no payload, so nothing reached the structured record either.
     */
    data class MisalignmentRejected(val reason: String?) : PairScoreOutcome
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
    private const val MAX_QUEUE_CAPACITY = 4
    private const val QUEUE_POLL_TIMEOUT_S = 30L
    private const val FRAME_COUNT_TOLERANCE = 2

    /**
     * Geometry ceiling for pixel scoring: 4K-class (3840x2176 covers 3840x2160 and its portrait
     * transpose). Scoring runs at NATIVE resolution — never rescaled — because every threshold in
     * [QualityProbePolicy] was calibrated against the offline harness
     * (`scripts/diagnostics/measure_quality.py`), which states as a contract that it "never
     * rescales or frame-rate-converts either input" and scores with plain `vmaf_v0.6.1`.
     * Downscaling 4K to 1080p here would hide exactly the high-frequency coding artifacts the
     * thresholds exist to catch, silently loosening the bar.
     *
     * Above this ceiling the pair would need two simultaneous 8K hardware decoders and ~50 MB per
     * decoded frame, which is neither reliably supported nor affordable inside a batch encode;
     * those sources keep the pre-existing "evidence unavailable" behavior.
     */
    const val MAX_COMPARE_PIXELS = 3840 * 2176

    /**
     * Peak decoded-frame bytes allowed across BOTH in-flight frame queues. Queue depth is derived
     * from frame size rather than fixed, so 1080p keeps its original 4-deep queues while 4K drops
     * to 2 and stays inside the same memory envelope (~50 MB) instead of scaling to ~100 MB.
     */
    // libvmaf feature extraction runs in its own thread pool; VMAF is deterministic per frame,
    // so the count changes only wall time. Two threads left most of the S23 Ultra's eight cores
    // idle while a 4K rung took three minutes (b165, job_965e925705f2). Four for the verdict
    // model, two for the shadow model that runs after it on the same frames.
    private const val SCORER_THREADS = 4
    private const val SHADOW_THREADS = 2
    private const val QUEUE_BYTE_BUDGET = 64L * 1024 * 1024

    /**
     * Summarizes per-frame CAMBI into a window diagnostic, or null when there is nothing
     * trustworthy to report. Fails to null rather than reporting a partial figure: `-1.0` is the
     * native per-frame retrieval-failure sentinel, and averaging it in would understate banding —
     * the wrong direction for a signal whose whole purpose is to catch a missed artifact.
     */
    internal fun summarizeBanding(perFrameCambi: DoubleArray?): WindowBandingDiag? {
        if (perFrameCambi == null || perFrameCambi.isEmpty()) return null
        if (perFrameCambi.any { it < 0.0 || !it.isFinite() }) return null
        val sorted = perFrameCambi.sortedArray()
        val p95Index = ((sorted.size - 1) * 0.95).toInt()
        return WindowBandingDiag(
            frames = sorted.size,
            mean = perFrameCambi.average(),
            p95 = sorted[p95Index],
            max = sorted.last()
        )
    }

    /** Per-queue depth for a frame of these dimensions; at least 1, never more than [MAX_QUEUE_CAPACITY]. */
    internal fun queueCapacityFor(width: Int, height: Int): Int {
        val frameBytes = width.toLong() * height.toLong() * 3L / 2L
        if (frameBytes <= 0L) return 1
        val perQueueBudget = QUEUE_BYTE_BUDGET / 2L
        return (perQueueBudget / frameBytes).coerceIn(1L, MAX_QUEUE_CAPACITY.toLong()).toInt()
    }

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
    fun score(
        context: Context,
        ref: Uri,
        dist: Uri,
        windows: List<ScoreWindow>,
        collectBanding: Boolean = false,
        // Also score each window with the VMAF v1 shadow model (VmafNativeV1). Certification
        // only: a second VMAF pass on every probe rung would spend the ladder's time budget, and
        // a ladder that runs out of budget cannot pass — shadow data must never cost a saving.
        shadowV1: Boolean = false
    ): PairScoreOutcome {
        if (!VmafNative.isAvailable) return PairScoreOutcome.Unavailable
        val refGeom = YuvFrameReader.displayGeometry(context, ref) ?: return PairScoreOutcome.Unavailable
        val distGeom = YuvFrameReader.displayGeometry(context, dist) ?: return PairScoreOutcome.Unavailable
        if (refGeom.first != distGeom.first || refGeom.second != distGeom.second) {
            DiagLog.w(TAG, "display geometry mismatch ${refGeom.first}x${refGeom.second} vs ${distGeom.first}x${distGeom.second}")
            return PairScoreOutcome.Unavailable
        }
        val width = refGeom.first
        val height = refGeom.second
        if (width * height > MAX_COMPARE_PIXELS) {
            DiagLog.i(TAG, "geometry ${width}x$height above pixel-scoring cap; skipping")
            return PairScoreOutcome.Unavailable
        }

        val results = mutableListOf<WindowScore>()
        for (window in windows) {
            when (val outcome = scoreWindow(context, ref, dist, window, width, height, collectBanding, shadowV1)) {
                is WindowOutcome.Scored -> results += outcome.score
                WindowOutcome.Unavailable -> return PairScoreOutcome.Unavailable
                is WindowOutcome.Misaligned -> return PairScoreOutcome.MisalignmentRejected(outcome.reason)
            }
        }
        return PairScoreOutcome.Scored(results)
    }

    private sealed interface WindowOutcome {
        data class Scored(val score: WindowScore) : WindowOutcome
        object Unavailable : WindowOutcome
        data class Misaligned(val reason: String?) : WindowOutcome
    }

    private fun scoreWindow(
        context: Context,
        ref: Uri,
        dist: Uri,
        window: ScoreWindow,
        width: Int,
        height: Int,
        collectBanding: Boolean,
        shadowV1: Boolean
    ): WindowOutcome {
        // Plain vmaf_v0.6.1 (no phone transform): every threshold in QualityProbePolicy was
        // calibrated against the PC harness's default-model scores, and mixing models would
        // silently loosen the bar (the phone transform maps scores upward).
        val handle = VmafNative.open(
            width, height, phoneModel = false, threads = SCORER_THREADS, collectBanding = collectBanding
        )
        if (handle == 0L) return WindowOutcome.Unavailable
        // Shadow v1 session over the SAME frame pairs. Zero when unavailable; every failure on
        // this path just drops the v1 diagnostic and never touches the verdict session.
        var v1Handle = if (shadowV1) VmafNativeV1.open(width, height, threads = SHADOW_THREADS) else 0L
        // Wall time spent in the shadow model, logged per window. v1 adds CAMBI and SpEED passes
        // on every certification frame, including 4K60 outputs, and that cost has never been
        // measured on the device. Shadow evidence has to justify its battery bill with a number.
        var v1Nanos = 0L
        fun closeV1() {
            if (v1Handle != 0L) {
                runCatching { VmafNativeV1.close(v1Handle) }
                v1Handle = 0L
            }
        }
        val queueCapacity = queueCapacityFor(width, height)
        val refQueue = ArrayBlockingQueue<I420Frame>(queueCapacity)
        val distQueue = ArrayBlockingQueue<I420Frame>(queueCapacity)
        val error = AtomicReference<String?>(null)
        // Both readers decode the lead-in as well as the window; only pairs whose reference frame
        // lies inside the window are scored (see the PAIR branch below).
        val decodeLenUs = window.contextUs + window.leadInUs + (window.endUs - window.startUs)

        fun reader(uri: Uri, startUs: Long, queue: ArrayBlockingQueue<I420Frame>, label: String) =
            thread(name = "vmaf-$label") {
                try {
                    YuvFrameReader(context, uri, startUs, startUs + decodeLenUs) { frame ->
                        queue.put(frame)
                        error.get() == null
                    }.run()
                } catch (t: Throwable) {
                    error.compareAndSet(null, "$label decode failed: ${t.message}")
                } finally {
                    runCatching { queue.put(END) }
                }
            }

        val refThread = reader(ref, (window.startUs - window.leadInUs - window.contextUs).coerceAtLeast(0L), refQueue, "ref")
        val distThread = reader(dist, (window.distStartUs - window.contextUs).coerceAtLeast(0L), distQueue, "dist")
        var leadInPairsSkipped = 0
        // The last aligned pair before the window: fed to VMAF as motion context, never scored.
        // See MotionContext.
        var contextRef: I420Frame? = null
        var contextDist: I420Frame? = null
        var contextFed = 0

        var fed = 0
        var refEnded = false
        var distEnded = false
        var refExtra = 0
        var distExtra = 0
        // Frames are paired by TIMESTAMP, not blind decode order: the Transformer probe clip
        // and the reference window reader can disagree by one frame about where the window
        // starts (measured: constant 33.2 ms skew at 29.97 fps, capture batch_20260716_185345),
        // and decode-order pairing then scores inter-frame motion as if it were encode quality.
        // The aligner drops the earlier head (budgeted) until the heads agree within a FIXED
        // 4 ms tolerance; unalignable windows FAIL — misalignment is never scored.
        val aligner = PtsAligner()
        val refOrigin = StreamOrigin(if (window.alignFirstFrames) null else window.startUs)
        val distOrigin = StreamOrigin(if (window.alignFirstFrames) null else window.distStartUs)
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
                        aligner.onRefFrame(refOrigin.normalize(r.ptsUs))
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
                        aligner.onDistFrame(distOrigin.normalize(d.ptsUs))
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
                when (aligner.decide(refOrigin.normalize(r.ptsUs), distOrigin.normalize(d.ptsUs))) {
                    PtsAligner.Action.PAIR -> {
                        if (r.ptsUs < window.startUs) {
                            // Lead-in: aligned and consumed, never scored. The last one is kept
                            // as motion context for the first scored frame.
                            leadInPairsSkipped++
                            contextRef = r
                            contextDist = d
                            pendingRef = null
                            pendingDist = null
                            continue
                        }
                        val cRef = contextRef
                        val cDist = contextDist
                        if (fed == 0 && cRef != null && cDist != null) {
                            val crc = VmafNative.readFrames(handle, cRef.data, cDist.data, width, height)
                            if (crc < 0) {
                                error.compareAndSet(null, "vmaf read_frames error $crc (context frame)")
                                break
                            }
                            if (v1Handle != 0L) {
                                val v1rc = runCatching {
                                    VmafNativeV1.readFrames(v1Handle, cRef.data, cDist.data, width, height)
                                }.getOrDefault(-1)
                                if (v1rc < 0) closeV1()
                            }
                            contextFed = 1
                            contextRef = null
                            contextDist = null
                        }
                        val skewUs = refOrigin.normalize(r.ptsUs) - distOrigin.normalize(d.ptsUs)
                        if (fed == 0) skewFirstUs = skewUs
                        val absSkew = kotlin.math.abs(skewUs)
                        if (absSkew > skewMaxAbsUs) skewMaxAbsUs = absSkew
                        skewAbsSumUs += absSkew
                        val rc = VmafNative.readFrames(handle, r.data, d.data, width, height)
                        if (rc < 0) {
                            error.compareAndSet(null, "vmaf read_frames error $rc")
                            break
                        }
                        if (v1Handle != 0L) {
                            val v1Start = System.nanoTime()
                            val v1rc = runCatching {
                                VmafNativeV1.readFrames(v1Handle, r.data, d.data, width, height)
                            }.getOrDefault(-1)
                            v1Nanos += System.nanoTime() - v1Start
                            if (v1rc < 0) closeV1()
                        }
                        fed++
                        pendingRef = null
                        pendingDist = null
                    }
                    PtsAligner.Action.DROP_REF -> pendingRef = null
                    PtsAligner.Action.DROP_DIST -> pendingDist = null
                    PtsAligner.Action.FAIL -> {
                        // Only classify the window as misaligned if the pairing verdict WINS
                        // the error race: an earlier decoder failure means the frames we were
                        // aligning are untrustworthy, and the honest classification for the
                        // window is "evidence unavailable", not "frame loss measured".
                        misaligned = error.compareAndSet(
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
            DiagLog.w(TAG, "window [${window.startUs}..${window.endUs}] failed: ${err ?: "no frames"}")
            VmafNative.close(handle)
            closeV1()
            // Measured misalignment is positive evidence, not mere absence of evidence.
            return if (misaligned) WindowOutcome.Misaligned(aligner.failureReason) else WindowOutcome.Unavailable
        }
        // The context frame (if any) is scored by libvmaf like any other; its score is dropped here.
        val perFrame = MotionContext.scoredSpan(VmafNative.flush(handle), contextFed)
        // Banding scores must be read AFTER the flush (which signals end-of-stream) and BEFORE
        // close. Telemetry only: any failure here yields a null diagnostic and never affects the
        // window's outcome.
        val perFrameCambi = if (collectBanding) MotionContext.scoredSpan(VmafNative.cambiScores(handle), contextFed) else null
        VmafNative.close(handle)
        val v1Diag = if (v1Handle != 0L) {
            val v1Start = System.nanoTime()
            WindowV1Diag.fromPerFrame(MotionContext.scoredSpan(runCatching { VmafNativeV1.flush(v1Handle) }.getOrNull(), contextFed))
                .also { v1Nanos += System.nanoTime() - v1Start }
        } else null
        closeV1()
        if (perFrame == null || perFrame.isEmpty() || perFrame.any { it < 0 }) {
            DiagLog.w(TAG, "vmaf flush failed for window")
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
            distAlignDrops = aligner.distDropped,
            leadUs = if (window.alignFirstFrames) {
                refOrigin.originUs?.let { it - (window.startUs - window.leadInUs) }
            } else null,
            leadInPairsSkipped = leadInPairsSkipped
        )
        val frameDiag = WindowFrameDiag.fromPerFrame(perFrame)
        val result = WindowScore(
            comparedFrames = perFrame.size,
            mean = perFrame.average(),
            p5 = sorted[p5Index],
            min = sorted.first(),
            pairing = pairing,
            banding = summarizeBanding(perFrameCambi),
            v1 = v1Diag,
            frameDiag = frameDiag
        )
        DiagLog.i(
            TAG,
            "window [${window.startUs / 1000}ms..${window.endUs / 1000}ms] frames=${result.comparedFrames} " +
                "mean=%.2f p5=%.2f min=%.2f".format(java.util.Locale.US, result.mean, result.p5, result.min) +
                " pairing[${pairing.compact()}]" +
                (frameDiag?.let { " frames[${it.compact()}]" } ?: "") +
                (result.banding?.let { " banding[${it.compact()}]" } ?: "") +
                (result.v1?.let { " v1shadow[${it.compact()} ms=${v1Nanos / 1_000_000}]" } ?: "")
        )
        return WindowOutcome.Scored(result)
    }
}
