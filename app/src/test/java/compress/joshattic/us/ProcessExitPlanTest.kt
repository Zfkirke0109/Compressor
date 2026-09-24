package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitPlanTest {

    @Test
    fun reasonCodesAreNamed() {
        assertEquals("CRASH_NATIVE", ProcessExitPlan.reasonName(5))
        assertEquals("LOW_MEMORY", ProcessExitPlan.reasonName(3))
        assertEquals("USER_REQUESTED", ProcessExitPlan.reasonName(10))
        assertEquals("ANR", ProcessExitPlan.reasonName(6))
        assertEquals("REASON_99", ProcessExitPlan.reasonName(99))
    }

    @Test
    fun readableRunsSurviveAProtobufTombstone() {
        // A tombstone proto interleaves binary field tags with plain strings; the strings are the
        // part that names the crashing library and signal.
        val bytes = byteArrayOf(0x0A, 0x07) + "SIGSEGV".toByteArray() + byteArrayOf(0x12, 0x00, 0x1F) +
            "libcompressorvmafv1.so".toByteArray() + byteArrayOf(0x02, 0x01) + "ab".toByteArray()
        val text = ProcessExitPlan.printableRuns(bytes)
        assertTrue(text.contains("SIGSEGV"))
        assertTrue(text.contains("libcompressorvmafv1.so"))
        assertFalse("runs shorter than the minimum are noise", text.contains("ab\n"))
    }

    @Test
    fun readableRunsAreBounded() {
        val text = ProcessExitPlan.printableRuns(ByteArray(10_000) { 'x'.code.toByte() }, maxChars = 100)
        assertTrue(text.length <= 100)
    }

    @Test
    fun onlyExitsNewerThanTheLastRecordedOneAreWritten() {
        val records = listOf(30L, 10L, 20L, 40L)
        assertEquals(listOf(30L, 40L), ProcessExitPlan.unseen(records, { it }, lastSeenTimestamp = 20L))
        assertTrue(ProcessExitPlan.unseen(records, { it }, lastSeenTimestamp = 40L).isEmpty())
    }
}
