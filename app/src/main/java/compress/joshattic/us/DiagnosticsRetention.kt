package compress.joshattic.us

/**
 * How many runs the device keeps. Pure so the rule is unit-tested.
 *
 * The export archive carries every retained run, and it has to stay something a phone can send.
 * 30 runs is several weeks of use at this project's pace; anything older has either been
 * exported already or is not going to be looked at. Crash reports have their own cap
 * (CrashReportPlan.MAX_REPORTS).
 */
object DiagnosticsRetention {

    const val MAX_RUNS = 30

    /** Run directory names to delete: everything past the newest [keep], oldest first. */
    fun runsToPrune(runDirectoryNames: Collection<String>, keep: Int = MAX_RUNS): List<String> {
        require(keep >= 0) { "keep must be non-negative, was $keep" }
        val runs = DiagnosticsArchivePlan.sortedNewestFirst(runDirectoryNames.filter { it.startsWith("batch_") })
        return if (runs.size <= keep) emptyList() else runs.drop(keep).map { it.batchId }.reversed()
    }
}
