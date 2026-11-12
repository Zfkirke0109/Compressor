package compress.joshattic.us

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Where a video sample count changes between a source and its re-encode, measured on the device.
 *
 * The regular-profile run of v1.6.287 rejected one 4K candidate (job_782a717d3217) because the
 * source held 168 video samples and the output 166, with the source's first video timestamp at
 * 97,488 us. The capture has one count, taken after the metadata remux, and the original is no
 * longer available, so which stage first differed is not known for that file. Media3 1.11.0's MP4
 * parser applies the edit list and leaves out samples that start after an edit ends, while
 * MediaExtractor returns the whole sample table: a source whose media runs past its presentation
 * edit would give those numbers with no stage losing a frame.
 *
 * The parser's half of that is observed on the build machine: Media3ParserParityTest runs Media3's
 * own Mp4Extractor over edit_tail_168.mp4 and it hands on 166 of the 168 samples. Everything else
 * needs a phone, and this is that observation: what the platform extractor lists for the source,
 * what the decoder, the encoder and Media3's muxer make of the samples they are handed, and what
 * the app's metadata remux writes.
 *
 * It needs no clip. The two assets are synthetic (scripts/device/make_edit_list_timeline_fixture.py:
 * ffmpeg test pattern, portrait rotation tag, HEVC with B-frames, variable frame rate, AAC):
 *
 *   edit_tail_168.mp4   168 samples in the table, 166 inside the presentation edit, video starting
 *                       after an empty edit of 97,488 us;
 *   edit_whole_168.mp4  the control: the same streams with all 168 inside the edit.
 *
 * Each goes through the three places a count can change, in the order the app runs them: what the
 * platform extractor lists for the source, a real Media3 export (the frame count Media3 reports and
 * what its closed file holds), and the app's metadata remux of that file. The run log prints every
 * count (`b287-timeline ...`) and the stage TimelineLocalization names from them, which is the same
 * call the app makes when a batch records a frame-count change.
 *
 * A failure here is a result, not a broken test: it means this device does not behave as the
 * reading above says, and its message carries the counts that say how.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class EditListTimelineDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val scratch = mutableListOf<File>()

    @After
    fun tearDown() {
        scratch.forEach { runCatching { it.delete() } }
    }

    private fun asset(name: String): File {
        val out = File(context.cacheDir, "timeline_$name").also { scratch += it }
        instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /** A real HEVC re-encode with Media3, audio passed through, as a batch's full encode does. */
    private suspend fun export(source: File, out: File): ExportResult = withContext(Dispatchers.Main) {
        withTimeout(180_000L) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(1_500_000).build())
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(exportResult)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source))).build(), out.absolutePath)
            }
        }
    }

    private class Counts(
        val edit: Mp4EditTimeline.Evidence,
        /** What the platform extractor lists for the source: the verifier's reference. */
        val sourceTable: Int,
        val sourceOriginUs: Long?,
        val media3Frames: Int,
        val rawSamples: Int,
        val rawOriginUs: Long?,
        val remux: Mp4MetadataRemuxResult,
        val finalSamples: Int,
        val finalOriginUs: Long?,
        val stage: TimelineLocalization.Result
    )

    private fun measure(name: String): Counts = runBlocking {
        val source = asset(name)
        val edit = FileInputStream(source).use { Mp4EditTimeline.read(it.channel) }
        assertNotNull("$name: the source's edit list could not be read", edit)
        val raw = File(context.cacheDir, "timeline_raw_$name").also { scratch += it; it.delete() }
        val exported = export(source, raw)
        // The verifier's own reader, on the file exactly as Media3 closed it.
        val rawTimeline = MediaTimelineEvidence.inspect(context, Uri.fromFile(source), raw)
        // The remux runs only for a source with a location to write. These coordinates are made up.
        val remux = Mp4MetadataRemuxer.remuxWithSourceMetadata(
            context, raw, VideoMetadataSnapshot(latitude = 12.5, longitude = -45.25)
        )
        scratch += remux.outputFile
        val finalTimeline = MediaTimelineEvidence.inspect(context, Uri.fromFile(source), remux.outputFile)
        val stage = TimelineLocalization.localize(
            sourceTableSamples = finalTimeline.sourceFrames,
            sourcePresentedSamples = edit!!.presentedSamples,
            media3FrameCount = exported.videoFrameCount,
            remuxReadSamples = remux.videoSamplesRead,
            remuxWrittenSamples = remux.videoSamplesWritten,
            finalSamples = finalTimeline.outputFrames
        )
        println(
            "b287-timeline $name extractorTable=${finalTimeline.sourceFrames} editTable=${edit.tableSamples} " +
                "presented=${edit.presentedSamples} afterEditEnd=${edit.samplesAfterEditEnd} emptyEditUs=${edit.initialEmptyEditUs} " +
                "sourceOriginUs=${finalTimeline.sourceOriginUs} media3Frames=${exported.videoFrameCount} " +
                "rawSamples=${rawTimeline.outputFrames} rawOriginUs=${rawTimeline.outputOriginUs} rawReason=${rawTimeline.reason} " +
                "remuxed=${remux.didRemux} remuxRead=${remux.videoSamplesRead} remuxWritten=${remux.videoSamplesWritten} " +
                "finalSamples=${finalTimeline.outputFrames} finalOriginUs=${finalTimeline.outputOriginUs} finalReason=${finalTimeline.reason} " +
                "stage=${stage.stage} because=${stage.reason}"
        )
        Counts(
            edit, finalTimeline.sourceFrames, finalTimeline.sourceOriginUs, exported.videoFrameCount, rawTimeline.outputFrames,
            rawTimeline.outputOriginUs, remux, finalTimeline.outputFrames, finalTimeline.outputOriginUs, stage
        )
    }

    private fun describe(c: Counts) =
        "extractor table ${c.sourceTable}, edit list presents ${c.edit.presentedSamples}, Media3 reported ${c.media3Frames}, " +
            "its file holds ${c.rawSamples}, the remux read ${c.remux.videoSamplesRead} and wrote ${c.remux.videoSamplesWritten}, " +
            "the final file holds ${c.finalSamples}; first differing stage: ${c.stage.stage} (${c.stage.reason})"

    @Test
    fun theFixturesAreWhatTheGeneratorSaysOnThisDevice() {
        // Before any export: what this device's own readers make of the two sources.
        for ((name, presented) in listOf("edit_tail_168.mp4" to 166, "edit_whole_168.mp4" to 168)) {
            val source = asset(name)
            val edit = FileInputStream(source).use { Mp4EditTimeline.read(it.channel) }
            assertNotNull("$name: the edit list could not be read", edit)
            assertEquals("$name: samples in the table", 168, edit!!.tableSamples)
            assertEquals("$name: samples the edit list presents", presented, edit.presentedSamples)
            assertEquals("$name: initial empty edit", 97_488L, edit.initialEmptyEditUs)
            assertTrue("$name: the fixture has no B-frames", edit.storedOutOfOrder)
            // The file against itself: the count the verifier would take as its reference.
            val self = MediaTimelineEvidence.inspect(context, Uri.fromFile(source), source)
            println("b287-timeline $name platformExtractorSamples=${self.sourceFrames} originUs=${self.sourceOriginUs} reason=${self.reason}")
            assertEquals("$name: the platform extractor does not list the whole sample table (${self.reason})", 168, self.sourceFrames)
        }
    }

    @Test
    fun samplesPastThePresentationEditAreLeftOutByMedia3sReadOfTheSource() {
        val c = measure("edit_tail_168.mp4")
        // The captured shape: 168 in the source's table, 166 in the output, and nothing lost on the way.
        assertEquals("the reference count is not the sample table: ${describe(c)}", 168, c.sourceTable)
        assertEquals(describe(c), TimelineLocalization.SOURCE_EDIT_LIST, c.stage.stage)
        assertEquals("Media3 did not write what the edit list presents: ${describe(c)}", c.edit.presentedSamples, c.media3Frames)
        assertEquals("Media3's closed file does not hold what it reported: ${describe(c)}", c.media3Frames, c.rawSamples)
        assertTrue("the metadata remux did not run: ${c.remux.message}", c.remux.didRemux)
        assertEquals("the metadata remux changed the count: ${describe(c)}", c.remux.videoSamplesRead, c.remux.videoSamplesWritten)
        assertEquals("the remuxed file does not hold what the remux wrote: ${describe(c)}", c.remux.videoSamplesWritten, c.finalSamples)
        assertEquals(describe(c), 166, c.finalSamples)
    }

    @Test
    fun aSourceWhoseSamplesAreAllPresentedKeepsEveryOneThroughTheExportAndTheRemux() {
        // The control: the same streams with every sample inside the edit. A count that changes
        // here is a stage losing a frame, whatever the other test shows.
        val c = measure("edit_whole_168.mp4")
        assertEquals(describe(c), TimelineLocalization.NONE, c.stage.stage)
        assertEquals(describe(c), 168, c.sourceTable)
        assertEquals(describe(c), 168, c.media3Frames)
        assertEquals(describe(c), 168, c.rawSamples)
        assertTrue("the metadata remux did not run: ${c.remux.message}", c.remux.didRemux)
        assertEquals(describe(c), 168, c.remux.videoSamplesRead)
        assertEquals(describe(c), 168, c.remux.videoSamplesWritten)
        assertEquals(describe(c), 168, c.finalSamples)
    }
}
