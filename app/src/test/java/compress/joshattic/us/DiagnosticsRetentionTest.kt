package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRetentionTest {

    @Test fun startingTheThirtyFirstRunReservesASlotBeforeWriting() {
        val names = (1..30).map { "batch_$it" }
        assertEquals(listOf("batch_1"), DiagnosticsRetention.runsToPruneBeforeStart(names, "batch_31"))
        assertTrue(DiagnosticsRetention.runsToPruneBeforeStart(names, "batch_30").isEmpty())
        assertEquals(emptyList<String>(), DiagnosticsRetention.runsToPruneBeforeStart(names.take(29), "selfcheck_31"))
    }

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

    // A run's name carries the wall clock at its start. The three below set that clock back.

    @Test
    fun theRunBeingStartedIsNeverTheOneDeletedWhenTheClockWasSetBack() {
        val retained = (1..30).map { "batch_${A_MONTH_LATER + it * MINUTE}" }
        val incoming = "batch_$ONE_DAY"

        val prune = DiagnosticsRetention.runsToPruneBeforeStart(retained, incoming)

        assertEquals("the oldest retained run makes room, so the cap holds", listOf(retained.first()), prune)
        // The same once the incoming directory exists, as it does when its decision log was written first.
        assertEquals(listOf(retained.first()), DiagnosticsRetention.runsToPruneBeforeStart(retained + incoming, incoming))
    }

    @Test
    fun aRunRecordedSinceTheClockWasSetBackOutlivesTheRunsRecordedBeforeIt() {
        val recordedBefore = (1..29).map { "batch_${A_MONTH_LATER + it * MINUTE}" }
        val recordedSince = "batch_$ONE_DAY"
        val incoming = "batch_${ONE_DAY + 60 * MINUTE}"

        val prune = DiagnosticsRetention.runsToPruneBeforeStart(recordedBefore + recordedSince, incoming)

        assertEquals(listOf(recordedBefore.first()), prune)
        assertTrue("the previous run is the newest thing on the device", recordedSince !in prune)
    }

    @Test
    fun runsDatedAfterTheOneBeingStartedGoFirstOldestFirst() {
        val datedBefore = (1..20).map { "batch_${ONE_DAY - it * MINUTE}" }
        val datedAfter = (1..12).map { "batch_${A_MONTH_LATER + it * MINUTE}" }

        val prune = DiagnosticsRetention.runsToPruneBeforeStart(datedBefore + datedAfter, "batch_$ONE_DAY")

        assertEquals(datedAfter.take(3), prune)
        assertEquals(DiagnosticsRetention.MAX_RUNS, datedBefore.size + datedAfter.size - prune.size + 1)
    }

    @Test
    fun aClockThatNeverWentBackPrunesOldestFirstAsBefore() {
        val retained = (1..34).map { "batch_${ONE_DAY + it * MINUTE}" } + "selfcheck_${ONE_DAY + 90_000L}" + "crashes"
        val incoming = "batch_$A_MONTH_LATER"

        val prune = DiagnosticsRetention.runsToPruneBeforeStart(retained, incoming)

        // 35 runs and the incoming one: six go, and the self-check dated between the first two batches is the second.
        assertEquals(
            listOf("batch_${ONE_DAY + MINUTE}", "selfcheck_${ONE_DAY + 90_000L}") + (2..5).map { "batch_${ONE_DAY + it * MINUTE}" },
            prune
        )
        assertEquals(prune, DiagnosticsRetention.runsToPrune(retained + incoming))
    }

    @Test
    fun aDirectoryThatIsNotARunTakesNoSlot() {
        val retained = (1..30).map { "batch_${ONE_DAY + it * MINUTE}" }
        assertTrue(DiagnosticsRetention.runsToPruneBeforeStart(retained, "selftest_token").isEmpty())
        assertEquals(
            listOf(retained.first()),
            DiagnosticsRetention.runsToPruneBeforeStart(retained + "batch_$A_MONTH_LATER", "selftest_token")
        )
    }

    @Test
    fun runsThatRecordedTheirPlaceArePrunedInTheOrderTheyStarted() {
        // Thirty retained runs whose names are no guide to the order they started in.
        val names = (1..30).map { "batch_${ONE_DAY + ((it * 7919) % 30) * MINUTE}" }
        val places = names.withIndex().associate { (i, name) -> name to (i + 1).toLong() }

        val prune = DiagnosticsRetention.runsToPruneBeforeStart(names, "batch_$A_MONTH_LATER") { places[it] }

        assertEquals("the first to start goes, not the oldest name", listOf(names.first()), prune)
        assertEquals(listOf(names.minBy { DiagnosticsArchivePlan.startedAtOf(it) }),
            DiagnosticsRetention.runsToPruneBeforeStart(names, "batch_$A_MONTH_LATER"))
    }

    @Test
    fun runsFromAnEarlierBuildGoBeforeEveryRunWithAPlace() {
        val earlierBuild = (1..5).map { "batch_${A_MONTH_LATER + it * MINUTE}" }
        val placed = (1..26).map { "batch_${ONE_DAY + it * MINUTE}" }
        val places = placed.withIndex().associate { (i, name) -> name to (i + 1).toLong() }

        val prune = DiagnosticsRetention.runsToPruneBeforeStart(earlierBuild + placed, "batch_${ONE_DAY + 99 * MINUTE}") { places[it] }

        assertEquals(earlierBuild.take(2), prune)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val ONE_DAY = 1_790_000_000_000L
        const val A_MONTH_LATER = ONE_DAY + 31L * 24 * 60 * MINUTE
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
