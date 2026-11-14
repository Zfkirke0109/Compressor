package compress.joshattic.us

import compress.joshattic.us.DiagnosticsArchivePlan.Run
import compress.joshattic.us.DiagnosticsArchivePlan.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsArchivePlanTest {

    @Test fun logOnlyPayloadsKeepCoreRecordsAndLeaveAllEvidenceUntouched() {
        val dir = java.nio.file.Files.createTempDirectory("diagnostics-log-only").toFile()
        try {
            for (path in listOf("session.jsonl", "decisions.log", "frames/window.jsonl.gz", "candidates/window.mp4")) {
                java.io.File(dir, path).apply { parentFile!!.mkdirs(); writeText(path) }
            }
            val logs = DiagnosticsArchivePlan.runPayloads(dir, includeEvidenceFiles = false)
            assertEquals(listOf("session.jsonl", "decisions.log"), logs.map { it.first })
            val full = DiagnosticsArchivePlan.runPayloads(dir, includeEvidenceFiles = true)
            assertEquals(listOf("session.jsonl", "decisions.log", "frames/window.jsonl.gz", "candidates/window.mp4"), full.map { it.first })
            for ((path, file) in full) assertEquals(path, file.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun logOnlyExportsAreNamedAndDeclaredSeparately() {
        val name = DiagnosticsArchivePlan.fileName("1.6.271", "20261029-161040", Scope.EVERYTHING, runs,
            includeEvidenceFiles = false)
        assertEquals("Compressor-v1.6.271-20261029-161040-Everything-LogsOnly.zip", name)
        val manifest = DiagnosticsArchivePlan.manifest(emptyMap(), "20261029-161040", Scope.EVERYTHING,
            "batch_1", null, runs, emptyList(), includeEvidenceFiles = false)
        assertTrue(manifest.contains("\"includesEvidenceFiles\": false"))
        assertTrue(manifest.contains("\"scope\": \"EVERYTHING\""))
    }

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
    fun twoExportsNamedInTheSameSecondAreBuiltInDifferentFiles() {
        // The download name is stamped to the second, so two exports of one scope can share it.
        val runs = listOf(Run("batch_1794292368590", 1794292368590L))
        val first = DiagnosticsArchivePlan.fileName("1.6.294", "20261112-170000", Scope.EVERYTHING, runs)
        assertEquals(first, DiagnosticsArchivePlan.fileName("1.6.294", "20261112-170000", Scope.EVERYTHING, runs))
        val directory = java.nio.file.Files.createTempDirectory("staging").toFile()
        try {
            val a = DiagnosticsArchivePlan.stagingFile(java.io.File(directory, "diagnostics_export"))
            val b = DiagnosticsArchivePlan.stagingFile(java.io.File(directory, "diagnostics_export"))
            assertTrue(a.isFile && b.isFile)
            assertTrue(a.canonicalPath != b.canonicalPath)
            assertEquals(a.parentFile, b.parentFile)
            // One export finishing and deleting its file leaves the other's alone.
            a.writeText("first"); b.writeText("second")
            assertTrue(a.delete())
            assertEquals("second", b.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun onlyAStagingFileNoRunningExportCouldOwnIsSwept() {
        val now = 1_794_292_368_590L
        assertTrue(!DiagnosticsArchivePlan.isAbandonedStaging(now, now))
        assertTrue(!DiagnosticsArchivePlan.isAbandonedStaging(now - 60L * 60 * 1000, now))
        assertTrue(!DiagnosticsArchivePlan.isAbandonedStaging(now - DiagnosticsArchivePlan.ABANDONED_STAGING_MS, now))
        assertTrue(DiagnosticsArchivePlan.isAbandonedStaging(now - DiagnosticsArchivePlan.ABANDONED_STAGING_MS - 1, now))
        // A clock set back never makes a fresh file look abandoned.
        assertTrue(!DiagnosticsArchivePlan.isAbandonedStaging(now + 5_000, now))
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

    // A run's name carries the wall clock at its start. These set that clock back a day.

    @Test
    fun afterTheClockIsSetBackTheCurrentRunIsTheOneThatStartedLast() {
        val day = 86_400_000L
        val t = 1_790_000_000_000L
        // In the order they started: two batches and a self-check between them, then the clock went
        // back and a third batch started, with a self-check after it.
        val names = listOf("batch_${t + 200}", "selfcheck_${t + 250}", "batch_${t + 300}", "batch_${t - day + 100}", "selfcheck_${t - day + 120}")
        val places = names.withIndex().associate { (i, name) -> name to (i + 1).toLong() }
        // By name alone the batch that started last looks the oldest, and was never the current run.
        val byName = DiagnosticsArchivePlan.sortedNewestFirst(names)
        assertEquals("batch_${t + 300}", DiagnosticsArchivePlan.runsFor(Scope.CURRENT_RUN, byName).first().batchId)

        val byPlace = DiagnosticsArchivePlan.sortedNewestFirst(names) { places[it] }
        assertEquals(names.reversed(), byPlace.map { it.batchId })
        assertEquals(listOf("batch_${t - day + 100}", "selfcheck_${t - day + 120}"),
            DiagnosticsArchivePlan.runsFor(Scope.CURRENT_RUN, byPlace).map { it.batchId })
        assertEquals(listOf("batch_${t + 300}", "selfcheck_${t + 250}"),
            DiagnosticsArchivePlan.runsFor(Scope.PREVIOUS_RUN, byPlace).map { it.batchId })

        // Crash reports carry only the clock: the previous run's window does not come out empty
        // because the batch after it is dated before it.
        val crashes = listOf("crash-${t + 400}.log", "crash-${t - day + 150}.log")
        assertEquals(listOf("crash-${t + 400}.log"),
            DiagnosticsArchivePlan.crashReportsFor(Scope.PREVIOUS_RUN, DiagnosticsArchivePlan.runsFor(Scope.PREVIOUS_RUN, byPlace), byPlace, crashes))
        assertTrue("crash-${t - day + 150}.log" in
            DiagnosticsArchivePlan.crashReportsFor(Scope.CURRENT_RUN, DiagnosticsArchivePlan.runsFor(Scope.CURRENT_RUN, byPlace), byPlace, crashes))
    }

    @Test
    fun runsAnEarlierBuildRecordedComeAfterEveryRunWithAPlace() {
        val t = 1_790_000_000_000L
        val names = listOf("batch_${t + 900}", "batch_${t + 100}", "batch_${t + 500}", "batch_${t + 50}")
        val places = mapOf("batch_${t + 100}" to 1L, "batch_${t + 500}" to 2L)
        assertEquals(listOf("batch_${t + 500}", "batch_${t + 100}", "batch_${t + 900}", "batch_${t + 50}"),
            DiagnosticsArchivePlan.sortedNewestFirst(names) { places[it] }.map { it.batchId })
        // With no places at all the order is the name order it always was.
        assertEquals(DiagnosticsArchivePlan.sortedNewestFirst(names).map { it.batchId },
            names.sortedByDescending { DiagnosticsArchivePlan.startedAtOf(it) })
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
    fun theManifestCarriesEachRunsPlaceInTheStartOrder() {
        val json = DiagnosticsArchivePlan.manifest(
            identity = emptyMap(), exportedAt = "20261112-220000", scope = Scope.ALL_RUNS,
            currentBatchId = "batch_1789913600000", previousBatchId = "batch_1790000000300",
            includedRuns = listOf(Run("batch_1789913600000", 1789913600000L, 7L), Run("batch_1790000000300", 1790000000300L)),
            entries = emptyList()
        )
        assertTrue(json, json.contains("\"sequence\": 7"))
        assertTrue(json, json.contains("\"sequence\": null"))
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
