package compress.joshattic.us

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * Media3's own classes, run on the build machine.
 *
 * Parts of what the app records about a Transformer export are statements about Media3: which
 * samples of a source its parser hands on, and what its muxer does to their timestamps. This
 * runs the classes the app ships (the build's media3-extractor, media3-muxer and
 * media3-transformer artifacts, not copies) so those statements are observed, and stay observed
 * across a Media3 upgrade.
 *
 * Media3 links against a few Android classes whose SDK stubs throw when called off a device.
 * src/test/media3host holds small replacements for them and the probes that drive Media3. They
 * are outside every source set: this compiles them once per test JVM into a temporary directory
 * and loads them, with the Media3 jars, in a class loader whose parent holds only the JDK. No
 * other test sees a replaced Android class, and nothing is packaged.
 *
 * What it cannot stand in for is a phone: the platform MediaExtractor and MediaMuxer, the codecs,
 * and Transformer's own threading run only there (EditListTimelineDeviceTest).
 */
internal object Media3Host {

    /** A file of the app module, from the module directory Gradle runs unit tests in or from the repository root. */
    fun inModule(path: String): File =
        listOf(path, "app/$path").map(::File).firstOrNull { it.exists() } ?: error("$path not found from ${File("").absolutePath}")

    /** The jar a class of the unit-test classpath comes from, without initializing the class. */
    private fun jarOf(className: String): File {
        val type = Class.forName(className, false, Media3Host::class.java.classLoader)
        val location = type.protectionDomain?.codeSource?.location ?: error("$className has no code source")
        return File(location.toURI())
    }

    private val loader: ClassLoader by lazy {
        val sources = inModule("src/test/media3host").walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        check(sources.any { it.name == "Media3Mp4Probe.java" }) { "the probe's source is missing from src/test/media3host" }
        // The Media3 classes the app ships, the Guava they call, and the SDK stubs for every
        // Android class the replacements do not cover.
        val libraries = listOf(
            "androidx.media3.extractor.mp4.Mp4Extractor",
            "androidx.media3.common.Format",
            "androidx.media3.container.NalUnitUtil",
            "androidx.media3.muxer.Mp4Muxer",
            "androidx.media3.transformer.Transformer",
            "androidx.media3.effect.DebugTraceUtil",
            "com.google.common.collect.ImmutableList",
            "android.os.Parcelable"
        ).map(::jarOf).distinct()
        val compiled = Files.createTempDirectory("media3host").toFile()
        // The JDK's compiler and platform class loader, by name: unit tests are compiled against
        // the Android API surface, which has neither, and run on a JDK, which has both.
        val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
            ?: error("no Java compiler in this JVM: the unit tests must run on a JDK")
        val run = Class.forName("javax.tools.Tool")
            .getMethod("run", InputStream::class.java, OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
        val log = ByteArrayOutputStream()
        val arguments = arrayOf("-d", compiled.path, "-classpath", libraries.joinToString(File.pathSeparator), "-proc:none", "-nowarn", "-Xlint:none") +
            sources.map { it.path }
        val status = run.invoke(compiler, null, log, log, arguments) as Int
        check(status == 0) { "the host probes did not compile against this build's Media3:\n$log" }
        // The compiled replacements come first, and the parent holds only the JDK.
        val jdkOnly = ClassLoader::class.java.getMethod("getPlatformClassLoader").invoke(null) as ClassLoader
        URLClassLoader((listOf(compiled) + libraries).map { it.toURI().toURL() }.toTypedArray(), jdkOnly).also { created ->
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching { created.close() }
                compiled.deleteRecursively()
            })
        }
    }

    private val parser: Class<*> by lazy { loader.loadClass("hostprobe.Media3Mp4Probe") }
    private val muxer: Class<*> by lazy { loader.loadClass("androidx.media3.transformer.Media3TransmuxProbe") }

    /** The Media3 the probes were compiled against and ran, e.g. "1.11.0". */
    val version: String by lazy { parser.getMethod("media3Version").invoke(null) as String }

    /** Mp4Extractor's flag for reading a sample table as stored, edit lists ignored. */
    val ignoreEditLists: Int by lazy { parser.getMethod("ignoreEditListsFlag").invoke(null) as Int }

    private fun <T> onMedia3(what: String, call: () -> T): T = try {
        call()
    } catch (e: InvocationTargetException) {
        throw AssertionError(
            "Media3 $version's $what did not run on the build machine: ${e.targetException}. " +
                "If that names an android method, src/test/media3host needs a replacement for its class.",
            e.targetException
        )
    }

    /** The video sample times Media3's Mp4Extractor emits for [file], in the order it emits them. */
    fun videoTimesUs(file: ByteArray, flags: Int = 0): LongArray = onMedia3("Mp4Extractor") {
        parser.getMethod("videoTimesUs", ByteArray::class.java, Int::class.javaPrimitiveType).invoke(null, file, flags) as LongArray
    }

    /** One track as Media3's Mp4Extractor emits it, edit lists applied. */
    class Track(private val fields: Map<String, Any?>) {
        val timesUs: LongArray get() = fields.getValue("timesUs") as LongArray
        /** SHA-256 of each sample's payload, in emission order. */
        @Suppress("UNCHECKED_CAST")
        val payloadDigests: List<String> get() = (fields.getValue("payloadDigests") as Array<String>).toList()
        /** The codec's initialization buffers, hex: for AAC, the AudioSpecificConfig. */
        @Suppress("UNCHECKED_CAST")
        val codecData: List<String> get() = fields.getValue("codecData") as List<String>
        val mime: String? get() = fields["mime"] as String?
        /** Samples to drop at the start and at the end, as Media3 derives them from an audio edit list; -1 without one. */
        val encoderDelay: Int get() = fields.getValue("encoderDelay") as Int
        val encoderPadding: Int get() = fields.getValue("encoderPadding") as Int
    }

    /** The video and audio tracks of [file], keyed "video" and "audio". */
    fun describe(file: ByteArray): Map<String, Track> = onMedia3("Mp4Extractor") {
        @Suppress("UNCHECKED_CAST")
        val described = parser.getMethod("describe", ByteArray::class.java).invoke(null, file) as Map<String, Map<String, Any?>>
        described.mapValues { Track(it.value) }
    }

    /** What Media3's muxer wrapper reported for one write. */
    class Written(private val report: Map<String, Any?>) {
        /** Video samples the wrapper wrote: the number an export reports as ExportResult.videoFrameCount. */
        val videoSamples: Int? get() = report["videoSamplesWritten"] as Int?
        val audioSamples: Int? get() = report["audioSamplesWritten"] as Int?
        /** What Media3 logged as a warning or an error while writing, as "Tag: message". */
        @Suppress("UNCHECKED_CAST")
        val log: List<String> get() = report.getValue("log") as List<String>
        /** The timestamp of each sample as the wrapper handed it to the muxer, in the order handed. */
        val videoTimesAtMuxerUs: LongArray get() = report.getValue("videoTimesAtMuxerUs") as LongArray
        val audioTimesAtMuxerUs: LongArray get() = report.getValue("audioTimesAtMuxerUs") as LongArray
    }

    /**
     * Writes the chosen tracks of [source], as Media3's extractor emits them, through Media3's
     * MuxerWrapper and default muxer into [output]: a Transformer export with the codecs left out.
     */
    fun transmux(source: ByteArray, output: File, video: Boolean = true, audio: Boolean = true): Written = onMedia3("MuxerWrapper") {
        @Suppress("UNCHECKED_CAST")
        val report = muxer.getMethod("transmux", ByteArray::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(null, source, output.absolutePath, video, audio) as Map<String, Any?>
        Written(report)
    }
}

/** The few MP4 boxes these tests read or rewrite. Sizes never change: a rewrite is in place. */
internal object Mp4Boxes {

    class Box(val kind: String, val start: Int, val end: Int)

    /** The boxes directly inside [start, end): each one's type, where its payload starts and where it ends. */
    fun boxes(data: ByteArray, start: Int = 0, end: Int = data.size): List<Box> {
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

    fun List<Box>.one(kind: String): Box = single { it.kind == kind }

    fun moov(file: ByteArray): Box = boxes(file).one("moov")

    /** The moov box's payload, which is what Mp4EditTimeline.fromMoov reads. */
    fun moovPayload(file: ByteArray): ByteArray = moov(file).let { file.copyOfRange(it.start, it.end) }

    /** The timescale of an mvhd or mdhd box. */
    fun timescale(file: ByteArray, header: Box): Long =
        ByteBuffer.wrap(file).getInt(header.start + 4 + if (file[header.start].toInt() == 1) 16 else 8).toLong() and 0xFFFFFFFFL

    /** The duration of an mvhd or mdhd box, in its own timescale. */
    fun duration(file: ByteArray, header: Box): Long = ByteBuffer.wrap(file).let { data ->
        if (file[header.start].toInt() == 1) data.getLong(header.start + 4 + 20) else data.getInt(header.start + 4 + 12).toLong() and 0xFFFFFFFFL
    }

    /** The one track whose handler is [handler] ("vide" or "soun"). */
    fun track(file: ByteArray, handler: String): Box = boxes(file, moov(file).start, moov(file).end).filter { it.kind == "trak" }.single { trak ->
        val mdia = boxes(file, trak.start, trak.end).one("mdia")
        val hdlr = boxes(file, mdia.start, mdia.end).one("hdlr")
        String(file, hdlr.start + 8, 4, Charsets.ISO_8859_1) == handler
    }

    /** [file] with the edit list of its [handler] track turned into free space: the track has none. */
    fun withoutEditList(file: ByteArray, handler: String): ByteArray = file.copyOf().also { copy ->
        val trak = track(file, handler)
        "free".toByteArray(Charsets.ISO_8859_1).copyInto(copy, boxes(file, trak.start, trak.end).one("edts").start - 4)
    }

    /** A track's edit list as (duration in movie units, media time in track units), or null without one. */
    fun edits(file: ByteArray, trak: Box): List<Pair<Long, Long>>? {
        val edts = boxes(file, trak.start, trak.end).singleOrNull { it.kind == "edts" } ?: return null
        val elst = boxes(file, edts.start, edts.end).one("elst")
        val data = ByteBuffer.wrap(file)
        val wide = file[elst.start].toInt() == 1
        var p = elst.start + 8
        return (0 until data.getInt(elst.start + 4)).map {
            if (wide) (data.getLong(p) to data.getLong(p + 8)).also { p += 20 }
            else ((data.getInt(p).toLong() and 0xFFFFFFFFL) to data.getInt(p + 4).toLong()).also { p += 12 }
        }
    }
}
