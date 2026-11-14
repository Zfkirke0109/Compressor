package compress.joshattic.us

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDirectoryTest {

    private fun <T> inRoot(block: (File) -> T): T {
        val root = Files.createTempDirectory("diagnostics").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }

    /** A run as its decision log leaves it: the directory, its place and one line of log. */
    private fun open(root: File, runId: String): File =
        DiagnosticsRecorder.openRunDirectory(root, runId)!!.also { File(it, "decisions.log").writeText("line\n") }

    @Test fun consecutiveSelfChecksKeepTheCapAndDeleteTheRunsThatStartedFirst() = inRoot { root ->
        val batches = (1..30).map { open(root, "batch_${1_790_000_000_000L + it}") }
        // A scorer self-check writes only a decision log. Five in a row used to grow the folder to 35.
        val checks = (1..5).map { open(root, "selfcheck_${1_790_000_100_000L + it}") }

        val kept = DiagnosticsRecorder.runDirectories(root).map { it.name }.toSet()
        assertEquals(DiagnosticsRetention.MAX_RUNS, kept.size)
        assertTrue("every self-check is kept", checks.all { it.name in kept })
        assertEquals("the five that started first went", batches.take(5).map { it.name }.toSet(), batches.map { it.name }.toSet() - kept)
        assertEquals(35L, RunSequence.of(checks.last()))
    }

    @Test fun openingARunAgainKeepsItsPlaceAndItsFiles() = inRoot { root ->
        val run = open(root, "batch_1790000000001")
        File(run, "session.jsonl").writeText("{}\n")
        // A batch opens its decision log, then its session record.
        assertEquals(run, DiagnosticsRecorder.openRunDirectory(root, run.name))
        assertEquals(1L, RunSequence.of(run))
        assertTrue(File(run, "session.jsonl").isFile && File(run, "decisions.log").isFile)
    }

    @Test fun directoriesThatAreNotRunsAreNeitherCountedNorDeleted() = inRoot { root ->
        val crashes = File(root, "crashes").apply { mkdirs() }.also { File(it, "crash-1.log").writeText("x") }
        (1..31).forEach { open(root, "batch_${1_790_000_000_000L + it}") }
        assertTrue(crashes.isDirectory)
        assertFalse(DiagnosticsRecorder.runDirectories(root).any { it.name == "crashes" })
        assertEquals(DiagnosticsRetention.MAX_RUNS, DiagnosticsRecorder.runDirectories(root).size)
    }
}
