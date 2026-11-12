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

    /**
     * What to delete before [incoming] starts writing, oldest first. The incoming run takes one
     * of the [MAX_RUNS] slots and is never one of the deleted, whether or not its directory
     * exists yet and whatever its name says about its age.
     *
     * A run that recorded its place in the start order ([RunSequence], [sequenceOf]) is as old as
     * that place says, whatever the clock did. Runs without one were recorded by an earlier build,
     * before every run that has one, so they go first.
     *
     * Among those, age is read off the name, which carries the wall clock at the run's start. When
     * that clock has been set back, a run dated after the one being started was recorded before
     * the change, and so before every run made since. Those go first. By name alone the runs made
     * since the change would look like the oldest on the device, and each new start would delete
     * the run before it. A name cannot say more than that: runs dated before the one being started
     * go oldest name first, whether they were recorded before the change or since.
     */
    fun runsToPruneBeforeStart(
        runDirectoryNames: Collection<String>,
        incoming: String,
        sequenceOf: (String) -> Long? = { null }
    ): List<String> {
        val others = runDirectoryNames.filter { it != incoming && DiagnosticsArchivePlan.isRunDirectoryName(it) }.distinct()
        val slots = if (DiagnosticsArchivePlan.isRunDirectoryName(incoming)) MAX_RUNS - 1 else MAX_RUNS
        if (others.size <= slots) return emptyList()
        val place = others.associateWith(sequenceOf)
        val startedAt = DiagnosticsArchivePlan.startedAtOf(incoming)
        fun recordedBeforeTheClockWentBack(name: String) = startedAt >= 0 && DiagnosticsArchivePlan.startedAtOf(name) > startedAt
        return others
            .sortedWith(
                compareBy<String> { place[it] != null }
                    .thenByDescending { place[it] == null && recordedBeforeTheClockWentBack(it) }
                    .thenBy { place[it] ?: 0L }
                    .thenBy { DiagnosticsArchivePlan.startedAtOf(it) }
            )
            .take(others.size - slots)
    }

    /**
     * The rule by name alone: everything past the newest [keep], oldest first. It is what
     * [runsToPruneBeforeStart] gives when no run has a recorded place and none is dated after the
     * incoming one.
     */
    fun runsToPrune(runDirectoryNames: Collection<String>, keep: Int = MAX_RUNS): List<String> {
        require(keep >= 0) { "keep must be non-negative, was $keep" }
        val runs = DiagnosticsArchivePlan.sortedNewestFirst(runDirectoryNames.filter(DiagnosticsArchivePlan::isRunDirectoryName))
        return if (runs.size <= keep) emptyList() else runs.drop(keep).map { it.batchId }.reversed()
    }
}
