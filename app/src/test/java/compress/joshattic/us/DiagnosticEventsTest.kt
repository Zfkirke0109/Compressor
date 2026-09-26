package compress.joshattic.us

import compress.joshattic.us.quality.VmafPairScorer
import compress.joshattic.us.quality.WindowScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiagnosticEventsTest {

    @Test
    fun theSnapshotHashIsOrderIndependentAndChangesWithTheState() {
        val a = LearnedStateSnapshot.of(linkedMapOf("k2" to "v2", "k1" to "v1"))
        val b = LearnedStateSnapshot.of(linkedMapOf("k1" to "v1", "k2" to "v2"))
        assertEquals(a.sha256, b.sha256)
        assertEquals(listOf("k1", "k2"), a.entries.keys.toList())
        assertNotEquals(a.sha256, LearnedStateSnapshot.of(mapOf("k1" to "v1", "k2" to "v3")).sha256)
        assertEquals(64, a.sha256.length)
        // The empty state has a hash too: "no learned state" is itself a starting state.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            LearnedStateSnapshot.of(emptyMap()).sha256
        )
    }

    @Test
    fun everyLearnedWriteIsReportedInOrderWithWhatItReplaced() {
        val writes = mutableListOf<Triple<String, String?, String>>()
        val store = RecordingProfileStore(SmartPerceptualProfileEngine.InMemoryProfileStore()) { k, before, after ->
            writes += Triple(k, before, after)
        }
        store.write("a", "1")
        store.write("a", "2")
        store.write("a", "2") // unchanged: nothing to replay
        store.write("b", "9")
        assertEquals(
            listOf(Triple("a", null, "1"), Triple("a", "1", "2"), Triple("b", null, "9")),
            writes
        )
        // Snapshot + updates replay to the final state.
        val replayed = mutableMapOf<String, String>()
        writes.forEach { (k, _, after) -> replayed[k] = after }
        assertEquals(store.snapshot(), replayed)
    }

    @Test
    fun aFailingListenerNeverLosesTheWrite() {
        val store = RecordingProfileStore(SmartPerceptualProfileEngine.InMemoryProfileStore()) { _, _, _ -> error("recorder closed") }
        store.write("a", "1")
        assertEquals("1", store.read("a"))
    }

    @Test
    fun windowEvidenceIsFullPrecision() {
        val fields = StageEvent.windowFields(
            "cert", listOf(WindowScore(comparedFrames = 14, mean = 95.42312345678, p5 = 92.856, min = 92.8561))
        )
        assertEquals("95.42312345678", fields["certMean"])
        assertEquals("14", fields["certFrames"])
        assertEquals(95.42312345678, (fields["certMean"] as String).toDouble(), 0.0)
        assertEquals(mapOf("certWindows" to 0), StageEvent.windowFields("cert", null))
    }

    @Test
    fun reasonCodesAreStable() {
        // Captures are compared across builds by these strings; renaming one breaks every comparison.
        assertEquals("cert_insufficient_frames", StageEvent.Reason.CERT_INSUFFICIENT)
        assertEquals("cert_measured_failure", StageEvent.Reason.CERT_MEASURED_FAILURE)
        assertEquals("retry_denied", StageEvent.Reason.RETRY_DENIED)
    }

    @Test
    fun productionScoringIsPlainV061WithoutThePhoneTransform() {
        // VmafNative.open defaults to phoneModel=true; the production scorer must pass false, as the
        // offline harness the thresholds were calibrated against does. b169 review: verified, not a bug.
        assertFalse(VmafPairScorer.PRODUCTION_PHONE_MODEL)
    }
}
