package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import compress.joshattic.us.quality.QualityProbePolicy

/** b177 F4: content and tool identities for matching runs. */
class RunIdentityTest {

    @Test
    fun capturedGateReadsTheProductionPolicyConstants() {
        val gate = ScoringIdentity.frozenGate()
        assertEquals("vmaf_v0.6.1", gate["verdictModel"])
        assertEquals(false, gate["phoneModel"])
        assertEquals(QualityProbePolicy.WINDOW_MEAN_MIN, gate["windowMeanMin"])
        assertEquals(QualityProbePolicy.WINDOW_P5_MIN, gate["windowP5Min"])
        assertEquals(QualityProbePolicy.WINDOW_MIN_MIN, gate["windowMinMin"])
        assertEquals(QualityProbePolicy.MIN_COMPARED_FRAMES_PER_WINDOW, gate["minComparedFramesPerWindow"])
        assertEquals(mapOf("mean" to QualityProbePolicy.PROBE_SELECTION_MARGIN_MEAN,
                           "p5" to QualityProbePolicy.PROBE_SELECTION_MARGIN_P5,
                           "min" to QualityProbePolicy.PROBE_SELECTION_MARGIN_MIN), gate["probeSelectionMargins"])
    }

    @Test
    fun fingerprintSamplesStartMiddleAndEndOfLargeFiles() {
        val c = SourceFingerprint.CHUNK.toLong()
        assertEquals(emptyList<Long>(), SourceFingerprint.offsets(0))
        assertEquals(listOf(0L), SourceFingerprint.offsets(3 * c))
        assertEquals(listOf(0L, 50 * c - c / 2, 100 * c - c), SourceFingerprint.offsets(100 * c))
    }

    @Test
    fun fingerprintDependsOnSizeAndBytes() {
        val a = SourceFingerprint.digest(10, listOf(byteArrayOf(1, 2, 3)))
        assertEquals(a, SourceFingerprint.digest(10, listOf(byteArrayOf(1, 2, 3))))
        assertNotEquals(a, SourceFingerprint.digest(11, listOf(byteArrayOf(1, 2, 3))))
        assertNotEquals(a, SourceFingerprint.digest(10, listOf(byteArrayOf(1, 2, 4))))
        assertEquals(64, a.length)
    }

    @Test
    fun manifestIsOrderIndependentAndNamesUnreadableSources() {
        val m = SourceFingerprint.manifest(linkedMapOf("job_b" to "2", "job_a" to "1"))
        assertEquals(m, SourceFingerprint.manifest(linkedMapOf("job_a" to "1", "job_b" to "2")))
        assertNotEquals(m, SourceFingerprint.manifest(linkedMapOf("job_a" to "1", "job_b" to null)))
    }

    @Test
    fun encoderEntriesAreComparableAcrossRuns() {
        val e = EncoderInventory.Entry(
            "c2.qti.hevc.encoder", "video/hevc", hardware = true, softwareOnly = false, vendor = true, alias = false,
            bitrateModes = listOf("VBR", "CBR", "CQ"), complexity = "0..0", quality = "0..100", profileLevels = 12, tenBit = true
        )
        assertEquals(
            "c2.qti.hevc.encoder|video/hevc|hw=true|sw=false|vendor=true|modes=VBR+CBR+CQ|complexity=0..0|quality=0..100|profiles=12|10bit=true",
            e.compact()
        )
    }
}
