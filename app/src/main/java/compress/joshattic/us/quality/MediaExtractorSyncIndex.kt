package compress.joshattic.us.quality

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable

/**
 * [ProbeWindowPlanner.SyncSampleIndex] over a source's first video track, using the platform
 * extractor's own seek modes. A seek reads the sample table only; no sample data is loaded.
 *
 * Also exposes what the two 30-minute timeouts in b163 needed and did not have: the keyframe
 * structure of the source ([keyframeStats]). One pass over the sample table without reading data
 * costs well under a second for a 30-minute file.
 */
class MediaExtractorSyncIndex private constructor(
    private val extractor: MediaExtractor,
    private val durationUs: Long
) : ProbeWindowPlanner.SyncSampleIndex, Closeable {

    override fun previousSyncUs(targetUs: Long): Long? = seek(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

    override fun nextSyncUs(targetUs: Long): Long? = seek(targetUs, MediaExtractor.SEEK_TO_NEXT_SYNC)

    /**
     * The first [maxGaps] gaps between consecutive keyframes, found by seeking from one sync
     * sample to the next. A seek reads the sample table only, so this costs milliseconds even on
     * a 30-minute file, unlike [structure], which walks every sample. Feeds
     * [KeyframeIntervalPolicy.typicalGapUs].
     */
    fun keyframeGapsUs(maxGaps: Int = 24): List<Long> {
        val gaps = mutableListOf<Long>()
        var previous = nextSyncUs(0L) ?: return gaps
        while (gaps.size < maxGaps) {
            val next = nextSyncUs(previous + 1L) ?: break
            if (next <= previous) break
            gaps += next - previous
            previous = next
        }
        return gaps
    }

    private fun seek(targetUs: Long, mode: Int): Long? = runCatching {
        extractor.seekTo(seekTargetUs(targetUs, durationUs), mode)
        val t = extractor.sampleTime
        if (t < 0L) null else t
    }.getOrNull()

    /**
     * Keyframe structure of the video track and, when the source has audio, how the two tracks
     * are interleaved in the file.
     *
     * Media3's MuxerWrapper never lets one track run more than 500 ms ahead of the other
     * (MAX_TRACK_WRITE_AHEAD_US). With a source whose audio samples are stored after its video
     * samples, the video track cannot be written until the reader reaches the audio, and after
     * 120 s of that the export dies with ERROR_CODE_MUXING_TIMEOUT. That is one candidate for the
     * two 30-minute files in b163; [maxInterleaveSkewUs] is the number that decides it.
     */
    data class Structure(
        val syncSamples: Int,
        val maxGapUs: Long,
        val videoSamples: Int,
        val hasAudio: Boolean,
        /** Largest |video time - audio time| seen while walking the file in storage order. */
        val maxInterleaveSkewUs: Long,
        /** Video time reached when the first audio sample appeared in storage order. */
        val firstAudioAtVideoUs: Long
    ) {
        fun compact(): String =
            "keyframes=$syncSamples,maxGapMs=${maxGapUs / 1000},videoSamples=$videoSamples" +
                (if (hasAudio) ",interleaveSkewMs=${maxInterleaveSkewUs / 1000},firstAudioAtVideoMs=${firstAudioAtVideoUs / 1000}" else ",audio=none")
    }

    /**
     * Walks the sample table in storage order with both tracks selected. No sample data is read.
     * Bounded by [maxSamples] so it cannot run away on a pathological file.
     */
    fun structure(maxSamples: Int = 600_000): Structure? = runCatching {
        val audioTrack = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (audioTrack != null) extractor.selectTrack(audioTrack)
        try {
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var samples = 0
            var videoSamples = 0
            var syncs = 0
            var lastSyncUs = -1L
            var maxGap = 0L
            var lastVideoUs = -1L
            var lastAudioUs = -1L
            var maxSkew = 0L
            var firstAudioAtVideo = -1L
            while (samples < maxSamples) {
                val t = extractor.sampleTime
                if (t < 0L) break
                samples++
                if (audioTrack != null && extractor.sampleTrackIndex == audioTrack) {
                    lastAudioUs = t
                    if (firstAudioAtVideo < 0L) firstAudioAtVideo = lastVideoUs.coerceAtLeast(0L)
                } else {
                    videoSamples++
                    lastVideoUs = t
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        if (lastSyncUs >= 0L) maxGap = maxOf(maxGap, t - lastSyncUs)
                        lastSyncUs = t
                        syncs++
                    }
                }
                if (lastVideoUs >= 0L && lastAudioUs >= 0L) {
                    maxSkew = maxOf(maxSkew, kotlin.math.abs(lastVideoUs - lastAudioUs))
                }
                if (!extractor.advance()) break
            }
            if (lastSyncUs >= 0L) maxGap = maxOf(maxGap, durationUs - lastSyncUs)
            Structure(syncs, maxGap, videoSamples, audioTrack != null, maxSkew, firstAudioAtVideo.coerceAtLeast(0L))
        } finally {
            if (audioTrack != null) runCatching { extractor.unselectTrack(audioTrack) }
        }
    }.getOrNull()

    override fun close() {
        runCatching { extractor.release() }
    }

    companion object {
        /**
         * Where to seek for [targetUs]. The track's duration only bounds the target when it is
         * known: a track without KEY_DURATION (fragmented MP4 from screen recorders, some
         * MKV/WebM) used to clamp every target into [0, 0], so every window snapped to the first
         * keyframe and a long file was certified on its opening seconds alone.
         */
        internal fun seekTargetUs(targetUs: Long, durationUs: Long): Long =
            if (durationUs > 0L) targetUs.coerceIn(0L, durationUs) else targetUs.coerceAtLeast(0L)

        /**
         * Null when the source has no video track or cannot be opened. [fallbackDurationUs] (the
         * item's container duration) stands in when the video track does not record its own.
         */
        fun open(context: Context, uri: Uri, fallbackDurationUs: Long = 0L): MediaExtractorSyncIndex? {
            val extractor = MediaExtractor()
            return runCatching {
                extractor.setDataSource(context, uri, null)
                var track = -1
                var durationUs = 0L
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        track = i
                        durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                        break
                    }
                }
                if (track < 0) error("no video track")
                if (durationUs <= 0L) durationUs = fallbackDurationUs.coerceAtLeast(0L)
                extractor.selectTrack(track)
                MediaExtractorSyncIndex(extractor, durationUs)
            }.getOrElse {
                runCatching { extractor.release() }
                null
            }
        }
    }
}
