package compress.joshattic.us

import java.io.File

/**
 * The order runs started in, kept apart from the wall clock.
 *
 * A run's directory name carries the wall clock at its start (`batch_<epochMs>`), and that clock
 * can be set back. A run started after such a change is then named as if it were older than the
 * runs started before it, and ordering by name made the export's "current run" an older batch and
 * left the latest one out. So each run directory also records its place in the start order: one
 * more than the highest place any retained run holds. Retention never deletes the newest run, so
 * the highest place survives every prune and places only grow.
 *
 * A run recorded by a build without this file has no place. It was recorded before every run that
 * has one, and is ordered after them, by its name as before.
 */
internal object RunSequence {
    /** The file inside each run directory that holds its place. */
    const val FILE = "run-sequence"

    /** [runDirectory]'s place in the start order, or null when it has none or it cannot be read. */
    fun of(runDirectory: File): Long? = runCatching {
        File(runDirectory, FILE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    }.getOrNull()?.takeIf { it > 0L }

    /**
     * [runDirectory]'s place, after giving it the next one if it has none yet. [root] holds every
     * run directory. Null when the place could not be written: the run is then ordered by its name.
     */
    @Synchronized
    fun stamp(root: File, runDirectory: File): Long? {
        of(runDirectory)?.let { return it }
        return runCatching {
            val highest = root.listFiles().orEmpty()
                .filter { it.isDirectory && DiagnosticsArchivePlan.isRunDirectoryName(it.name) }
                .mapNotNull(::of)
                .maxOrNull() ?: 0L
            val next = highest + 1
            runDirectory.mkdirs()
            val written = File(runDirectory, "$FILE.tmp")
            written.writeText(next.toString())
            check(written.renameTo(File(runDirectory, FILE))) { "the run's place could not be recorded" }
            next
        }.getOrNull()
    }
}
