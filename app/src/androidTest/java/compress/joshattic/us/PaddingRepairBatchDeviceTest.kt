package compress.joshattic.us

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Two real Perceptually Lossless batches over the harness's synthetic fixture (no one's media):
 *
 *  a. Copy mode. The fixture's AAC track has an encoder delay of 1,024 samples and an end padding
 *     of 0 written exactly (a 48 kHz movie timescale); Media3's muxer writes the copied track's edit
 *     on a 1/10,000 s timescale, which the platform reads as end padding the source does not have.
 *     With no location to write, the metadata remux is skipped, so the candidate reaches the
 *     end padding repair (CandidateContainerRepair) as Media3 wrote it. The case passes only if
 *     a smaller candidate was repaired and then verified, certified and accepted.
 *  b. Replacement of a disposable copy (C) of the fixture, with Replace Originals and Backup before
 *     replace on: the backup must be published and read back as the fixture before C is staged and
 *     written, and C must then hold the accepted output.
 *
 * Neither case is assumed to compress: a batch that keeps the original, or accepts without needing
 * the repair, is reported incomplete with what it did, never passed. Both run under a HarnessScope:
 * planned from the user's learned state, learning into the test's own store. Starting a batch clears
 * the app's cached outputs, so neither starts while any unsaved output is there.
 *
 *   scripts/device/safe-copy-harness.sh run-copy-tests --run DIR --cases padding-repair,batch-replace
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PaddingRepairBatchDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val outputDir get() = File(context.cacheDir, "batch_compressed_videos")
    private lateinit var copies: HarnessCopies
    private lateinit var fixturePath: String
    private lateinit var fixtureSha: String
    private var fixtureBytes = 0L
    private var running: BatchCompressorViewModel? = null
    private val scratch = mutableListOf<File>()

    @Before
    fun setUp() {
        val args = HarnessCopies.args("pllabRunId", "pllabFixture", "pllabFixtureSha256", "pllabFixtureBytes")
        copies = HarnessCopies(context, args.getValue("pllabRunId"))
        fixturePath = args.getValue("pllabFixture")
        fixtureSha = args.getValue("pllabFixtureSha256").lowercase()
        fixtureBytes = args.getValue("pllabFixtureBytes").toLong()
        // Starting a batch clears the cached outputs: never while one is waiting to be saved.
        assumeTrue(
            "unsaved outputs are waiting in the app (${outputDir.listFiles().orEmpty().size}); save or discard them first",
            outputDir.listFiles().orEmpty().isEmpty()
        )
    }

    @After
    fun tearDown() {
        running?.let { vm ->
            if (vm.uiState.value.isCompressing) {
                instrumentation.runOnMainSync { vm.cancelCompression() }
                val deadline = System.currentTimeMillis() + 60_000L
                while (vm.uiState.value.isCompressing && System.currentTimeMillis() < deadline) Thread.sleep(200)
            }
        }
        // What these batches wrote to the cache is theirs: the directory was empty before.
        outputDir.listFiles().orEmpty().forEach { runCatching { it.delete() } }
        scratch.forEach { runCatching { it.delete() } }
    }

    private class Run(val row: BatchVideoItem, val records: List<JSONObject>) {
        val job: JSONObject get() = records.last { it.optString("type") == "job" }
        fun stages(name: String) = records.filter { it.optString("type") == "stage" && it.optString("stage") == name }
        val terminal: String get() = job.optString("terminal")
        fun describe() = "terminal=$terminal attempts=${job.opt("attempts")} " +
            "certify=${stages("certify").joinToString { "${it.optString("reasonCode")}:${it.opt("certMean")}" }} " +
            "finalize=${stages("finalize").joinToString { "${it.opt("operation")}:${it.opt("restoredAudioEndPadding")}" }} " +
            "verify=${stages("verify").joinToString { it.optString("reasonCode") }}"
    }

    private fun latestSession(since: Long): List<JSONObject> {
        val dirs = File(context.filesDir, "diagnostics").listFiles { f -> f.isDirectory && f.name.startsWith("batch_") }.orEmpty()
        val newest = dirs.filter { it.name.removePrefix("batch_").toLongOrNull()?.let { t -> t >= since } == true }
            .maxByOrNull { it.name.removePrefix("batch_").toLong() } ?: return emptyList()
        return File(newest, "session.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    }

    private fun runBatch(source: Uri, replace: Boolean, label: String): Run {
        val app = context.applicationContext as Application
        val scope = HarnessScope("${copies.runId}-$label", OverlayProfileStore(SmartPerceptualProfileEngine.SharedPreferencesProfileStore(app)))
        val vm = BatchCompressorViewModel(app, CandidateVeto.NONE, scope).also { running = it }
        val startedAt = System.currentTimeMillis()
        instrumentation.runOnMainSync {
            vm.setQuality("Perceptually Lossless")
            vm.setExhaustivePerceptualLossless(true)
            vm.loadUris(context, listOf(source))
        }
        val loadDeadline = System.currentTimeMillis() + 120_000L
        while ((vm.uiState.value.isLoading || vm.uiState.value.items.isEmpty()) && System.currentTimeMillis() < loadDeadline) Thread.sleep(200)
        assumeTrue("the view model could not read the fixture", vm.uiState.value.items.size == 1)
        if (replace) instrumentation.runOnMainSync { vm.toggleReplaceOriginals() }
        assertEquals(replace, vm.uiState.value.replaceOriginals)
        assertTrue("Backup before replace must be on", vm.uiState.value.backupBeforeReplace)
        instrumentation.runOnMainSync { vm.startCompression(context) }
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        Thread.sleep(1_000)
        while (vm.uiState.value.isCompressing && System.currentTimeMillis() < deadline) Thread.sleep(500)
        assertFalse("the batch did not finish in 30 minutes", vm.uiState.value.isCompressing)
        val records = latestSession(startedAt)
        assertTrue("no session written", records.isNotEmpty())
        val run = Run(vm.uiState.value.items.single(), records)
        val settings = records.last { it.optString("type") == "run_identity" }.getJSONObject("batchSettings")
        assertEquals("a harness batch says so", scope.runLabel, settings.optString("testHarnessRun"))
        println("pllab-batch $label ${run.describe()}")
        return run
    }

    @Test
    fun a_aSmallerCandidateHasItsAudioEndPaddingRepairedAndIsAccepted() {
        val source = File(context.cacheDir, "pllab_fixture_${copies.runId}.mp4").also { scratch += it }
        copies.readShellFile(fixturePath).use { input -> source.outputStream().use { input.copyTo(it) } }
        assertEquals(fixtureSha, source.inputStream().use { ReplacementContentProof.sha256(it) })

        val run = runBatch(Uri.fromFile(source), replace = false, label = "padding")
        assertEquals("the batch changed its source", fixtureSha, source.inputStream().use { ReplacementContentProof.sha256(it) })
        val repaired = run.stages("finalize").filter { it.optBoolean("restoredAudioEndPadding", false) }
        val accepted = run.terminal == BatchTerminalResult.TRANSCODED_SMALLER.name
        if (!accepted || repaired.isEmpty()) {
            copies.record("end_padding_repair", "INCOMPLETE", mapOf("observed" to run.describe()))
        }
        assumeTrue("no smaller candidate was accepted for this fixture on this phone: ${run.describe()}", accepted)
        assumeTrue("the accepted candidate did not need the end padding repair: ${run.describe()}", repaired.isNotEmpty())
        val repair = repaired.last()
        assertEquals(0, repair.optInt("audioEndPaddingSource", -1))
        assertTrue("the candidate's copy read no extra padding before the rewrite", repair.optInt("audioEndPaddingBefore", 0) > 0)
        assertTrue(run.job.optBoolean("countsAsRealCompression"))
        assertTrue(run.job.optBoolean("pixelCertified"))
        assertTrue(run.job.getLong("outputSize") in 1 until run.job.getLong("sourceSize"))
        copies.record("end_padding_repair", "PASS", mapOf(
            "sourceBytes" to run.job.getLong("sourceSize"), "outputBytes" to run.job.getLong("outputSize"),
            "paddingBefore" to repair.optInt("audioEndPaddingBefore"), "paddingSource" to repair.optInt("audioEndPaddingSource"),
            "operation" to repair.optString("operation"), "audio" to run.job.opt("audioPreservation")
        ))
    }

    /** This app's own row named [name] in Movies/Compressor/, as the backup is saved; null when there is none. */
    private fun ownBackupRow(name: String): Uri? {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name, context.packageName, "Movies/Compressor/"))
        }
        return context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use {
            if (it.moveToFirst()) android.content.ContentUris.withAppendedId(collection, it.getLong(0)) else null
        }
    }

    @Test
    fun b_aHarnessBatchReplacesItsDisposableCopyOnlyAfterAProvenBackup() {
        val c = copies.createDisposableCopy("batch", fixturePath, fixtureSha, fixtureBytes)
        copies.requireDisposable(c, fixtureSha)
        val run = runBatch(c, replace = true, label = "replace")
        val replaced = run.stages("replace").lastOrNull()
        if (run.row.status != BatchItemStatus.Replaced) {
            copies.record("batch_replace", "INCOMPLETE", mapOf("observed" to run.describe(), "replace" to replaced?.optString("reasonCode")))
        }
        assumeTrue("the batch accepted no candidate, so it replaced nothing: ${run.describe()}", run.row.status == BatchItemStatus.Replaced)
        assertEquals(ReplacementTransaction.COMMITTED, replaced?.optString("reasonCode"))
        assertEquals(ReplacementTransaction.COMMITTED, run.row.replacementOutcome)
        // The backup was saved first, and it is the fixture's bytes.
        val base = run.row.originalName.substringBeforeLast(".")
        val backup = ownBackupRow("${base}_Backup.mp4")
        assertNotNull("no backup of the copy was saved to Movies/Compressor", backup)
        assertEquals(fixtureSha, copies.digest(backup!!)?.sha256)
        // C holds the accepted output.
        val output = File(checkNotNull(run.row.outputPath))
        assertEquals(output.inputStream().use { ReplacementContentProof.sha256(it) }, copies.digest(c)?.sha256)
        copies.record("batch_replace", "PASS", mapOf(
            "outcome" to replaced?.optString("reasonCode"), "backup" to "read back as the fixture",
            "copyHolds" to "the accepted output", "outputBytes" to output.length()
        ))
        // The backup is a copy of a synthetic file this test made: remove it from the user's gallery folder.
        context.contentResolver.delete(backup, null, null)
    }
}
