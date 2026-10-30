package compress.joshattic.us

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

    @Test
    fun aProbePassKeptForSizeSaysWhatWasMeasuredAndWhatWasPredicted() {
        // b167 job_535f880370d6 passed its probe at 0.95 and was kept because the output was
        // predicted not to be smaller. Its basis read "produced no measurement that could decide
        // it", which misstates a measured pass. The size gate's basis says what decided.
        val basis = KeepOriginalMessages.sizeGateBasis(
            provenRatio = 0.90, predictedBytes = 542_900_000L, sourceBytes = 528_770_015L,
            overshoot = 1.13, overshootMeasured = true
        )
        assertTrue(basis, basis.startsWith("Basis: pixel probes passed at 0.90; the size prediction decided."))
        assertTrue(basis, basis.contains("542.9 MB against the 528.8 MB original"))
        assertTrue(basis, basis.contains("measured on this file's probe clips"))
        assertTrue(basis, basis.endsWith("The quality was measured; the size was predicted."))
        assertFalse(basis, basis.contains("visibly"))
        val learned = KeepOriginalMessages.sizeGateBasis(0.95, 4_700_000L, 4_604_039L, 1.0, overshootMeasured = false)
        assertTrue(learned, learned.contains("learned for this device and content class"))
    }

    @Test
    fun theSizeGateBasisWinsOverInferenceAndClassHistory() {
        val gate = "Basis: pixel probes passed at 0.95; the size prediction decided. X"
        val m = KeepOriginalMessages.upFront(
            reason = KeepOriginalMessages.SIZE_GATE_REASON, evidencePreferred = true,
            probedRatios = listOf(0.95), probeDetail = "windows passed at 0.95",
            pixelCertifiableBlockReason = null, sizeGateBasis = gate
        )
        assertEquals("Kept original, no copy written. Re-encoding would not make this file smaller. $gate", m)
    }
}
