package compress.joshattic.us.quality

import compress.joshattic.us.DiagLog
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File

/**
 * Checks that an exported probe clip actually contains the window it was asked for.
 *
 * The whole probe path rests on one unverified assumption, stated in `probeOneRatio` as "the probe
 * file contains ONLY the window, starting at 0": the clip is exported with
 * `setStartPositionUs(window.startUs)` and then handed to the scorer as
 * `ScoreWindow(startUs, endUs, distStartUs = 0)`, so the reference is normalised by `startUs` and
 * the clip by 0. If that assumption is wrong the scorer compares the right timestamps against the
 * wrong pixels, and nothing downstream can tell.
 *
 * Two device findings say it needs checking rather than assuming:
 *
 *  - 12 of 13 misaligned ladders in batch_1788257645030 failed as "leading offset not aligned
 *    within 8 drops" — the two streams were never lined up at the window's start at all.
 *  - The windows that DID score split into two populations, and the catastrophic one has *lower*
 *    skew than the passing one (min < 20 at 1.5–3.5 ms; min >= 84 at 3.4–3.8 ms). Timestamps
 *    agreeing while scores collapse to a mean of 13–42 is not what a slightly-lossy re-encode
 *    looks like; it is what comparing different footage looks like.
 *
 * Reading the clip's own timestamps settles it without decoding a frame: [MediaExtractor] exposes
 * the first sample's presentation time and the track duration directly.
 *
 * Diagnostic only. Nothing here changes an acceptance decision — a mismatch is reported so a
 * capture can prove the cause, not used to reject or repair a measurement.
 *
 * ANSWERED by batch_1790263711162 (b161): firstSample=0 in 715 of 715 probe clips. The clip's
 * first frame is the source's first frame at or after startUs, written at time 0. Pairing it
 * against a reference normalised by the requested start left every pair a sub-frame lead apart.
 * The scorer now pairs the two first frames (ScoreWindow.alignFirstFrames). This line stays so
 * that a future Media3 or muxer change that breaks the premise shows up in a capture.
 */
object ProbeClipGeometry {

    private const val TAG = "CompressorProbe"

    /** What the clip actually contains, in its own timeline. */
    data class Actual(val firstSampleUs: Long, val durationUs: Long)

    /** Reads the first video sample's pts and the video track duration, or null if unreadable. */
    fun read(file: File): Actual? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var found: Actual? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                extractor.selectTrack(i)
                val duration =
                    if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
                // sampleTime is the first sample's pts once a track is selected and not advanced.
                found = Actual(firstSampleUs = extractor.sampleTime, durationUs = duration)
                break
            }
            found
        } catch (t: Throwable) {
            DiagLog.w(TAG, "could not read probe clip geometry: ${t.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * One line naming requested vs actual, or null when the clip could not be read.
     *
     * [offsetUs] is the number that matters: the scorer assumes it is 0. Anything else means the
     * clip's frame at its own time t corresponds to source time `startUs + offsetUs + t`, so every
     * pair the aligner forms is off by that much CONTENT even when the timestamps agree.
     */
    fun describe(file: File, requestedStartUs: Long, requestedEndUs: Long): String? {
        val actual = read(file) ?: return null
        val requestedDurationUs = requestedEndUs - requestedStartUs
        return "clip geometry; requested=[${requestedStartUs}us..${requestedEndUs}us]" +
            " (${requestedDurationUs}us); actual firstSample=${actual.firstSampleUs}us" +
            " duration=${actual.durationUs}us; offsetUs=${actual.firstSampleUs}" +
            " durationDeltaUs=${actual.durationUs - requestedDurationUs}"
    }
}
