package compress.joshattic.us

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.file.Files

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
 * Media3 needs a few Android classes that only throw off a device. src/test/media3host holds
 * small replacements and the probe, compiled here into a temporary directory and loaded with the
 * Media3 jars in a class loader of their own, so no other test sees a replaced Android class.
 */
class Media3ParserParityTest {

    companion object {
        private lateinit var loader: URLClassLoader
        private lateinit var compiled: File
        private lateinit var videoTimesUs: Method
        private lateinit var media3Version: String
        private var ignoreEditLists = 0

        private fun inModule(path: String): File =
            listOf(path, "app/$path").map(::File).firstOrNull { it.exists() } ?: error("$path not found from ${File("").absolutePath}")

        /** The jar a class of the unit-test classpath comes from, without initializing the class. */
        private fun jarOf(className: String): File {
            val type = Class.forName(className, false, Media3ParserParityTest::class.java.classLoader)
            val location = type.protectionDomain?.codeSource?.location ?: error("$className has no code source")
            return File(location.toURI())
        }

        @BeforeClass
        @JvmStatic
        fun compileTheProbe() {
            val sources = inModule("src/test/media3host").walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
            check(sources.any { it.name == "Media3Mp4Probe.java" }) { "the probe's source is missing from src/test/media3host" }
            // The Media3 classes the app ships, the Guava they call, and the SDK stubs for every
            // Android class the replacements do not cover.
            val libraries = listOf(
                "androidx.media3.extractor.mp4.Mp4Extractor",
                "androidx.media3.common.Format",
                "androidx.media3.container.NalUnitUtil",
                "com.google.common.collect.ImmutableList",
                "android.os.Parcelable"
            ).map(::jarOf).distinct()
            compiled = Files.createTempDirectory("media3host").toFile()
            // The JDK's compiler and platform class loader, by name: unit tests are compiled
            // against the Android API surface, which has neither, and run on a JDK, which has both.
            val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
                ?: error("no Java compiler in this JVM: the unit tests must run on a JDK")
            val run = Class.forName("javax.tools.Tool")
                .getMethod("run", InputStream::class.java, OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
            val log = ByteArrayOutputStream()
            val arguments = arrayOf("-d", compiled.path, "-classpath", libraries.joinToString(File.pathSeparator), "-proc:none", "-nowarn", "-Xlint:none") +
                sources.map { it.path }
            val status = run.invoke(compiler, null, log, log, arguments) as Int
            check(status == 0) { "the host probe did not compile against this build's Media3:\n$log" }
            // The compiled replacements come first, and the parent holds only the JDK.
            val jdkOnly = ClassLoader::class.java.getMethod("getPlatformClassLoader").invoke(null) as ClassLoader
            loader = URLClassLoader((listOf(compiled) + libraries).map { it.toURI().toURL() }.toTypedArray(), jdkOnly)
            val probe = loader.loadClass("hostprobe.Media3Mp4Probe")
            videoTimesUs = probe.getMethod("videoTimesUs", ByteArray::class.java, Int::class.javaPrimitiveType)
            media3Version = probe.getMethod("media3Version").invoke(null) as String
            ignoreEditLists = probe.getMethod("ignoreEditListsFlag").invoke(null) as Int
        }

        @AfterClass
        @JvmStatic
        fun closeTheProbe() {
            if (::loader.isInitialized) loader.close()
            if (::compiled.isInitialized) compiled.deleteRecursively()
        }
    }

    /** The video sample times Media3's extractor emits for [file], in the order it emits them. */
    private fun media3(file: ByteArray, flags: Int = 0): LongArray = try {
        videoTimesUs.invoke(null, file, flags) as LongArray
    } catch (e: InvocationTargetException) {
        throw AssertionError(
            "Media3 $media3Version's Mp4Extractor did not run on the build machine: ${e.targetException}. " +
                "If that names an android method, src/test/media3host needs a replacement for its class.",
            e.targetException
        )
    }

    private class Box(val kind: String, val start: Int, val end: Int)

    private fun boxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val buffer = ByteBuffer.wrap(data)
        val found = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = buffer.getInt(p).toLong() and 0xFFFFFFFFL
            val kind = String(data, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = buffer.getLong(p + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            check(size >= header && p + size <= end) { "malformed box '$kind' at $p" }
            found += Box(kind, p + header, (p + size).toInt())
            p += size.toInt()
        }
        return found
    }

    private fun moovOf(file: ByteArray): ByteArray = boxes(file, 0, file.size).single { it.kind == "moov" }.let { file.copyOfRange(it.start, it.end) }

    /**
     * One of the synthetic sources, and where its video track's edit list and sync table are, so
     * an edit can be rewritten in place. No sample data is touched and no box changes size.
     */
    private inner class Fixture(name: String) {
        val bytes: ByteArray = inModule("src/androidTest/assets/$name").readBytes()
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
            val moov = boxes(bytes, 0, bytes.size).single { it.kind == "moov" }
            val inMoov = boxes(bytes, moov.start, moov.end)
            fun timescaleAt(header: Box) = data.getInt(header.start + 4 + if (bytes[header.start].toInt() == 1) 16 else 8).toLong() and 0xFFFFFFFFL
            movieTimescale = timescaleAt(inMoov.single { it.kind == "mvhd" })
            val video = inMoov.filter { it.kind == "trak" }.single { trak ->
                val mdia = boxes(bytes, trak.start, trak.end).single { it.kind == "mdia" }
                val hdlr = boxes(bytes, mdia.start, mdia.end).single { it.kind == "hdlr" }
                String(bytes, hdlr.start + 8, 4, Charsets.ISO_8859_1) == "vide"
            }
            val inTrack = boxes(bytes, video.start, video.end)
            val mdia = inTrack.single { it.kind == "mdia" }
            val inMedia = boxes(bytes, mdia.start, mdia.end)
            timescale = timescaleAt(inMedia.single { it.kind == "mdhd" })
            val minf = inMedia.single { it.kind == "minf" }
            val stbl = boxes(bytes, minf.start, minf.end).single { it.kind == "stbl" }
            stssKind = boxes(bytes, stbl.start, stbl.end).single { it.kind == "stss" }.start - 4
            val edts = inTrack.single { it.kind == "edts" }
            edtsKind = edts.start - 4
            elst = boxes(bytes, edts.start, edts.end).single { it.kind == "elst" }.start
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
