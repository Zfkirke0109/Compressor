package compress.joshattic.us

import android.app.Application
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import compress.joshattic.us.quality.CertificationDecision
import compress.joshattic.us.quality.PerceptualQualityProber
import compress.joshattic.us.quality.VmafNative
import compress.joshattic.us.quality.scoredWindows
import kotlinx.coroutines.runBlocking
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
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * b177 device checks of what the JVM cannot run: the platform extractor on a file with no track
 * duration, and the real BatchCompressorViewModel pipeline writing the b177 evidence fields.
 *
 * The missing-duration test uses the synthetic assets made by
 * scripts/device/make_missing_duration_fixture.py and always runs. The batch test needs the same
 * `sourceVideo` argument as PipelineDeviceTest and is skipped (not passed) without it.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ProductionEvidenceDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val scratch = mutableListOf<File>()

    @After
    fun tearDown() {
        scratch.forEach { runCatching { it.delete() } }
    }

    private fun asset(name: String): File {
        val out = File(context.cacheDir, "fixture_$name").also { scratch += it }
        instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
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

    /** What the platform reports for the fixture, printed so the run log records the assumption. */
    private fun platformDurations(file: File): String {
        val retriever = MediaMetadataRetriever().use {
            it.setDataSource(file.absolutePath)
            it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        }
        val extractor = MediaExtractor()
        val trackDurationUs = try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.let { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else null }
        } finally {
            extractor.release()
        }
        return "retrieverDurationMs=$retriever extractorTrackDurationUs=$trackDurationUs"
    }

    @Test
    fun certificationWindowsAreSpreadAcrossFilesWithNoTrackDuration() = runBlocking {
        assumeTrue("native VMAF not loaded", VmafNative.isAvailable)
        for (name in listOf("zero_track_duration.mp4", "fragmented_no_duration.mp4")) {
            val file = asset(name)
            println("b177-fixture $name ${platformDurations(file)}")
            // The container duration the app falls back to: 12 s, as generated.
            val outcome = PerceptualQualityProber(context).certify(Uri.fromFile(file), file, 12_000L, 10.0)
            val windows = outcome.scoredWindows
            assertNotNull("$name: nothing scored ($outcome)", windows)
            val starts = windows!!.mapNotNull { it.windowStartUs }
            println("b177-fixture $name windowStartsUs=$starts decision=${CertificationDecision.of(outcome).wire}")
            // Before 75e3b96 an unknown duration clamped every seek target to 0.
            assertEquals("$name: windows collapsed onto one start: $starts", starts.size, starts.toSet().size)
            assertTrue("$name: no window past the middle: $starts", starts.maxOrNull()!! >= 6_000_000L)
            assertTrue("$name: a window starts outside the clip: $starts", starts.all { it in 1L until 12_000_000L })
            // The file against itself: identical frames.
            assertEquals(CertificationDecision.PASSED, CertificationDecision.of(outcome))
        }
    }

    private fun latestSession(since: Long): List<JSONObject> {
        val dirs = File(context.filesDir, "diagnostics").listFiles { f -> f.isDirectory && f.name.startsWith("batch_") }.orEmpty()
        val newest = dirs.filter { it.name.removePrefix("batch_").toLongOrNull()?.let { t -> t >= since } == true }
            .maxByOrNull { it.name.removePrefix("batch_").toLong() } ?: return emptyList()
        return File(newest, "session.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    }

    @Test
    fun aRealPerceptuallyLosslessBatchWritesTheB177EvidenceFields() {
        val path = InstrumentationRegistry.getArguments().getString("sourceVideo")
        assumeTrue("pass -e sourceVideo <path> (see PipelineDeviceTest)", !path.isNullOrBlank())
        val source = File(context.cacheDir, "evidence_test_source.mp4").also { scratch += it }
        val pfd = instrumentation.uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> source.outputStream().use { input.copyTo(it) } }
        assumeTrue("could not read $path", source.length() > 0L)
        val before = sha256(source)
        val startedAt = System.currentTimeMillis()

        val vm = BatchCompressorViewModel(context.applicationContext as Application)
        instrumentation.runOnMainSync {
            vm.setQuality("Perceptually Lossless")
            vm.loadUris(context, listOf(Uri.fromFile(source)))
        }
        val loadDeadline = System.currentTimeMillis() + 60_000L
        while ((vm.uiState.value.isLoading || vm.uiState.value.items.isEmpty()) && System.currentTimeMillis() < loadDeadline) Thread.sleep(200)
        assumeTrue("the ViewModel could not read the clip", vm.uiState.value.items.isNotEmpty())
        assertFalse("replace-original must be off for this test", vm.uiState.value.replaceOriginals)
        instrumentation.runOnMainSync { vm.startCompression(context) }
        val deadline = System.currentTimeMillis() + 20 * 60_000L
        Thread.sleep(1_000)
        while (vm.uiState.value.isCompressing && System.currentTimeMillis() < deadline) Thread.sleep(500)
        assertFalse("batch did not finish in 20 minutes", vm.uiState.value.isCompressing)

        val records = latestSession(startedAt)
        assertTrue("no session written", records.isNotEmpty())
        fun type(r: JSONObject) = r.optString("type")
        assertTrue("no run_identity record", records.any { type(it) == "run_identity" })
        val job = records.last { type(it) == "job" }
        println("b177-evidence job terminal=${job.optString("terminal")} attempts=${job.opt("attempts")} audio=${job.opt("audioPreservation")}")
        assertTrue("no source fingerprint", job.optString("sourceFingerprint").length == 64)
        if (!job.isNull("encodePlan")) {
            val ledger = job.getJSONArray("attemptLedger")
            assertEquals(ledger.length(), job.getInt("attemptsStarted"))
            assertTrue(job.optString("audioRequested") in setOf("copy", "reencode", "none"))
            // Every attempt was finished by some path.
            for (i in 0 until ledger.length()) assertFalse(ledger.getJSONObject(i).getString("outcome") == AttemptLedger.OPEN)
        }
        val stages = records.filter { type(it) == "stage" }
        assertTrue("stage events without attemptIndex", stages.all { it.has("attemptIndex") })
        if (!job.isNull("encodePlan")) {
            assertTrue(stages.any { it.optString("stage") == "encode" })
            assertTrue(stages.any { it.optString("stage") == "verify" })
        }
        if (!job.isNull("probedRatios")) assertTrue(stages.any { it.optString("stage") == "probe_rung" })
        records.filter { type(it) == "learned_state_update" }.forEach {
            assertFalse("a learned-state write names no job: $it", it.isNull("jobId"))
        }
        // Nothing about the run touched the source.
        assertEquals(before, sha256(source))
    }
}
