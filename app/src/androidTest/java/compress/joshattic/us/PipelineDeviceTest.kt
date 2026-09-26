package compress.joshattic.us

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import compress.joshattic.us.quality.CertificationDecision
import compress.joshattic.us.quality.PairScoreOutcome
import compress.joshattic.us.quality.PerceptualQualityProber
import compress.joshattic.us.quality.VmafNative
import compress.joshattic.us.quality.VmafPairScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real-device checks of what JVM tests cannot exercise: a Media3/MediaCodec export, the native
 * VMAF scorer, the phase transitions they drive, and that nothing touches the source.
 *
 * Needs one short SDR H.264 or HEVC clip with audio (10-30 s is plenty), passed by path:
 *
 *   adb push clip.mp4 /data/local/tmp/compressor_smoke.mp4
 *   ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.sourceVideo=/data/local/tmp/compressor_smoke.mp4
 *
 * The clip is copied into the app's cache through the shell, so this also works for a secondary
 * profile (user 150) run with `--user`. Without the argument every test is skipped, not passed.
 * See scripts/device/run-device-checks.sh.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PipelineDeviceTest {

    private lateinit var context: Context
    private lateinit var source: File
    private val scratch = mutableListOf<File>()

    private class ListBoard(items: List<BatchVideoItem>) : ItemBoard {
        val items = items.toMutableList()
        @Synchronized override fun item(index: Int): BatchVideoItem? = items.getOrNull(index)
        @Synchronized override fun update(index: Int, transform: (BatchVideoItem) -> BatchVideoItem) {
            items[index] = transform(items[index])
        }
    }

    @Before
    fun setUp() {
        val path = InstrumentationRegistry.getArguments().getString("sourceVideo")
        assumeTrue("pass -e sourceVideo <path> (see class doc)", !path.isNullOrBlank())
        context = InstrumentationRegistry.getInstrumentation().targetContext
        source = File(context.cacheDir, "device_test_source.mp4").also { scratch += it }
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> source.outputStream().use { input.copyTo(it) } }
        assumeTrue("could not read $path", source.length() > 0L)
    }

    @After
    fun tearDown() {
        scratch.forEach { runCatching { it.delete() } }
    }

    private fun durationMs(file: File): Long = MediaMetadataRetriever().use { r ->
        r.setDataSource(file.absolutePath)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
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

    private fun item(): BatchVideoItem = BatchVideoItem(
        sourceUri = Uri.fromFile(source), originalName = source.name, originalSize = source.length(),
        originalWidth = 0, originalHeight = 0, originalBitrate = 0, originalAudioBitrate = 0,
        originalFps = 30f, durationMs = durationMs(source)
    )

    /**
     * A real HEVC export of the source at [bitrate], reporting through [phases] exactly as
     * BatchCompressorViewModel.compressOne does: Media3's fraction while it runs, encodeFinished at the end.
     */
    private suspend fun export(bitrate: Int, phases: PhaseReporter, out: File): ExportResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build()
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        phases.encodeFinished()
                        if (cont.isActive) cont.resume(exportResult)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { transformer.cancel() }
            transformer.start(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source))).build(), out.absolutePath)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                while (cont.isActive) {
                    val holder = ProgressHolder()
                    val state = transformer.getProgress(holder)
                    phases.encodeProgress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else null, out.length())
                    delay(100)
                }
            }
        }
    }

    @Test
    fun productionScorerIsV061WithoutPhoneTransformAndScoresIdenticalFramesAs100() = runBlocking {
        assumeTrue("native VMAF not loaded", VmafNative.isAvailable)
        assertFalse(VmafPairScorer.PRODUCTION_PHONE_MODEL)
        val outcome = PerceptualQualityProber(context).certify(Uri.fromFile(source), source, durationMs(source))
        assertEquals(CertificationDecision.PASSED, CertificationDecision.of(outcome))
        // With one motion-context pair per window, identical frames score 100, not 97.43.
        (outcome as PairScoreOutcome.Scored).windows.forEach { assertTrue("min ${it.min}", it.min >= 99.95) }
    }

    @Test
    fun aRealEncodeGoesThroughPhasesAndIsNotCompleteAtOneHundredPercent() = runBlocking {
        val board = ListBoard(listOf(item()))
        val token = AttemptToken("device_test", 0, 1)
        board.update(0) { ItemProgressModel.begin(it, token, 0L) }
        val phases = PhaseReporter(board, token)
        val fractions = mutableListOf<Float?>()
        val out = File(context.cacheDir, "device_test_encode.mp4").also { scratch += it }
        phases.enter(ItemPhase.ENCODING)
        val watcher = launch { while (true) { fractions += board.item(0)!!.phaseFraction; delay(50) } }
        withTimeout(10 * 60_000L) { export(2_000_000, phases, out) }
        watcher.cancel()
        val row = board.item(0)!!
        assertEquals(ItemPhase.FINALIZING, row.phase)
        assertFalse(ItemProgressModel.isTerminal(row))
        assertEquals(0f, ItemProgressModel.batchFraction(board.items), 0f)
        assertTrue("no fraction may claim the item complete: $fractions", fractions.filterNotNull().all { it <= ItemProgressModel.MAX_RUNNING_FRACTION })
        // Certification reports real window counts.
        phases.enter(ItemPhase.CERTIFYING)
        val steps = mutableListOf<Pair<Int, Int>>()
        val outcome = PerceptualQualityProber(context).certify(Uri.fromFile(source), out, durationMs(source)) { d, t ->
            steps += d to t
            phases.certifyStep(d, t)
        }
        if (outcome is PairScoreOutcome.Scored) {
            assertEquals(outcome.windows.size, steps.last().first)
            assertTrue(ItemProgressModel.describe(board.item(0)!!).startsWith("Certifying pixels"))
        }
        assertNotEquals(CertificationDecision.UNAVAILABLE, CertificationDecision.of(outcome))
    }

    @Test
    fun theSourceIsByteIdenticalAfterADiscardedCandidate() = runBlocking {
        val before = sha256(source)
        val board = ListBoard(listOf(item()))
        val token = AttemptToken("device_test", 0, 1)
        board.update(0) { ItemProgressModel.begin(it, token, 0L) }
        val out = File(context.cacheDir, "device_test_discard.mp4").also { scratch += it }
        withTimeout(10 * 60_000L) { export(300_000, PhaseReporter(board, token), out) }
        // The discard path: the candidate is deleted, the item keeps its original.
        assertTrue(out.delete())
        board.update(0) { it.copy(status = BatchItemStatus.Skipped, terminalResult = BatchTerminalResult.SKIPPED_WOULD_DEGRADE, outputPath = null) }
        assertEquals(before, sha256(source))
        assertEquals(null, board.item(0)!!.outputPath)
    }

    @Test
    fun aRetriedRowIgnoresTheSupersededExportsCallbacks() = runBlocking {
        val board = ListBoard(listOf(item()))
        val first = AttemptToken("device_test", 0, 1)
        val second = AttemptToken("device_test", 0, 2)
        board.update(0) { ItemProgressModel.begin(it, first, 0L) }
        val old = PhaseReporter(board, first)
        old.enter(ItemPhase.ENCODING)
        // The retry takes the row before the first export has finished reporting.
        board.update(0) { ItemProgressModel.handOver(it, first, second) }
        PhaseReporter(board, second).enter(ItemPhase.ENCODING)
        val out = File(context.cacheDir, "device_test_stale.mp4").also { scratch += it }
        withTimeout(10 * 60_000L) { export(2_000_000, old, out) }
        val row = board.item(0)!!
        assertEquals(second, row.attempt)
        // The first export's onCompleted -> encodeFinished was ignored.
        assertEquals(ItemPhase.ENCODING, row.phase)
        assertEquals(0L, row.currentOutputSize)
    }
}
