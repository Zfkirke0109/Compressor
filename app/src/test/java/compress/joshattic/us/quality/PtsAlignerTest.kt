package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The aligner is the fail-closed guard between decode order and scored pairs: it must pass
 * aligned streams untouched, repair the measured off-by-one clip start with a single drop,
 * and refuse to score streams it cannot align (misalignment is never quality evidence).
 */
class PtsAlignerTest {

    /** Drives the aligner exactly like the scorer: feed pts on first sight of each head. */
    private fun run(
        refPts: List<Long>,
        distPts: List<Long>,
        aligner: PtsAligner = PtsAligner()
    ): Triple<List<Pair<Long, Long>>, PtsAligner.Action?, PtsAligner> {
        var ri = 0
        var di = 0
        var refSeen = -1
        var distSeen = -1
        val pairs = mutableListOf<Pair<Long, Long>>()
        while (ri < refPts.size && di < distPts.size) {
            if (ri > refSeen) {
                aligner.onRefFrame(refPts[ri]); refSeen = ri
            }
            if (di > distSeen) {
                aligner.onDistFrame(distPts[di]); distSeen = di
            }
            when (aligner.decide(refPts[ri], distPts[di])) {
                PtsAligner.Action.PAIR -> {
                    pairs += refPts[ri] to distPts[di]; ri++; di++
                }
                PtsAligner.Action.DROP_REF -> ri++
                PtsAligner.Action.DROP_DIST -> di++
                PtsAligner.Action.FAIL -> return Triple(pairs, PtsAligner.Action.FAIL, aligner)
            }
        }
        return Triple(pairs, null, aligner)
    }

    private fun cfr(n: Int, intervalUs: Long, offsetUs: Long = 0L): List<Long> =
        List(n) { offsetUs + it * intervalUs }

    @Test
    fun alignedCfrStreamsPairEverythingWithoutDrops() {
        val pts = cfr(36, 33_367L)
        val (pairs, fail, aligner) = run(pts, pts)
        assertNull(fail)
        assertEquals(36, pairs.size)
        assertEquals(0, aligner.refDropped + aligner.distDropped)
        assertTrue(pairs.all { (r, d) -> r == d })
    }

    @Test
    fun measuredOffByOneClipStartIsRepairedWithASingleDistDrop() {
        // The device signature from capture batch_20260716_185345: the clip contains one
        // leading frame the reference reader excluded, so decode-order pairing was off by
        // exactly one frame (constant 33.2 ms skew) for the whole window.
        val ref = cfr(35, 33_367L, offsetUs = 33_367L)
        val dist = cfr(35, 33_367L, offsetUs = 0L)
        val (pairs, fail, aligner) = run(ref, dist)
        assertNull(fail)
        assertEquals(1, aligner.distDropped)
        assertEquals(0, aligner.refDropped)
        assertEquals(34, pairs.size)
        assertTrue(pairs.all { (r, d) -> r == d }) // every scored pair is the same instant
    }

    @Test
    fun offByOneTheOtherWayDropsTheLeadingRefFrame() {
        val ref = cfr(35, 33_367L, offsetUs = 0L)
        val dist = cfr(35, 33_367L, offsetUs = 33_367L)
        val (pairs, fail, aligner) = run(ref, dist)
        assertNull(fail)
        assertEquals(1, aligner.refDropped)
        assertEquals(34, pairs.size)
        assertTrue(pairs.all { (r, d) -> r == d })
    }

    @Test
    fun vfrStreamsWithMatchingTimestampsPairWithoutDrops() {
        // Screen-recording cadence: irregular gaps, but the clip re-encodes the same frames.
        val pts = listOf(0L, 16_000L, 33_000L, 383_000L, 400_000L, 750_000L, 1_100_000L)
        val (pairs, fail, aligner) = run(pts, pts)
        assertNull(fail)
        assertEquals(pts.size, pairs.size)
        assertEquals(0, aligner.refDropped + aligner.distDropped)
    }

    @Test
    fun subMillisecondMuxerJitterStillPairs() {
        val ref = cfr(30, 33_367L)
        val dist = ref.map { it + 900L } // sub-ms rebasing jitter, within the 4ms floor
        val (pairs, fail, aligner) = run(ref, dist)
        assertNull(fail)
        assertEquals(30, pairs.size)
        assertEquals(0, aligner.refDropped + aligner.distDropped)
    }

    @Test
    fun retimedStreamFailsClosed() {
        // A CFR-ized/duplicated clip of VFR content: cadences never agree for long. The
        // aligner must give up (fail closed) instead of scoring a partially-fabricated set.
        val ref = cfr(30, 33_333L)
        val dist = cfr(60, 16_667L, offsetUs = 8_000L) // offset half-ish frames, double cadence
        val (_, fail, _) = run(ref, dist)
        assertEquals(PtsAligner.Action.FAIL, fail)
    }

    @Test
    fun internalMissingFramesAreLostFrameEvidenceNotRepairable() {
        // The dist stream drops frames 10-12 mid-window (genuine temporal degradation).
        // Aligning around them would score the surviving pairs at ~100 and hide the loss:
        // the aligner must fail closed at the first internal misalignment instead.
        val ref = cfr(30, 33_333L)
        val dist = ref.filterIndexed { i, _ -> i !in 10..12 }
        val (pairs, fail, aligner) = run(ref, dist)
        assertEquals(PtsAligner.Action.FAIL, fail)
        assertEquals(10, pairs.size) // pairs 0-9 scored, then the gap is detected
        assertEquals(0, aligner.refDropped + aligner.distDropped) // no internal "repair"
    }

    @Test
    fun leadingOffsetRepairNeverHidesALaterInternalGap() {
        // Both defects at once: one-frame leading offset AND an internal missing frame.
        // The leading offset is repaired (one drop), the internal gap still fails closed.
        val interval = 33_367L
        val ref = cfr(30, interval, offsetUs = interval)
        val dist = cfr(30, interval, offsetUs = 0L).filterIndexed { i, _ -> i != 15 }
        val (_, fail, aligner) = run(ref, dist)
        assertEquals(PtsAligner.Action.FAIL, fail)
        assertEquals(1, aligner.distDropped) // the legitimate leading repair
    }

    @Test
    fun toleranceAdaptsToTheSmallestObservedFrameInterval() {
        val aligner = PtsAligner()
        assertEquals(PtsAligner.TOLERANCE_FLOOR_US, aligner.toleranceUs())
        aligner.onRefFrame(0L); aligner.onRefFrame(33_367L)
        aligner.onDistFrame(0L); aligner.onDistFrame(33_367L)
        assertEquals(33_367L / 2, aligner.toleranceUs())
        // 120fps-class dist stream tightens it further
        aligner.onDistFrame(33_367L + 8_333L)
        assertEquals(8_333L / 2, aligner.toleranceUs())
    }
}
