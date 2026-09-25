package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeClipBitrateTest {

    @Test
    fun theSteadyStatePredictionSpreadsOneKeyframeOverTheGop() {
        // 30 fps, 3 s GOP: 90 frames, one 900 kB keyframe and 89 P-frames of 30 kB.
        val split = ProbeClipBitrate.Split(syncBytes = 900_000L, syncCount = 1, otherBytes = 30_000L * 35, otherCount = 35, spanUs = 1_166_666L)
        val bps = ProbeClipBitrate.predictedSteadyStateBps(split, fps = 30.0, gopSeconds = 3.0f)!!
        // (900_000 + 89 * 30_000) bytes per 3 s = 1,190,000 B/s = 9,520,000 bps
        assertEquals(9_520_000.0, bps.toDouble(), 1_000.0)
    }

    @Test
    fun withoutAKeyframeSampleTheKeyframeIsAssumedToCostAPFrame() {
        val split = ProbeClipBitrate.Split(0L, 0, 30_000L * 36, 36, 1_166_666L)
        assertEquals((30_000L * 8 * 30).toDouble(), ProbeClipBitrate.predictedSteadyStateBps(split, 30.0, 3.0f)!!.toDouble(), 1.0)
    }

    @Test
    fun nothingMeasuredMeansNoPrediction() {
        assertNull(ProbeClipBitrate.predictedSteadyStateBps(ProbeClipBitrate.Split(0, 0, 0, 0, 0), 30.0, 3.0f))
        assertNull(ProbeClipBitrate.measuredBps(ProbeClipBitrate.Split(0, 0, 0, 0, 0)))
    }

    @Test
    fun theCompactLineNamesRequestWindowSplitAndFactor() {
        val split = ProbeClipBitrate.Split(142_000L, 1, 18_000L * 35, 35, 1_166_666L)
        val line = ProbeClipBitrate.compact(requestedBps = 5_183_000, split = split, fps = 30.0, gopSeconds = 3.0f)
        assertEquals(true, line.startsWith("rate[req=5183kbps,win="))
        assertEquals(true, line.contains("I=1x142kB,P=35x18kB,steady="))
        assertEquals(true, line.endsWith("]"))
    }
}
