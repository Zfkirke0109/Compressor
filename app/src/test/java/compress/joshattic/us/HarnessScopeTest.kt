package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A harness batch plans from the user's learned state and never writes to it. */
class HarnessScopeTest {

    private fun userStore() = SmartPerceptualProfileEngine.InMemoryProfileStore().apply { write("hevc|1080p30", "user-v4") }

    @Test
    fun anOverlayReadsTheUsersStateAndKeepsEveryWriteToItself() {
        val user = userStore()
        val overlay = OverlayProfileStore(user)
        assertEquals("user-v4", overlay.read("hevc|1080p30"))
        overlay.write("hevc|1080p30", "test-v5")
        assertTrue(overlay.writeDurably("avc|720p30", "test-a"))
        assertTrue(overlay.writeObservation("avc|4k30", "test-b", null))
        assertEquals("test-v5", overlay.read("hevc|1080p30"))
        assertEquals(mapOf("hevc|1080p30" to "test-v5", "avc|720p30" to "test-a", "avc|4k30" to "test-b"), overlay.writes)
        // The user's store saw none of it.
        assertEquals(mapOf("hevc|1080p30" to "user-v4"), user.snapshot())
        assertNull(user.read("avc|720p30"))
        // The overlay's snapshot is the user's state with its writes over it; clearing forgets only its writes.
        assertEquals("test-v5", overlay.snapshot()["hevc|1080p30"])
        overlay.clear()
        assertEquals("user-v4", overlay.read("hevc|1080p30"))
        assertEquals(mapOf("hevc|1080p30" to "user-v4"), user.snapshot())
    }

    @Test
    fun theLearningEngineThroughAnOverlayLeavesTheUsersProfilesAsTheyWere() {
        val user = userStore()
        val engine = SmartPerceptualProfileEngine(OverlayProfileStore(user))
        val key = SmartPerceptualProfileEngine.EncodeProfileKey(
            "samsung", "sm-s948u", 37, "video/hevc", "video/avc", "1080p", "30", "sdr", "lt20m", "mp4a-latm-lt160k"
        )
        val learned = engine.recordVerifiedSuccess(key, 0.85, 0.80, 0.55, null, pixelCertified = true, observation = null)
        // It learned, in the overlay: the next read through it sees the update.
        assertEquals(learned, engine.profile(key))
        assertEquals(mapOf("hevc|1080p30" to "user-v4"), user.snapshot())
    }

    @Test
    fun aRunLabelIsSafeAsPartOfAFileName() {
        HarnessScope("20261113-1530-ab12_cd", SmartPerceptualProfileEngine.InMemoryProfileStore())
        for (bad in listOf("", "../x", "a/b", "a b", "x".repeat(65))) {
            assertFalse(bad, runCatching { HarnessScope(bad, SmartPerceptualProfileEngine.InMemoryProfileStore()) }.isSuccess)
        }
    }
}
