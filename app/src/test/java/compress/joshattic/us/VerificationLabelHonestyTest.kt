package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QUAL-001. `OutputVerifier` decides "Perceptually Lossless Verified" from STRUCTURAL checks alone,
 * and it runs BEFORE sampled pixel certification — so on its own that wording would imply perceptual
 * proof for outputs whose pixels were never scored (source above the VMAF geometry cap, VMAF
 * unavailable on the device/ABI, HDR/codec-downgrade, or a certification that produced no measured
 * evidence and was accepted structurally at the codec-default ratio).
 *
 * These tests pin the invariant: the full perceptual claim is reachable ONLY when pixels were
 * actually measured and passed. They must fail if anyone re-widens the wording.
 */
class VerificationLabelHonestyTest {

    private fun report(
        verdict: String,
        replacementSafe: Boolean = true,
        verified: Boolean = true
    ) = OutputVerificationReport(
        verdict = verdict,
        playability = "opens",
        video = "1920x1080 -> 1920x1080 ok",
        fps = "30 -> 30 ok",
        videoBitrate = "10 Mbps -> 8 Mbps",
        videoCodec = "avc -> hevc ok",
        audioCodec = "aac -> aac ok",
        audioDetails = "48000/2 -> 48000/2 ok",
        audioBitrate = "128 kbps -> 128 kbps ok",
        hdr = "SDR -> SDR ok",
        colorStandard = "BT709 -> BT709 ok",
        colorRange = "limited -> limited ok",
        mediaStoreDate = "preserved",
        mp4Date = "preserved",
        location = "preserved",
        rotation = "0 -> 0 ok",
        fileSize = "100 MB -> 80 MB",
        replacementSafe = replacementSafe,
        verified = verified
    )

    @Test
    fun defaultsToNotPixelCertified() {
        // Anything that does not explicitly prove pixels must read as unproven.
        assertFalse(report(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED).pixelCertified)
    }

    @Test
    fun structuralOnlyNeverWearsTheFullPerceptualLabel() {
        val structural = report(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED)
            .withCertificationBasis(pixelCertified = false)

        assertFalse(structural.pixelCertified)
        assertEquals(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_STRUCTURAL_ONLY, structural.verdict)
        assertNotEquals(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED, structural.verdict)
        // The wording must not be able to read as a bare perceptual guarantee.
        assertTrue(structural.verdict.contains("structural checks only"))
        assertTrue(structural.verdict.contains("pixels not sampled"))
    }

    @Test
    fun measuredPixelPassKeepsTheFullPerceptualLabel() {
        val certified = report(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED)
            .withCertificationBasis(pixelCertified = true)

        assertTrue(certified.pixelCertified)
        assertEquals(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED, certified.verdict)
    }

    @Test
    fun aStructuralVerdictIsNeverUpgradedToPixelCertifiedWording() {
        // Even if a caller wrongly claims pixel proof, an already-qualified verdict must not be
        // rewritten back into the strong claim.
        val alreadyStructural = report(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_STRUCTURAL_ONLY)
            .withCertificationBasis(pixelCertified = true)

        assertEquals(
            OutputVerificationReport.PERCEPTUALLY_LOSSLESS_STRUCTURAL_ONLY,
            alreadyStructural.verdict
        )
    }

    @Test
    fun nonPerceptualVerdictsPassThroughUntouched() {
        // Remux / failure / unverified wording is owned by OutputVerifier and must not be rewritten.
        listOf(
            "Remux Verified",
            "Remux Verification Failed",
            "Perceptually Lossless Verification Failed",
            "Perceptually Lossless Unverified",
            "High Quality (lossy mode)",
            "Storage Saver (lossy mode)"
        ).forEach { original ->
            assertEquals(original, report(original).withCertificationBasis(false).verdict)
            assertEquals(original, report(original).withCertificationBasis(true).verdict)
        }
    }

    @Test
    fun certificationBasisNeverAltersAcceptanceOrReplacementSafety() {
        // Label honesty must not become a behavior change: acceptance-bearing fields are untouched.
        val base = report(OutputVerificationReport.PERCEPTUALLY_LOSSLESS_VERIFIED, replacementSafe = true)
        val structural = base.withCertificationBasis(false)
        val certified = base.withCertificationBasis(true)

        assertEquals(base.replacementSafe, structural.replacementSafe)
        assertEquals(base.replacementSafe, certified.replacementSafe)
        assertEquals(base.verified, structural.verified)
        assertEquals(base.verified, certified.verified)
        assertEquals(base.playability, structural.playability)
    }
}
