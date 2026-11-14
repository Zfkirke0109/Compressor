package compress.joshattic.us

import android.app.Application
import android.content.Context
import android.net.Uri
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * One ordinary Perceptually Lossless batch, Replace Originals off, over disposable copies of every
 * baseline the harness staged: the bounded corpus. Each copy is made in the app's cache from its B
 * (read through the shell) and checked against B's digest first.
 *
 * Nothing here expects a clip to compress. What must hold for every item, whatever it ends as: the
 * copy it was made from is byte-identical afterwards, the row ends in a terminal state, an accepted
 * output is smaller than its source, nothing is replaced, and a row that took a second attempt says
 * which and why. Each item's outcome is recorded for the report. Planned from the user's learned
 * state, learning into the test's own store (HarnessScope).
 *
 *   scripts/device/safe-copy-harness.sh run-copy-tests --run DIR --cases corpus
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CorpusBatchDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val outputDir get() = File(context.cacheDir, "batch_compressed_videos")
    private val scratch = mutableListOf<File>()
    private var running: BatchCompressorViewModel? = null

    @After
    fun tearDown() {
        running?.let { vm ->
            if (vm.uiState.value.isCompressing) {
                instrumentation.runOnMainSync { vm.cancelCompression() }
                val deadline = System.currentTimeMillis() + 60_000L
                while (vm.uiState.value.isCompressing && System.currentTimeMillis() < deadline) Thread.sleep(200)
            }
        }
        outputDir.listFiles().orEmpty().forEach { runCatching { it.delete() } }
        scratch.forEach { runCatching { it.delete() } }
    }

    @Test
    fun everyCopyEndsSomewhereHonestAndNoneIsChanged() {
        val args = HarnessCopies.args("pllabRunId", "pllabBaselines")
        val copies = HarnessCopies(context, args.getValue("pllabRunId"))
        assumeTrue("unsaved outputs are waiting in the app; save or discard them first", outputDir.listFiles().orEmpty().isEmpty())
        // "path:sha256:bytes,path:sha256:bytes": the harness's B files, in its run folder.
        val baselines = args.getValue("pllabBaselines").split(',').map { triple ->
            val (path, sha, bytes) = triple.split(':').also { require(it.size == 3) { "not path:sha256:bytes: $triple" } }
            Triple(path, sha.lowercase(), bytes.toLong())
        }
        val sources = baselines.mapIndexed { k, (path, sha, bytes) ->
            val file = File(context.cacheDir, "pllab_corpus_${copies.runId}_${k + 1}.mp4").also { scratch += it }
            copies.readShellFile(path).use { input -> file.outputStream().use { input.copyTo(it) } }
            assertEquals("copy ${k + 1} is not its baseline's length", bytes, file.length())
            assertEquals("copy ${k + 1} is not its baseline's bytes", sha, file.inputStream().use { ReplacementContentProof.sha256(it) })
            file to sha
        }

        val app = context.applicationContext as Application
        val scope = HarnessScope("${copies.runId}-corpus", OverlayProfileStore(SmartPerceptualProfileEngine.SharedPreferencesProfileStore(app)))
        val vm = BatchCompressorViewModel(app, CandidateVeto.NONE, scope).also { running = it }
        val startedAt = System.currentTimeMillis()
        instrumentation.runOnMainSync {
            vm.setQuality("Perceptually Lossless")
            vm.setExhaustivePerceptualLossless(true)
            vm.loadUris(context, sources.map { Uri.fromFile(it.first) })
        }
        val loadDeadline = System.currentTimeMillis() + 300_000L
        while ((vm.uiState.value.isLoading || vm.uiState.value.items.size < sources.size) && System.currentTimeMillis() < loadDeadline) Thread.sleep(250)
        assertEquals("the view model did not load every copy", sources.size, vm.uiState.value.items.size)
        assertFalse("Replace Originals must be off for the corpus", vm.uiState.value.replaceOriginals)
        instrumentation.runOnMainSync { vm.startCompression(context) }
        // Bounded: about 20 minutes a clip, as the safer-rung retry's own item budget allows.
        val deadline = System.currentTimeMillis() + sources.size * 20 * 60_000L
        Thread.sleep(1_000)
        while (vm.uiState.value.isCompressing && System.currentTimeMillis() < deadline) Thread.sleep(1_000)
        assertFalse("the corpus batch did not finish in time", vm.uiState.value.isCompressing)

        val dirs = File(context.filesDir, "diagnostics").listFiles { f -> f.isDirectory && f.name.startsWith("batch_") }.orEmpty()
        val session = dirs.filter { it.name.removePrefix("batch_").toLongOrNull()?.let { t -> t >= startedAt } == true }
            .maxByOrNull { it.name.removePrefix("batch_").toLong() }
        assertNotNull("no session written", session)
        val records = File(session, "session.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        val settings = records.last { it.optString("type") == "run_identity" }.getJSONObject("batchSettings")
        assertEquals(scope.runLabel, settings.optString("testHarnessRun"))
        assertFalse(settings.getBoolean("replaceOriginals"))
        val jobs = records.filter { it.optString("type") == "job" }

        // Rows are matched to their copies by URI: the view model may order them its own way.
        val byUri = sources.associateBy { Uri.fromFile(it.first).toString() }
        vm.uiState.value.items.forEachIndexed { k, row ->
            val (file, sha) = checkNotNull(byUri[row.sourceUri.toString()]) { "row ${k + 1} is not one of the copies" }
            assertEquals("copy ${k + 1} was changed by the batch", sha, file.inputStream().use { ReplacementContentProof.sha256(it) })
            assertTrue("row ${k + 1} did not end: ${row.status}", ItemProgressModel.isTerminal(row))
            assertTrue("row ${k + 1} replaced something", row.status != BatchItemStatus.Replaced && row.replacementOutcome == null)
            if (row.terminalResult?.countsAsRealCompression == true) {
                assertTrue("row ${k + 1} was accepted without being smaller", row.outputSize in 1 until row.originalSize)
            }
            row.attemptContext?.let { assertEquals("row ${k + 1}", 2, it.ordinal) }
            val job = jobs.firstOrNull { it.optLong("sourceSize", -1L) == row.originalSize }
            copies.record("corpus_item_${k + 1}", "OBSERVED", mapOf(
                "terminal" to row.terminalResult?.name, "status" to row.status.name,
                "sourceBytes" to row.originalSize, "outputBytes" to row.outputSize,
                "attempt" to row.attemptContext?.label, "attempts" to job?.opt("attempts"),
                "resolution" to "${row.originalWidth}x${row.originalHeight}", "fps" to row.originalFps.toInt()
            ))
        }
        copies.record("corpus_batch", "PASS", mapOf(
            "items" to sources.size,
            "accepted" to vm.uiState.value.items.count { it.terminalResult?.countsAsRealCompression == true }
        ))
    }
}
