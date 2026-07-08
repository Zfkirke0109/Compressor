package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPerceptualProfileEngineTest {
    private fun engine() = SmartPerceptualProfileEngine(SmartPerceptualProfileEngine.InMemoryProfileStore())

    private fun hdr4k60Source() = VideoSourceInfo(
        width = 3840,
        height = 2160,
        frameRate = 60f,
        durationMs = 60_000,
        totalBitrate = 119_900_000,
        audioBitrate = 320_000,
        videoMime = MimeTypes.VIDEO_H265,
        audioMime = MimeTypes.AUDIO_AAC,
        colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
        colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
        colorRange = MediaFormat.COLOR_RANGE_LIMITED
    )

    private fun key() = SmartPerceptualProfileEngine.profileKeyFor(
        source = hdr4k60Source(),
        encoderMime = MimeTypes.VIDEO_H265,
        manufacturer = "samsung",
        model = "SM-S918B",
        sdkInt = 34
    )

    @Test
    fun profileKeyBucketsAreTechnicalAndStable() {
        val k = key()
        assertEquals("4k", k.resolutionBucket)
        assertEquals("60", k.fpsBucket)
        assertEquals("pq-bt2020", k.hdrBucket)
        assertEquals("80-120m", k.bitrateBucket)
        assertEquals("mp4a-latm-gte288k", k.audioBucket)
        // Key must be a stable machine string with no free-form user data.
        assertTrue(k.asKey().contains("samsung|sm-s918b|34"))
    }

    @Test
    fun bucketHelpersCoverEdgeProfiles() {
        assertEquals("8k", SmartPerceptualProfileEngine.resolutionBucket(7680, 4320))
        assertEquals("4k", SmartPerceptualProfileEngine.resolutionBucket(2160, 3840)) // portrait 4K
        assertEquals("1080p", SmartPerceptualProfileEngine.resolutionBucket(1920, 1080))
        assertEquals("120", SmartPerceptualProfileEngine.fpsBucket(119.88f))
        assertEquals("60", SmartPerceptualProfileEngine.fpsBucket(59.94f))
        assertEquals("24", SmartPerceptualProfileEngine.fpsBucket(23.976f))
        assertEquals("variable", SmartPerceptualProfileEngine.fpsBucket(0f))
    }

    @Test
    fun unknownProfileUsesDefaultRatio() {
        val e = engine()
        assertEquals(0.95, e.recommendedTargetRatio(key(), defaultRatio = 0.95, floorRatio = 0.80), 1e-9)
        assertFalse(e.shouldPreferRemux(key()))
    }

    @Test
    fun verifiedSuccessStoresProfileAndStepsDownCautiously() {
        val e = engine()
        val updated = e.recordVerifiedSuccess(key(), usedTargetRatio = 0.95, outputToSourceBytesRatio = 0.96, floorRatio = 0.80)

        assertEquals(1, updated.successCount)
        assertEquals(0.93, updated.nextTargetRatio!!, 1e-9)
        assertNull(updated.lastFailureReason)
        assertEquals(0.93, e.recommendedTargetRatio(key(), defaultRatio = 0.95, floorRatio = 0.80), 1e-9)
    }

    @Test
    fun repeatedSuccessesNeverStepBelowSafetyFloor() {
        val e = engine()
        var ratio = 0.95
        repeat(30) {
            ratio = e.recordVerifiedSuccess(key(), ratio, 0.9, floorRatio = 0.80).nextTargetRatio!!
        }
        assertEquals(0.80, ratio, 1e-9)
        assertEquals(0.80, e.recommendedTargetRatio(key(), defaultRatio = 0.95, floorRatio = 0.80), 1e-9)
    }

    @Test
    fun failureRaisesFutureTargetRatio() {
        val e = engine()
        val updated = e.recordFailure(key(), usedTargetRatio = 0.85, reason = "output bitrate below floor", floorRatio = 0.80)

        assertEquals(1, updated.failureCount)
        assertEquals(0.90, updated.nextTargetRatio!!, 1e-9)
        assertFalse(updated.preferRemux)
        assertEquals("output bitrate below floor", updated.lastFailureReason)
    }

    @Test
    fun repeatedHighRatioFailuresMarkProfileAsRemuxPreferred() {
        val e = engine()
        e.recordFailure(key(), usedTargetRatio = 0.95, reason = "size grew beyond tolerance", floorRatio = 0.80)
        assertFalse(e.shouldPreferRemux(key()))

        e.recordFailure(key(), usedTargetRatio = 0.97, reason = "size grew beyond tolerance", floorRatio = 0.80)
        assertTrue(e.shouldPreferRemux(key()))

        // A later verified success clears the remux preference again.
        e.recordVerifiedSuccess(key(), usedTargetRatio = 0.95, outputToSourceBytesRatio = 0.9, floorRatio = 0.80)
        assertFalse(e.shouldPreferRemux(key()))
    }

    @Test
    fun recommendationsAreAlwaysClampedToSafetyBounds() {
        val e = engine()
        // Failures can never push the recommendation above the maximum useful ratio…
        e.recordFailure(key(), usedTargetRatio = 0.97, reason = "unverified", floorRatio = 0.80)
        assertTrue(
            e.recommendedTargetRatio(key(), 0.95, 0.80) <=
                BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO
        )
        // …and a corrupt/hostile stored value can never drop below the floor.
        val store = SmartPerceptualProfileEngine.InMemoryProfileStore()
        store.write(key().asKey(), "success=1;failure=0;highFail=0;nextRatio=0.05;preferRemux=false;lastReason=;lastSizeRatio=")
        val tampered = SmartPerceptualProfileEngine(store)
        assertEquals(0.90, tampered.recommendedTargetRatio(key(), 0.95, 0.90), 1e-9)
    }

    @Test
    fun learnedProfileEncodingRoundTrips() {
        val original = SmartPerceptualProfileEngine.LearnedEncodeProfile(
            successCount = 3,
            failureCount = 2,
            consecutiveHighRatioFailures = 1,
            nextTargetRatio = 0.91,
            preferRemux = false,
            lastFailureReason = "hdr metadata lost",
            lastOutputToSourceRatio = 0.88
        )
        val decoded = SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(original.encode())
        assertEquals(original, decoded)
        assertNull(SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(null))
        assertNull(SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(""))
    }

    @Test
    fun learningCannotBypassVerificationDecisions() {
        // The engine only produces a target ratio; feeding that ratio into the policy still
        // clamps to the safety floor, and OutputVerifier remains the only source of "verified".
        val e = engine()
        repeat(10) {
            e.recordVerifiedSuccess(key(), 0.95, 0.9, floorRatio = 0.80)
        }
        val learnedRatio = e.recommendedTargetRatio(key(), 0.95, 0.80)
        val source = hdr4k60Source()
        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265,
            learnedTargetRatio = learnedRatio
        )
        assertTrue(target >= BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(source))
    }
}
