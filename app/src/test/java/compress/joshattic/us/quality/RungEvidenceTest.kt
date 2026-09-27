package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** b177 F2: per-rung probe evidence, and the join keys that stop two rungs being mixed. */
class RungEvidenceTest {

    private fun w(mean: Double) = WindowScore(36, mean, mean - 2, mean - 2)

    private fun rung(ratio: Double, vararg means: Double, outcome: String = RungEvidence.MEASURED) = RungEvidence(
        ratio = ratio, outcome = outcome, verdict = "passed",
        requestedVideoBitrate = (ratio * 1_000_000).toInt(),
        configId = EncodeConfigIdentity.of("video/hevc", ProbeEncodeShape(iFrameIntervalSeconds = 3f, maxBFrames = 2), (ratio * 1_000_000).toInt()),
        windows = listOf(WindowRef(12_000_000, 13_200_000), WindowRef(64_000_000, 65_200_000), WindowRef(102_000_000, 103_200_000)),
        scores = means.map { w(it) }, rateFactors = emptyList(), rateDiag = null, elapsedMs = 1
    )

    @Test
    fun theSaferRetryFindsItsOwnRungNotTheProvenOne() {
        // job_478c2fa19100's ladder: 0.90 passed, then the refinement 0.85 passed. The retry at
        // 0.90 must carry 0.90's windows; b177 recorded 0.85's (99.081;97.694;96.491).
        val rungs = listOf(rung(0.90, 99.4, 98.3, 97.1), rung(0.85, 99.081, 97.694, 96.491))
        val safer = RungEvidence.measuredAt(rungs, 0.90)!!
        assertEquals(listOf(99.4, 98.3, 97.1), safer.scores.map { it.mean })
        assertNotEquals(RungEvidence.measuredAt(rungs, 0.85)!!.id, safer.id)
        assertNull(RungEvidence.measuredAt(rungs, 0.95))
        assertNull(RungEvidence.measuredAt(listOf(rung(0.90, outcome = RungEvidence.UNAVAILABLE)), 0.90))
    }

    @Test
    fun scoredWindowIdsFollowTheScoresOfAnEarlyStoppedRung() {
        val stopped = rung(0.80, 94.0)
        assertEquals(listOf("12000000-13200000"), stopped.scoredWindowIds)
        assertEquals(3, stopped.windows.size)
    }

    @Test
    fun aProbeAndAFullEncodeWithTheSameRequestShareAConfigId() {
        val probe = EncodeConfigIdentity.of("video/hevc", ProbeEncodeShape(iFrameIntervalSeconds = 3f, maxBFrames = 2), 826_407)
        val encode = EncodeConfigIdentity.of("video/hevc", "VBR", 3f, 2, 826_407)
        assertEquals(probe, encode)
        assertNotEquals(probe, EncodeConfigIdentity.of("video/hevc", "VBR", 3f, 2, 780_495))
        assertNotEquals(probe, EncodeConfigIdentity.of("video/hevc", "VBR", 3f, 0, 826_407))
        assertNotEquals(probe, EncodeConfigIdentity.of("video/hevc", "CBR", 3f, 2, 826_407))
        assertEquals(12, probe.length)
    }

    @Test
    fun windowIdsJoinProbeAndCertificationScores() {
        val cert = WindowScore(36, 98.9, 97.1, 97.1, windowStartUs = 12_000_000, windowEndUs = 13_200_000)
        assertEquals(rung(0.9, 99.0).windows.first().id, cert.windowId)
        assertNull(WindowScore(36, 1.0, 1.0, 1.0).windowId)
    }
}
