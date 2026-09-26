package compress.joshattic.us.quality

import compress.joshattic.us.BatchQualityBitratePolicy
import compress.joshattic.us.BatchQualityMode
import compress.joshattic.us.VideoSourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worth-encoding gate, replayed on b167 files through the production prediction. It must skip
 * the encode that was wasted (job_732f7ecfb699) and none of the encodes the raw probe prediction
 * would have wrongly skipped (job_4e02400464fd, job_c0f82bb62f94). Numbers are the job records'.
 */
class MeasuredOvershootTest {

    @Test
    fun tooFewWindowsGiveNoBound() {
        assertNull(MeasuredOvershoot.lowerBound(emptyList()))
        assertNull(MeasuredOvershoot.lowerBound(listOf(1.3)))
        assertNull(MeasuredOvershoot.lowerBound(listOf(1.3, Double.NaN)))
    }

    @Test
    fun theBoundIsTheMeanLessTheMargin() {
        assertEquals(1.2597 - MeasuredOvershoot.MARGIN, MeasuredOvershoot.lowerBound(listOf(1.262, 1.248, 1.269))!!, 1e-3)
    }

    @Test
    fun theLearnedValueIsNeverLowered() {
        // A measurement that bounds below the learned factor changes nothing.
        assertEquals(1.08, MeasuredOvershoot.forPrediction(1.08, listOf(1.0, 1.05)), 1e-12)
        assertEquals(1.0, MeasuredOvershoot.forPrediction(1.0, listOf(1.03, 1.07, 1.13)), 1e-12)
    }

    @Test
    fun theWasted4kEncodeIsSkipped() {
        // job_732f7ecfb699: 4K H.264 -> HEVC at 0.90, learned overshoot 1.0025, probe windows
        // x1.262/1.248/1.269; the real encode came out at x1.235, 587 MB for a 528 MB source.
        val source = VideoSourceInfo(
            width = 2160, height = 3840, frameRate = 30f, durationMs = 279_266L,
            totalBitrate = 15_147_401, audioBitrate = 128_000, videoMime = "video/avc"
        )
        val sourceBytes = 528_770_015L
        assertFaithful(source, 0.90, recordedTarget = 13_517_460)
        val learned = 1.0025086928129157
        val factors = listOf(1.262, 1.248, 1.269)
        val before = predicted(source, 0.90, learned)
        val after = predicted(source, 0.90, MeasuredOvershoot.forPrediction(learned, factors))
        assertTrue("the learned factor predicted a saving, so the encode ran", before < sourceBytes)
        assertFalse(
            "with the measured bound the encode is not worth running",
            ExhaustivePerceptualLosslessPolicy.worthEncoding(sourceBytes, after, exhaustive = true, meetsNoiseThreshold = false)
        )
    }

    @Test
    fun theEncodesThatSavedAreStillRun() {
        // job_4e02400464fd saved 2.7 % and job_c0f82bb62f94 4.6 %. The raw probe mean would have
        // skipped both; the bound leaves them to the learned factor.
        val a = VideoSourceInfo(
            width = 1080, height = 1858, frameRate = 24f, durationMs = 610_801L,
            totalBitrate = 4_011_044, audioBitrate = 128_000, videoMime = "video/avc"
        )
        assertFaithful(a, 0.97, recordedTarget = 3_766_552)
        val aBytes = predicted(a, 0.97, MeasuredOvershoot.forPrediction(1.0, listOf(0.996, 1.066, 1.053)))
        assertTrue(ExhaustivePerceptualLosslessPolicy.worthEncoding(306_243_731L, aBytes, exhaustive = true, meetsNoiseThreshold = false))

        val b = VideoSourceInfo(
            width = 2560, height = 1440, frameRate = 30f, durationMs = 17_963L,
            totalBitrate = 8_551_928, audioBitrate = 256_000, videoMime = "video/hevc"
        )
        assertFaithful(b, 0.95, recordedTarget = 7_881_131)
        val bBytes = predicted(b, 0.95, MeasuredOvershoot.forPrediction(1.0, listOf(1.030, 1.070, 1.129)))
        assertTrue(ExhaustivePerceptualLosslessPolicy.worthEncoding(19_201_965L, bBytes, exhaustive = true, meetsNoiseThreshold = false))
    }

    @Test
    fun theDescriptionShowsMeasuredBoundLearnedAndUsed() {
        assertEquals(
            "measuredOvershoot=1.260(n=3,bound=1.130),learned=1.003,used=1.130",
            MeasuredOvershoot.describe(1.0025, listOf(1.26, 1.26, 1.26))
        )
        assertEquals(
            "measuredOvershoot=1.300(n=1,too few windows),learned=1.000,used=1.000",
            MeasuredOvershoot.describe(1.0, listOf(1.3))
        )
    }

    private fun predicted(source: VideoSourceInfo, ratio: Double, overshoot: Double): Long =
        BatchQualityBitratePolicy.predictedPerceptualLosslessBytes(
            source = source,
            outputMimeType = "video/hevc",
            learnedTargetRatio = ratio,
            expectedOvershootFactor = overshoot,
            pixelProvenRatioFloor = ratio
        )

    /** The replay must request what the device requested, or it proves nothing. */
    private fun assertFaithful(source: VideoSourceInfo, ratio: Double, recordedTarget: Int) {
        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = "video/hevc",
            learnedTargetRatio = ratio,
            pixelProvenRatioFloor = ratio
        )
        assertEquals(recordedTarget.toDouble(), target.toDouble(), recordedTarget * 0.005)
    }
}
