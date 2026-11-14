package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size gate's record keeps the assessed rung's own verdict and scope, on either decision. */
class SizeGateEvidenceTest {

    private val hevc = ProbeEncodeShape(iFrameIntervalSeconds = 1f, maxBFrames = 0)

    private fun rung(ratio: Double, verdict: String, planned: Int, vararg means: Double) = RungEvidence(
        ratio = ratio, outcome = RungEvidence.MEASURED, verdict = verdict,
        requestedVideoBitrate = (ratio * 12_000_000).toInt(),
        configId = EncodeConfigIdentity.of("video/hevc", hevc, (ratio * 12_000_000).toInt()),
        windows = (0 until planned).map { WindowRef(it * 10_000_000L, it * 10_000_000L + 1_200_000L) },
        scores = means.map { WindowScore(71, it, it - 1.5, it - 1.6) },
        rateFactors = emptyList(), rateDiag = null, elapsedMs = 1, encoderNames = listOf("c2.qti.hevc.encoder")
    )

    @Test
    fun aMarginalRungKeptOnPredictedSizeStaysMarginal() {
        // b287 regular job_8227cd6d6a43: 0.97 measured 95.70 on its one planned window.
        val assessed = rung(0.97, "marginal", 1, 95.7019398749062)
        val gate = SizeGateEvidence.of(
            keepOriginal = true, assessedRatio = 0.97, rung = assessed,
            predictedBytes = 114_912_600L, sourceBytes = 102_508_481L,
            overshoot = 1.1444063863537028, overshootMeasured = false
        )
        assertEquals(SizeGateEvidence.KEEP_ORIGINAL, gate.verdict)
        assertTrue(gate.keepsOriginal)
        assertEquals("marginal", gate.rungVerdict)
        assertFalse(gate.clearedSelectionMargin)
        assertEquals(1, gate.measuredWindows)
        assertEquals(1, gate.plannedWindows)
        assertEquals(SizeGateEvidence.SCOPE_COMPLETE, gate.scope)
        assertEquals(SizeGateEvidence.PREDICTED_NOT_SMALLER, gate.disposition)
        val fields = gate.fields()
        assertEquals(0.97, fields["assessedRatio"])
        assertEquals(assessed.id, fields["assessedRungId"])
        assertEquals("marginal", fields["rungVerdict"])
        assertEquals(false, fields["clearedSelectionMargin"])
        assertEquals("complete", fields["scope"])
        assertEquals("predicted", fields["sizeBasis"])
        assertEquals("sampled_probe_windows", fields["qualityBasis"])
        assertEquals("predicted_not_smaller", fields["disposition"])
        assertEquals(false, fields["overshootMeasured"])
    }

    @Test
    fun aCleanPassKeptOnPredictedSizeIsToldApartFromAMarginalOne() {
        // b287 Secure Folder job_31e5faeaf665: 0.90 passed on three of three windows.
        val gate = SizeGateEvidence.of(
            keepOriginal = true, assessedRatio = 0.90,
            rung = rung(0.90, "passed", 3, 99.5743957723856, 98.54452288978827, 96.99546166108556),
            predictedBytes = 126_265_012L, sourceBytes = 123_783_185L,
            overshoot = 1.1222605797067002, overshootMeasured = true
        )
        assertEquals("passed", gate.rungVerdict)
        assertTrue(gate.clearedSelectionMargin)
        assertEquals(3, gate.measuredWindows)
        assertEquals(SizeGateEvidence.SCOPE_COMPLETE, gate.scope)
        assertEquals(SizeGateEvidence.PREDICTED_NOT_SMALLER, gate.disposition)
    }

    @Test
    fun anEncodeVerdictIsStillOnlyAPredictionAboutSize() {
        val gate = SizeGateEvidence.of(
            keepOriginal = false, assessedRatio = 0.75, rung = rung(0.75, "marginal", 3, 96.4, 97.5, 96.0),
            predictedBytes = 33_366_670L, sourceBytes = 44_000_000L, overshoot = 1.05, overshootMeasured = true
        )
        assertEquals(SizeGateEvidence.ENCODE, gate.verdict)
        assertFalse(gate.keepsOriginal)
        assertEquals(SizeGateEvidence.PREDICTED_SMALLER, gate.disposition)
        // A marginal rung sent on to a full encode has still not cleared the selection margin.
        assertFalse(gate.clearedSelectionMargin)
        assertEquals("predicted", gate.fields()["sizeBasis"])
    }

    @Test
    fun aRungThatStoppedEarlyOrWasNotFoundIsNeverReportedComplete() {
        val early = SizeGateEvidence.of(true, 0.95, rung(0.95, "marginal", 3, 95.6), 10L, 9L, 1.0, false)
        assertEquals(SizeGateEvidence.SCOPE_PARTIAL, early.scope)
        assertEquals(1, early.measuredWindows)
        assertEquals(3, early.plannedWindows)
        val missing = SizeGateEvidence.of(true, 0.95, null, 10L, 9L, 1.0, false)
        assertNull(missing.rungVerdict)
        assertNull(missing.rungId)
        assertFalse(missing.clearedSelectionMargin)
        assertEquals(SizeGateEvidence.SCOPE_UNKNOWN, missing.scope)
        assertEquals("unknown", missing.fields()["scope"])
    }
}
