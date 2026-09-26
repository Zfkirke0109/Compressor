package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3InputNormalizerTest {

    private val gb = 1_000_000_000L

    @Test
    fun anSdrSourceWithRoomIsNormalised() {
        assertNull(Media3InputNormalizer.declineReason(isHdr = false, sourceBytes = 1_432_387_113L, usableBytes = 40 * gb))
    }

    @Test
    fun hdrIsNeverRoutedThroughThePlatformMuxer() {
        val why = Media3InputNormalizer.declineReason(isHdr = true, sourceBytes = gb, usableBytes = 100 * gb)
        assertTrue(why!!.startsWith("HDR source"))
    }

    @Test
    fun theCopyAndTheEncodeMustBothFit() {
        // A 4.25 GB source needs room for its copy, an encode as large as the source, and 1 GiB.
        val source = 4_250_000_000L
        val needed = 2 * source + Media3InputNormalizer.FREE_SPACE_MARGIN_BYTES
        assertNull(Media3InputNormalizer.declineReason(false, source, needed))
        val why = Media3InputNormalizer.declineReason(false, source, needed - 1)
        assertTrue(why, why!!.startsWith("not enough free space"))
    }

    @Test
    fun anUnknownSizeIsDeclined() {
        assertEquals("source size unknown", Media3InputNormalizer.declineReason(false, 0L, 100 * gb))
    }

    @Test
    fun aCopyEndingOneFrameShortIsComplete() {
        // 31 min 24 s at 30 fps: the last sample starts one frame before the declared end.
        val declared = 1_884_032_000L
        assertTrue(Media3InputNormalizer.isComplete(declared - 33_367L, declared))
    }

    @Test
    fun aCopyCutShortByAnExtractorErrorIsRejected() {
        // Stopping where Media3 stopped in b167 (about 200 s into a 31-minute file).
        assertFalse(Media3InputNormalizer.isComplete(200_000_000L, 1_884_032_000L))
        // Short by more than the tolerance near the end: 0.2 % of 31 min is 3.8 s.
        assertFalse(Media3InputNormalizer.isComplete(1_884_032_000L - 5_000_000L, 1_884_032_000L))
        // Nothing copied at all.
        assertFalse(Media3InputNormalizer.isComplete(-1L, 1_884_032_000L))
    }

    @Test
    fun shortClipsKeepAHalfSecondTolerance() {
        assertTrue(Media3InputNormalizer.isComplete(7_340_000L - 450_000L, 7_340_000L))
        assertFalse(Media3InputNormalizer.isComplete(7_340_000L - 600_000L, 7_340_000L))
    }
}
