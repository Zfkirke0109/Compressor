package compress.joshattic.us

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.util.Locale

/**
 * A copy of the source that Media3 may be able to read, for the files whose own bytes stop its
 * extractor ([SourceParseFailure]). The platform extractor reads every sample and the platform
 * muxer writes them back, re-deriving each NAL length on the way; this is the Remux Only path the
 * app has used for years. The copy is ONLY the Transformer's input. The probe windows, the
 * scoring reference, output verification, certification and the output's metadata all stay on
 * the original, so nothing is judged against the copy.
 *
 * Whether the platform reads these files cleanly is not known yet: in b167 nothing on the platform
 * side read their sample data (the probes and encodes stopped first). This finds out, and the
 * decision log says which way it went. Rejected here: a copy that ends early, judged by its last
 * video timestamp against the track duration the container declares. Output verification's
 * duration check would reject such an encode anyway; this saves the encode.
 */
object Media3InputNormalizer {

    sealed interface Result {
        data class Normalised(
            val file: File,
            val bytes: Long,
            val elapsedMs: Long,
            val videoSamples: Int,
            val lastVideoUs: Long,
            val declaredVideoDurationUs: Long
        ) : Result {
            fun compact(): String =
                "copy=${String.format(Locale.US, "%.1f", bytes / 1e6)}MB in ${elapsedMs}ms; videoSamples=$videoSamples; " +
                    "lastVideoMs=${lastVideoUs / 1000}; declaredVideoMs=${declaredVideoDurationUs / 1000}"
        }

        data class Declined(val reason: String) : Result
    }

    /** Free space kept beyond the copy and an encode output the size of the source. */
    const val FREE_SPACE_MARGIN_BYTES = 1L shl 30

    /**
     * Null when a copy may be written, else why not. The copy lives until the encode ends and the
     * encode output is written beside it, so room for both is required.
     */
    fun declineReason(isHdr: Boolean, sourceBytes: Long, usableBytes: Long): String? = when {
        // MediaMuxer's carriage of HDR static metadata is not verified on this path, and HDR must
        // never lose its signalling to a workaround.
        isHdr -> "HDR source; the platform muxer's HDR metadata carriage is not verified"
        sourceBytes <= 0L -> "source size unknown"
        usableBytes < 2 * sourceBytes + FREE_SPACE_MARGIN_BYTES ->
            "not enough free space for the copy and the encode (need ${mb(2 * sourceBytes + FREE_SPACE_MARGIN_BYTES)}, have ${mb(usableBytes)})"
        else -> null
    }

    /**
     * True when a copy whose latest video sample is at [lastVideoUs] covers a track that declares
     * [declaredDurationUs]. The last sample starts about one frame before the end, and edit lists
     * can shift a few frames more, hence the tolerance; a copy cut short by an extractor error is
     * short by far more than that, except within the tolerance of the end, which output
     * verification's duration check still covers.
     */
    fun isComplete(lastVideoUs: Long, declaredDurationUs: Long): Boolean {
        if (lastVideoUs < 0L) return false
        if (declaredDurationUs <= 0L) return true
        val toleranceUs = maxOf(500_000L, declaredDurationUs / 500)
        return lastVideoUs >= declaredDurationUs - toleranceUs
    }

    fun normalise(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        rotationDegrees: Int?,
        cancellationCheck: () -> Unit
    ): Result {
        val startedAt = System.currentTimeMillis()
        val declaredUs = declaredVideoDurationUs(context, sourceUri)
        var videoSamples = 0
        var lastVideoUs = -1L
        val remux = try {
            Mp4MetadataRemuxer.remuxSourceWithoutReencode(
                context,
                sourceUri,
                outputFile,
                // Rotation only: Media3 must see the same orientation it would in the original.
                // Location and dates stay out of a temporary file; the output takes the
                // original's metadata as before.
                VideoMetadataSnapshot(rotationDegrees = rotationDegrees),
                cancellationCheck = cancellationCheck,
                onVideoSample = { t ->
                    videoSamples++
                    if (t > lastVideoUs) lastVideoUs = t
                }
            )
        } catch (e: java.util.concurrent.CancellationException) {
            runCatching { outputFile.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { outputFile.delete() }
            return Result.Declined("platform remux failed: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!isComplete(lastVideoUs, declaredUs)) {
            runCatching { outputFile.delete() }
            return Result.Declined(
                "platform copy ended early: last video sample at ${lastVideoUs / 1000} ms of a declared " +
                    "${declaredUs / 1000} ms ($videoSamples samples); the platform extractor cannot read it either"
            )
        }
        return Result.Normalised(
            file = remux.outputFile,
            bytes = remux.outputFile.length(),
            elapsedMs = System.currentTimeMillis() - startedAt,
            videoSamples = videoSamples,
            lastVideoUs = lastVideoUs,
            declaredVideoDurationUs = declaredUs
        )
    }

    private fun declaredVideoDurationUs(context: Context, uri: Uri): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).asSequence()
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION) ?: -1L
        } catch (_: Exception) {
            -1L
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%,d MB", bytes / 1_000_000)
}
