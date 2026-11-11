package compress.joshattic.us

import compress.joshattic.us.quality.SizeGateEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A keep-original message must name its basis, and only a measurement may say "visibly". */
class KeepOriginalMessagesTest {

    private val bppReason = "Source is already heavily compressed; a re-encode would visibly lose quality, so the exact stream copy was kept."

    @Test
    fun anEightKSourceSaysItCannotBeMeasuredHere() {
        val m = KeepOriginalMessages.upFront(
            reason = bppReason, evidencePreferred = false, probedRatios = emptyList(),
            probeDetail = null, pixelCertifiableBlockReason = CertificationStatus.SKIPPED_GEOMETRY_ABOVE_CAP
        )
        assertTrue(m.contains("cannot be pixel-measured on this device"))
        assertTrue(m.contains("above the 4K scoring limit"))
        assertFalse("a heuristic may not claim what the user would see", m.contains("visibly"))
        assertTrue(m.contains("predicted to lose quality"))
    }

    @Test
    fun aLearnedLatchSaysItIsNotAMeasurementOfThisFile() {
        val m = KeepOriginalMessages.upFront(
            reason = "This device profile repeatedly failed perceptually lossless verification, so the exact stream copy was kept.",
            evidencePreferred = true, probedRatios = emptyList(), probeDetail = null, pixelCertifiableBlockReason = null
        )
        assertTrue(m.contains("learned"))
        assertTrue(m.contains("not a measurement of this file"))
    }

    @Test
    fun aProbedButUnmeasuredFileSaysSo() {
        val m = KeepOriginalMessages.upFront(
            reason = bppReason, evidencePreferred = false, probedRatios = listOf(0.9),
            probeDetail = "no probe rung could be measured (0 not time-alignable, 1 unmeasurable [1x export timed out after 60000ms]) — nothing was scored",
            pixelCertifiableBlockReason = null
        )
        assertTrue(m.contains("Probes at 0.90"))
        assertTrue(m.contains("no measurement that could decide it"))
        assertTrue(m.contains("export timed out"))
        assertFalse(m.contains("nothing was scored"))
    }

    @Test
    fun hdrNamesTheMissingModel() {
        val b = KeepOriginalMessages.basis(false, emptyList(), null, CertificationStatus.SKIPPED_HDR)
        assertEquals("Basis: heuristic. This file cannot be pixel-measured on this device: HDR has no validated quality model.", b)
    }

    private fun gate(
        ratio: Double, rungVerdict: String?, measured: Int, planned: Int, predicted: Long, source: Long,
        overshoot: Double, overshootMeasured: Boolean
    ) = SizeGateEvidence(
        verdict = SizeGateEvidence.KEEP_ORIGINAL, assessedRatio = ratio, rungId = null, rungVerdict = rungVerdict,
        measuredWindows = measured, plannedWindows = planned, predictedBytes = predicted, sourceBytes = source,
        overshoot = overshoot, overshootMeasured = overshootMeasured
    )

    @Test
    fun aProbePassKeptForSizeSaysWhatWasMeasuredAndWhatWasPredicted() {
        // b167 job_535f880370d6 passed its probe at 0.95 and was kept because the output was
        // predicted not to be smaller. Its basis read "produced no measurement that could decide
        // it", which misstates a measured pass. The size gate's basis says what decided.
        val basis = KeepOriginalMessages.sizeGateBasis(
            gate(0.90, "passed", 3, 3, predicted = 542_900_000L, source = 528_770_015L, overshoot = 1.13, overshootMeasured = true)
        )
        assertTrue(basis, basis.startsWith("Basis: pixel probes passed at 0.90 on 3 of 3 planned windows; the size prediction decided."))
        assertTrue(basis, basis.contains("542.9 MB against the 528.8 MB original"))
        assertTrue(basis, basis.contains("measured on this file's probe clips"))
        assertTrue(basis, basis.endsWith("The quality was measured on sampled windows; the size was predicted."))
        assertFalse(basis, basis.contains("visibly"))
        val learned = KeepOriginalMessages.sizeGateBasis(
            gate(0.95, "passed", 3, 3, predicted = 4_700_000L, source = 4_604_039L, overshoot = 1.0, overshootMeasured = false)
        )
        assertTrue(learned, learned.contains("learned for this device and content class"))
    }

    @Test
    fun aMarginalRungKeptForSizeIsNeverCalledAPass() {
        // b287 regular job_8227cd6d6a43: 0.75 failed, 0.95 and 0.97 cleared the bar by 0.06 and
        // 0.20 on the one planned window, below the selection margin. The gate assessed 0.97 and
        // kept the original on predicted size; the row read "pixel probes passed at 0.97".
        val basis = KeepOriginalMessages.sizeGateBasis(
            gate(0.97, "marginal", 1, 1, predicted = 114_912_600L, source = 102_508_481L,
                overshoot = 1.1444063863537028, overshootMeasured = false)
        )
        assertEquals(
            "Basis: the 0.97 probe cleared the Perceptually Lossless bar on 1 of 1 planned window but not the " +
                "selection margin (marginal), so no ratio was selected on quality; the size prediction decided. " +
                "At that rate the re-encode is predicted at 114.9 MB against the 102.5 MB original " +
                "(encoder overshoot ×1.14, learned for this device and content class; this file's probes gave too few windows), " +
                "so it would not be smaller. The quality was measured on sampled windows; the size was predicted.",
            basis
        )
        assertFalse(basis, basis.contains("probes passed"))
        assertFalse(basis, basis.contains("visibly"))
    }

    @Test
    fun aCleanPassAndAMarginalRungReadDifferently() {
        // b287 Secure Folder job_31e5faeaf665: 0.90 passed with the margin on three of three windows.
        val clean = KeepOriginalMessages.sizeGateBasis(
            gate(0.90, "passed", 3, 3, predicted = 126_265_012L, source = 123_783_185L,
                overshoot = 1.1222605797067002, overshootMeasured = true)
        )
        assertTrue(clean, clean.startsWith("Basis: pixel probes passed at 0.90 on 3 of 3 planned windows; the size prediction decided."))
        assertTrue(clean, clean.contains("126.3 MB against the 123.8 MB original"))
        assertFalse(clean, clean.contains("marginal"))
        // A rung whose record was not found says so rather than borrowing either word.
        val unknown = KeepOriginalMessages.sizeGateBasis(
            gate(0.90, null, 0, 0, predicted = 126_265_012L, source = 123_783_185L, overshoot = 1.12, overshootMeasured = true)
        )
        assertTrue(unknown, unknown.startsWith("Basis: the 0.90 probe was the rung assessed, and its verdict was not recorded; the size prediction decided."))
        assertFalse(unknown, unknown.contains("passed"))
        // Every wording keeps the phrase the capture summary classifies on.
        for (text in listOf(clean, unknown)) assertTrue(text, text.contains("the size prediction decided"))
    }

    @Test
    fun theSizeGateBasisWinsOverInferenceAndClassHistory() {
        val gate = "Basis: pixel probes passed at 0.95 on 3 of 3 planned windows; the size prediction decided. X"
        val m = KeepOriginalMessages.upFront(
            reason = KeepOriginalMessages.SIZE_GATE_REASON, evidencePreferred = true,
            probedRatios = listOf(0.95), probeDetail = "windows passed at 0.95",
            pixelCertifiableBlockReason = null, sizeGateBasis = gate
        )
        assertEquals("Kept original, no copy written. Re-encoding would not make this file smaller. $gate", m)
    }
}
