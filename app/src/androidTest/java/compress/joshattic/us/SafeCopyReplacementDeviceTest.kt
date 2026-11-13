package compress.joshattic.us

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.IOException

/**
 * The app's replacement, start to finish, on this phone's MediaStore, against disposable copies (C)
 * of a harness baseline (B) and never anything else (HarnessCopies).
 *
 * Each case makes its own C, checks it is one, and runs OriginalReplacementCoordinator.replace with
 * the IO the app uses (ContentResolverReplacementIo) and the journal it writes. Faults are injected
 * by wrapping that IO for one step, never by changing the phone: no full disk, no revoked grant.
 * The rollback copies live in this run's own folder, not the app's recovery directory, so the app's
 * own start-up recovery never sees them and nothing here touches what it holds.
 *
 * The "output" written over C is a small synthetic video from the test APK (edit_whole_168.mp4):
 * test-made bytes, not anyone's media. A C that a case leaves damaged stays in the run folder with
 * the rest of the run's files until the owner approves its cleanup.
 *
 *   scripts/device/safe-copy-harness.sh run-replacement-tests --run DIR --disposable-only
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SafeCopyReplacementDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var copies: HarnessCopies
    private lateinit var baselinePath: String
    private lateinit var baselineSha: String
    private var baselineBytes = 0L
    private lateinit var output: File
    private lateinit var outputSha: String

    @Before
    fun setUp() {
        val args = HarnessCopies.args("pllabRunId", "pllabBaseline", "pllabBaselineSha256", "pllabBaselineBytes")
        copies = HarnessCopies(context, args.getValue("pllabRunId"))
        baselinePath = args.getValue("pllabBaseline")
        baselineSha = args.getValue("pllabBaselineSha256").lowercase()
        baselineBytes = args.getValue("pllabBaselineBytes").toLong()
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("edit_whole_168.mp4").use { it.readBytes() }
        output = File(copies.runDir, "output-edit_whole_168.mp4").apply { if (!isFile || length() != bytes.size.toLong()) writeBytes(bytes) }
        outputSha = HarnessCopies.sha256(output.readBytes())
        assertNotEquals("the output must differ from the baseline", baselineSha, outputSha)
    }

    /** One replacement of [target] through the app's own IO, with [wrap] around it for a fault. */
    private fun replace(case: String, target: Uri, wrap: (ContentResolverReplacementIo) -> OriginalReplacementIo = { it }): Pair<ReplacementTransaction, File> {
        copies.requireDisposable(target, null)
        val recovery = ReplacementContentProof.newRecoveryFile(copies.recoveryDir, "job_pllab_$case")
        ReplacementJournal.markActive(recovery)
        val io = ContentResolverReplacementIo(context.contentResolver, target, output, recovery, outputSha)
        val entry = ReplacementJournalEntry(
            jobId = "job_pllab_$case", state = ReplacementJournalEntry.STAGING, targetUri = target.toString(),
            displayName = "PLLAB-C-$case.mp4", sourceBytes = baselineBytes, boundSourceSha256 = baselineSha, stagedSha256 = null,
            outputBytes = output.length(), outputSha256 = outputSha, createdAtMs = System.currentTimeMillis()
        )
        val tx = OriginalReplacementCoordinator.replace(
            wrap(io), ReplacementRequest(output.length(), baselineBytes, baselineSha), ReplacementJournal.sink(recovery, entry)
        )
        return tx to recovery
    }

    private fun copyFor(case: String): Uri = copies.createDisposableCopy(case, baselinePath, baselineSha, baselineBytes).also {
        copies.requireDisposable(it, baselineSha)
    }

    /** Writes [bytes] over [target] as a fault would, after checking it is a disposable copy. */
    private fun overwrite(target: Uri, bytes: ByteArray, limit: Int = bytes.size, thenThrow: Boolean = false) {
        copies.requireDisposable(target, null)
        context.contentResolver.openOutputStream(target, "rwt")!!.use { out ->
            out.write(bytes, 0, limit)
            out.flush()
            if (thenThrow) throw IOException("injected: the write stopped after $limit bytes")
        }
    }

    private fun baselineBytes(): ByteArray = copies.readShellFile(baselinePath).use { it.readBytes() }

    @Test
    fun a_aDisposableCopyIsReplacedAndTheWriteConfirmedByContent() {
        val c = copyFor("success")
        val (tx, recovery) = replace("success", c)
        assertEquals(ReplacementAttempt.Replaced, tx.attempt)
        assertEquals(ReplacementTransaction.COMMITTED, tx.outcome)
        assertEquals(baselineSha, tx.stagedDigest)
        assertEquals(ReadBack.CONFIRMED, tx.writeReadBack)
        assertEquals(outputSha, copies.digest(c)?.sha256)
        assertFalse("the rollback copy is discarded once the write is confirmed", recovery.exists())
        assertFalse("and its journal with it", ReplacementJournal.fileFor(recovery).exists())
        ReplacementJournal.release(recovery)
        copies.record("replace_success", "PASS", mapOf("outcome" to tx.outcome, "copyHolds" to "output"))
    }

    @Test
    fun b_aWriteThatStopsPartWayIsRolledBackToTheCopysBytes() {
        val c = copyFor("interrupted")
        val bytes = output.readBytes()
        val (tx, recovery) = replace("interrupted", c) { real ->
            object : OriginalReplacementIo by real {
                override fun writeOutputOverOriginal() = overwrite(c, bytes, limit = bytes.size / 2, thenThrow = true)
            }
        }
        assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.WRITE_FAILED_RESTORED), tx.attempt)
        assertEquals(ReadBack.WRITE_THREW, tx.writeReadBack)
        assertEquals(ReadBack.CONFIRMED, tx.restoreReadBack)
        assertEquals("C holds the baseline's bytes again", baselineSha, copies.digest(c)?.sha256)
        // The caller discards the copy once nothing destructive remains, as the app does.
        assertTrue(recovery.exists())
        ReplacementJournal.discard(recovery)
        ReplacementJournal.release(recovery)
        copies.record("interrupted_write_rolled_back", "PASS", mapOf("outcome" to tx.outcome, "copyHolds" to "baseline"))
    }

    @Test
    fun c_aWriteOfTheRightLengthButWrongBytesIsRolledBack() {
        val c = copyFor("wrong_bytes")
        val wrong = output.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val (tx, recovery) = replace("wrong_bytes", c) { real ->
            object : OriginalReplacementIo by real {
                override fun writeOutputOverOriginal() = overwrite(c, wrong)
            }
        }
        assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.WRITE_UNVERIFIED_RESTORED), tx.attempt)
        assertEquals(ReadBack.CONTENT_MISMATCH, tx.writeReadBack)
        assertEquals(baselineSha, copies.digest(c)?.sha256)
        ReplacementJournal.discard(recovery); ReplacementJournal.release(recovery)
        copies.record("same_size_wrong_digest_rolled_back", "PASS", mapOf("outcome" to tx.outcome, "readBack" to tx.writeReadBack?.wire))
    }

    @Test
    fun d_aWriteWhoseSizeCannotBeReadBackIsRolledBack() {
        val c = copyFor("no_size")
        var asked = 0
        val (tx, recovery) = replace("no_size", c) { real ->
            object : OriginalReplacementIo by real {
                // Only the first read-back, the write's, reports nothing.
                override fun readBackOriginalSize(): Long = if (asked++ == 0) -1L else real.readBackOriginalSize()
            }
        }
        assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.WRITE_UNVERIFIED_RESTORED), tx.attempt)
        assertEquals(ReadBack.SIZE_UNAVAILABLE, tx.writeReadBack)
        assertEquals(baselineSha, copies.digest(c)?.sha256)
        ReplacementJournal.discard(recovery); ReplacementJournal.release(recovery)
        copies.record("unreadable_size_rolled_back", "PASS", mapOf("outcome" to tx.outcome, "readBack" to tx.writeReadBack?.wire))
    }

    @Test
    fun e_aCopyThatChangedBeforeStagingIsNeverOpenedForWriting() {
        val c = copyFor("changed")
        val changed = baselineBytes().also { it[it.size / 3] = (it[it.size / 3] + 1).toByte() }
        overwrite(c, changed)
        val changedSha = HarnessCopies.sha256(changed)
        val (tx, recovery) = replace("changed", c)
        assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.SOURCE_CHANGED), tx.attempt)
        assertEquals(false, tx.stagedMatchesBoundSource)
        assertFalse(tx.originalWasOpenedForWriting)
        assertEquals("C is exactly as the change left it", changedSha, copies.digest(c)?.sha256)
        assertFalse(recovery.exists())
        ReplacementJournal.release(recovery)
        copies.record("source_changed_refused", "PASS", mapOf("outcome" to tx.outcome))
    }

    @Test
    fun f_aRollbackThatFailsKeepsTheCopyAndTheNextStartPreservesItOnce() {
        val c = copyFor("rollback_fails")
        val bytes = output.readBytes()
        val (tx, recovery) = replace("rollback_fails", c) { real ->
            object : OriginalReplacementIo by real {
                override fun writeOutputOverOriginal() = overwrite(c, bytes, limit = bytes.size / 3, thenThrow = true)
                override fun restoreOriginalFromRecovery() = throw IOException("injected: the restore failed")
            }
        }
        assertEquals(ReplacementAttempt.OriginalAtRisk, tx.attempt)
        assertTrue("the rollback copy is the intact original now and is kept", recovery.isFile)
        assertEquals(baselineSha, recovery.inputStream().use { ReplacementContentProof.sha256(it) })
        assertEquals(ReplacementJournalEntry.STAGED, ReplacementJournal.read(recovery)?.state)

        fun startUp() = ReplacementRecovery.recoverLeftovers(
            copies.recoveryDir,
            targetSha256 = { entry -> copies.digest(Uri.parse(entry.targetUri))?.sha256 },
            preserve = { leftover, expected ->
                copies.publish(ReplacementRecovery.recoveredName(leftover.journal, leftover.recoveryCopy.lastModified()), expected) {
                    leftover.recoveryCopy.inputStream()
                }
            }
        )
        // While the replacement that made it is still running, the copy is not a leftover.
        assertEquals(ReplacementRecovery.StartupResult(0, 0), startUp())
        assertTrue(recovery.isFile)
        // The next start (the process that made it is gone): saved to this run's folder, read
        // back as the staged bytes, and only then deleted. A second start finds nothing.
        ReplacementJournal.release(recovery)
        assertEquals(ReplacementRecovery.StartupResult(1, 0), startUp())
        assertFalse(recovery.exists())
        assertEquals(ReplacementRecovery.StartupResult(0, 0), startUp())
        copies.record("failed_rollback_recovered_once", "PASS", mapOf("outcome" to tx.outcome, "startups" to "0/0, 1/0, 0/0"))
    }

    @Test
    fun g_anotherWriterAfterStagingIsOverwrittenAndNotKept() {
        // The limit the coordinator documents, shown on the phone's provider with a disposable copy.
        val c = copyFor("concurrent")
        val other = baselineBytes().also { it[7] = (it[7] + 1).toByte() }
        val (tx, recovery) = replace("concurrent", c) { real ->
            object : OriginalReplacementIo by real {
                override fun writeOutputOverOriginal() {
                    overwrite(c, other) // another app's write, after the staged copy was checked
                    real.writeOutputOverOriginal()
                }
            }
        }
        assertEquals(ReplacementAttempt.Replaced, tx.attempt)
        assertEquals(baselineSha, tx.stagedDigest)
        assertEquals(outputSha, copies.digest(c)?.sha256)
        assertFalse(recovery.exists())
        ReplacementJournal.release(recovery)
        copies.record("concurrent_write_after_staging", "LIMITATION", mapOf(
            "outcome" to tx.outcome, "note" to "the other writer's bytes were overwritten and not kept, as documented"))
    }
}
