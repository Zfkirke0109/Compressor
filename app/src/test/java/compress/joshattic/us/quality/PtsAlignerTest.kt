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
    fun toleranceIsFixedAndNeverWidensWithTheFrameInterval() {
        // The superseded rule returned half the smallest observed interval (20.0 ms at 25 fps),
        // widening the tolerance exactly where frames are furthest apart. It is now constant.
        val aligner = PtsAligner()
        assertEquals(PtsAligner.TOLERANCE_FLOOR_US, aligner.toleranceUs())
        assertNull(aligner.smallestFrameIntervalUs())

        aligner.onRefFrame(0L); aligner.onRefFrame(40_000L)   // 25 fps
        aligner.onDistFrame(0L); aligner.onDistFrame(40_000L)
        assertEquals(PtsAligner.TOLERANCE_FLOOR_US, aligner.toleranceUs())
        assertEquals(40_000L, aligner.smallestFrameIntervalUs())

        // A 120 fps-class stream does not move it either — only the observation changes.
        aligner.onDistFrame(48_333L)
        assertEquals(PtsAligner.TOLERANCE_FLOOR_US, aligner.toleranceUs())
        assertEquals(8_333L, aligner.smallestFrameIntervalUs())
    }

    @Test
    fun halfFrameGridOffsetAt25FpsFailsClosedInsteadOfBeingScored() {
        // Device signature (job 80bff5a136f7, 676x1080 @ 25 fps): the heads sat 20.0 ms apart —
        // exactly half a 40 ms frame interval, i.e. precisely ON the superseded tolerance — and
        // were SCORED, returning VMAF 1.860/0.000/0.000 for an HEVC ratio-0.70 encode. A
        // half-frame-offset grid is not a measurement; it must produce no pixel evidence at all.
        val ref = cfr(30, 40_000L, offsetUs = 0L)
        val dist = cfr(30, 40_000L, offsetUs = 20_000L)
        val (pairs, fail, aligner) = run(ref, dist)
        assertEquals(PtsAligner.Action.FAIL, fail)
        assertEquals(0, pairs.size) // nothing was ever scored
        assertEquals(PtsAligner.MAX_ALIGNMENT_DROPS, aligner.refDropped + aligner.distDropped)
        assertTrue(aligner.failureReason!!.contains("not aligned"))
    }

    @Test
    fun halfFrameOffsetFailsAtEveryRateTheOldRuleWouldHaveScored() {
        // 24 / 25 / 30 fps: the superseded minGap/2 tolerance was 20.8 / 20.0 / 16.7 ms, so a
        // half-frame offset sat at or inside the boundary and was scored at every one of them.
        for (intervalUs in listOf(41_667L, 40_000L, 33_333L)) {
            val ref = cfr(30, intervalUs)
            val dist = cfr(30, intervalUs, offsetUs = intervalUs / 2)
            val (pairs, fail, _) = run(ref, dist)
            assertEquals("interval $intervalUs must fail closed", PtsAligner.Action.FAIL, fail)
            assertEquals("interval $intervalUs must score nothing", 0, pairs.size)
        }
    }

    @Test
    fun worstCaseMsGranularClipStartSkewStillPairsAtEveryFrameRate() {
        // The probe clip is cut with setStartPositionMs(startUs / 1000), so a CORRECTLY aligned
        // probe carries a constant skew of -(startUs % 1000): at most 999 us, whatever the frame
        // rate. That bound is exactly what the 4 ms floor exists to absorb — it must never fail.
        for (intervalUs in listOf(41_667L, 40_000L, 33_367L, 16_667L, 8_333L)) {
            val ref = cfr(30, intervalUs)
            val dist = ref.map { it - 999L }
            val (pairs, fail, aligner) = run(ref, dist)
            assertNull("interval $intervalUs must still pair", fail)
            assertEquals("interval $intervalUs must score every frame", 30, pairs.size)
            assertEquals(0, aligner.refDropped + aligner.distDropped)
        }
    }

    @Test
    fun theDocumentedOneFrameLeadingOffsetStillRepairsUnderTheFixedTolerance() {
        // capture batch_20260716_185345: constant 33.2 ms skew at 29.97 fps (interval 33_367 us),
        // i.e. the probe clip carried one leading frame the reference reader excluded. The 4 ms
        // tolerance must still repair this with a SINGLE drop, leaving a residual well inside it.
        val interval = 33_367L
        val ref = cfr(35, interval, offsetUs = 33_200L) // measured skew, not an exact frame
        val dist = cfr(35, interval, offsetUs = 0L)
        val (pairs, fail, aligner) = run(ref, dist)
        assertNull(fail)
        assertEquals(1, aligner.distDropped)
        assertEquals(0, aligner.refDropped)
        assertEquals(34, pairs.size)
        // Residual skew after the drop is 33_200 - 33_367 = -167 us: inside the 4 ms floor.
        assertTrue(pairs.all { (r, d) -> kotlin.math.abs(r - d) <= PtsAligner.TOLERANCE_FLOOR_US })
    }
}
