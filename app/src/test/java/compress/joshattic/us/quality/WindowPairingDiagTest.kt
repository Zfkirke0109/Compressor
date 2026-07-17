package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pairing diagnostics are telemetry-only: they must format deterministically for capture
 * parsing and must never influence a pass/fail verdict (windowsPass reads scores, not pairing).
 */
class WindowPairingDiagTest {

    @Test
    fun compactFormatsCountsAndMillisecondSkews() {
        val diag = WindowPairingDiag(
            refFrames = 36,
            distFrames = 38,
            refExtra = 0,
            distExtra = 2,
            skewFirstUs = 0L,
            skewMaxAbsUs = 867_000L,
            skewMeanAbsUs = 115_200L
        )
        assertEquals("ref=36,dist=38,extra=0/2,skewMs=0.0/867.0/115.2,drop=0/0", diag.compact())
    }

    @Test
    fun compactKeepsSignOfFirstSkewAndSubMillisecondPrecision() {
        val diag = WindowPairingDiag(
            refFrames = 12,
            distFrames = 12,
            refExtra = 1,
            distExtra = 0,
            skewFirstUs = -16_700L, // dist leads ref by one 60fps frame interval
            skewMaxAbsUs = 16_700L,
            skewMeanAbsUs = 50L,
            refAlignDrops = 1,
            distAlignDrops = 0
        )
        assertEquals("ref=12,dist=12,extra=1/0,skewMs=-16.7/16.7/0.1,drop=1/0", diag.compact())
    }

    @Test
    fun compactIsLocaleStableOnCommaDecimalDevices() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val diag = WindowPairingDiag(35, 34, 1, 0, 33_200L, 33_200L, 33_200L, 0, 1)
            assertEquals("ref=35,dist=34,extra=1/0,skewMs=33.2/33.2/33.2,drop=0/1", diag.compact())
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun windowScoreCarriesNoPairingByDefaultAndVerdictNeverReadsIt() {
        // Legacy construction (certification paths, tests) stays valid with pairing = null,
        // and two scores differing only in pairing data pass/fail identically.
        val bare = WindowScore(comparedFrames = 30, mean = 96.0, p5 = 92.0, min = 90.0)
        assertNull(bare.pairing)
        val misaligned = bare.copy(
            pairing = WindowPairingDiag(30, 30, 0, 0, 500_000L, 900_000L, 400_000L)
        )
        assertEquals(
            QualityProbePolicy.windowsPass(listOf(bare)),
            QualityProbePolicy.windowsPass(listOf(misaligned))
        )
    }
}
