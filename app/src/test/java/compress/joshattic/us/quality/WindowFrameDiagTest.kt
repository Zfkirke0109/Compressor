package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowFrameDiagTest {

    @Test
    fun namesTheWorstFrameAndTheEnds() {
        val d = WindowFrameDiag.fromPerFrame(doubleArrayOf(77.5, 79.0, 98.0, 99.0, 97.2))!!
        assertEquals(5, d.frames)
        assertEquals(0, d.minIndex)
        assertEquals(77.5, d.first, 0.0)
        assertEquals(97.2, d.last, 0.0)
        assertEquals(listOf(0 to 77.5, 1 to 79.0, 4 to 97.2), d.lowest)
        assertEquals("n=5,minAt=0,first=77.5,last=97.2,low=0:77.5|1:79.0|4:97.2", d.compact())
    }

    @Test
    fun aWarmUpMinimumReadsDifferentlyFromAHardFrameInTheMiddle() {
        val warmUp = WindowFrameDiag.fromPerFrame(doubleArrayOf(80.0, 90.0, 98.0, 98.0))!!
        val hard = WindowFrameDiag.fromPerFrame(doubleArrayOf(98.0, 98.0, 80.0, 98.0))!!
        assertEquals(0, warmUp.minIndex)
        assertEquals(2, hard.minIndex)
    }

    @Test
    fun emptyInputHasNoDiagnostic() {
        assertNull(WindowFrameDiag.fromPerFrame(DoubleArray(0)))
    }

    @Test
    fun theCompactFormIsLocalePinned() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val d = WindowFrameDiag.fromPerFrame(doubleArrayOf(90.5))!!
            assertEquals("n=1,minAt=0,first=90.5,last=90.5,low=0:90.5", d.compact())
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
