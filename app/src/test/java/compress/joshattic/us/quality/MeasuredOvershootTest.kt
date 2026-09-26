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
    fun thisFilesProbesOutrankTheLearnedValue() {
        // The learned value is evidence about OTHER files; when the probes bound this one, it is
        // not used, in either direction.
        assertEquals(1.025 - MeasuredOvershoot.MARGIN, MeasuredOvershoot.forPrediction(1.08, listOf(1.0, 1.05)), 1e-12)
        assertEquals(1.26 - MeasuredOvershoot.MARGIN, MeasuredOvershoot.forPrediction(1.0, listOf(1.26, 1.26)), 1e-12)
        // Too few windows: the learned value stands in.
        assertEquals(1.061, MeasuredOvershoot.forPrediction(1.061, listOf(1.052)), 1e-12)
        assertTrue(MeasuredOvershoot.fromThisFile(listOf(1.0, 1.05)))
        assertFalse(MeasuredOvershoot.fromThisFile(listOf(1.052)))
    }

    @Test
    fun b168sFalseNegativeIsEncodedAgain() {
        // job_458aa0663c3e, 4K H.264 -> HEVC at 0.90. b167 encoded it at x1.003 and saved 9.5 %,
        // pixel-certified. In b168 its probes measured x1.089/1.073/1.025, but the bucket's learned
        // value had become 1.119 (the mean of 458a's 1.003 and 732f's 1.235), and the gate kept the
        // original on a prediction of 74,852,436 bytes against 73,612,248.
        val source = VideoSourceInfo(
            width = 2160, height = 3840, frameRate = 30f, durationMs = 25_031L,
            totalBitrate = 23_526_641, audioBitrate = 128_000, videoMime = "video/avc"
        )
        val sourceBytes = 73_612_248L
        assertFaithful(source, 0.90, recordedTarget = 21_058_776)
        val learned = 1.1186904993523514
        val factors = listOf(1.089, 1.073, 1.025)
        // The b168 rule, max(learned, bound), reproduces the logged prediction.
        val old = predicted(source, 0.90, maxOf(learned, MeasuredOvershoot.lowerBound(factors)!!))
        assertEquals(74_852_436.0, old.toDouble(), 74_852_436 * 0.005)
        assertFalse(ExhaustivePerceptualLosslessPolicy.worthEncoding(sourceBytes, old, exhaustive = true, meetsNoiseThreshold = false))
        // Now the file's own bound decides, and it is encoded.
        val now = predicted(source, 0.90, MeasuredOvershoot.forPrediction(learned, factors))
        assertTrue(ExhaustivePerceptualLosslessPolicy.worthEncoding(sourceBytes, now, exhaustive = true, meetsNoiseThreshold = false))
        // Within 1 % of what b167's real encode wrote (66,598,388 bytes).
        assertEquals(66_598_388.0, now.toDouble(), 66_598_388 * 0.01)
    }

    @Test
    fun theWasted4kEncodeIsStillSkippedWithB168sLearnedValue() {
        val source = VideoSourceInfo(
            width = 2160, height = 3840, frameRate = 30f, durationMs = 279_266L,
            totalBitrate = 15_147_401, audioBitrate = 128_000, videoMime = "video/avc"
        )
        val bytes = predicted(source, 0.90, MeasuredOvershoot.forPrediction(1.1186904993523514, listOf(1.262, 1.248, 1.269)))
        assertFalse(ExhaustivePerceptualLosslessPolicy.worthEncoding(528_770_015L, bytes, exhaustive = true, meetsNoiseThreshold = false))
    }

    @Test
    fun replayingB168sGateDecisionsOnlyUnblocks458a() {
        // (learned, window factors, b168 verdict) for every size-gate line of batch_1790391146747.
        // The prediction is monotonic in the factor, so a decision can only flip to "keep" if the
        // factor rose, and only flip to "encode" if it fell.
        data class Gate(val job: String, val learned: Double, val factors: List<Double>, val kept: Boolean)
        val gates = listOf(
            Gate("be917e", 1.008, listOf(1.065, 1.065, 1.065), false), Gate("0291f3", 1.000, listOf(0.970), false),
            Gate("f72925", 1.000, listOf(1.010, 1.010, 1.010), false), Gate("434bc1", 1.000, listOf(1.013, 1.013, 1.013), false),
            Gate("2d1bda", 1.003, listOf(1.029, 1.029, 1.029), false), Gate("48ea8d", 1.005, listOf(0.986, 0.986, 0.986), false),
            Gate("458aa0", 1.119, listOf(1.062, 1.062, 1.062), true), Gate("f92567", 1.003, listOf(1.024, 1.024, 1.024), false),
            Gate("1c5ac6", 1.000, listOf(1.006, 1.006, 1.006), false), Gate("286623", 1.000, listOf(1.030, 1.030, 1.030), false),
            Gate("34f357", 1.000, listOf(1.030, 1.030, 1.030), false), Gate("9260d5", 1.000, listOf(1.031, 1.031, 1.031), false),
            Gate("7a615b", 1.000, listOf(1.005, 1.005, 1.005), false), Gate("a15a8b", 1.002, listOf(1.009, 1.009, 1.009), false),
            Gate("4e0240", 1.000, listOf(1.039, 1.039, 1.039), false), Gate("d12746", 1.000, listOf(1.015, 1.015, 1.015), false),
            Gate("c0f82b", 1.010, listOf(1.076, 1.076, 1.076), false), Gate("402118", 1.000, listOf(1.018, 1.018, 1.018), false),
            Gate("dbe605", 1.000, listOf(1.067, 1.067, 1.067), false), Gate("732f7e", 1.119, listOf(1.260, 1.260, 1.260), true),
            Gate("bf0946", 1.000, listOf(0.996, 0.996, 0.996), false), Gate("e43fe6", 1.000, listOf(1.057, 1.057, 1.057), false),
            Gate("4dbbc7", 1.043, listOf(1.263), false), Gate("535f88", 1.061, listOf(1.052), true),
            Gate("c92a4c", 1.001, listOf(1.020, 1.020, 1.020), false), Gate("f61d78", 1.061, listOf(1.013, 1.013, 1.013), false),
            Gate("50d1ff", 1.027, listOf(1.041), false), Gate("d17cde", 1.013, listOf(1.112), false)
        )
        val rose = mutableListOf<String>()
        val fellOnAKeep = mutableListOf<String>()
        for (g in gates) {
            val before = maxOf(g.learned, MeasuredOvershoot.lowerBound(g.factors) ?: g.learned).coerceAtLeast(1.0)
            val after = MeasuredOvershoot.forPrediction(g.learned, g.factors).coerceAtLeast(1.0)
            if (after > before + 1e-12) rose += g.job
            if (g.kept && after < before - 1e-12) fellOnAKeep += g.job
        }
        assertTrue("no encode may newly be skipped: $rose", rose.isEmpty())
        assertEquals(listOf("458aa0"), fellOnAKeep)
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
