package compress.joshattic.us

import compress.joshattic.us.Mp4Boxes.one
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Mp4EditTimeline against the parser it describes.
 *
 * Mp4EditTimeline says which samples of a source Media3 reads, by following BoxParser.parseStbl
 * step for step. That is a second implementation of someone else's rule, and on its own it is a
 * reading of their code. This runs Media3's own Mp4Extractor, the class the app ships, over the
 * same bytes on the build machine and compares the two sample for sample.
 *
 * What it settles, and what it does not:
 *
 *  - b287 regular job_782a717d3217 recorded 168 source samples and 166 in the output. The
 *    synthetic source built to that shape (edit_tail_168.mp4, an androidTest asset) is read by
 *    Media3 as 166 samples: the two past the presentation edit are never handed to a decoder.
 *    With edit lists ignored the same extractor returns all 168. The control, whose samples are
 *    all inside the edit, is read as 168. So for a file built this way the count changes at
 *    Media3's read of the source, before any codec, muxer or remux runs.
 *  - Whether the original of that job is built this way is still not known: it is not available.
 *  - The platform MediaExtractor, the encoder, Media3's muxer and the metadata remux run only on
 *    a device. EditListTimelineDeviceTest measures those; nothing here stands in for it.
 *
 * It also keeps the reader honest as Media3 moves. Every edit shape below is checked for the
 * exact samples kept and their times to the microsecond, so a Media3 upgrade that changes how an
 * edit list is applied fails here, and names the first shape that differs.
 *
 * Media3Host says how Media3 is run off a device and why no other test is affected by it.
 * Media3MuxerObservationTest is the next stage of the same path: what Media3's muxer does with
 * the samples the parser hands on.
 */
class Media3ParserParityTest {

    private val media3Version get() = Media3Host.version
    private val ignoreEditLists get() = Media3Host.ignoreEditLists

    /** The video sample times Media3's extractor emits for [file], in the order it emits them. */
    private fun media3(file: ByteArray, flags: Int = 0): LongArray = Media3Host.videoTimesUs(file, flags)

    private fun moovOf(file: ByteArray): ByteArray = Mp4Boxes.moovPayload(file)

    /**
     * One of the synthetic sources, and where its video track's edit list and sync table are, so
     * an edit can be rewritten in place. No sample data is touched and no box changes size.
     */
    private inner class Fixture(name: String) {
        val bytes: ByteArray = Media3Host.inModule("src/androidTest/assets/$name").readBytes()
        val movieTimescale: Long
        val timescale: Long
        private val elst: Int
        private val edtsKind: Int
        private val stssKind: Int
        val emptyEdit: Long
        val duration: Long
        val mediaTime: Long
        /** Every sample's timestamp in the track's own units, in presentation order. */
        val ticks: List<Long>

        init {
            val data = ByteBuffer.wrap(bytes)
            val moov = Mp4Boxes.moov(bytes)
            movieTimescale = Mp4Boxes.timescale(bytes, Mp4Boxes.boxes(bytes, moov.start, moov.end).one("mvhd"))
            val video = Mp4Boxes.track(bytes, "vide")
            val inTrack = Mp4Boxes.boxes(bytes, video.start, video.end)
            val mdia = inTrack.one("mdia")
            val inMedia = Mp4Boxes.boxes(bytes, mdia.start, mdia.end)
            timescale = Mp4Boxes.timescale(bytes, inMedia.one("mdhd"))
            val minf = inMedia.one("minf")
            val stbl = Mp4Boxes.boxes(bytes, minf.start, minf.end).one("stbl")
            stssKind = Mp4Boxes.boxes(bytes, stbl.start, stbl.end).one("stss").start - 4
            val edts = inTrack.one("edts")
            edtsKind = edts.start - 4
            elst = Mp4Boxes.boxes(bytes, edts.start, edts.end).one("elst").start
            check(bytes[elst].toInt() == 0 && data.getInt(elst + 4) == 2) { "$name: expected a version 0 edit list of two entries" }
            emptyEdit = data.getInt(elst + 8).toLong() and 0xFFFFFFFFL
            check(data.getInt(elst + 12) == -1) { "$name: expected the first edit to be empty" }
            duration = data.getInt(elst + 20).toLong() and 0xFFFFFFFFL
            mediaTime = data.getInt(elst + 24).toLong()
            check(timescale in 1..1_000_000L && movieTimescale >= timescale) { "$name: unexpected timescales $timescale and $movieTimescale" }
            // From Media3 itself, edit lists ignored: microseconds, each the truncation of a whole
            // number of track units, which the ceiling below recovers exactly.
            ticks = media3(bytes, ignoreEditLists).sorted().map { us ->
                val t = (us * timescale + 999_999L) / 1_000_000L
                check(t * 1_000_000L / timescale == us) { "$name: $us us is not a whole number of 1/$timescale s" }
                t
            }
        }

        /** The movie-timescale duration whose end, as Media3 computes it, is [offset] track units after an edit's start. */
        fun durationEndingAt(offset: Long): Long {
            val d = (offset * movieTimescale + timescale - 1) / timescale
            check(d * timescale / movieTimescale == offset) { "no duration ends exactly $offset units in" }
            return d
        }

        fun withEdits(first: Pair<Long, Long>, second: Pair<Long, Long>, syncTable: Boolean = true): ByteArray = bytes.copyOf().also { copy ->
            val data = ByteBuffer.wrap(copy)
            data.putInt(elst + 8, first.first.toInt()).putInt(elst + 12, first.second.toInt())
            data.putInt(elst + 20, second.first.toInt()).putInt(elst + 24, second.second.toInt())
            if (!syncTable) "free".toByteArray(Charsets.ISO_8859_1).copyInto(copy, stssKind)
        }

        /** One edit: the second entry is left in the box as bytes past the declared count. */
        fun withOneEdit(edit: Pair<Long, Long>): ByteArray = bytes.copyOf().also { copy ->
            ByteBuffer.wrap(copy).putInt(elst + 4, 1).putInt(elst + 8, edit.first.toInt()).putInt(elst + 12, edit.second.toInt())
        }

        fun without(editList: Boolean = false, syncTable: Boolean = false): ByteArray = bytes.copyOf().also { copy ->
            if (editList) "free".toByteArray(Charsets.ISO_8859_1).copyInto(copy, edtsKind)
            if (syncTable) "free".toByteArray(Charsets.ISO_8859_1).copyInto(copy, stssKind)
        }
    }

    /**
     * Every shape must give the same samples from both: the count, the stored order and each time.
     * [expected] is how many shapes the caller means to check, so a loop that built none fails.
     * Each shape is built when it is checked: a list of patched copies would not fit the heap.
     */
    private fun assertTheReaderAgrees(shapes: List<Pair<String, () -> ByteArray>>, expected: Int) {
        assertEquals("shapes built", expected, shapes.size)
        val differing = ArrayList<String>()
        for ((name, build) in shapes) {
            val file = build()
            val emitted = media3(file)
            val moov = moovOf(file)
            val read = Mp4EditTimeline.presentedTimesUs(moov)
            val evidence = Mp4EditTimeline.fromMoov(moov)
            val problem = when {
                read == null || evidence == null -> "the reader returned nothing"
                read.size != emitted.size -> "Media3 emits ${emitted.size} samples, the reader says ${read.size}"
                !read.contentEquals(emitted) -> {
                    val at = read.indices.first { read[it] != emitted[it] }
                    "sample $at of ${read.size}: Media3 emits it at ${emitted[at]} us, the reader says ${read[at]} us"
                }
                evidence.presentedSamples != emitted.size -> "the evidence says ${evidence.presentedSamples} presented, Media3 emits ${emitted.size}"
                evidence.presentedFirstUs != emitted.minOrNull() -> "the evidence says first at ${evidence.presentedFirstUs} us, Media3's earliest is ${emitted.minOrNull()} us"
                else -> null
            }
            if (problem != null) differing += "$name: $problem"
        }
        assertTrue(
            "${differing.size} of ${shapes.size} edit shapes are read differently by Media3 $media3Version and by Mp4EditTimeline; first: " +
                differing.take(5).joinToString(" | "),
            differing.isEmpty()
        )
    }

    @Test
    fun media3ReadsTheTailFixtureAsItsEditListPresentsItAndNotAsItsTableHoldsIt() {
        val file = Fixture("edit_tail_168.mp4").bytes
        val presented = media3(file)
        val table = media3(file, ignoreEditLists)
        // The captured pair of numbers, from Media3's own parser and before any codec runs.
        assertEquals("samples in the table (Media3 $media3Version, edit lists ignored)", 168, table.size)
        assertEquals("samples Media3 $media3Version hands on", 166, presented.size)
        assertEquals("first video sample", 97_488L, presented.min())
        // The two it leaves out are the last two in presentation order: what it hands on spans
        // the table's first 166 (to the microsecond that truncating two offsets can differ by).
        assertTrue(Math.abs((presented.max() - presented.min()) - (table.sorted()[165] - table.min())) <= 1)
        val evidence = checkNotNull(Mp4EditTimeline.fromMoov(moovOf(file)))
        assertEquals(table.size, evidence.tableSamples)
        assertEquals(presented.size, evidence.presentedSamples)
        assertEquals(2, evidence.samplesAfterEditEnd)
        assertEquals(0, evidence.samplesBeforeEditStart)
        assertEquals(97_488L, evidence.presentedFirstUs)
        assertTrue(presented.contentEquals(checkNotNull(Mp4EditTimeline.presentedTimesUs(moovOf(file)))))
        // An export cannot write a frame it was never handed: with these counts the stage named
        // is the source's own edit list.
        val stage = TimelineLocalization.localize(
            sourceTableSamples = table.size, sourcePresentedSamples = evidence.presentedSamples, media3FrameCount = presented.size,
            remuxReadSamples = null, remuxWrittenSamples = null, finalSamples = presented.size
        )
        assertEquals(stage.reason, TimelineLocalization.SOURCE_EDIT_LIST, stage.stage)
    }

    @Test
    fun media3ReadsEverySampleOfTheControl() {
        val file = Fixture("edit_whole_168.mp4").bytes
        val presented = media3(file)
        assertEquals(168, presented.size)
        assertEquals(168, media3(file, ignoreEditLists).size)
        assertEquals(97_488L, presented.min())
        val evidence = checkNotNull(Mp4EditTimeline.fromMoov(moovOf(file)))
        assertEquals(168, evidence.presentedSamples)
        assertEquals(0, evidence.samplesAfterEditEnd)
        assertTrue(presented.contentEquals(checkNotNull(Mp4EditTimeline.presentedTimesUs(moovOf(file)))))
        val stage = TimelineLocalization.localize(168, evidence.presentedSamples, presented.size, null, null, presented.size)
        assertEquals(stage.reason, TimelineLocalization.NONE, stage.stage)
    }

    @Test
    fun theReaderKeepsWhatMedia3KeepsWhereverThePresentationEnds() {
        // The case at hand, at every frame: the edit ending one track unit before the start of
        // each sample, exactly at it, and one unit after.
        val control = Fixture("edit_whole_168.mp4")
        val shapes = ArrayList<Pair<String, () -> ByteArray>>()
        for ((k, t) in control.ticks.withIndex()) for (nudge in -1L..1L) {
            val offset = t - control.mediaTime + nudge
            if (offset < 0) continue
            shapes += "edit ends ${nudge.signed()} at sample $k" to
                { control.withEdits(control.emptyEdit to -1L, control.durationEndingAt(offset) to control.mediaTime) }
        }
        // 168 samples, three ends each, less the one that would end before the first sample.
        assertTheReaderAgrees(shapes, expected = 168 * 3 - 1)
    }

    @Test
    fun theReaderKeepsWhatMedia3KeepsWhereverThePresentationStarts() {
        // An edit that starts inside a group of pictures keeps samples from before its start, at
        // negative offsets, and depends on which samples are sync samples: with the sync table
        // and without it.
        val control = Fixture("edit_whole_168.mp4")
        val shapes = ArrayList<Pair<String, () -> ByteArray>>()
        for ((k, t) in control.ticks.withIndex()) for (nudge in -1L..1L) for (syncTable in listOf(true, false)) {
            val start = t + nudge
            if (start < 0) continue
            shapes += "edit starts ${nudge.signed()} at sample $k${if (syncTable) "" else ", no sync table"}" to
                { control.withEdits(control.emptyEdit to -1L, control.duration to start, syncTable) }
        }
        assertTheReaderAgrees(shapes, expected = 168 * 3 * 2)
    }

    @Test
    fun theReaderKeepsWhatMedia3KeepsForOneEditAndForSeveral() {
        val control = Fixture("edit_whole_168.mp4")
        val t = control.ticks
        val shapes = ArrayList<Pair<String, () -> ByteArray>>()
        for (k in listOf(0, 1, 2, 3, 7, 47, 48, 49, 100, 167)) {
            // One edit of zero duration is Media3's special case: every sample kept, shifted.
            shapes += "one edit of zero duration from sample $k" to { control.withOneEdit(0L to t[k]) }
            shapes += "one edit of the whole duration from sample $k" to { control.withOneEdit(control.duration to t[k]) }
            shapes += "one edit of a third of the duration from sample $k" to { control.withOneEdit(control.duration / 3 to t[k]) }
        }
        for ((a, b) in listOf(0 to 60, 10 to 90, 48 to 96, 96 to 0, 100 to 20)) {
            shapes += "two edits, from samples $a and $b" to { control.withEdits(control.duration / 4 to t[a], control.duration / 5 to t[b]) }
        }
        // The edges: an edit one unit long, one far longer than the media, one of no length
        // after the empty edit, and one starting past the last sample.
        shapes += "an edit one unit long" to { control.withEdits(control.emptyEdit to -1L, 1L to control.mediaTime) }
        shapes += "an edit longer than the media" to { control.withEdits(control.emptyEdit to -1L, control.duration * 20 to control.mediaTime) }
        shapes += "an edit of no length after the empty edit" to { control.withEdits(control.emptyEdit to -1L, 0L to control.mediaTime) }
        shapes += "an edit starting past the last sample" to { control.withEdits(control.emptyEdit to -1L, control.duration to t.last() + 9_000L) }
        assertTheReaderAgrees(shapes, expected = 10 * 3 + 5 + 4)
    }

    @Test
    fun theReaderKeepsWhatMedia3KeepsWithoutAnEditListOrASyncTable() {
        val shapes = ArrayList<Pair<String, () -> ByteArray>>()
        for (name in listOf("edit_whole_168.mp4", "edit_tail_168.mp4")) {
            val fixture = Fixture(name)
            shapes += "$name as built" to { fixture.bytes }
            shapes += "$name without its edit list" to { fixture.without(editList = true) }
            shapes += "$name without its sync table" to { fixture.without(syncTable = true) }
            shapes += "$name without either" to { fixture.without(editList = true, syncTable = true) }
        }
        assertTheReaderAgrees(shapes, expected = 8)
    }

    @Test
    fun theBoxesTheReaderTestsHoldAreTheDeviceFixturesOwn() {
        // Mp4EditTimelineTest reads edit_*.moov from the test resources; the device test and the
        // checks above read the whole files. They must be one pair of fixtures, not two.
        for (name in listOf("edit_tail_168", "edit_whole_168")) {
            val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream("$name.moov")) { "$name.moov" }.use { it.readBytes() }
            assertTrue("$name.moov is not the moov box of $name.mp4", resource.contentEquals(moovOf(Fixture("$name.mp4").bytes)))
        }
    }

    private fun Long.signed() = when {
        this < 0 -> "one unit before"
        this > 0 -> "one unit after"
        else -> "exactly"
    }
}
