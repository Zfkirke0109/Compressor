package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRetentionTest {

    @Test
    fun keepsTheNewestRunsAndNamesTheOldestToPruneFirst() {
        val names = (1..35).map { "batch_${1_790_000_000_000L + it * 1000}" } + "crashes"
        val prune = DiagnosticsRetention.runsToPrune(names, keep = 30)
        assertEquals(5, prune.size)
        assertEquals("batch_1790000001000", prune.first())
        assertTrue("crashes" !in prune)
    }

    @Test
    fun nothingIsPrunedUnderTheCap() {
        assertTrue(DiagnosticsRetention.runsToPrune(listOf("batch_1790000001000", "batch_1790000002000")).isEmpty())
    }
}

class DiagnosticsSelfCheckRunsTest {

    @org.junit.Test
    fun selfCheckDirectoriesAreRunsAndSortByTheirEpoch() {
        org.junit.Assert.assertTrue(DiagnosticsArchivePlan.isRunDirectoryName("selfcheck_1790289000000"))
        org.junit.Assert.assertTrue(DiagnosticsArchivePlan.isRunDirectoryName("batch_1790289694972"))
        org.junit.Assert.assertFalse(DiagnosticsArchivePlan.isRunDirectoryName("crashes"))
        org.junit.Assert.assertEquals(1790289000000L, DiagnosticsArchivePlan.startedAtOf("selfcheck_1790289000000"))
        val sorted = DiagnosticsArchivePlan.sortedNewestFirst(
            listOf("batch_1790280254600", "selfcheck_1790289000000", "batch_1790289694972")
        ).map { it.batchId }
        org.junit.Assert.assertEquals(listOf("batch_1790289694972", "selfcheck_1790289000000", "batch_1790280254600"), sorted)
    }

    @org.junit.Test
    fun retentionCountsSelfChecksAndIgnoresOtherDirectories() {
        val names = listOf("crashes", "selfcheck_3", "batch_1", "batch_2", "selfcheck_4")
        org.junit.Assert.assertEquals(listOf("batch_1", "batch_2"), DiagnosticsRetention.runsToPrune(names, keep = 2))
    }

    @org.junit.Test
    fun aSingleRunScopePicksABatchAndCarriesTheSelfChecksAroundIt() {
        val runs = DiagnosticsArchivePlan.sortedNewestFirst(
            listOf("batch_100", "selfcheck_150", "batch_200", "selfcheck_250", "batch_300", "selfcheck_350")
        )
        val current = DiagnosticsArchivePlan.runsFor(DiagnosticsArchivePlan.Scope.CURRENT_RUN, runs).map { it.batchId }
        org.junit.Assert.assertEquals(listOf("batch_300", "selfcheck_350", "selfcheck_250"), current)
        val previous = DiagnosticsArchivePlan.runsFor(DiagnosticsArchivePlan.Scope.PREVIOUS_RUN, runs).map { it.batchId }
        org.junit.Assert.assertEquals(listOf("batch_200", "selfcheck_250", "selfcheck_150"), previous)
        org.junit.Assert.assertEquals(
            "Compressor-v1.6.166-20260925-120000-batch_300.zip",
            DiagnosticsArchivePlan.fileName("1.6.166", "20260925-120000", DiagnosticsArchivePlan.Scope.CURRENT_RUN,
                DiagnosticsArchivePlan.runsFor(DiagnosticsArchivePlan.Scope.CURRENT_RUN, runs))
        )
    }

    @org.junit.Test
    fun aSelfCheckAloneIsNotACurrentRun() {
        val runs = DiagnosticsArchivePlan.sortedNewestFirst(listOf("selfcheck_1"))
        org.junit.Assert.assertTrue(DiagnosticsArchivePlan.runsFor(DiagnosticsArchivePlan.Scope.CURRENT_RUN, runs).isEmpty())
        org.junit.Assert.assertEquals(1, DiagnosticsArchivePlan.runsFor(DiagnosticsArchivePlan.Scope.ALL_RUNS, runs).size)
    }
}
