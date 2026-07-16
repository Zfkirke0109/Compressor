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
    fun verifiedSuccessStoresProfileWithoutSteppingTheTargetDown() {
        // 2026-07-14 VMAF evidence: structural verification is perceptually blind, so a pass may
        // never lower the next target (the old -0.02 step walked f030dffc553d down to 0.60).
        val e = engine()
        val updated = e.recordVerifiedSuccess(key(), usedTargetRatio = 0.95, outputToSourceBytesRatio = 0.96, floorRatio = 0.80)

        assertEquals(1, updated.successCount)
        assertEquals(0.95, updated.nextTargetRatio!!, 1e-9)
        assertNull(updated.lastFailureReason)
        assertEquals(0.95, e.recommendedTargetRatio(key(), defaultRatio = 0.95, floorRatio = 0.80), 1e-9)
    }

    @Test
    fun repeatedSuccessesNeverLowerTheTargetBelowTheDefault() {
        val e = engine()
        var ratio = 0.95
        repeat(30) {
            ratio = e.recordVerifiedSuccess(key(), ratio, 0.9, floorRatio = 0.80).nextTargetRatio!!
        }
        assertEquals(0.95, ratio, 1e-9)
        assertEquals(0.95, e.recommendedTargetRatio(key(), defaultRatio = 0.95, floorRatio = 0.80), 1e-9)

        // Even a hostile stored history below the default is clamped back up to the default.
        val store = SmartPerceptualProfileEngine.InMemoryProfileStore()
        store.write(key().asKey(), "success=9;failure=0;highFail=0;nextRatio=0.60;preferRemux=false;lastReason=;lastSizeRatio=")
        val walkedDown = SmartPerceptualProfileEngine(store)
        assertEquals(0.95, walkedDown.recommendedTargetRatio(key(), defaultRatio = 0.95, floorRatio = 0.80), 1e-9)
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
        // …and a corrupt/hostile stored value can never drop below max(floor, default).
        val store = SmartPerceptualProfileEngine.InMemoryProfileStore()
        store.write(key().asKey(), "success=1;failure=0;highFail=0;nextRatio=0.05;preferRemux=false;lastReason=;lastSizeRatio=")
        val tampered = SmartPerceptualProfileEngine(store)
        assertEquals(0.95, tampered.recommendedTargetRatio(key(), 0.95, 0.90), 1e-9)
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
            lastOutputToSourceRatio = 0.88,
            measuredOvershootFactor = 1.25
        )
        val decoded = SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(original.encode())
        assertEquals(original, decoded)
        assertNull(SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(null))
        assertNull(SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(""))
    }

    @Test
    fun overshootFactorIsLearnedBlendedAndClamped() {
        val e = engine()
        // No measurement yet: this is the near-ceiling S23 4K60 HDR HEVC->HEVC class, which is seeded
        // with the documented ~1.25 QTI VBR overshoot so the FIRST encounter prefers an honest remux.
        assertEquals(
            SmartPerceptualProfileEngine.SEEDED_NEAR_CEILING_OVERSHOOT,
            e.expectedOvershootFactor(key()),
            1e-9
        )

        // The real S23 Ultra measurement from the device run: 142.3 / 113.9 ≈ 1.249.
        e.recordFailure(key(), 0.95, "size grew", floorRatio = 0.80, measuredOvershootFactor = 1.249)
        assertEquals(1.249, e.expectedOvershootFactor(key()), 1e-9)

        // A second measurement is blended, not overwritten, so one outlier can't swing prediction.
        e.recordFailure(key(), 0.97, "size grew", floorRatio = 0.80, measuredOvershootFactor = 1.0)
        assertEquals((1.249 + 1.0) / 2.0, e.expectedOvershootFactor(key()), 1e-9)

        // Absurd measurements are clamped into [1.0, 2.0] before blending.
        e.recordVerifiedSuccess(key(), 0.95, 0.9, floorRatio = 0.80, measuredOvershootFactor = 50.0)
        assertTrue(e.expectedOvershootFactor(key()) <= SmartPerceptualProfileEngine.MAX_OVERSHOOT_FACTOR)

        // A corrupt/hostile stored value can never poison prediction below 1.0.
        val store = SmartPerceptualProfileEngine.InMemoryProfileStore()
        store.write(key().asKey(), "success=1;failure=0;highFail=0;nextRatio=0.95;preferRemux=false;lastReason=;lastSizeRatio=;overshoot=0.01")
        val tampered = SmartPerceptualProfileEngine(store)
        assertEquals(1.0, tampered.expectedOvershootFactor(key()), 1e-9)
    }

    @Test
    fun attemptsWithoutOvershootMeasurementDoNotEraseLearnedFactor() {
        val e = engine()
        e.recordFailure(key(), 0.95, "size grew", floorRatio = 0.80, measuredOvershootFactor = 1.25)
        // A later attempt where measurement was unavailable must keep the learned factor.
        e.recordFailure(key(), 0.97, "unverified", floorRatio = 0.80, measuredOvershootFactor = null)
        assertEquals(1.25, e.expectedOvershootFactor(key()), 1e-9)
    }

    @Test
    fun overshootSeedAppliesOnlyToNearCeilingHdrHevcClass() {
        val e = engine()

        fun keyFor(source: VideoSourceInfo, encoderMime: String) =
            SmartPerceptualProfileEngine.profileKeyFor(source, encoderMime, "samsung", "SM-S918B", 34)

        // Seeded: 4K60 HDR HEVC -> HEVC (the documented near-ceiling class).
        assertEquals(
            SmartPerceptualProfileEngine.SEEDED_NEAR_CEILING_OVERSHOOT,
            e.expectedOvershootFactor(keyFor(hdr4k60Source(), MimeTypes.VIDEO_H265)),
            1e-9
        )

        // NOT seeded: ordinary 1080p SDR H.264 -> HEVC (the case that SHOULD compress).
        val sdr1080pH264 = VideoSourceInfo(
            width = 1920, height = 1080, frameRate = 30f, durationMs = 60_000,
            totalBitrate = 5_000_000, audioBitrate = 128_000,
            videoMime = MimeTypes.VIDEO_H264, audioMime = MimeTypes.AUDIO_AAC,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        assertEquals(1.0, e.expectedOvershootFactor(keyFor(sdr1080pH264, MimeTypes.VIDEO_H265)), 1e-9)

        // NOT seeded: 4K60 SDR HEVC (no HDR) — high-res/fps but not the HDR near-ceiling class.
        val sdr4k60Hevc = VideoSourceInfo(
            width = 3840, height = 2160, frameRate = 60f, durationMs = 60_000,
            totalBitrate = 60_000_000, audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H265, colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        assertEquals(1.0, e.expectedOvershootFactor(keyFor(sdr4k60Hevc, MimeTypes.VIDEO_H265)), 1e-9)

        // NOT seeded: 1080p HDR HEVC — HDR but not high resolution.
        val hdr1080pHevc = VideoSourceInfo(
            width = 1920, height = 1080, frameRate = 30f, durationMs = 60_000,
            totalBitrate = 30_000_000, audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H265,
            colorTransfer = MediaFormat.COLOR_TRANSFER_HLG, colorStandard = MediaFormat.COLOR_STANDARD_BT2020
        )
        assertEquals(1.0, e.expectedOvershootFactor(keyFor(hdr1080pHevc, MimeTypes.VIDEO_H265)), 1e-9)
    }

    @Test
    fun probeSkipLatchIsDecayingAndEvidenceOnly() {
        val e = engine()

        // Fresh profile: never skip (no measured evidence).
        assertFalse(e.shouldSkipProbes(key()))

        // One measured rejection is not enough.
        e.recordMeasuredProbeRejection(key())
        assertFalse(e.shouldSkipProbes(key()))

        // Two consecutive measured rejections arm the latch.
        e.recordMeasuredProbeRejection(key())
        assertTrue(e.shouldSkipProbes(key()))

        // The skip budget is bounded: after PROBE_SKIPS_BETWEEN_REPROBES skips the next
        // encounter MUST re-probe — the latch is never permanent.
        repeat(SmartPerceptualProfileEngine.PROBE_SKIPS_BETWEEN_REPROBES) {
            assertTrue(e.shouldSkipProbes(key()))
            e.noteProbeSkipped(key())
        }
        assertFalse(e.shouldSkipProbes(key()))

        // A renewed measured rejection restarts the skip cycle...
        e.recordMeasuredProbeRejection(key())
        assertTrue(e.shouldSkipProbes(key()))

        // ...and a probe pass clears the latch entirely.
        e.recordProbePass(key())
        assertFalse(e.shouldSkipProbes(key()))

        // A verified full encode also clears it.
        e.recordMeasuredProbeRejection(key())
        e.recordMeasuredProbeRejection(key())
        assertTrue(e.shouldSkipProbes(key()))
        e.recordVerifiedSuccess(key(), 0.95, 0.9, floorRatio = 0.80)
        assertFalse(e.shouldSkipProbes(key()))
    }

    @Test
    fun knownWinnableClassIsNeverLatchSuppressed() {
        // Evidence: batch_20260715_141019 measured 26 files latch-suppressed in three profile
        // classes that ALSO verified compressions. A class proven to contain compressible
        // content must keep probing every file, whatever the latch counters say.
        val e = engine()

        // Arm the latch with two measured rejections.
        e.recordMeasuredProbeRejection(key())
        e.recordMeasuredProbeRejection(key())
        assertTrue(e.shouldSkipProbes(key()))

        // A verified compression in this class flips everCompressed and exempts it forever.
        e.recordVerifiedSuccess(key(), usedTargetRatio = 0.65, outputToSourceBytesRatio = 0.6, floorRatio = 0.80)
        assertTrue(e.profile(key()).everCompressed)
        assertFalse(e.shouldSkipProbes(key()))

        // Even a fresh burst of measured rejections cannot re-arm the skip for a winnable class.
        e.recordMeasuredProbeRejection(key())
        e.recordMeasuredProbeRejection(key())
        e.recordMeasuredProbeRejection(key())
        assertFalse(e.shouldSkipProbes(key()))

        // The exemption round-trips through the store and defaults false on legacy profiles.
        val decoded = SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(
            SmartPerceptualProfileEngine.LearnedEncodeProfile(everCompressed = true).encode()
        )
        assertTrue(decoded!!.everCompressed)
        val legacy = SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(
            "success=1;failure=0;highFail=0;nextRatio=0.95;preferRemux=false;lastReason=;lastSizeRatio="
        )
        assertFalse(legacy!!.everCompressed)
    }

    @Test
    fun probeSkipLatchSurvivesRoundTripAndResistsTampering() {
        // Encode/decode round-trips the latch fields.
        val original = SmartPerceptualProfileEngine.LearnedEncodeProfile(
            consecutiveMeasuredProbeRejections = 2,
            probeSkipsSinceLastProbe = 1
        )
        assertEquals(original, SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(original.encode()))

        // Pre-latch stored profiles (missing fields) default to no-skip.
        val legacy = SmartPerceptualProfileEngine.LearnedEncodeProfile.decode(
            "success=1;failure=0;highFail=0;nextRatio=0.95;preferRemux=false;lastReason=;lastSizeRatio="
        )
        assertEquals(0, legacy!!.consecutiveMeasuredProbeRejections)
        assertEquals(0, legacy.probeSkipsSinceLastProbe)

        // Corrupt/hostile negative values clamp to 0 — a tampered store cannot manufacture skips.
        val store = SmartPerceptualProfileEngine.InMemoryProfileStore()
        store.write(
            key().asKey(),
            "success=0;failure=0;highFail=0;nextRatio=;preferRemux=false;lastReason=;lastSizeRatio=;probeRejects=-5;probeSkips=-9"
        )
        val tampered = SmartPerceptualProfileEngine(store)
        assertFalse(tampered.shouldSkipProbes(key()))
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
