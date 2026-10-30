package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The self-check identity controls judge identity by bytes, not by a VMAF score (b184). */
class SelfCheckVerdictTest {

    private fun pairing(identical: Int?) = WindowPairingDiag(
        refFrames = 87, distFrames = 87, refExtra = 0, distExtra = 0,
        skewFirstUs = 0, skewMaxAbsUs = 0, skewMeanAbsUs = 0, leadInPairsSkipped = 15,
        identicalFrames = identical
    )

    private fun window(mean: Double, p5: Double, min: Double, identical: Int?) =
        WindowScore(comparedFrames = 72, mean = mean, p5 = p5, min = min, pairing = pairing(identical))

    @Test
    fun identicalFramesThatVmafScoresBelow100AreAPassNotADefect() {
        // b184 selfcheck_1790698977922, job_0b01f05b3686 (1080x1920 @ 60 fps): exact pairing, a
        // low-motion run in window 2 at 98.86-98.9. It read "FAIL (... a scorer or pairing defect)".
        val windows = listOf(
            window(99.98, 99.84, 99.50, identical = 72),
            window(99.82, 98.88, 98.86, identical = 72),
            window(100.0, 100.0, 100.0, identical = 72)
        )
        val verdict = SelfCheckVerdict.identity(windows)
        assertTrue(verdict, verdict.startsWith("PASS (all 216 frames byte-identical; VMAF v0.6.1"))
        assertTrue(verdict, verdict.contains("min 98.86"))
    }

    @Test
    fun anyDifferingFrameIsAFailWhateverItScored() {
        // A one-byte decode difference can still score 99.9; the bytes decide.
        val windows = listOf(window(99.99, 99.9, 99.9, identical = 71), window(100.0, 100.0, 100.0, identical = 72))
        assertEquals(
            "FAIL (1 of 144 scored frames differ from the source's bytes: a decode, crop, rotation or pairing defect) ",
            SelfCheckVerdict.identity(windows)
        )
    }

    @Test
    fun perfectScoresOnIdenticalFramesPassAndUncountedScoresAreNotProof() {
        assertEquals("PASS (all 72 frames byte-identical) ", SelfCheckVerdict.identity(listOf(window(100.0, 100.0, 100.0, 72))))
        // Without the byte count the score alone cannot prove identity.
        assertTrue(SelfCheckVerdict.identity(listOf(window(99.8, 98.9, 98.86, null))).startsWith("UNPROVEN (min 98.86"))
        assertTrue(SelfCheckVerdict.identity(listOf(window(97.46, 97.44, 97.44, null))).startsWith("STATIC?"))
        assertEquals("PASS ", SelfCheckVerdict.identity(listOf(window(100.0, 100.0, 100.0, null))))
    }

    @Test
    fun theByteCountAppearsInTheCaptureForm() {
        assertTrue(pairing(72).compact().endsWith(",leadIn=15,identical=72"))
        assertTrue(!pairing(null).compact().contains("identical"))
    }
}
