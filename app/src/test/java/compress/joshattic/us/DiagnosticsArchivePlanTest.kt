package compress.joshattic.us

import compress.joshattic.us.DiagnosticsArchivePlan.Run
import compress.joshattic.us.DiagnosticsArchivePlan.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsArchivePlanTest {

    private val runs = DiagnosticsArchivePlan.sortedNewestFirst(
        listOf("batch_1790263711162", "batch_1790273169359", "batch_1790270611232", "batch_1790262412832")
    )

    @Test
    fun runsSortNewestFirstByTheirEpoch() {
        assertEquals(
            listOf("batch_1790273169359", "batch_1790270611232", "batch_1790263711162", "batch_1790262412832"),
            runs.map { it.batchId }
        )
    }

    @Test
    fun scopesPickTheRightRuns() {
        assertEquals(listOf("batch_1790273169359"), DiagnosticsArchivePlan.runsFor(Scope.CURRENT_RUN, runs).map { it.batchId })
        assertEquals(listOf("batch_1790270611232"), DiagnosticsArchivePlan.runsFor(Scope.PREVIOUS_RUN, runs).map { it.batchId })
        assertEquals(4, DiagnosticsArchivePlan.runsFor(Scope.ALL_RUNS, runs).size)
        assertEquals(4, DiagnosticsArchivePlan.runsFor(Scope.EVERYTHING, runs).size)
        assertTrue(DiagnosticsArchivePlan.runsFor(Scope.PREVIOUS_RUN, runs.take(1)).isEmpty())
    }

    @Test
    fun aSingleRunScopeKeepsOnlyTheCrashReportsFromItsTime() {
        // The b163 native crash (13:15) came after the High Quality run started (13:06) and
        // there was no later run, so it belongs to that run. The 10:22 stop belongs to the
        // earlier batch.
        val crashes = listOf("crash-1790273718621.log", "crash-1790263350345.log", "crash-1788578696988.log", "notes.txt")
        val current = DiagnosticsArchivePlan.runsFor(Scope.CURRENT_RUN, runs)
        assertEquals(listOf("crash-1790273718621.log"), DiagnosticsArchivePlan.crashReportsFor(Scope.CURRENT_RUN, current, runs, crashes))
        val previous = DiagnosticsArchivePlan.runsFor(Scope.PREVIOUS_RUN, runs)
        assertTrue(DiagnosticsArchivePlan.crashReportsFor(Scope.PREVIOUS_RUN, previous, runs, crashes).isEmpty())
        assertEquals(3, DiagnosticsArchivePlan.crashReportsFor(Scope.ALL_RUNS, runs, runs, crashes).size)
    }

    @Test
    fun fileNamesCarryAppVersionTimestampAndScope() {
        assertEquals(
            "Compressor-v1.6.163-20260924-132315-AllRuns.zip",
            DiagnosticsArchivePlan.fileName("1.6.163", "20260924-132315", Scope.ALL_RUNS, runs)
        )
        assertEquals(
            "Compressor-v1.6.163-20260924-132315-batch_1790273169359.zip",
            DiagnosticsArchivePlan.fileName("1.6.163", "20260924-132315", Scope.CURRENT_RUN, runs.take(1))
        )
        assertEquals(
            "Compressor-v1.6.163-20260924-132315-Everything.zip",
            DiagnosticsArchivePlan.fileName("1.6.163", "20260924-132315", Scope.EVERYTHING, runs)
        )
    }

    @Test
    fun entryPathsKeepEachRunsRawFilesApart() {
        assertEquals("runs/batch_1790270611232/session.jsonl", DiagnosticsArchivePlan.runEntryPath("batch_1790270611232", "session.jsonl"))
        assertEquals("crashes/crash-1790273718621.log", DiagnosticsArchivePlan.crashEntryPath("crash-1790273718621.log"))
    }

    @Test
    fun theManifestListsBuildDeviceScopeRunsAndFiles() {
        val json = DiagnosticsArchivePlan.manifest(
            identity = linkedMapOf("appVersionName" to "1.6.163", "appVersionCode" to 483871L, "buildTag" to "pr44-b163", "deviceModel" to "SM-S918U1", "androidRelease" to "17"),
            exportedAt = "20260924-132315",
            scope = Scope.CURRENT_RUN,
            currentBatchId = "batch_1790273169359",
            previousBatchId = "batch_1790270611232",
            includedRuns = runs.take(1),
            entries = listOf(DiagnosticsArchivePlan.Entry("runs/batch_1790273169359/session.jsonl", 1234))
        )
        for (expected in listOf(
            "\"appVersionName\": \"1.6.163\"", "\"buildTag\": \"pr44-b163\"", "\"scope\": \"CURRENT_RUN\"",
            "\"currentBatchId\": \"batch_1790273169359\"", "\"previousBatchId\": \"batch_1790270611232\"",
            "\"startedAtMs\": 1790273169359", "\"path\": \"runs/batch_1790273169359/session.jsonl\"", "\"bytes\": 1234"
        )) {
            assertTrue("manifest should contain $expected:\n$json", json.contains(expected))
        }
    }

    @Test
    fun theJsonEmitterEscapesAndNests() {
        val json = JsonText.render(linkedMapOf("a" to "x\"y\\z\n", "n" to 5, "b" to true, "z" to null, "l" to listOf(1, "two")))
        assertEquals(
            "{\n  \"a\": \"x\\\"y\\\\z\\n\",\n  \"n\": 5,\n  \"b\": true,\n  \"z\": null,\n  \"l\": [\n    1,\n    \"two\"\n  ]\n}",
            json
        )
    }

    @Test
    fun onlyEverythingCarriesTheLearnedProfiles() {
        assertTrue(DiagnosticsArchivePlan.includesLearnedProfiles(Scope.EVERYTHING))
        Scope.entries.filter { it != Scope.EVERYTHING }.forEach { assertTrue(!DiagnosticsArchivePlan.includesLearnedProfiles(it)) }
    }
}
