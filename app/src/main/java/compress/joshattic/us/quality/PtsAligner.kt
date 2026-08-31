package compress.joshattic.us.quality

/**
 * Streaming timestamp aligner for frame pairing.
 *
 * Both streams are normalized to window-relative time (ref: pts − windowStart; dist:
 * pts − distStart). Two head frames may be scored as a pair only when their normalized
 * timestamps sit within [TOLERANCE_FLOOR_US] — a FIXED tolerance that does not vary with
 * frame rate.
 *
 * Why fixed, and why 4 ms specifically. A correctly aligned pair's skew is bounded by how the
 * two streams were ADDRESSED, not by how far apart their frames are. The probe path is the
 * widest case: [VmafPairScorer] is handed `ScoreWindow(startUs, endUs, distStartUs = 0)` while
 * `PerceptualQualityProber.exportClip` now cuts the clip with `setStartPositionUs(startUs)` —
 * the same microseconds the scorer normalizes by — so that term is zero by construction. It was
 * not always: the clip was previously requested with `setStartPositionMs(startUs / 1000)`, which
 * began it at `startUs − (startUs % 1000)` and left a CONSTANT skew of `−(startUs % 1000)`, at
 * most 999 µs at any frame rate. Device captures show exactly that signature — a clip whose
 * window starts ended in 200/500/800 µs produced windows at 0.2/0.5/0.8 ms skew. The floor is
 * kept at 4 ms rather than tightened to match: the residual that appears when the trimmed clip
 * lands on a different FRAME than the requested instant is a separate, larger mechanism, and
 * narrowing the floor to the newly-smaller term needs its own calibration round, not an
 * inference.
 * Certification is tighter still (both streams share one timeline; only muxer timescale rounding
 * separates them). 4 ms is ~4× that 999 µs bound: wide enough to absorb legitimate addressing
 * noise, narrow enough that a one-frame offset — ≥ 8.3 ms even at 120 fps — is never mistaken
 * for alignment.
 *
 * This is deliberately NOT adaptive. A superseded rule widened the tolerance to half the smallest
 * observed frame interval, which grew it exactly where frames are furthest apart (20.0 ms at
 * 25 fps, 16.7 ms at 30 fps) and so scored frames up to half a frame out of step as if they were
 * aligned. Measured on device across 368 probe windows in five 219-file batches: every window
 * that CLEARED the Perceptually Lossless bar had skew ≤ 0.80 ms, while every catastrophic window
 * (min < 20) had skew ≥ 1.90 ms — two populations with no overlap. A fixed 4 ms tolerance keeps
 * 100% of the passing windows and refuses 80 of the 90 catastrophic ones, which were otherwise
 * being fed to the probe-skip ratchet as if they were quality measurements.
 *
 * Two limits this does NOT remove, recorded rather than papered over:
 *  - Catastrophic windows whose skew falls between 1.90 ms and the 4 ms floor (10 of the 90
 *    observed) are still scored. Narrowing the floor to reach them would trade the 4× margin
 *    above for ~1.5×, on a passing-window corpus of only three distinct clips; that needs a
 *    broader corpus first, not a guess.
 *  - When the frame interval is itself at or below the floor (> 250 fps, or VFR frames closer
 *    than 4 ms), a one-frame offset can still land inside the tolerance. The superseded rule
 *    behaved identically there — `max(4 ms, minGap/2)` is exactly 4 ms whenever minGap ≤ 8 ms —
 *    so this is pre-existing and unchanged, not a regression. [smallestFrameIntervalUs] records
 *    the observation a future calibration round would need to close it honestly.
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

    /**
     * The pairing tolerance: fixed at [toleranceFloorUs], independent of frame rate. See the
     * class KDoc for why a correctly aligned pair's skew is bounded by stream addressing
     * (≤ 999 µs) rather than by the frame interval.
     */
    fun toleranceUs(): Long = toleranceFloorUs

    /**
     * Smallest frame interval observed on either stream so far, or null until two frames of one
     * stream have been seen. Diagnostic only — no decision reads it. Recorded because the fixed
     * tolerance's one known blind spot is content whose frame interval is at or below the floor,
     * and closing that honestly needs this observation from real high-frame-rate content.
     */
    fun smallestFrameIntervalUs(): Long? =
        minOf(minRefGapUs, minDistGapUs).takeIf { it != Long.MAX_VALUE }

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
