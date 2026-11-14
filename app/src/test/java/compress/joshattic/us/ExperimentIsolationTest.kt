package compress.joshattic.us

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** b177 WP3: an experimental encoder configuration never reads or writes the baseline's learning. */
class ExperimentIsolationTest {

    private val source = VideoSourceInfo(
        width = 1920, height = 1080, frameRate = 30f, durationMs = 60_000L,
        totalBitrate = 20_000_000, audioBitrate = 128_000,
        videoMime = MimeTypes.VIDEO_H264, audioMime = MimeTypes.AUDIO_AAC
    )

    private fun key(longGop: Boolean) = SmartPerceptualProfileEngine.profileKeyFor(
        source, MimeTypes.VIDEO_H265 + EncoderExperiments.learningKeySuffix(longGop), "samsung", "SM-S918U1", 37
    )

    @Test
    fun theBaselineKeyIsUnchangedAndTheExperimentKeyIsSeparate() {
        val baseline = SmartPerceptualProfileEngine.profileKeyFor(source, MimeTypes.VIDEO_H265, "samsung", "SM-S918U1", 37)
        assertEquals(baseline.asKey(), key(longGop = false).asKey())
        assertNotEquals(baseline.asKey(), key(longGop = true).asKey())
    }

    @Test
    fun learningUnderTheExperimentLeavesTheBaselineUntouched() {
        val store = SmartPerceptualProfileEngine.InMemoryProfileStore()
        val engine = SmartPerceptualProfileEngine(store)
        engine.recordFailure(key(longGop = true), 0.85, "measured", 0.6, 1.0)
        assertEquals(setOf(key(longGop = true).asKey()), store.snapshot().keys)
        assertEquals(null, engine.profile(key(longGop = false)).nextTargetRatio)
    }

    @Test
    fun aBFrameSettingOtherThanThePinnedBaselineLearnsSeparately() {
        // The user's existing state was learned with B-frames on (2): that stays the unsuffixed key.
        assertEquals("", EncoderExperiments.learningKeySuffix(longGop = false, bFrames = 2, baselineBFrames = 2))
        assertEquals(";bf0", EncoderExperiments.learningKeySuffix(longGop = false, bFrames = 0, baselineBFrames = 2))
        assertEquals(";bf0;gopx2", EncoderExperiments.learningKeySuffix(longGop = true, bFrames = 0, baselineBFrames = 2))
        // A device that never enabled B-frames keeps its keys too.
        assertEquals("", EncoderExperiments.learningKeySuffix(longGop = false, bFrames = 0, baselineBFrames = 0))
    }

    @Test
    fun theLongerIntervalIsDoubledAndCapped() {
        assertEquals(3f, EncoderExperiments.keyframeIntervalSeconds(3f, longGop = false), 0f)
        assertEquals(6f, EncoderExperiments.keyframeIntervalSeconds(3f, longGop = true), 0f)
        assertEquals(EncoderExperiments.LONG_GOP_MAX_SECONDS, EncoderExperiments.keyframeIntervalSeconds(5f, longGop = true), 0f)
    }
}
