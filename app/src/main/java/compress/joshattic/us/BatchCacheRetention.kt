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
     * Prefix of a rollback copy staged by the destructive replace path. Such a file is retained ONLY
     * when the original on disk may be incomplete and the copy could not be preserved elsewhere — at
     * that moment it is the user's last intact original, so it must survive even an explicit cache
     * clear. Otherwise the "an intact copy is still held inside the app" message would be a lie.
     */
    const val RECOVERY_FILE_PREFIX = "replace_recovery_"

    /**
     * @param fileAbsolutePath a file found in the batch cache directory.
     * @param preservePaths absolute paths the current UI state still references (live outputPaths).
     *   Empty means "preserve nothing" — the explicit user-initiated clear.
     */
    fun isDeletable(fileAbsolutePath: String, preservePaths: Set<String>): Boolean {
        val name = fileAbsolutePath.substringAfterLast('/').substringAfterLast('\\')
        if (name.startsWith(RECOVERY_FILE_PREFIX)) return false
        return fileAbsolutePath !in preservePaths
    }

    /** Convenience for tests/callers: the subset of [allPaths] that may be reclaimed. */
    fun deletablePaths(allPaths: List<String>, preservePaths: Set<String>): List<String> =
        allPaths.filter { isDeletable(it, preservePaths) }
}
