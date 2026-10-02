package compress.joshattic.us.quality

import compress.joshattic.us.DiagnosticsArchivePlan
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class FrameEvidenceTraceTest {
    private fun trace(): FrameEvidenceTrace {
        val dir = Files.createTempDirectory("frame-evidence-test").toFile()
        return FrameEvidenceTrace(FrameTraceRequest(dir, "job", "1:1", "control"), ScoreWindow(1, 100), 2, 2)
    }
    private fun feed(t: FrameEvidenceTrace, context: Boolean = false, pts: Long = 1) {
        val f = I420Frame(byteArrayOf(0, 1, 2, 3, 128.toByte(), 128.toByte()), 2, 2, pts)
        t.pair(f, f, pts, pts, context)
    }
    private fun text(t: FrameEvidenceTrace, scores: DoubleArray?): String {
        val file = t.finish(scores, null, null, "SCORED")!!
        return try { GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() } }
        finally { file.parentFile.deleteRecursively() }
    }
    @Test fun contextScoreIsExportedButExcludedFromTheGate() {
        val t = trace(); feed(t, context = true, pts = 0)
        repeat(12) { feed(t, pts = it + 1L) }
        val output = text(t, doubleArrayOf(0.0) + DoubleArray(12) { 99.0 })
        assertTrue(output.contains("\"windowGatePassed\": true"))
        assertTrue(output.contains("\"scoredFrames\": 12"))
        assertTrue(output.contains("\"vmafV0\": 0.0"))
        assertTrue(output.contains("\"context\": true"))
    }
    @Test fun incompleteNativeOutputCannotReplayAsPassed() {
        val t = trace(); repeat(12) { feed(t) }
        val output = text(t, DoubleArray(11) { 100.0 })
        assertTrue(output.contains("\"complete\": false"))
        assertTrue(output.contains("\"windowGatePassed\": false"))
    }
    @Test fun chromaChangesAreVisibleInTheHashEvenWithIdenticalLuma() {
        val a = byteArrayOf(1, 2, 3, 4, 5, 6)
        val b = a.copyOf().also { it[5] = 7 }
        val ah = FrameEvidenceTrace.planeHashes(a, 2, 2)
        val bh = FrameEvidenceTrace.planeHashes(b, 2, 2)
        assertEquals(ah["y"], bh["y"])
        assertNotEquals(ah["v"], bh["v"])
        assertNotEquals(ah["i420"], bh["i420"])
    }
    @Test fun manifestHashAndLengthDescribeExactlyTheCopiedBytes() {
        val out = ByteArrayOutputStream()
        val entry = DiagnosticsArchivePlan.copyPayload("a", "abc".byteInputStream(), out)
        assertEquals(3L, entry.bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", entry.sha256)
        assertEquals("abc", out.toString("UTF-8"))
    }
}
