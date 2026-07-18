package compress.joshattic.us

/**
 * Which cached batch outputs may be reclaimed.
 *
 * Pure so the retention rule is unit-testable: deleting a file the UI still points at is silent data
 * loss from the user's perspective (share/save suddenly reference a file that no longer exists),
 * while never reclaiming anything would let the cache grow without bound. The rule is deliberately
 * conservative — anything currently referenced is kept, everything else is an orphan.
 */
object BatchCacheRetention {

    /**
     * @param fileAbsolutePath a file found in the batch cache directory.
     * @param preservePaths absolute paths the current UI state still references (live outputPaths).
     *   Empty means "preserve nothing" — the explicit user-initiated clear.
     */
    fun isDeletable(fileAbsolutePath: String, preservePaths: Set<String>): Boolean =
        fileAbsolutePath !in preservePaths

    /** Convenience for tests/callers: the subset of [allPaths] that may be reclaimed. */
    fun deletablePaths(allPaths: List<String>, preservePaths: Set<String>): List<String> =
        allPaths.filter { isDeletable(it, preservePaths) }
}
