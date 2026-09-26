package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBytesTest {

    @Test
    fun theMarkSplitsWhatWasReadFromWhatFollows() {
        val bytes = byteArrayOf(0x00, 0x00, 0x12, 0xa4.toByte(), 0xff.toByte(), 0x41)
        assertEquals(
            "bytes[152,398,206..152,398,212): 00 00 12 a4 | ff 41",
            SourceBytes.hexWindow(bytes, start = 152_398_206L, mark = 152_398_210L)
        )
    }

    @Test
    fun aMarkAtTheEndOfTheWindowIsStillShown() {
        assertEquals("bytes[0..2): 01 02 |", SourceBytes.hexWindow(byteArrayOf(1, 2), start = 0L, mark = 2L))
    }

    @Test
    fun aMarkBeforeTheWindowIsOmitted() {
        assertEquals("bytes[10..11): 7f", SourceBytes.hexWindow(byteArrayOf(0x7f), start = 10L, mark = 3L))
    }

    @Test
    fun zeroFilledDataIsRecognisedAndABitstreamIsNot() {
        assertEquals(1.0, SourceBytes.zeroFraction(ByteArray(65_536))!!, 0.0)
        val stream = ByteArray(65_536) { i -> ((i * 7919) % 251 + 1).toByte() }
        assertEquals(0.0, SourceBytes.zeroFraction(stream)!!, 0.0)
        // H.264 has start-code-like zero runs; a real stream is nowhere near 99 % zeros.
        val sparse = ByteArray(65_536) { i -> if (i % 4 == 0) 0x41 else 0 }
        assertTrue(SourceBytes.zeroFraction(sparse)!! < SourceBytes.DAMAGED_ZERO_FRACTION)
        assertEquals(null, SourceBytes.zeroFraction(ByteArray(0)))
    }
}
