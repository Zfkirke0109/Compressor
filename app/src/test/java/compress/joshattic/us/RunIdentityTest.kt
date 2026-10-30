package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import compress.joshattic.us.quality.QualityProbePolicy

/** b177 F4: content and tool identities for matching runs. */
class RunIdentityTest {

    @Test
    fun fullSourceHashIsTheStandardSha256OfEveryByte() {
        // FIPS 180-2 test vector.
        val (abc, n) = FullSourceHash.digest("abc".byteInputStream())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abc)
        assertEquals(3L, n)
        // Content longer than one read buffer, fed in uneven pieces, hashes as the whole.
        val bytes = ByteArray(FullSourceHash.BUFFER * 2 + 12_345) { (it * 31 + 7).toByte() }
        val trickle = object : java.io.InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= bytes.size) return -1
                val k = minOf(len, 777_777, bytes.size - pos)
                System.arraycopy(bytes, pos, b, off, k)
                pos += k
                return k
            }
        }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
        assertEquals(expected to bytes.size.toLong(), FullSourceHash.digest(trickle))
        // One changed byte anywhere changes the hash: the sampled fingerprint could miss this one.
        val edited = bytes.copyOf().also { it[FullSourceHash.BUFFER + 5] = (it[FullSourceHash.BUFFER + 5] + 1).toByte() }
        assertNotEquals(expected, FullSourceHash.digest(edited.inputStream()).first)
    }

    @Test
    fun aCancelledBatchStopsHashingAtTheNextBuffer() {
        var reads = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return len }
        }
        val thrown = runCatching {
            FullSourceHash.digest(endless) { if (reads >= 3) throw kotlinx.coroutines.CancellationException("cancelled") }
        }.exceptionOrNull()
        assertEquals(true, thrown is kotlinx.coroutines.CancellationException)
        assertEquals(3, reads)
    }

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
        // Geometry limits, when the inventory measured them, follow the existing fields.
        assertEquals(
            e.compact() + "|sizeRate=widths=[64, 4096],1920x1080@60=true",
            e.copy(geometry = listOf("widths=[64, 4096]", "1920x1080@60=true")).compact()
        )
    }
}
