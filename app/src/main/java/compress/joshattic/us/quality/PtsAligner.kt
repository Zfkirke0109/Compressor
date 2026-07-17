package compress.joshattic.us.quality

/**
 * Streaming timestamp aligner for frame pairing.
 *
 * Both streams are normalized to window-relative time (ref: pts − windowStart; dist:
 * pts − distStart). Two head frames may be scored as a pair only when their normalized
 * timestamps sit within an adaptive tolerance: half the smallest frame interval observed
 * on either stream so far, floored at [TOLERANCE_FLOOR_US]. Before any interval is known
 * the tolerance stays at the floor — a truly aligned pair has ~0 skew at any frame rate
 * (the probe clip re-encodes the same source frames with preserved timestamps), while a
 * one-frame offset is ≥ 8.3 ms even at 120 fps.
 *
 * Drop-based repair is allowed ONLY at the window's leading boundary — before the first
 * scored pair (measured on device: the Transformer probe clip and the reference window
 * reader disagree by exactly one frame about where a window starts — capture
 * batch_20260716_185345, skew 33.2 ms constant at 29.97 fps). Leading drops are budgeted.
 * Once a pair has been scored, ANY later misalignment is positive evidence of internal
 * frame loss or retiming and fails immediately: aligning around a missing middle frame
 * would hide real temporal degradation behind clean per-frame scores. In every failure
 * case the window has no honest pixel evidence and the caller must fail closed —
 * misalignment is never scored as quality.
 */
class PtsAligner(
    private val toleranceFloorUs: Long = TOLERANCE_FLOOR_US,
    private val maxDrops: Int = MAX_ALIGNMENT_DROPS
) {
    companion object {
        const val TOLERANCE_FLOOR_US = 4_000L
        const val MAX_ALIGNMENT_DROPS = 8
    }

    enum class Action { PAIR, DROP_REF, DROP_DIST, FAIL }

    private var lastRefUs = Long.MIN_VALUE
    private var lastDistUs = Long.MIN_VALUE
    private var minRefGapUs = Long.MAX_VALUE
    private var minDistGapUs = Long.MAX_VALUE
    private var pairedAtLeastOnce = false

    var refDropped = 0
        private set
    var distDropped = 0
        private set

    /** Human-readable cause of the last FAIL decision; null while alignment is healthy. */
    var failureReason: String? = null
        private set

    /** Feed every newly decoded ref frame's normalized pts (in decode order). */
    fun onRefFrame(normPtsUs: Long) {
        if (lastRefUs != Long.MIN_VALUE) {
            val gap = normPtsUs - lastRefUs
            if (gap in 1 until minRefGapUs) minRefGapUs = gap
        }
        lastRefUs = normPtsUs
    }

    /** Feed every newly decoded dist frame's normalized pts (in decode order). */
    fun onDistFrame(normPtsUs: Long) {
        if (lastDistUs != Long.MIN_VALUE) {
            val gap = normPtsUs - lastDistUs
            if (gap in 1 until minDistGapUs) minDistGapUs = gap
        }
        lastDistUs = normPtsUs
    }

    /** Current pairing tolerance: half the smallest observed frame interval, floored. */
    fun toleranceUs(): Long {
        val minGap = minOf(minRefGapUs, minDistGapUs)
        if (minGap == Long.MAX_VALUE) return toleranceFloorUs
        return maxOf(toleranceFloorUs, minGap / 2)
    }

    /** Decide what to do with the current heads. Never scores a pair beyond tolerance. */
    fun decide(refNormUs: Long, distNormUs: Long): Action {
        val skew = refNormUs - distNormUs
        if (kotlin.math.abs(skew) <= toleranceUs()) {
            pairedAtLeastOnce = true
            return Action.PAIR
        }
        if (pairedAtLeastOnce) {
            // Misalignment AFTER a scored pair = a frame is missing or retimed INSIDE the
            // window. Dropping around it would hide real temporal degradation; fail closed.
            failureReason = "internal frame misalignment after ${refDropped + distDropped} leading drops (frame loss or retiming inside the window)"
            return Action.FAIL
        }
        if (refDropped + distDropped >= maxDrops) {
            failureReason = "leading offset not aligned within $maxDrops drops"
            return Action.FAIL
        }
        return if (skew > 0) {
            // dist head is earlier: it has no ref counterpart inside the window.
            distDropped++
            Action.DROP_DIST
        } else {
            refDropped++
            Action.DROP_REF
        }
    }
}
