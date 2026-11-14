package compress.joshattic.us

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * The video timeline of an MP4 as its sample table holds it and as its edit list presents it.
 *
 * Two readers of one file can count different frames. The sample table lists every sample of the
 * track. An edit list says which stretch of that media is the presentation, and samples that
 * start after an edit ends are not part of it. Android's MediaExtractor, which the verifier reads
 * the source with, returns the sample table. Media3's MP4 parser (BoxParser.parseStbl, 1.11.0)
 * applies the edit list and leaves such samples out, so a Transformer export never decodes them.
 *
 * b287 regular job_782a717d3217 recorded 168 source samples and 166 in the output, with the
 * source's first video timestamp at 97,488 us. A video track delayed by an initial empty edit and
 * running two frames past the end of its presentation edit gives exactly that pair with no frame
 * lost by the encoder, the muxer or the metadata remux. The capture cannot say whether that file is
 * built so. This reads the boxes that answer it, when a timeline comparison fails.
 *
 * Evidence only. It decides nothing: the verifier compares every sample of the source's table,
 * and a count that differs keeps the original. That is the rule and no longer an open question
 * (decided November 12, MediaTimelineEvidence): the platform's reader lists the samples an edit
 * list leaves out, so to a player built on it a candidate without them is short.
 *
 * Pure (java.nio only), so it is tested without a device. Unfragmented MP4/MOV, one video track.
 * scripts/diagnostics/mp4_edit_timeline.py is the same reading for files on a workstation.
 *
 * It is a second implementation of Media3's rule, so Media3ParserParityTest runs Media3's own
 * Mp4Extractor on the build machine and holds this against it: the samples kept and the time of
 * each, over edits that end and start around every sample of a synthetic source. A Media3
 * upgrade that changes how an edit list is applied fails there.
 */
object Mp4EditTimeline {

    data class Evidence(
        /** Samples in the video track's table: what MediaExtractor returns. */
        val tableSamples: Int,
        /** Samples Media3 1.11.0 keeps after applying the edit list. */
        val presentedSamples: Int,
        /** Table samples that start after the last edit ends and are not presented. */
        val samplesAfterEditEnd: Int,
        /** Table samples before the first edit starts that are not presented. */
        val samplesBeforeEditStart: Int,
        val initialEmptyEditUs: Long,
        val editCount: Int,
        val movieDurationUs: Long,
        val mediaDurationUs: Long,
        val tableFirstUs: Long?,
        val presentedFirstUs: Long?,
        /** Samples are stored out of presentation order (B-frames). */
        val storedOutOfOrder: Boolean
    ) {
        fun fields(): Map<String, Any?> = linkedMapOf(
            "tableSamples" to tableSamples,
            "presentedSamples" to presentedSamples,
            "samplesAfterEditEnd" to samplesAfterEditEnd,
            "samplesBeforeEditStart" to samplesBeforeEditStart,
            "initialEmptyEditUs" to initialEmptyEditUs,
            "editCount" to editCount,
            "movieDurationUs" to movieDurationUs,
            "mediaDurationUs" to mediaDurationUs,
            "tableFirstUs" to tableFirstUs,
            "presentedFirstUs" to presentedFirstUs,
            "storedOutOfOrder" to storedOutOfOrder
        )
    }

    /** Most reorder Media3 searches past an edit's end; 16 is the limit for HEVC and H.264. */
    private const val MAX_REORDER = 16

    /**
     * Reads the moov box through [channel] by walking top-level box headers (mdat is skipped, never
     * read). Null when there is no moov, it is larger than [maxMoovBytes], or it does not hold
     * exactly one video track with a sample table. Never throws for a malformed file.
     */
    fun read(channel: SeekableByteChannel, maxMoovBytes: Long = 64L shl 20): Evidence? = runCatching {
        var position = 0L
        val header = ByteBuffer.allocate(16)
        val total = channel.size()
        while (position + 8 <= total) {
            header.clear().limit(8)
            channel.position(position)
            if (!fill(channel, header)) return null
            header.flip()
            var size = header.int.toLong() and 0xFFFFFFFFL
            val kind = header.int
            var headerBytes = 8L
            if (size == 1L) {
                header.clear().limit(8)
                if (!fill(channel, header)) return null
                header.flip()
                size = header.long
                headerBytes = 16L
            } else if (size == 0L) {
                size = total - position
            }
            if (size < headerBytes) return null
            if (kind == MOOV) {
                val payload = size - headerBytes
                if (payload <= 0 || payload > maxMoovBytes) return null
                val moov = ByteBuffer.allocate(payload.toInt())
                channel.position(position + headerBytes)
                if (!fill(channel, moov)) return null
                return fromMoov(moov.array())
            }
            position += size
        }
        null
    }.getOrNull()

    private fun fill(channel: SeekableByteChannel, buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return false
        return true
    }

    private class Box(val kind: Int, val start: Int, val end: Int)

    private fun boxes(data: ByteBuffer, start: Int, end: Int): List<Box> {
        val found = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = data.getInt(p).toLong() and 0xFFFFFFFFL
            val kind = data.getInt(p + 4)
            var headerBytes = 8
            if (size == 1L) {
                if (p + 16 > end) break
                size = data.getLong(p + 8)
                headerBytes = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < headerBytes || p + size > end) break
            found += Box(kind, p + headerBytes, (p + size).toInt())
            p += size.toInt()
        }
        return found
    }

    private fun ByteBuffer.find(kind: Int, start: Int, end: Int): Box? = boxes(this, start, end).firstOrNull { it.kind == kind }

    private class Track(
        val timescale: Long, val mediaDuration: Long, val timestamps: LongArray,
        val sync: BooleanArray?, val edits: List<Pair<Long, Long>>?
    )

    private class Movie(val timescale: Long, val duration: Long, val video: Track)

    /** The movie header and the one video track; null when the moov does not hold exactly that. */
    private fun movie(moov: ByteArray): Movie? {
        val data = ByteBuffer.wrap(moov)
        val mvhd = data.find(MVHD, 0, moov.size) ?: return null
        val movieVersion = data.get(mvhd.start).toInt()
        val movieTimescale = (data.getInt(mvhd.start + 4 + if (movieVersion == 1) 16 else 8).toLong() and 0xFFFFFFFFL)
        val movieDuration = if (movieVersion == 1) data.getLong(mvhd.start + 4 + 20) else data.getInt(mvhd.start + 4 + 12).toLong() and 0xFFFFFFFFL
        if (movieTimescale <= 0) return null
        val videos = boxes(data, 0, moov.size).filter { it.kind == TRAK }.mapNotNull { track(data, it) }
        return Movie(movieTimescale, movieDuration, videos.singleOrNull() ?: return null)
    }

    /**
     * The presentation time of every sample Media3 keeps, in stored order: what its extractor
     * hands on, sample for sample. Media3ParserParityTest holds this against Media3's own parser.
     */
    internal fun presentedTimesUs(moov: ByteArray): LongArray? = runCatching {
        val movie = movie(moov) ?: return null
        presented(movie.video, movie.timescale).map { it.second }.toLongArray()
    }.getOrNull()

    /** The moov box's payload to evidence; null when it has no single video track to describe. */
    internal fun fromMoov(moov: ByteArray): Evidence? = runCatching {
        val movie = movie(moov) ?: return null
        val track = movie.video
        val movieTimescale = movie.timescale
        val movieDuration = movie.duration
        val kept = presented(track, movieTimescale)
        val keptIndices = BooleanArray(track.timestamps.size).also { flags -> kept.forEach { flags[it.first] = true } }
        val edits = track.edits.orEmpty()
        val ends = edits.filter { it.second != -1L && it.first != 0L }.map { it.second + it.first * track.timescale / movieTimescale }
        val starts = edits.filter { it.second != -1L }.map { it.second }
        fun us(value: Long, base: Long) = value * 1_000_000L / base
        Evidence(
            tableSamples = track.timestamps.size,
            presentedSamples = kept.size,
            samplesAfterEditEnd = if (ends.isEmpty()) 0 else track.timestamps.indices.count { track.timestamps[it] > ends.max() && !keptIndices[it] },
            samplesBeforeEditStart = if (starts.isEmpty()) 0 else track.timestamps.indices.count { track.timestamps[it] < starts.min() && !keptIndices[it] },
            initialEmptyEditUs = us(edits.filter { it.second == -1L }.sumOf { it.first }, movieTimescale),
            editCount = edits.size,
            movieDurationUs = us(movieDuration, movieTimescale),
            mediaDurationUs = us(track.mediaDuration, track.timescale),
            tableFirstUs = track.timestamps.minOrNull()?.let { us(it, track.timescale) },
            presentedFirstUs = kept.minOfOrNull { it.second },
            storedOutOfOrder = (1 until track.timestamps.size).any { track.timestamps[it] < track.timestamps[it - 1] }
        )
    }.getOrNull()

    private fun track(data: ByteBuffer, trak: Box): Track? {
        val mdia = data.find(MDIA, trak.start, trak.end) ?: return null
        val hdlr = data.find(HDLR, mdia.start, mdia.end) ?: return null
        if (data.getInt(hdlr.start + 8) != VIDE) return null
        val mdhd = data.find(MDHD, mdia.start, mdia.end) ?: return null
        val version = data.get(mdhd.start).toInt()
        val timescale = data.getInt(mdhd.start + 4 + if (version == 1) 16 else 8).toLong() and 0xFFFFFFFFL
        val mediaDuration = if (version == 1) data.getLong(mdhd.start + 4 + 20) else data.getInt(mdhd.start + 4 + 12).toLong() and 0xFFFFFFFFL
        if (timescale <= 0) return null
        val minf = data.find(MINF, mdia.start, mdia.end) ?: return null
        val stbl = data.find(STBL, minf.start, minf.end) ?: return null
        val stts = data.find(STTS, stbl.start, stbl.end) ?: return null
        val decode = ArrayList<Long>()
        var time = 0L
        for (i in 0 until data.getInt(stts.start + 4)) {
            val count = data.getInt(stts.start + 8 + 8 * i)
            val delta = data.getInt(stts.start + 12 + 8 * i).toLong() and 0xFFFFFFFFL
            require(count >= 0 && decode.size.toLong() + count <= MAX_SAMPLES) { "sample budget exceeded" }
            repeat(count) { decode += time; time += delta }
        }
        val timestamps = decode.toLongArray()
        data.find(CTTS, stbl.start, stbl.end)?.let { ctts ->
            var index = 0
            for (i in 0 until data.getInt(ctts.start + 4)) {
                val count = data.getInt(ctts.start + 8 + 8 * i)
                // Version 0 is unsigned by the letter of the spec; writers store negative offsets
                // there too, and reading it signed is what Media3 does.
                val offset = data.getInt(ctts.start + 12 + 8 * i).toLong()
                repeat(count) { if (index < timestamps.size) timestamps[index] += offset; index++ }
            }
        }
        val sync = data.find(STSS, stbl.start, stbl.end)?.let { stss ->
            BooleanArray(timestamps.size).also { flags ->
                for (i in 0 until data.getInt(stss.start + 4)) {
                    val sample = data.getInt(stss.start + 8 + 4 * i) - 1
                    if (sample in flags.indices) flags[sample] = true
                }
            }
        }
        val edits = data.find(EDTS, trak.start, trak.end)?.let { edts -> data.find(ELST, edts.start, edts.end) }?.let { elst ->
            val elstVersion = data.get(elst.start).toInt()
            var p = elst.start + 8
            (0 until data.getInt(elst.start + 4)).map {
                if (elstVersion == 1) (data.getLong(p) to data.getLong(p + 8)).also { p += 20 }
                else ((data.getInt(p).toLong() and 0xFFFFFFFFL) to data.getInt(p + 4).toLong()).also { p += 12 }
            }
        }
        return Track(timescale, mediaDuration, timestamps, sync, edits)
    }

    /**
     * (sample index, presentation time in microseconds) for each sample Media3 1.11.0 keeps,
     * following BoxParser.parseStbl step for step: no edit list keeps everything; one edit of zero
     * duration keeps everything, shifted; otherwise each non-empty edit keeps the samples from the
     * sync sample at or before its start to the last one starting at or before its end, searching
     * the STORED order as Media3 does and then past the end for frames stored out of order.
     */
    private fun presented(track: Track, movieTimescale: Long): List<Pair<Int, Long>> {
        val ts = track.timestamps
        // Toward zero, as Media3's Util.scaleLargeTimestamp rounds. A sample kept from before an
        // edit's start has a negative offset, and flooring it would be a microsecond early.
        fun us(value: Long, base: Long) = value * 1_000_000L / base
        val edits = track.edits ?: return ts.indices.map { it to us(ts[it], track.timescale) }
        if (edits.size == 1 && edits[0].first == 0L) return ts.indices.map { it to us(ts[it] - edits[0].second, track.timescale) }
        fun isSync(i: Int) = track.sync?.get(i) ?: true
        val kept = ArrayList<Pair<Int, Long>>()
        var pts = 0L
        for ((duration, mediaTime) in edits) {
            if (mediaTime != -1L && ts.isNotEmpty()) {
                val endTime = mediaTime + duration * track.timescale / movieTimescale
                var start = floorInclusive(ts, mediaTime)
                val firstAfter = ceilExclusive(ts, endTime)
                var lastValid = firstAfter - 1
                var seenAfter = 0
                for (j in firstAfter until ts.size) {
                    if (ts[j] < endTime) lastValid = j
                    else if (++seenAfter > MAX_REORDER) break
                }
                val end = lastValid + 1
                val initial = start
                while (start > 0 && !isSync(start)) start--
                if (start == 0 && !isSync(0)) {
                    start = initial
                    while (start < end && !isSync(start)) start++
                }
                val base = us(pts, movieTimescale)
                for (j in start until end) kept += j to base + us(ts[j] - mediaTime, track.timescale)
            }
            pts += duration
        }
        return kept
    }

    /** Media3 Util.binarySearchFloor(array, value, inclusive = true, stayInBounds = true). */
    private fun floorInclusive(array: LongArray, value: Long): Int {
        var index = java.util.Arrays.binarySearch(array, value)
        if (index < 0) {
            index = -(index + 2)
        } else {
            while (--index >= 0 && array[index] == value) { /* walk to the first equal element */ }
            index++
        }
        return maxOf(0, index)
    }

    /** Media3 Util.binarySearchCeil(array, value, inclusive = false, stayInBounds = false). */
    private fun ceilExclusive(array: LongArray, value: Long): Int {
        var index = java.util.Arrays.binarySearch(array, value)
        if (index < 0) return index.inv()
        while (++index < array.size && array[index] == value) { /* walk past the equal elements */ }
        return index
    }

    private const val MAX_SAMPLES = 4_000_000L
    private fun fourCc(text: String) = text.fold(0) { acc, c -> (acc shl 8) or c.code }
    private val MOOV = fourCc("moov")
    private val MVHD = fourCc("mvhd")
    private val TRAK = fourCc("trak")
    private val MDIA = fourCc("mdia")
    private val HDLR = fourCc("hdlr")
    private val VIDE = fourCc("vide")
    private val MDHD = fourCc("mdhd")
    private val MINF = fourCc("minf")
    private val STBL = fourCc("stbl")
    private val STTS = fourCc("stts")
    private val CTTS = fourCc("ctts")
    private val STSS = fourCc("stss")
    private val EDTS = fourCc("edts")
    private val ELST = fourCc("elst")
}

/**
 * Where a video sample count first changed between the source and the finished candidate, from
 * the counts each stage reported. A statement about counts, written so a capture can localize a
 * timeline failure without the media: the verifier's verdict does not read it.
 */
object TimelineLocalization {
    const val NONE = "none"
    /** The source's edit list presents fewer samples than its table holds, and Media3 read those. */
    const val SOURCE_EDIT_LIST = "source_edit_list"
    /** Media3 reported writing a different number of frames than it was given to read. */
    const val MEDIA3_EXPORT = "media3_export"
    /** The closed Media3 file holds a different number of samples than Media3 reported writing. */
    const val MEDIA3_OUTPUT_FILE = "media3_output_file"
    /** The metadata remux wrote a different number of samples than it read. */
    const val METADATA_REMUX = "metadata_remux"
    /** After the last stage that reported a count: between the remux and the verifier's read. */
    const val AFTER_REMUX = "after_remux"
    /** The counts differ and no intermediate stage reported one. */
    const val UNKNOWN = "unknown"

    data class Result(val stage: String, val reason: String) {
        fun fields(): Map<String, Any?> = linkedMapOf("firstChangedStage" to stage, "reason" to reason)
    }

    /**
     * @param sourceTableSamples samples in the source's table (MediaExtractor): the verifier's reference.
     * @param sourcePresentedSamples samples the source's edit list presents (Mp4EditTimeline); null when unread.
     * @param media3FrameCount ExportResult.videoFrameCount; null or 0 when Media3 did not report one.
     * @param remuxReadSamples video samples the metadata remux read from the closed Media3 file; null when it did not run.
     * @param remuxWrittenSamples video samples it wrote; null when it did not run.
     * @param finalSamples samples in the candidate the verifier read.
     */
    fun localize(
        sourceTableSamples: Int,
        sourcePresentedSamples: Int?,
        media3FrameCount: Int?,
        remuxReadSamples: Int?,
        remuxWrittenSamples: Int?,
        finalSamples: Int
    ): Result {
        if (finalSamples == sourceTableSamples) return Result(NONE, "the candidate holds every sample of the source's table ($sourceTableSamples)")
        val media3 = media3FrameCount?.takeIf { it > 0 }
        // What Media3 was given: the presented samples when the edit list was read, else the table.
        val given = sourcePresentedSamples ?: sourceTableSamples
        val firstReported = media3 ?: remuxReadSamples ?: finalSamples
        if (sourcePresentedSamples != null && sourcePresentedSamples != sourceTableSamples && firstReported == sourcePresentedSamples) {
            return Result(
                SOURCE_EDIT_LIST,
                "the source's edit list presents $sourcePresentedSamples of the $sourceTableSamples samples in its table, and " +
                    "the first stage that reported a count had $firstReported: Media3 read what the edit list presents"
            )
        }
        if (media3 != null && media3 != given) {
            return Result(MEDIA3_EXPORT, "Media3 reported $media3 video frames written from $given it was given to read")
        }
        if (media3 != null && remuxReadSamples != null && remuxReadSamples != media3) {
            return Result(MEDIA3_OUTPUT_FILE, "the closed Media3 file holds $remuxReadSamples video samples; Media3 reported writing $media3")
        }
        if (remuxReadSamples != null && remuxWrittenSamples != null && remuxWrittenSamples != remuxReadSamples) {
            return Result(METADATA_REMUX, "the metadata remux read $remuxReadSamples video samples and wrote $remuxWrittenSamples")
        }
        if (remuxWrittenSamples != null && finalSamples != remuxWrittenSamples) {
            return Result(AFTER_REMUX, "the metadata remux wrote $remuxWrittenSamples video samples; the candidate read back holds $finalSamples")
        }
        return Result(
            UNKNOWN,
            "$sourceTableSamples samples in the source's table, $finalSamples in the candidate" +
                (if (sourcePresentedSamples == null) "; the source's edit list was not read" else "; the source's edit list presents $sourcePresentedSamples") +
                (if (media3 == null) "; Media3 reported no frame count" else "; Media3 reported $media3") +
                (if (remuxReadSamples == null) "; no metadata remux ran" else "; the remux read $remuxReadSamples")
        )
    }
}
