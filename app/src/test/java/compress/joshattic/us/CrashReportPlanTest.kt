package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportPlanTest {

    @Test
    fun namesSortInTimeOrderAsPlainStrings() {
        val earlier = CrashReportPlan.fileName(999_999_999_999L)
        val later = CrashReportPlan.fileName(1_790_259_775_700L)
        assertTrue("zero-padding must keep lexical order = time order", earlier < later)
        assertEquals("crash-1790259775700.log", later)
    }

    @Test
    fun onlyCrashReportsAreRecognised() {
        assertTrue(CrashReportPlan.isReportName("crash-1790259775700.log"))
        assertFalse(CrashReportPlan.isReportName("session.jsonl"))
        assertFalse(CrashReportPlan.isReportName("decisions.log"))
        assertFalse(CrashReportPlan.isReportName("crash-.log"))
        assertFalse(CrashReportPlan.isReportName("crash-12ab.log"))
    }

    @Test
    fun pruningKeepsTheNewestAndNeverTouchesOtherFiles() {
        val names = (1L..25L).map { CrashReportPlan.fileName(it) } + "notes.txt"
        val pruned = CrashReportPlan.reportsToPrune(names, keep = 20)
        assertEquals((1L..5L).map { CrashReportPlan.fileName(it) }, pruned)
        assertFalse("notes.txt" in pruned)
    }

    @Test
    fun nothingIsPrunedUnderTheCap() {
        val names = (1L..3L).map { CrashReportPlan.fileName(it) }
        assertTrue(CrashReportPlan.reportsToPrune(names, keep = 20).isEmpty())
    }

    @Test
    fun theReportLeadsWithTheBuildThatProducedIt() {
        val text = CrashReportPlan.render(
            linkedMapOf("appVersionName" to "1.6.159", "buildTag" to "pr44-b159", "sdkInt" to "37"),
            threadName = "main",
            stackTrace = "java.lang.IllegalStateException: boom\n\tat x.y(z:1)"
        )
        val lines = text.lines()
        assertEquals("Compressor crash report", lines[0])
        assertEquals("appVersionName: 1.6.159", lines[1])
        assertTrue("thread: main" in lines)
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun crashReportsLiveWhereSessionExportsCannotMistakeThemForASession() {
        // The session and decision exports scan diagnostics/<dir>/session.jsonl and decisions.log.
        // A crash directory holding neither is skipped by both.
        assertEquals("crashes", CrashReportPlan.DIRECTORY)
    }

    @Test
    fun theLogExportCollectsThePlatformCrashLine() {
        assertTrue("AndroidRuntime" in DiagnosticsExportPlan.DIAGNOSTIC_TAGS)
        assertTrue("BatchFgs" in DiagnosticsExportPlan.DIAGNOSTIC_TAGS)
    }
}
