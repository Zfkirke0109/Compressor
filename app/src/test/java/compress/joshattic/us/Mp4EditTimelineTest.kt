package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * A sample table and an edit list can count different frames in one file. The cases and numbers
 * are the ones scripts/diagnostics/test_mp4_edit_timeline.py holds for the workstation reader.
 */
class Mp4EditTimelineTest {

    private fun box(kind: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(8 + payload.size); writeBytes(kind); write(payload) }
        return out.toByteArray()
    }

    private fun full(kind: String, version: Int, write: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(version shl 24); write() }
        return box(kind, out.toByteArray())
    }

    /** The moov payload of a one-video-track movie with these presentation timestamps in stored order. */
    private fun moov(
        timestamps: List<Long>, timescale: Int = 90_000, movieTimescale: Int = 1_000_000,
        edits: List<Pair<Long, Long>>? = null, sync: List<Int>? = null, handler: String = "vide", tracks: Int = 1
    ): ByteArray {
        val step = 1_500
        val stts = full("stts", 0) { writeInt(1); writeInt(timestamps.size); writeInt(step) }
        val ctts = full("ctts", 0) {
            writeInt(timestamps.size)
            timestamps.forEachIndexed { i, t -> writeInt(1); writeInt((t - i.toLong() * step).toInt()) }
        }
        val stss = sync?.let { list -> full("stss", 0) { writeInt(list.size); list.forEach { writeInt(it + 1) } } } ?: ByteArray(0)
        val mdhd = full("mdhd", 0) { writeInt(0); writeInt(0); writeInt(timescale); writeInt(((timestamps.maxOrNull() ?: 0L) + step).toInt()); writeInt(0) }
        val hdlr = full("hdlr", 0) { writeInt(0); writeBytes(handler); write(ByteArray(13)) }
        val mdia = box("mdia", mdhd + hdlr + box("minf", box("stbl", stts + ctts + stss)))
        val tkhd = full("tkhd", 0) { write(ByteArray(80)) }
        val edts = edits?.let { list ->
            box("edts", full("elst", 1) { writeInt(list.size); list.forEach { (duration, mediaTime) -> writeLong(duration); writeLong(mediaTime); writeInt(0x00010000) } })
        } ?: ByteArray(0)
        val mvhd = full("mvhd", 0) { writeInt(0); writeInt(0); writeInt(movieTimescale); writeInt(0); write(ByteArray(80)) }
        var body = mvhd
        repeat(tracks) { body += box("trak", tkhd + edts + mdia) }
        return body
    }

    private fun ticks(us: Long) = us * 90_000 / 1_000_000

    private fun evidence(moov: ByteArray) = checkNotNull(Mp4EditTimeline.fromMoov(moov))

    @Test
    fun withoutAnEditListEverySampleIsPresented() {
        val e = evidence(moov(listOf(0, 3000, 6000, 9000)))
        assertEquals(4, e.tableSamples)
        assertEquals(4, e.presentedSamples)
        assertEquals(0, e.samplesAfterEditEnd)
        assertEquals(0, e.editCount)
        assertEquals(0L, e.tableFirstUs)
        assertEquals(0L, e.presentedFirstUs)
        assertFalse(e.storedOutOfOrder)
    }

    @Test
    fun anEmptyEditDelaysTheTrackWithoutRemovingAnything() {
        // b287 job_4e26f86286b1: video starts 14,277 us after the audio; every frame in and out.
        val e = evidence(moov((0 until 12).map { it * 3000L }, edits = listOf(14_277L to -1L, 400_000L to 0L)))
        assertEquals(12, e.tableSamples)
        assertEquals(12, e.presentedSamples)
        assertEquals(0, e.samplesAfterEditEnd)
        assertEquals(14_277L, e.initialEmptyEditUs)
        assertEquals(14_277L, e.presentedFirstUs)
        assertEquals(0L, e.tableFirstUs)
    }

    @Test
    fun samplesThatStartAfterTheEditEndsAreInTheTableAndNotInThePresentation() {
        // The shape that gives b287 job_782a717d3217's numbers: 168 samples about 39 ms apart, the
        // video delayed 97,488 us, the presentation ending between the 166th and 167th frames.
        val stamps = (0 until 168).map { ticks(it * 39_300L) }
        val endUs = (165 * 39_300L + 166 * 39_300L) / 2
        val e = evidence(moov(stamps, edits = listOf(97_488L to -1L, endUs to 0L)))
        assertEquals(168, e.tableSamples)
        assertEquals(166, e.presentedSamples)
        assertEquals(2, e.samplesAfterEditEnd)
        assertEquals(0, e.samplesBeforeEditStart)
        assertEquals(97_488L, e.initialEmptyEditUs)
        assertEquals(97_488L, e.presentedFirstUs)
        assertEquals(2, e.editCount)
        assertEquals(
            listOf("tableSamples", "presentedSamples", "samplesAfterEditEnd", "samplesBeforeEditStart", "initialEmptyEditUs",
                "editCount", "movieDurationUs", "mediaDurationUs", "tableFirstUs", "presentedFirstUs", "storedOutOfOrder"),
            e.fields().keys.toList()
        )
    }

    @Test
    fun aSampleStartingExactlyAtTheEditEndIsKept() {
        // Media3 keeps a clipped final frame: only a sample that starts AFTER the end is left out.
        for ((endTicks, kept) in listOf(6000L to 3, 5999L to 2, 6001L to 3, 9000L to 4, 8999L to 3)) {
            val e = evidence(moov(listOf(0, 3000, 6000, 9000), movieTimescale = 90_000, edits = listOf(endTicks to 0L)))
            assertEquals("$endTicks", kept, e.presentedSamples)
        }
    }

    @Test
    fun framesStoredOutOfOrderAreJudgedByTheirPresentationTime() {
        // I P B B | P B B | P B B in decode order; by time the edit ends after the seventh frame.
        val stored = listOf(0L, 9000, 3000, 6000, 18000, 12000, 15000, 27000, 21000, 24000)
        val e = evidence(moov(stored, movieTimescale = 90_000, edits = listOf(18_500L to 0L), sync = listOf(0)))
        assertTrue(e.storedOutOfOrder)
        assertEquals(10, e.tableSamples)
        assertEquals(7, e.presentedSamples)
        assertEquals(3, e.samplesAfterEditEnd)
    }

    @Test
    fun oneEditOfZeroDurationKeepsEverySample() {
        val e = evidence(moov(listOf(3000, 6000, 9000), edits = listOf(0L to 3000L)))
        assertEquals(3, e.presentedSamples)
        assertEquals(0L, e.presentedFirstUs)
    }

    @Test
    fun anEditThatStartsInsideAGroupOfPicturesKeepsTheFramesNeededToDecodeIt() {
        val e = evidence(moov((0 until 10).map { it * 3000L }, movieTimescale = 90_000, edits = listOf(15_000L to 12_000L), sync = listOf(0, 6)))
        assertEquals(10, e.presentedSamples)
        assertTrue(e.presentedFirstUs!! < 0)
    }

    @Test
    fun aSampleKeptFromBeforeAnEditsStartIsTimedAsMedia3TimesIt() {
        // Media3 rounds a time toward zero. The sync sample kept for decoding sits 12,000 units of
        // 1/90,000 s before the edit's start: 133,333.3 us, so -133,333 and not the -133,334 that
        // flooring gives. Media3ParserParityTest holds the same thing against Media3 itself.
        val moov = moov((0 until 10).map { it * 3000L }, movieTimescale = 90_000, edits = listOf(15_000L to 12_000L), sync = listOf(0, 6))
        assertEquals(-133_333L, evidence(moov).presentedFirstUs)
        val times = checkNotNull(Mp4EditTimeline.presentedTimesUs(moov))
        assertEquals(listOf(-133_333L, -100_000L, -66_666L, -33_333L, 0L, 33_333L), times.take(6))
        assertEquals(10, times.size)
    }

    @Test
    fun aFileIsReadByWalkingPastItsMediaData() {
        val stamps = (0 until 168).map { ticks(it * 39_300L) }
        val endUs = (165 * 39_300L + 166 * 39_300L) / 2
        val file = File.createTempFile("edit-timeline", ".mp4")
        try {
            file.writeBytes(
                box("ftyp", "isom".toByteArray() + ByteArray(4) + "isom".toByteArray()) +
                    box("mdat", ByteArray(4096)) + // as a camera writes it: media first, moov last
                    box("moov", moov(stamps, edits = listOf(97_488L to -1L, endUs to 0L)))
            )
            val e = RandomAccessFile(file, "r").channel.use { Mp4EditTimeline.read(it) }
            assertNotNull(e)
            assertEquals(168 to 166, e!!.tableSamples to e.presentedSamples)
            // A moov larger than the caller allows is not read.
            assertNull(RandomAccessFile(file, "r").channel.use { Mp4EditTimeline.read(it, maxMoovBytes = 64) })
        } finally {
            file.delete()
        }
    }

    @Test
    fun whatIsNotOneVideoTrackWithASampleTableGivesNoEvidenceAndNeverThrows() {
        assertNull(Mp4EditTimeline.fromMoov(ByteArray(0)))
        assertNull(Mp4EditTimeline.fromMoov(ByteArray(64) { 0x7f }))
        assertNull(Mp4EditTimeline.fromMoov(moov(listOf(0, 3000), handler = "soun")))
        assertNull(Mp4EditTimeline.fromMoov(moov(listOf(0, 3000), tracks = 2)))
        val truncated = moov(listOf(0, 3000, 6000)).let { it.copyOf(it.size - 20) }
        assertNull(Mp4EditTimeline.fromMoov(truncated))
        val empty = File.createTempFile("edit-timeline", ".bin")
        try {
            empty.writeBytes(box("ftyp", "isom".toByteArray()))
            assertNull(RandomAccessFile(empty, "r").channel.use { Mp4EditTimeline.read(it) })
        } finally {
            empty.delete()
        }
    }

    @Test
    fun theBoxesFfmpegWroteForTheSyntheticFixtureReadTheSameAsOnTheWorkstation() {
        // The moov boxes of scripts/device/make_edit_list_timeline_fixture.py's two files (ffmpeg
        // test patterns: HEVC with B-frames, variable frame rate, video delayed 97,488 us). On the
        // tail file ffmpeg's own demuxer flags two packets for discard and decodes 166 frames.
        fun resource(name: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { name }.use { it.readBytes() }
        val tail = evidence(resource("edit_tail_168.moov"))
        assertEquals(168, tail.tableSamples)
        assertEquals(166, tail.presentedSamples)
        assertEquals(2, tail.samplesAfterEditEnd)
        assertEquals(97_488L, tail.initialEmptyEditUs)
        assertEquals(97_488L, tail.presentedFirstUs)
        assertEquals(83_333L, tail.tableFirstUs)
        assertEquals(6_989_154L, tail.movieDurationUs)
        assertEquals(6_966_666L, tail.mediaDurationUs)
        assertTrue(tail.storedOutOfOrder)
        val whole = evidence(resource("edit_whole_168.moov"))
        assertEquals(168 to 168, whole.tableSamples to whole.presentedSamples)
        assertEquals(0, whole.samplesAfterEditEnd)
        assertEquals(97_488L, whole.presentedFirstUs)
        // What the capture recorded for the real file, read off the synthetic one.
        val localized = TimelineLocalization.localize(tail.tableSamples, tail.presentedSamples, null, null, null, 166)
        assertEquals(TimelineLocalization.SOURCE_EDIT_LIST, localized.stage)
    }

    // ---- Where the count first changed ----------------------------------------------------

    @Test
    fun theCapturedCountsAreLocalizedByWhatEachStageReported() {
        // The reading that needs no lost frame: the edit list presents 166 of 168 and Media3 read those.
        val edit = TimelineLocalization.localize(168, 166, media3FrameCount = 166, remuxReadSamples = 166, remuxWrittenSamples = 166, finalSamples = 166)
        assertEquals(TimelineLocalization.SOURCE_EDIT_LIST, edit.stage)
        assertTrue(edit.reason, edit.reason.contains("presents 166 of the 168 samples"))
        // The source presents all 168 and Media3 wrote 166: the loss is inside the export.
        val export = TimelineLocalization.localize(168, 168, 166, 166, 166, 166)
        assertEquals(TimelineLocalization.MEDIA3_EXPORT, export.stage)
        assertEquals("Media3 reported 166 video frames written from 168 it was given to read", export.reason)
        // Media3 wrote 168 and its closed file holds 166.
        assertEquals(TimelineLocalization.MEDIA3_OUTPUT_FILE, TimelineLocalization.localize(168, 168, 168, 166, 166, 166).stage)
        // The file held 168 and the app's own remux wrote 166.
        val remux = TimelineLocalization.localize(168, 168, 168, 168, 166, 166)
        assertEquals(TimelineLocalization.METADATA_REMUX, remux.stage)
        assertEquals("the metadata remux read 168 video samples and wrote 166", remux.reason)
        // Everything reported 168 and the verifier read 166.
        assertEquals(TimelineLocalization.AFTER_REMUX, TimelineLocalization.localize(168, 168, 168, 168, 168, 166).stage)
    }

    @Test
    fun aStageThatReportedNothingIsNeverBlamedOrCleared() {
        // b287 itself: one measurement after the remux, nothing in between.
        val blind = TimelineLocalization.localize(168, null, null, null, null, 166)
        assertEquals(TimelineLocalization.UNKNOWN, blind.stage)
        assertTrue(blind.reason, blind.reason.contains("the source's edit list was not read"))
        assertTrue(blind.reason, blind.reason.contains("Media3 reported no frame count"))
        // Media3 reports 0 when it has no count: not a claim that it wrote no frames.
        assertEquals(TimelineLocalization.UNKNOWN, TimelineLocalization.localize(168, 168, 0, null, null, 166).stage)
        // The edit list alone, with no remux and no Media3 count: the candidate equals what it presents.
        assertEquals(TimelineLocalization.SOURCE_EDIT_LIST, TimelineLocalization.localize(168, 166, null, null, null, 166).stage)
        // The edit list presents 166 but the candidate holds 165: that does not explain it.
        assertEquals(TimelineLocalization.UNKNOWN, TimelineLocalization.localize(168, 166, null, null, null, 165).stage)
        assertEquals(TimelineLocalization.MEDIA3_EXPORT, TimelineLocalization.localize(168, 166, 165, 165, 165, 165).stage)
        // Equal counts: nothing to localize.
        assertEquals(TimelineLocalization.NONE, TimelineLocalization.localize(168, 166, 166, 168, 168, 168).stage)
        assertEquals(listOf("firstChangedStage", "reason"), blind.fields().keys.toList())
    }
}
