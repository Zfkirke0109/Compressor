package compress.joshattic.us

import compress.joshattic.us.Mp4Boxes.one
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * What Media3's muxer does to the timestamps it is handed, observed on the build machine.
 *
 * A Transformer export ends in Media3's MuxerWrapper and, by default, Media3's own MP4 muxer. A
 * copied audio track goes from Media3's extractor straight into them; a re-encoded video track
 * reaches them from the encoder with the presentation times its frames were decoded with. This
 * feeds them the samples Media3's extractor emits for the synthetic sources, with no codec in
 * between (Media3Host), and reads the written file back.
 *
 * Two things the app depends on or records are settled here:
 *
 *  1. The first video timestamp. b287 regular job_782a717d3217's source starts its video at
 *     97,488 us and its output started at zero. With an audio track present, MuxerWrapper moves
 *     the first video sample's timestamp to zero, says so in its log with the number, and moves
 *     no other sample. That is the pattern MediaTimelineEvidence recognises and the first
 *     timestamp repair undoes, so the repair's premise is held against Media3 itself: an upgrade
 *     that changes it fails here and not as repairs that silently stop in the field.
 *  2. A copied AAC track. Every packet, the AudioSpecificConfig and the encoder delay come
 *     through unchanged. A track with an encoder delay has every timestamp handed to the muxer
 *     one delay early (1,024 samples at 48 kHz is 21,333 us), and the muxer writes an edit that
 *     starts after the delay. That edit is on the muxer's movie timescale of 1/10,000 s, so the
 *     padding at the end of the track, which a reader derives from the edit, can come out
 *     different. AudioTrackIdentity's configuration hash covers delay and padding as the platform
 *     reports them. A track with no delay is written as it was.
 *
 * Not settled here: what the platform MediaExtractor reports for either file, and so whether a
 * phone judges such a copy "decoder configuration differs". The earlier capture that did
 * (b213 job_6d1173f4c0f9) recorded neither side's values and its original is not available.
 * EditListTimelineDeviceTest prints both sides on a device.
 */
class Media3MuxerObservationTest {

    private class Result(val written: Media3Host.Written, val file: ByteArray, val before: Map<String, Media3Host.Track>, val after: Map<String, Media3Host.Track>)

    private fun source(name: String): ByteArray = Media3Host.inModule("src/androidTest/assets/$name").readBytes()

    /** [name]'s tracks through Media3's muxer, and both files as Media3's extractor reads them. */
    private fun transmux(name: String, video: Boolean = true, audio: Boolean = true): Result = transmux(source(name), video, audio)

    private fun transmux(bytes: ByteArray, video: Boolean = true, audio: Boolean = true): Result {
        val output = File.createTempFile("media3host", ".mp4")
        try {
            val written = Media3Host.transmux(bytes, output, video, audio)
            val file = output.readBytes()
            return Result(written, file, Media3Host.describe(bytes), Media3Host.describe(file))
        } finally {
            output.delete()
        }
    }

    private fun Media3Host.Track.presentation(): LongArray = timesUs.sortedArray()

    private val shiftLine = "MuxerWrapper: Shifting first video timestamp from 97488 to zero."

    @Test
    fun withAudioPresentMedia3MovesTheFirstVideoTimestampToZeroAndNoOther() {
        val result = transmux("edit_whole_168.mp4")
        val before = result.before.getValue("video").presentation()
        val after = result.after.getValue("video").presentation()
        assertEquals(168, before.size)
        assertEquals(168, result.written.videoSamples)
        assertEquals(168, after.size)
        assertEquals(97_488L, before.first())
        assertEquals(0L, after.first())
        // Media3 says what it did, with the number. This is the line to look for in a device log.
        assertTrue("Media3 ${Media3Host.version} logged ${result.written.log}", shiftLine in result.written.log)
        // It is the wrapper that does it: the muxer is handed zero for the first frame and the
        // extractor's own time for every other.
        val handed = result.written.videoTimesAtMuxerUs.sortedArray()
        assertEquals(0L, handed.first())
        assertEquals(before.drop(1), handed.drop(1))
        // Every later frame stays where it was, to the microsecond the 1/90,000 s track units allow.
        val moved = (1 until before.size).maxOf { abs(after[it] - before[it]) }
        assertTrue("a later frame moved by $moved us", moved <= 12)
        // The frames themselves are untouched, and nothing in the file compensates for the move:
        // the video track is written without an edit list.
        assertEquals(result.before.getValue("video").payloadDigests, result.after.getValue("video").payloadDigests)
        val evidence = checkNotNull(Mp4EditTimeline.fromMoov(Mp4Boxes.moovPayload(result.file)))
        assertEquals(0, evidence.editCount)
        assertEquals(168 to 168, evidence.tableSamples to evidence.presentedSamples)
        assertEquals(0L, evidence.presentedFirstUs)

        // The app's comparator names exactly this pattern and proposes the repair, while still
        // refusing the candidate as it stands.
        val verdict = MediaTimelineEvidence.compare(before, after)
        assertFalse(verdict.matches)
        assertEquals(1, verdict.firstMismatch)
        assertEquals(97_488L, verdict.firstVideoPtsToRestoreUs)
        // With the first timestamp put back, which is all the repair's remux changes, the two
        // timelines match frame for frame.
        val repaired = after.copyOf().also { it[0] = checkNotNull(verdict.firstVideoPtsToRestoreUs) }
        val afterRepair = MediaTimelineEvidence.compare(before, repaired)
        assertTrue(afterRepair.reason, afterRepair.matches)
        assertNull(afterRepair.firstVideoPtsToRestoreUs)
    }

    @Test
    fun theCapturedCountsAndTheCapturedShiftComeFromMedia3sReadAndThenItsMuxer() {
        // The captured job in one pass over the synthetic source: 168 samples in the table, 166
        // handed on by the parser, 166 written by the muxer, and the first of them moved from
        // 97,488 us to zero.
        val bytes = source("edit_tail_168.mp4")
        val table = Media3Host.videoTimesUs(bytes, Media3Host.ignoreEditLists).sortedArray()
        val result = transmux("edit_tail_168.mp4")
        val handedOn = result.before.getValue("video").presentation()
        val written = result.after.getValue("video").presentation()
        assertEquals(168, table.size)
        assertEquals(166, handedOn.size)
        assertEquals(166, result.written.videoSamples)
        assertEquals(166, written.size)
        assertEquals(97_488L, handedOn.first())
        assertEquals(0L, written.first())
        assertTrue("Media3 ${Media3Host.version} logged ${result.written.log}", shiftLine in result.written.log)

        // Held against the whole table, as the verifier holds it, the output is two frames short:
        // the verdict the capture recorded. No repair is proposed for a count that differs.
        val verdict = MediaTimelineEvidence.compare(table, written)
        assertFalse(verdict.matches)
        assertEquals("frame count 168 -> 166", verdict.reason)
        assertNull(verdict.firstVideoPtsToRestoreUs)
        // The counts a batch records name the stage. The muxer's own sample count is the number
        // an export reports as its video frame count.
        val presented = checkNotNull(Mp4EditTimeline.fromMoov(Mp4Boxes.moovPayload(bytes))).presentedSamples
        val stage = TimelineLocalization.localize(
            sourceTableSamples = table.size, sourcePresentedSamples = presented, media3FrameCount = result.written.videoSamples,
            remuxReadSamples = null, remuxWrittenSamples = null, finalSamples = written.size
        )
        assertEquals(stage.reason, TimelineLocalization.SOURCE_EDIT_LIST, stage.stage)
        // Against what the source presents, the muxer's file differs only in its first timestamp.
        assertEquals(97_488L, MediaTimelineEvidence.compare(handedOn, written).firstVideoPtsToRestoreUs)
    }

    @Test
    fun withNoAudioTrackMedia3MovesTheWholeVideoTimelineAndThereIsNothingToRepair() {
        // Why the repair applies only to a source with audio. Written alone, the video track is
        // moved as a whole to start at zero: every frame keeps its distance from the first, which
        // the comparator counts as the same timeline.
        val result = transmux("edit_whole_168.mp4", audio = false)
        val before = result.before.getValue("video").presentation()
        val after = result.after.getValue("video").presentation()
        assertEquals(168, after.size)
        assertEquals(0L, after.first())
        assertFalse("Media3 ${Media3Host.version} logged ${result.written.log}", result.written.log.any { "Shifting first video timestamp" in it })
        val drift = before.indices.maxOf { abs((after[it] - after[0]) - (before[it] - before[0])) }
        assertTrue("a frame's distance from the first changed by $drift us", drift <= 12)
        val verdict = MediaTimelineEvidence.compare(before, after)
        assertTrue(verdict.reason, verdict.matches)
        assertNull(verdict.firstVideoPtsToRestoreUs)
        assertNull(result.after["audio"])
    }

    @Test
    fun aCopiedAacTrackKeepsEveryPacketItsConfigurationAndItsEncoderDelay() {
        for ((name, packets) in listOf("edit_whole_168.mp4" to 376, "edit_tail_168.mp4" to 329)) {
            val result = transmux(name)
            val before = result.before.getValue("audio")
            val after = result.after.getValue("audio")
            assertEquals("$name: codec", "audio/mp4a-latm", after.mime)
            assertEquals("$name: packets read", packets, before.timesUs.size)
            assertEquals("$name: packets written", packets, result.written.audioSamples)
            assertEquals("$name: packet payloads", before.payloadDigests, after.payloadDigests)
            assertEquals("$name: AudioSpecificConfig", before.codecData, after.codecData)
            assertEquals("$name: one configuration buffer", 1, after.codecData.size)
            assertTrue("$name: packet times", before.timesUs.contentEquals(after.timesUs))
            // 1,024 samples of encoder delay, one AAC frame: 21,333 us at 48 kHz.
            assertEquals("$name: encoder delay read", 1024, before.encoderDelay)
            assertEquals("$name: encoder delay written", 1024, after.encoderDelay)
            // How the delay travels: the wrapper hands the muxer every audio timestamp one delay
            // earlier, so the first is negative, and the muxer writes an edit that starts after it.
            val handed = result.written.audioTimesAtMuxerUs
            assertEquals("$name: first audio timestamp handed to the muxer", -21_333L, handed.first())
            assertTrue("$name: every audio timestamp is 21,333 us early", handed.indices.all { handed[it] == before.timesUs[it] - 21_333L })
            assertEquals("$name: the edit starts after the delay", 1024L, checkNotNull(Mp4Boxes.edits(result.file, Mp4Boxes.track(result.file, "soun"))).single().second)
        }
    }

    @Test
    fun anAacTrackWithoutAnEncoderDelayIsWrittenAsItWasAndGetsNoEdit() {
        // The control, and the ordinary case: with no encoder delay to carry, nothing about the
        // audio track's timing is touched and no edit list is written for it.
        val plain = Mp4Boxes.withoutEditList(source("edit_whole_168.mp4"), "soun")
        val result = transmux(plain)
        val before = result.before.getValue("audio")
        val after = result.after.getValue("audio")
        assertEquals(0, before.encoderDelay)
        assertEquals(0, after.encoderDelay)
        assertEquals(before.encoderPadding, after.encoderPadding)
        assertEquals(376, result.written.audioSamples)
        assertEquals(before.payloadDigests, after.payloadDigests)
        assertEquals(before.codecData, after.codecData)
        assertTrue(before.timesUs.contentEquals(after.timesUs))
        assertTrue(before.timesUs.contentEquals(result.written.audioTimesAtMuxerUs))
        assertNull(Mp4Boxes.edits(result.file, Mp4Boxes.track(result.file, "soun")))
        // The video's first timestamp is still moved: that depends on an audio track being
        // present, not on its delay.
        assertTrue(shiftLine in result.written.log)
    }

    @Test
    fun aCopiedAacTracksPaddingFollowsTheEditTheMuxerWroteAndThatEditIsRounded() {
        // The delay survives as an edit starting 1,024 samples in. The edit's length is written in
        // the muxer's movie timescale, 1/10,000 s, and the padding a reader reports is whatever
        // the track holds past the end of that edit. So a track whose length is not a whole
        // number of 1/10,000 s gains padding it did not have.
        for ((name, expectedPadding) in listOf("edit_whole_168.mp4" to 0, "edit_tail_168.mp4" to 2)) {
            val result = transmux(name)
            val file = result.file
            assertEquals("$name: padding Media3 reads from the source", 0, result.before.getValue("audio").encoderPadding)
            val moov = Mp4Boxes.moov(file)
            val movieTimescale = Mp4Boxes.timescale(file, Mp4Boxes.boxes(file, moov.start, moov.end).one("mvhd"))
            assertEquals("$name: the muxer's movie timescale", 10_000L, movieTimescale)
            val track = Mp4Boxes.track(file, "soun")
            val mdia = Mp4Boxes.boxes(file, track.start, track.end).one("mdia")
            val mdhd = Mp4Boxes.boxes(file, mdia.start, mdia.end).one("mdhd")
            assertEquals("$name: audio timescale", 48_000L, Mp4Boxes.timescale(file, mdhd))
            val (editDuration, editStart) = checkNotNull(Mp4Boxes.edits(file, track)) { "$name: no audio edit list was written" }.single()
            assertEquals("$name: the edit starts after the encoder delay", 1024L, editStart)
            // What the track holds, in samples, past the end of the edit as written.
            val pastTheEdit = Mp4Boxes.duration(file, mdhd) - editStart - editDuration * 48_000L / movieTimescale
            assertEquals("$name: padding by the written edit", expectedPadding.toLong(), pastTheEdit)
            assertEquals("$name: padding Media3 reads back", expectedPadding, result.after.getValue("audio").encoderPadding)
            // The cause: whether the presented length is a whole number of movie units.
            val presentedSamples = Mp4Boxes.duration(file, mdhd) - editStart
            assertEquals("$name: presented length is exact in 1/10,000 s", expectedPadding == 0, presentedSamples * movieTimescale % 48_000L == 0L)
        }
    }
}
