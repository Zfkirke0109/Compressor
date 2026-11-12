package compress.joshattic.us

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import compress.joshattic.us.quality.SaferRungRetry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * The fallback a marginal trial takes when its first full encode is rejected, with real encodes.
 *
 * A marginal trial is a plan whose ladder measured a lower ratio that only cleared the bar and a
 * higher one that passed with the selection margin: the smaller encode is tried first, and if it
 * is rejected the safer measured ratio is encoded once. The Secure Folder run of v1.6.287 selected
 * one real trial (job_1e25c8805630: 0.75 marginal, 0.80 passed) and its 0.75 encode certified, so
 * the 0.80 fallback never ran. A real first-encode failure cannot be produced on demand, so these
 * tests refuse the first candidate with a [CandidateVeto] and let production do the rest:
 *
 *  - the refused candidate is real (encoded, finalized, structurally verified) and is discarded;
 *  - SaferRungRetry decides the retry with its usual limits;
 *  - the second encode is real, and is verified, certified against the unchanged thresholds and
 *    accepted or kept as the original by the unchanged rules. Nothing here decides its verdict.
 *
 * Needs `-e sourceVideo <path>`, as PipelineDeviceTest does, and a source whose ladder selects a
 * marginal trial on this device with its current learned state. Without either, a test is skipped
 * (reported incomplete), never passed. Two controls run alongside: a refusal without the retry
 * experiment keeps the original after one encode, and a second refusal never starts a third.
 *
 * What it changes on the device, and puts back: the opt-in safer-rung retry switch. It reads a
 * copy of the clip in the app's cache; Replace Originals must be off and is asserted off. Each
 * batch writes its usual diagnostics session, marked `testCandidateVeto`, and the real second
 * encode's result reaches the learned profile exactly as it would in the app. The refused attempt
 * writes nothing there. Starting a batch clears the app's cached outputs, as it does in the app:
 * run it when no results are waiting to be saved.
 *
 *   scripts/device/run-device-checks.sh --fallback CLIP.mp4 [ANDROID_USER]
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SaferRungFallbackDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val scratch = mutableListOf<File>()
    private val outputDir get() = File(context.cacheDir, "batch_compressed_videos")
    private var saferRetryBefore = false
    private var running: BatchCompressorViewModel? = null

    @Before
    fun rememberSettings() {
        saferRetryBefore = EncoderExperiments.isSaferRungRetryEnabled(context)
    }

    @After
    fun tearDown() {
        // A batch still running after a timeout holds the settings lock: end it first.
        running?.let { vm ->
            if (vm.uiState.value.isCompressing) {
                instrumentation.runOnMainSync { vm.cancelCompression() }
                val deadline = System.currentTimeMillis() + 60_000L
                while (vm.uiState.value.isCompressing && System.currentTimeMillis() < deadline) Thread.sleep(200)
            }
        }
        val restored = EncoderExperiments.setSaferRungRetryEnabled(context, saferRetryBefore)
        scratch.forEach { runCatching { it.delete() } }
        assertTrue("could not restore the safer-rung retry switch to $saferRetryBefore", restored)
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sourceCopy(): File {
        val path = InstrumentationRegistry.getArguments().getString("sourceVideo")
        assumeTrue("pass -e sourceVideo <path> (see PipelineDeviceTest)", !path.isNullOrBlank())
        val source = File(context.cacheDir, "fallback_test_source.mp4").also { scratch += it }
        val pfd = instrumentation.uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> source.outputStream().use { input.copyTo(it) } }
        assumeTrue("could not read $path", source.length() > 0L)
        return source
    }

    /** One finished batch over [source]: its session records and what it left behind. */
    private class Batch(
        val records: List<JSONObject>, val job: JSONObject, val stages: List<JSONObject>,
        val refused: List<Pair<Int, Double>>, val leftovers: List<File>
    ) {
        fun stage(name: String, attemptIndex: Int? = null) = stages.filter {
            it.optString("stage") == name && (attemptIndex == null || it.optInt("attemptIndex", -1) == attemptIndex)
        }
        /** The certification of an attempt's output. A failed bitrate-floor recovery writes its own event. */
        fun certification(attemptIndex: Int) = stage("certify", attemptIndex).filter { it.optString("scope") != "floor_recovery" }
        /** The probe rung an attempt relied on, by the identity its ledger entry carries. */
        fun rungOf(attemptIndex: Int): JSONObject {
            val entry = ledger[attemptIndex - 1]
            assertFalse("attempt $attemptIndex's ledger entry names no probe rung", entry.isNull("probeRungId"))
            val id = entry.getString("probeRungId")
            return stage("probe_rung").lastOrNull { it.optString("rungId") == id }
                ?: throw AssertionError("attempt $attemptIndex relied on rung $id, which this ladder did not record")
        }
        /** Files a full encode writes: the candidate, its metadata remux and a first-timestamp repair. */
        val candidatesLeft: List<File> get() = leftovers.filter {
            it.name.endsWith("_Compressed.mp4") || it.name.endsWith("_remux.mp4") || it.name.startsWith("pl-timing-")
        }
        /** Every full encode this job started; empty when it never encoded. */
        val ledger: List<JSONObject> get() = job.optJSONArray("attemptLedger")?.let { a -> (0 until a.length()).map(a::getJSONObject) }.orEmpty()
        val identity: JSONObject get() = records.last { it.optString("type") == "run_identity" }
        val learnedUpdates: List<JSONObject> get() = records.filter {
            it.optString("type") == "learned_state_update" && it.optString("jobId") == job.optString("jobId")
        }
    }

    private fun latestSession(since: Long): List<JSONObject> {
        val dirs = File(context.filesDir, "diagnostics").listFiles { f -> f.isDirectory && f.name.startsWith("batch_") }.orEmpty()
        val newest = dirs.filter { it.name.removePrefix("batch_").toLongOrNull()?.let { t -> t >= since } == true }
            .maxByOrNull { it.name.removePrefix("batch_").toLong() } ?: return emptyList()
        return File(newest, "session.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    }

    /**
     * Runs one real Perceptually Lossless batch over [source] in a view model built with a veto
     * that refuses the attempts in [refuseAttempts], and checks what must hold for every such
     * batch: Replace Originals off, the source byte-identical afterwards, the session marked as a
     * test's, every attempt closed and nothing learned from a refused attempt.
     */
    private fun runBatch(source: File, saferRetry: Boolean, refuseAttempts: Set<Int>): Batch {
        assertTrue("could not set the safer-rung retry switch", EncoderExperiments.setSaferRungRetryEnabled(context, saferRetry))
        val before = sha256(source)
        val refused = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Double>>())
        val veto = CandidateVeto { attemptIndex, targetRatio ->
            if (attemptIndex in refuseAttempts) {
                refused += attemptIndex to targetRatio
                "refused by SaferRungFallbackDeviceTest to exercise what follows a rejected encode"
            } else null
        }
        val startedAt = System.currentTimeMillis()
        val vm = BatchCompressorViewModel(context.applicationContext as Application, veto).also { running = it }
        instrumentation.runOnMainSync {
            vm.setQuality("Perceptually Lossless")
            vm.setExhaustivePerceptualLossless(true)
            vm.loadUris(context, listOf(Uri.fromFile(source)))
        }
        val loadDeadline = System.currentTimeMillis() + 120_000L
        while ((vm.uiState.value.isLoading || vm.uiState.value.items.isEmpty()) && System.currentTimeMillis() < loadDeadline) Thread.sleep(200)
        assumeTrue("the ViewModel could not read the clip", vm.uiState.value.items.isNotEmpty())
        assertFalse("replace-original must be off for this test", vm.uiState.value.replaceOriginals)
        assertEquals(1, vm.uiState.value.items.size)
        instrumentation.runOnMainSync { vm.startCompression(context) }
        // Two full encodes of a long clip, each within the retry's own 20-minute item budget.
        val deadline = System.currentTimeMillis() + 60 * 60_000L
        Thread.sleep(1_000)
        while (vm.uiState.value.isCompressing && System.currentTimeMillis() < deadline) Thread.sleep(500)
        assertFalse("batch did not finish in 60 minutes", vm.uiState.value.isCompressing)

        val records = latestSession(startedAt)
        assertTrue("no session written", records.isNotEmpty())
        val job = records.last { it.optString("type") == "job" }
        val jobId = job.getString("jobId")
        val stages = records.filter { it.optString("type") == "stage" && it.optString("jobId") == jobId }
        // Starting a batch clears this directory, so whatever is in it now is this batch's.
        val leftovers = outputDir.listFiles().orEmpty().toList().also { scratch += it }
        val batch = Batch(records, job, stages, refused.toList(), leftovers)
        println(
            "b287-fallback saferRetry=$saferRetry refuse=$refuseAttempts refused=${batch.refused} terminal=${job.optString("terminal")} " +
                "attempts=${job.opt("attempts")} probed=${job.opt("probedRatios")} " +
                "rungs=${batch.stage("probe_rung").joinToString { "${it.opt("ratio")}:${it.optString("verdict")}" }} " +
                "retry=${batch.stage("retry").joinToString { "${it.optString("reasonCode")}:${it.opt("denial")}:${it.opt("usedRatio")}->${it.opt("saferRatio")}" }} " +
                "certify=${batch.stage("certify").joinToString { "${it.opt("attemptIndex")}:${it.optString("reasonCode")}:${it.opt("certMean")}" }} " +
                "left=${leftovers.map { it.length() }}"
        )

        assertEquals("the run touched its source", before, sha256(source))
        val settings = batch.identity.getJSONObject("batchSettings")
        assertFalse(settings.getBoolean("replaceOriginals"))
        assertTrue("a batch run with a veto must say so", settings.getBoolean("testCandidateVeto"))
        // Every attempt was closed by some path, and the ledger is the retry denominator.
        assertEquals(batch.ledger.size, job.optInt("attemptsStarted", 0))
        batch.ledger.forEach { assertNotEquals(AttemptLedger.OPEN, it.getString("outcome")) }
        // A refused attempt is recorded as refused, never as a measurement, and teaches nothing.
        for ((attemptIndex, ratio) in batch.refused) {
            val certify = batch.certification(attemptIndex).single()
            assertEquals(StageEvent.Reason.CERT_TEST_REJECTED, certify.getString("reasonCode"))
            assertFalse(certify.getBoolean("accepted"))
            assertFalse(certify.getBoolean("pixelCertified"))
            assertEquals(0, certify.getInt("certWindows"))
            assertTrue(certify.getString("injectedRejection").isNotBlank())
            assertEquals(ratio, certify.getDouble("usedRatio"), 1e-9)
            val entry = batch.ledger[attemptIndex - 1]
            assertEquals(CandidateVeto.WIRE, entry.getString("outcome"))
            assertEquals(ratio, entry.getDouble("ratio"), 1e-9)
            // It was a real candidate: encoded, finalized and structurally verified before it was refused.
            assertTrue("no candidate was produced for attempt $attemptIndex", entry.getLong("candidateBytes") > 0L)
            assertTrue(batch.stage("encode", attemptIndex).any { it.optString("reasonCode") == StageEvent.Reason.ENCODE_COMPLETED })
            assertEquals(StageEvent.Reason.STRUCTURAL_PASSED, batch.stage("verify", attemptIndex).single().getString("reasonCode"))
            assertTrue(
                "the refused attempt $attemptIndex wrote to the learned profile",
                batch.learnedUpdates.none { it.optInt("attemptIndex", -1) == attemptIndex }
            )
        }
        // No attempt the test did not refuse is recorded as refused.
        assertEquals(batch.refused.size, batch.stage("certify").count { it.optString("reasonCode") == StageEvent.Reason.CERT_TEST_REJECTED })
        assertEquals(batch.refused.size, batch.ledger.count { it.getString("outcome") == CandidateVeto.WIRE })
        return batch
    }

    /** The original was kept after a refusal: no output is recorded and nothing is left in the cache. */
    private fun assertOriginalKept(batch: Batch) {
        assertFalse(batch.job.getBoolean("finalAccepted"))
        assertEquals(0L, batch.job.getLong("outputSize"))
        assertFalse(batch.job.optBoolean("countsAsRealCompression", false))
        assertEquals("a discarded candidate was left in the cache", emptyList<String>(), batch.leftovers.map { "${it.name.takeLast(16)} ${it.length()} bytes" })
        assertEquals(StageEvent.Reason.REJECTED, batch.stage("accept").last().getString("reasonCode"))
    }

    /** The retry event of a refused marginal trial, or a skip when this source and device gave none. */
    private fun allowedRetry(batch: Batch): JSONObject {
        assumeTrue(
            "attempt 1 never reached certification (terminal ${batch.job.optString("terminal")}): nothing to refuse",
            batch.refused.any { it.first == 1 }
        )
        val retry = batch.stage("retry").firstOrNull()
        assumeTrue(
            "this source's ladder did not select a marginal trial with a measured safer rung on this device " +
                "(rungs ${batch.stage("probe_rung").joinToString { "${it.opt("ratio")}:${it.optString("verdict")}" }})",
            retry != null
        )
        if (retry!!.getString("reasonCode") != StageEvent.Reason.RETRY_ALLOWED) {
            val denial = retry.optString("denial")
            // The device's state at that moment, not the code under test.
            assumeTrue("the retry was denied for a resource reason: $denial", denial !in setOf("item_time_budget", "thermal", "storage"))
            fail("a refused marginal trial was denied its measured safer rung: $denial")
        }
        return retry
    }

    @Test
    fun a_theSaferMeasuredRungIsEncodedAndPassesEveryGateOnItsOwn() {
        val batch = runBatch(sourceCopy(), saferRetry = true, refuseAttempts = setOf(1))
        val retry = allowedRetry(batch)
        val usedRatio = retry.getDouble("usedRatio")
        val saferRatio = retry.getDouble("saferRatio")
        assertEquals(listOf(1), batch.refused.map { it.first })
        assertEquals(usedRatio, batch.refused.single().second, 1e-9)
        assertTrue("the fallback $saferRatio is not above the refused $usedRatio", saferRatio > usedRatio)
        assertEquals(CandidateVeto.WIRE, retry.getString("trigger"))
        assertEquals(1, retry.getInt("attemptIndex"))
        // Decided once for the refused attempt. A real failure of the second encode may record its
        // own decision, which can only be a denial: there is one retry per item.
        assertEquals(1, batch.stage("retry", 1).size)
        assertEquals(1, batch.stage("retry").count { it.optString("reasonCode") == StageEvent.Reason.RETRY_ALLOWED })

        // The second attempt is its own encode at the safer rung, under that rung's identity.
        assertEquals(2, batch.ledger.size)
        assertEquals(2, batch.job.getInt("attemptsStarted"))
        assertTrue("a third attempt started", batch.stages.none { it.optInt("attemptIndex", 0) > 2 })
        assertEquals(usedRatio, batch.ledger[0].getDouble("ratio"), 1e-9)
        assertEquals(saferRatio, batch.ledger[1].getDouble("ratio"), 1e-9)
        // The plan was a marginal trial: the refused rung only cleared the bar, the fallback's passed.
        val trialRung = batch.rungOf(1)
        val saferRung = batch.rungOf(2)
        assertEquals("marginal", trialRung.getString("verdict"))
        assertEquals("passed", saferRung.getString("verdict"))
        assertEquals(usedRatio, trialRung.getDouble("ratio"), 1e-9)
        assertEquals(saferRatio, saferRung.getDouble("ratio"), 1e-9)
        // Same ladder, and the fallback is encoded with the request its rung was measured with.
        assertEquals(trialRung.getString("outputMime"), saferRung.getString("outputMime"))
        assertEquals(trialRung.getString("plannedWindowIds"), saferRung.getString("plannedWindowIds"))
        assertEquals(saferRung.getString("configId"), batch.ledger[1].getString("configId"))
        assertNotEquals(batch.ledger[0].getString("configId"), batch.ledger[1].getString("configId"))
        val started = batch.stage("encode", 2).single { it.optString("reasonCode") == StageEvent.Reason.ENCODE_STARTED }
        assertEquals(saferRatio, started.getDouble("ratio"), 1e-9)
        assertEquals(saferRung.getString("rungId"), started.getString("probeRungId"))
        assertTrue(batch.stage("encode", 2).any { it.optString("reasonCode") == StageEvent.Reason.ENCODE_COMPLETED })
        assertTrue(batch.stage("finalize", 2).isNotEmpty())
        val verify = batch.stage("verify", 2).single()

        // Its verdict is the device's. Whatever it is, it was reached by the production gates.
        val certify = batch.certification(2).singleOrNull()
        if (verify.getString("reasonCode") == StageEvent.Reason.STRUCTURAL_PASSED) {
            assertNotNull("a structurally verified retry was not certified", certify)
            assertFalse(certify!!.has("injectedRejection"))
            assertEquals(saferRatio, certify.getDouble("usedRatio"), 1e-9)
        }
        if (batch.ledger[1].getString("outcome") == AttemptLedger.ACCEPTED) {
            // Accepted only on a complete, passing sample of the real output: plain VMAF v0.6.1 on
            // every planned window, at least 12 compared frames in each, mean 95.5 / p5 91 / min 84.
            assertEquals(StageEvent.Reason.CERT_PASSED, certify!!.getString("reasonCode"))
            assertTrue(certify.getBoolean("accepted"))
            assertTrue(certify.getBoolean("pixelCertified"))
            assertEquals(saferRung.getString("plannedWindowIds"), certify.getString("certWindowIds"))
            fun values(key: String) = certify.getString(key).split(";").map { it.toDouble() }
            assertEquals(certify.getInt("certWindows"), values("certFrames").size)
            assertTrue("a window held fewer than 12 compared frames: ${certify.getString("certFrames")}", values("certFrames").all { it >= 12 })
            assertTrue("a window mean is below 95.5: ${certify.getString("certMean")}", values("certMean").all { it >= 95.5 })
            assertTrue("a window p5 is below 91: ${certify.getString("certP5")}", values("certP5").all { it >= 91.0 })
            assertTrue("a window minimum is below 84: ${certify.getString("certMin")}", values("certMin").all { it >= 84.0 })
            val accept = batch.stage("accept").last()
            assertEquals(StageEvent.Reason.ACCEPTED, accept.getString("reasonCode"))
            assertEquals(2, accept.getInt("attemptIndex"))
            assertEquals(1, accept.getInt("retries"))
            assertTrue(batch.job.getBoolean("finalAccepted"))
            assertTrue(batch.job.getBoolean("pixelCertified"))
            assertTrue(batch.job.getBoolean("verified"))
            assertEquals(saferRatio, batch.job.getDouble("pixelProvenRatio"), 1e-9)
            val outputSize = batch.job.getLong("outputSize")
            assertTrue("the accepted output is not smaller than its source", outputSize in 1 until batch.job.getLong("sourceSize"))
            // One file remains, the accepted output: the refused candidate is gone.
            assertEquals(listOf(outputSize), batch.leftovers.map { it.length() })
            // The real result, and only it, reached the learned profile.
            assertTrue(batch.learnedUpdates.any { it.optInt("attemptIndex", -1) == 2 })
        } else {
            // A real rejection of the second encode, by whichever production rule made it: nothing
            // is counted as a compression and no encode candidate is left behind.
            assertFalse(batch.job.optBoolean("countsAsRealCompression", false))
            assertFalse(batch.job.optBoolean("pixelCertified", false))
            assertEquals("a rejected candidate was left in the cache", emptyList<Long>(), batch.candidatesLeft.map { it.length() })
        }
    }

    @Test
    fun b_aRefusalWithoutTheRetryExperimentKeepsTheOriginalAfterOneEncode() {
        val batch = runBatch(sourceCopy(), saferRetry = false, refuseAttempts = setOf(1))
        assumeTrue(
            "attempt 1 never reached certification (terminal ${batch.job.optString("terminal")}): nothing to refuse",
            batch.refused.any { it.first == 1 }
        )
        assertEquals(listOf(1), batch.refused.map { it.first })
        assertTrue("a retry was allowed with the experiment off", batch.stage("retry").none { it.optString("reasonCode") == StageEvent.Reason.RETRY_ALLOWED })
        assertEquals(1, batch.ledger.size)
        assertEquals(1, batch.job.getInt("attemptsStarted"))
        assertTrue("a second attempt started", batch.stages.none { it.optInt("attemptIndex", 0) > 1 })
        assertOriginalKept(batch)
        // Kept as "could not be verified", not as a measured loss, and certification did not run.
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX.name, batch.job.getString("terminal"))
        assertTrue(batch.job.isNull("certificationDecision"))
        assertEquals(CertificationStatus.SKIPPED_TEST_VETO, batch.job.getString("certificationStatus"))
        assertTrue(batch.job.getString("fallbackReason").contains("refused by an instrumented test"))
        assertTrue(batch.learnedUpdates.none { it.optInt("attemptIndex", -1) >= 1 })
    }

    @Test
    fun c_aSecondRefusalKeepsTheOriginalAndNeverStartsAThirdEncode() {
        val batch = runBatch(sourceCopy(), saferRetry = true, refuseAttempts = setOf(1, 2))
        val retry = allowedRetry(batch)
        assertEquals(listOf(1, 2), batch.refused.map { it.first })
        assertEquals(retry.getDouble("usedRatio"), batch.refused[0].second, 1e-9)
        assertEquals(retry.getDouble("saferRatio"), batch.refused[1].second, 1e-9)
        // One retry, decided once, within the per-item limit; the second refusal is not retried.
        assertEquals(1, batch.stage("retry", 1).size)
        assertEquals(1, batch.stage("retry").count { it.optString("reasonCode") == StageEvent.Reason.RETRY_ALLOWED })
        assertEquals(SaferRungRetry.MAX_RETRIES_PER_ITEM, batch.ledger.size - 1)
        assertEquals(2, batch.ledger.size)
        assertEquals(2, batch.job.getInt("attemptsStarted"))
        assertTrue("a third attempt started", batch.stages.none { it.optInt("attemptIndex", 0) > 2 })
        assertOriginalKept(batch)
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX.name, batch.job.getString("terminal"))
        assertTrue(batch.learnedUpdates.none { it.optInt("attemptIndex", -1) >= 1 })
    }
}
