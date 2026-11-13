package compress.joshattic.us

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A process that dies in the middle of overwriting a disposable copy, and the starts after it.
 * Three separate instrumentation runs, which the harness makes in order (`-e pllabPhase`):
 *
 *  1. `die`: a C is made from the baseline, the app's replacement stages its rollback copy and
 *     journal, begins the truncating write, and the process is killed a third of the way in. The
 *     run ends "Process crashed", which is what is meant to happen.
 *  2. `recover-1`: a new process. The rollback copy is found, C is neither the staged bytes nor the
 *     output, so the copy is saved to the run folder, read back as the staged bytes, then deleted.
 *  3. `recover-2`: nothing is left to do, and nothing is saved twice.
 *
 * Only this run's own recovery folder is scanned, and only C is written. No other process is
 * stopped.
 */
@RunWith(AndroidJUnit4::class)
class SafeCopyProcessDeathDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun phase(): String = InstrumentationRegistry.getArguments().getString("pllabPhase").orEmpty()

    @Test
    fun theProcessDiesMidWriteOrTheNextStartRecoversTheCopyExactlyOnce() {
        val phase = phase()
        assumeTrue("run through scripts/device/safe-copy-harness.sh with -e pllabPhase", phase in setOf("die", "recover-1", "recover-2"))
        val args = HarnessCopies.args("pllabRunId", "pllabBaselineSha256")
        val copies = HarnessCopies(context, args.getValue("pllabRunId"))
        val baselineSha = args.getValue("pllabBaselineSha256").lowercase()
        when (phase) {
            "die" -> die(copies, baselineSha)
            else -> recover(copies, baselineSha, expectPreserved = phase == "recover-1")
        }
    }

    private fun die(copies: HarnessCopies, baselineSha: String) {
        val args = HarnessCopies.args("pllabBaseline", "pllabBaselineBytes")
        val baselineBytes = args.getValue("pllabBaselineBytes").toLong()
        val c = copies.createDisposableCopy("death", args.getValue("pllabBaseline"), baselineSha, baselineBytes)
        copies.requireDisposable(c, baselineSha)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("edit_whole_168.mp4").use { it.readBytes() }
        val output = File(copies.runDir, "output-death.mp4").apply { writeBytes(bytes) }
        val outputSha = HarnessCopies.sha256(bytes)
        val recovery = ReplacementContentProof.newRecoveryFile(copies.recoveryDir, "job_pllab_death")
        ReplacementJournal.markActive(recovery)
        val real = ContentResolverReplacementIo(context.contentResolver, c, output, recovery, outputSha)
        val dying = object : OriginalReplacementIo by real {
            override fun writeOutputOverOriginal() {
                copies.requireDisposable(c, null)
                context.contentResolver.openOutputStream(c, "rwt")!!.use { out ->
                    out.write(bytes, 0, bytes.size / 3)
                    out.flush()
                }
                copies.record("process_death_mid_write", "STARTED", mapOf("note" to "killing the test process a third of the way into the write"))
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        val entry = ReplacementJournalEntry(
            jobId = "job_pllab_death", state = ReplacementJournalEntry.STAGING, targetUri = c.toString(), displayName = "PLLAB-C-death.mp4",
            sourceBytes = baselineBytes, boundSourceSha256 = baselineSha, stagedSha256 = null,
            outputBytes = output.length(), outputSha256 = outputSha, createdAtMs = System.currentTimeMillis()
        )
        OriginalReplacementCoordinator.replace(dying, ReplacementRequest(output.length(), baselineBytes, baselineSha), ReplacementJournal.sink(recovery, entry))
        throw AssertionError("the process was meant to die during the write")
    }

    private fun recover(copies: HarnessCopies, baselineSha: String, expectPreserved: Boolean) {
        val leftovers = ReplacementJournal.leftovers(copies.recoveryDir)
        if (expectPreserved) {
            val only = leftovers.singleOrNull()
            assertTrue("expected the one rollback copy the dead process left, found ${leftovers.size}", only != null)
            val target = Uri.parse(only!!.journal!!.targetUri)
            copies.requireDisposable(target, null)
            val now = copies.digest(target)?.sha256
            assertNotEquals("C should be part-written", baselineSha, now)
            assertNotEquals("C should be part-written", only.journal!!.outputSha256, now)
        }
        val result = ReplacementRecovery.recoverLeftovers(
            copies.recoveryDir,
            targetSha256 = { entry -> copies.digest(Uri.parse(entry.targetUri))?.sha256 },
            preserve = { leftover, expected ->
                copies.publish(ReplacementRecovery.recoveredName(leftover.journal, leftover.recoveryCopy.lastModified()), expected) {
                    leftover.recoveryCopy.inputStream()
                }
            }
        )
        assertEquals(ReplacementRecovery.StartupResult(if (expectPreserved) 1 else 0, 0), result)
        assertEquals(emptyList<ReplacementLeftover>(), ReplacementJournal.leftovers(copies.recoveryDir))
        copies.record(if (expectPreserved) "recovery_after_death_first_start" else "recovery_after_death_second_start", "PASS",
            mapOf("preserved" to result.preserved, "held" to result.held))
    }
}
