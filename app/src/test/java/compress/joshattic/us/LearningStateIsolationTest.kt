package compress.joshattic.us

import androidx.media3.common.MimeTypes
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Learned state is a hidden confounder in any A/B run: it persists across executions and silently
 * changes what later runs do. The two 219-job captures differed in probe count and runtime partly
 * because the second inherited measured-rejection latches from the first, which is exactly why no
 * cause could be attributed to the code change between them.
 *
 * An experiment must be able to record the state it started from and reset between arms.
 */
class LearningStateIsolationTest {

    private fun engine(store: SmartPerceptualProfileEngine.ProfileStore = SmartPerceptualProfileEngine.InMemoryProfileStore()) =
        SmartPerceptualProfileEngine(store)

    private fun source() = VideoSourceInfo(
        width = 1920, height = 1080, frameRate = 30f, durationMs = 10_000,
        totalBitrate = 25_000_000, audioBitrate = 128_000,
        videoMime = MimeTypes.VIDEO_H264, audioMime = MimeTypes.AUDIO_AAC,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
        colorRange = MediaFormat.COLOR_RANGE_LIMITED
    )

    private fun key() = SmartPerceptualProfileEngine.profileKeyFor(
        source = source(), encoderMime = MimeTypes.VIDEO_H265,
        manufacturer = "samsung", model = "SM-S918U1", sdkInt = 36
    )

    @Test
    fun aFreshEngineReportsEmptyIdentity() {
        assertEquals("empty", engine().learnedStateIdentity())
        assertTrue(engine().snapshotLearnedState().isEmpty())
    }

    @Test
    fun learningChangesTheStateIdentity() {
        val e = engine()
        val before = e.learnedStateIdentity()
        e.recordFailure(key(), 0.90, "verification failed", floorRatio = 0.85)
        val after = e.learnedStateIdentity()
        assertNotEquals("recorded learning must change the identity", before, after)
        assertEquals(1, e.snapshotLearnedState().size)
    }

    @Test
    fun resetReturnsTheEngineToAKnownEmptyBaseline() {
        val e = engine()
        e.recordFailure(key(), 0.90, "verification failed", floorRatio = 0.85)
        assertTrue(e.snapshotLearnedState().isNotEmpty())

        e.resetLearnedState()

        assertEquals("empty", e.learnedStateIdentity())
        assertTrue(e.snapshotLearnedState().isEmpty())
        // And the recommendation returns to the unlearned default.
        assertEquals(0.90, e.recommendedTargetRatio(key(), defaultRatio = 0.90, floorRatio = 0.85), 1e-9)
    }

    @Test
    fun twoArmsResetToTheSameIdentityAreComparable() {
        // The property an A/B needs: arms that start equal report equal identities, so a report
        // can prove the comparison was fair rather than asserting it.
        val a = engine()
        val b = engine()
        a.recordFailure(key(), 0.90, "x", floorRatio = 0.85)
        b.recordVerifiedSuccess(key(), 0.95, 0.9, floorRatio = 0.85, pixelCertified = true)
        assertNotEquals(a.learnedStateIdentity(), b.learnedStateIdentity())

        a.resetLearnedState()
        b.resetLearnedState()
        assertEquals(a.learnedStateIdentity(), b.learnedStateIdentity())
    }

    @Test
    fun identicalLearnedStateYieldsTheSameIdentityRegardlessOfWriteOrder() {
        // Order-independence matters: two arms that learned the same things in a different
        // sequence are equivalent starting points and must not look different in a report.
        val k1 = key()
        val k2 = SmartPerceptualProfileEngine.profileKeyFor(
            source = source().copy(width = 1280, height = 720),
            encoderMime = MimeTypes.VIDEO_H265,
            manufacturer = "samsung", model = "SM-S918U1", sdkInt = 36
        )
        val a = engine()
        a.recordFailure(k1, 0.90, "x", floorRatio = 0.85)
        a.recordFailure(k2, 0.90, "x", floorRatio = 0.85)

        val b = engine()
        b.recordFailure(k2, 0.90, "x", floorRatio = 0.85)
        b.recordFailure(k1, 0.90, "x", floorRatio = 0.85)

        assertEquals(a.learnedStateIdentity(), b.learnedStateIdentity())
    }

    @Test
    fun aSnapshotContainsOnlyTechnicalBucketKeys() {
        // Snapshots get attached to experiment reports, so they must carry nothing private.
        val e = engine()
        e.recordFailure(key(), 0.90, "verification failed", floorRatio = 0.85)
        e.snapshotLearnedState().keys.forEach { k ->
            assertTrue("key must be pipe-delimited technical buckets: $k", k.contains("|"))
            // A bare "/" is legitimate here — MIME types contain one (video/hevc). What must never
            // appear is anything identifying a FILE: a storage path, a content URI, or an extension.
            listOf("/storage/", "/data/", "content://", "file://", "/sdcard", "DCIM", ".mp4", ".mov")
                .forEach { forbidden ->
                    assertTrue(
                        "key must not carry a file identifier ($forbidden): $k",
                        !k.contains(forbidden, ignoreCase = true)
                    )
                }
        }
    }
}
