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

    @Test
    fun aCopyHoldingAFractionOfTheSourceIsNotANormalisedInput() {
        // b168: 177.4 MB copied from 1,432 MB; 72.9 MB from 4,250 MB. Both passed the timestamp check.
        assertFalse(Media3InputNormalizer.isSubstantial(177_400_000L, 1_432_387_113L))
        assertFalse(Media3InputNormalizer.isSubstantial(72_900_000L, 4_250_584_607L))
        assertTrue(Media3InputNormalizer.isSubstantial(1_430_000_000L, 1_432_387_113L))
        assertTrue(Media3InputNormalizer.isSubstantial(1L, 0L))
    }

    @Test
    fun aHeavilyPaddedButCompleteCopyIsStillDeclinedWhichOnlyCostsTheSaving() {
        // Known limit (b169 review, R8): a size ratio cannot tell dropped samples from removed
        // padding. A source with 60 % free/skip atoms whose copy is complete in time is declined
        // too. That keeps the original (the safe side) and loses at most that file's saving; the
        // guard stays until a padded-container device fixture shows it matters.
        val declared = 30_000_000L
        assertTrue(Media3InputNormalizer.isComplete(declared - 33_000L, declared))
        assertFalse(Media3InputNormalizer.isSubstantial(400_000_000L, 1_000_000_000L))
    }

    @Test
    fun copiesLeftByADeadProcessAreClearedAtBatchStartAndNothingElseIs() {
        val cache = java.nio.file.Files.createTempDirectory("cache").toFile()
        try {
            val work = Media3InputNormalizer.workDir(cache)
            java.io.File(work, "media3input_1.mp4").writeBytes(ByteArray(1000))
            java.io.File(work, "media3input_2.mp4").writeBytes(ByteArray(500))
            // An older build wrote copies into the cache root.
            java.io.File(cache, "media3input_3.mp4").writeBytes(ByteArray(250))
            // Unrelated cache files must survive: outputs, other temp files.
            val outputs = java.io.File(cache, "batch_compressed_videos").apply { mkdirs() }
            val output = java.io.File(outputs, "clip_compressed.mp4").apply { writeBytes(ByteArray(10)) }
            val other = java.io.File(cache, "selfcheck_remux_1.mp4").apply { writeBytes(ByteArray(10)) }

            assertEquals(1750L, Media3InputNormalizer.clearLeftovers(cache))
            assertTrue(work.listFiles().orEmpty().isEmpty())
            assertFalse(java.io.File(cache, "media3input_3.mp4").exists())
            assertTrue(output.exists())
            assertTrue(other.exists())
            assertEquals(0L, Media3InputNormalizer.clearLeftovers(cache))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun newCopiesAreWrittenIntoTheClearedFolder() {
        val cache = java.nio.file.Files.createTempDirectory("cache").toFile()
        try {
            assertEquals(java.io.File(cache, Media3InputNormalizer.CACHE_SUBDIR), Media3InputNormalizer.workDir(cache))
            assertTrue(Media3InputNormalizer.workDir(cache).isDirectory)
        } finally {
            cache.deleteRecursively()
        }
    }
}
