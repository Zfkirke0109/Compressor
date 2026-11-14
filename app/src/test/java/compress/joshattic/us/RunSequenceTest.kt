package compress.joshattic.us

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunSequenceTest {

    private fun <T> inRoot(block: (File) -> T): T {
        val root = Files.createTempDirectory("diagnostics").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun placesFollowTheOrderRunsStartedInWhateverTheirNamesSay() = inRoot { root ->
        val first = File(root, "batch_1790000000300")
        // Started second, after the clock was set back a day.
        val second = File(root, "batch_1789913600000")
        assertEquals(1L, RunSequence.stamp(root, first))
        assertEquals(2L, RunSequence.stamp(root, second))
        // Giving a run its place again keeps the place it has.
        assertEquals(1L, RunSequence.stamp(root, first))
        assertEquals(2L, RunSequence.of(second))
        assertEquals(3L, RunSequence.stamp(root, File(root, "selfcheck_1789913600500")))
    }

    @Test fun placesOnlyGrowWhenOlderRunsArePruned() = inRoot { root ->
        val runs = (1..3).map { File(root, "batch_17900000000$it") }
        runs.forEach { RunSequence.stamp(root, it) }
        runs.take(2).forEach { it.deleteRecursively() }
        assertEquals(4L, RunSequence.stamp(root, File(root, "batch_1790000000099")))
    }

    @Test fun aRunWithoutAReadablePlaceHasNoneAndOtherDirectoriesDoNotCount() = inRoot { root ->
        val run = File(root, "batch_1790000000001").apply { mkdirs() }
        assertNull(RunSequence.of(run))
        for (text in listOf("", "junk", "0", "-3")) {
            File(run, RunSequence.FILE).writeText(text)
            assertNull("'$text' is not a place", RunSequence.of(run))
        }
        // A directory that is not a run cannot raise the next place.
        File(root, "crashes").apply { mkdirs() }.resolve(RunSequence.FILE).writeText("99")
        assertEquals(1L, RunSequence.stamp(root, File(root, "batch_1790000000002")))
        // The run whose file was unreadable gets a place when it is stamped.
        assertEquals(2L, RunSequence.stamp(root, run))
    }
}
