package compress.joshattic.us.quality

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * What a probe clip says about the bitrate the FULL encode will produce.
 *
 * The probe clip is a real encode of this file by this encoder at the requested bitrate, so its
 * sample sizes are the best available estimate of the encoder's overshoot on this content. In b165
 * job_732f7ecfb699 (4K portrait) the full encode came out 23% above its request and was rejected
 * for size after four and a half minutes; the probe clips had encoded the same content minutes
 * earlier. Every rung logs its measured window bitrate and the steady-state prediction next to
 * the request. b166/b167 checked the model against the `encodeResult` lines of the same files (40
 * encodes): it runs high, by 0.030 on average (sd 0.033). The proven rung's factors now feed the
 * worth-encoding prediction, through the lower bound in MeasuredOvershoot, never directly.
 *
 * The model separates keyframes from the rest because a 1.2 s window holds a whole keyframe
 * whichever way the GOP falls, which would over-count intra bits by the ratio of GOP to window.
 * [predictedSteadyStateBps] spreads one keyframe over a GOP instead.
 */
object ProbeClipBitrate {

    /** Sample bytes of a clip split by sync flag over [spanUs] of presentation time. */
    data class Split(
        val syncBytes: Long,
        val syncCount: Int,
        val otherBytes: Long,
        val otherCount: Int,
        val spanUs: Long
    ) {
        val totalBytes: Long get() = syncBytes + otherBytes
    }

    /** Bits per second actually written for the measured span, or null when nothing was measured. */
    fun measuredBps(split: Split): Long? =
        if (split.spanUs <= 0L || split.totalBytes <= 0L) null else split.totalBytes * 8_000_000L / split.spanUs

    /**
     * Bits per second the encoder would produce at [fps] with one keyframe every [gopSeconds],
     * given the clip's mean keyframe and mean non-keyframe sizes. Null when no non-keyframes were
     * measured. Without a keyframe sample the keyframe is assumed to cost a non-keyframe, which
     * under-predicts; the caller sees that in [Split.syncCount].
     */
    fun predictedSteadyStateBps(split: Split, fps: Double, gopSeconds: Float): Long? {
        if (split.otherCount <= 0 || fps <= 0.0 || gopSeconds <= 0f) return null
        val meanOther = split.otherBytes.toDouble() / split.otherCount
        val meanSync = if (split.syncCount > 0) split.syncBytes.toDouble() / split.syncCount else meanOther
        val framesPerGop = fps * gopSeconds
        if (framesPerGop < 1.0) return null
        val bytesPerSecond = fps * (meanSync / framesPerGop + meanOther * (1.0 - 1.0 / framesPerGop))
        return (bytesPerSecond * 8.0).toLong()
    }

    /** [predictedSteadyStateBps] over the request: the encoder's overshoot on this clip, or null. */
    fun overshootFactor(requestedBps: Int, split: Split, fps: Double, gopSeconds: Float): Double? {
        if (requestedBps <= 0) return null
        return predictedSteadyStateBps(split, fps, gopSeconds)?.let { it.toDouble() / requestedBps }
    }

    /** `rate[req=5183kbps,win=5610kbps,I=1x142kB,P=35x18kB,steady=5240kbps,x1.011]` */
    fun compact(requestedBps: Int, split: Split, fps: Double, gopSeconds: Float): String {
        val measured = measuredBps(split)
        val predicted = predictedSteadyStateBps(split, fps, gopSeconds)
        val factor = overshootFactor(requestedBps, split, fps, gopSeconds)?.let { "%.3f".format(java.util.Locale.US, it) }
        return "rate[req=${requestedBps / 1000}kbps,win=${measured?.let { "${it / 1000}kbps" } ?: "?"}," +
            "I=${split.syncCount}x${split.syncCount.takeIf { it > 0 }?.let { split.syncBytes / it / 1000 } ?: 0}kB," +
            "P=${split.otherCount}x${split.otherCount.takeIf { it > 0 }?.let { split.otherBytes / it / 1000 } ?: 0}kB," +
            "steady=${predicted?.let { "${it / 1000}kbps" } ?: "?"},x${factor ?: "?"}]"
    }

    /**
     * Reads the clip's first video track. Non-keyframe statistics come from samples at or after
     * [fromUs] (the scored window, past the rate-control lead-in); keyframe statistics come from
     * every keyframe except the clip's first, which is the warm-up frame the lead-in exists to skip.
     * Null when the file cannot be read.
     */
    fun measure(clip: File, fromUs: Long): Split? {
        val extractor = MediaExtractor()
        return runCatching {
            extractor.setDataSource(clip.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return@runCatching null
            extractor.selectTrack(track)
            var syncBytes = 0L
            var syncCount = 0
            var otherBytes = 0L
            var otherCount = 0
            var firstUs = -1L
            var lastUs = -1L
            var keyframesSeen = 0
            while (true) {
                val t = extractor.sampleTime
                if (t < 0L) break
                val size = extractor.sampleSize
                val sync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if (sync) {
                    keyframesSeen++
                    if (keyframesSeen > 1 && size > 0L) {
                        syncBytes += size
                        syncCount++
                    }
                } else if (t >= fromUs && size > 0L) {
                    otherBytes += size
                    otherCount++
                    if (firstUs < 0L || t < firstUs) firstUs = t
                    if (t > lastUs) lastUs = t
                }
                if (!extractor.advance()) break
            }
            val spanUs = if (firstUs >= 0L && lastUs > firstUs) lastUs - firstUs else 0L
            Split(syncBytes, syncCount, otherBytes, otherCount, spanUs)
        }.getOrNull().also { runCatching { extractor.release() } }
    }
}
