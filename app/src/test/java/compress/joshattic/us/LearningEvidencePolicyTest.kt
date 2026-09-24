package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningEvidencePolicyTest {

    private val key = SmartPerceptualProfileEngine.EncodeProfileKey(
        "samsung", "sm-s918u1", 37, "video/hevc", "video/avc", "720p", "24", "unknown", "lt10m", "mp4a-latm-lt160k"
    )

    @Test
    fun onlyAStarvedEncodeCountsAsQualityEvidence() {
        assertEquals(LearningEvidencePolicy.Kind.QUALITY, LearningEvidencePolicy.classifyVerificationFailure(listOf("videoBitratePass")))
        assertEquals(
            LearningEvidencePolicy.Kind.QUALITY,
            LearningEvidencePolicy.classifyVerificationFailure(listOf("audioBitratePass", "videoBitratePass"))
        )
    }

    @Test
    fun audioColourAndIntegrityFailuresTeachNothing() {
        for (check in listOf("audioBitratePass", "standardMatches", "frameCountMatches", "playable", "criticalFieldsComplete")) {
            assertEquals(check, LearningEvidencePolicy.Kind.PIPELINE, LearningEvidencePolicy.classifyVerificationFailure(listOf(check)))
        }
    }

    @Test
    fun everythingPassingButTheSizeIsSizeEvidence() {
        assertEquals(LearningEvidencePolicy.Kind.SIZE, LearningEvidencePolicy.classifyVerificationFailure(emptyList()))
    }

    @Test
    fun aSizeFailureKeepsTheRatioButStillCountsTowardTheLatch() {
        val engine = SmartPerceptualProfileEngine(SmartPerceptualProfileEngine.InMemoryProfileStore())
        val first = engine.recordFailure(key, 0.95, "not smaller", floorRatio = 0.85, stepUp = false)
        assertEquals(0.95, first.nextTargetRatio!!, 1e-9)
        assertFalse(first.preferRemux)
        val second = engine.recordFailure(key, 0.95, "not smaller", floorRatio = 0.85, stepUp = false)
        assertEquals(0.95, second.nextTargetRatio!!, 1e-9)
        assertTrue(second.preferRemux)
    }

    @Test
    fun aQualityFailureStillStepsUp() {
        val engine = SmartPerceptualProfileEngine(SmartPerceptualProfileEngine.InMemoryProfileStore())
        val learned = engine.recordFailure(key, 0.90, "bitrate floor", floorRatio = 0.85)
        assertEquals(0.95, learned.nextTargetRatio!!, 1e-9)
    }
}
