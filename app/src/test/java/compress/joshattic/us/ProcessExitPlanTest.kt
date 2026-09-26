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

    @Test
    fun onlyCrashesAnrsAndStartFailuresAreFailures() {
        // CRASH, CRASH_NATIVE, ANR, INITIALIZATION_FAILURE.
        listOf(4, 5, 6, 7).forEach { assertTrue(ProcessExitPlan.reasonName(it), ProcessExitPlan.isFailure(it)) }
        // LOW_MEMORY, USER_REQUESTED, USER_STOPPED, PACKAGE_UPDATED, SIGNALED, EXIT_SELF, OTHER.
        listOf(3, 10, 11, 16, 2, 1, 13).forEach { assertFalse(ProcessExitPlan.reasonName(it), ProcessExitPlan.isFailure(it)) }
    }

    @Test
    fun routineExitsCanNoLongerPushARealCrashOut() {
        // One real crash, then 30 ordinary exits (background kills, swipes, updates).
        val crash = CrashReportPlan.fileName(1_000L)
        val exits = (1..30).map { CrashReportPlan.exitRecordName(2_000L + it) }
        val names = listOf(crash) + exits
        assertTrue(CrashReportPlan.reportsToPrune(names).isEmpty())
        // Exit records are capped on their own, oldest first.
        val prunedExits = CrashReportPlan.exitRecordsToPrune(names)
        assertEquals(10, prunedExits.size)
        assertEquals(exits.take(10), prunedExits)
        assertFalse(crash in prunedExits)
    }

    @Test
    fun exitRecordsTravelWithACaptureAndKeepTheirTime() {
        val name = CrashReportPlan.exitRecordName(1_790_423_201_187L)
        assertEquals("exit-1790423201187.log", name)
        assertTrue(CrashReportPlan.isExitRecordName(name))
        assertFalse(CrashReportPlan.isReportName(name))
        assertEquals(1_790_423_201_187L, DiagnosticsArchivePlan.crashEpochOf(name))
        assertEquals(1_790_423_201_187L, DiagnosticsArchivePlan.crashEpochOf("crash-1790423201187.log"))
        val all = DiagnosticsArchivePlan.crashReportsFor(
            DiagnosticsArchivePlan.Scope.EVERYTHING, emptyList(), emptyList(),
            listOf(name, "crash-0000000001000.log", ".last-exit-timestamp")
        )
        assertEquals(listOf("crash-0000000001000.log", name), all)
    }
}
