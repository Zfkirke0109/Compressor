package compress.joshattic.us

import org.junit.Assert.assertTrue
import org.junit.Test

class ExportInputProbeReportTest {

    private fun report(
        opens: Int = 1, closes: Int = 0, bytes: Long = 164_000_000L, entered: Long = 2500, exited: Long = 2500,
        lastRead: Int = 65536, lastByteAt: Long = 1_000L, lastOpenAt: Long = 500L, lastCloseAt: Long = 0L
    ) = ExportInputProbeReport(opens, closes, bytes, entered, exited, 0L, -1L, lastOpenAt, lastByteAt, lastCloseAt, lastRead)

    @Test
    fun aBlockedReadIsNamedAsAnIoStall() {
        val d = report(entered = 2501, exited = 2500).diagnosis(nowMs = 121_000L)
        assertTrue(d, d.contains("blocked") && d.contains("I/O stall"))
    }

    @Test
    fun anIdleOpenSourceMeansTheLoaderStoppedAsking() {
        val d = report().diagnosis(nowMs = 121_000L)
        assertTrue(d, d.contains("loader stopped asking"))
    }

    @Test
    fun endOfInputIsReportedWithTheByteCount() {
        val d = report(lastRead = -1).diagnosis(nowMs = 121_000L)
        assertTrue(d, d.contains("end of input") && d.contains("164000000"))
    }

    @Test
    fun aClosedSourceIsNamedAsClosed() {
        val d = report(closes = 1, lastCloseAt = 2_000L).diagnosis(nowMs = 121_000L)
        assertTrue(d, d.contains("closed 119000 ms ago"))
    }

    @Test
    fun theCompactLineCarriesEveryCounter() {
        val c = report().compact(nowMs = 2_000L)
        assertTrue(c, c.startsWith("input[opens=1,closes=0,open=true,bytes=164.0MB,"))
        assertTrue(c, c.contains("readInFlight=false") && c.contains("lastByte=1000ms ago") && c.contains("lastClose=never"))
    }

    @Test
    fun anExtractorErrorIsNamedBeforeItsSymptoms() {
        // b167: input closed and never reopened after a 5-byte read. The closed input is the
        // symptom; the diagnosis must name the parse error that caused it.
        val r = ExportInputProbeReport(
            1, 1, 152_400_000L, 25_927, 25_927, 0L, -1L, 500L, 1_000L, 1_001L, 5,
            parseFailure = "ParserException: Invalid NAL length at byte 152,398,211 (Mp4Extractor)"
        )
        val d = r.diagnosis(nowMs = 121_000L)
        assertTrue(d, d.startsWith("the extractor threw ParserException: Invalid NAL length"))
        assertTrue(d, !d.contains("closed 119"))
    }

    @Test
    fun retriedReadErrorsAppearOnlyWhenThereWereAny() {
        assertTrue(!report().compact(nowMs = 2_000L).contains("retriedReadErrors"))
        val r = ExportInputProbeReport(1, 0, 1L, 1, 1, 0L, -1L, 0L, 0L, 0L, 1, retriedReadErrors = 3)
        assertTrue(r.compact(nowMs = 2_000L).endsWith(",retriedReadErrors=3]"))
    }
}
