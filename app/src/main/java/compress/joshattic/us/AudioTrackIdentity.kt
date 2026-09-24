package compress.joshattic.us

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Proves, or fails to prove, that an output's audio track is a bit-exact copy of the source's.
 *
 * Perceptually Lossless passes AAC audio through unchanged (BatchQualityBitratePolicy
 * .shouldPassThroughAudio). The verifier still judged audio by a bitrate rule written for
 * the old re-encode: at least max(source, 256 kbps), less 10%. A copied 128 kbps track is
 * exactly 128 kbps, so the verifier rejected every perfect copy. In batch_1790263711162 (b161)
 * that discarded 13 otherwise-passing encodes as "audio bitrate fell below the verified safety
 * threshold", and each of those trained the learning engine against the profile.
 *
 * The bitrate label cannot tell a copy from a re-encode at the same bitrate. The packets can.
 * Identical packets, same count, same bytes, same order, mean nothing was re-encoded and no
 * audio was lost. Anything short of that returns [Result.DIFFERENT] or [Result.UNAVAILABLE], and
 * the verifier keeps its old bitrate rule. This can only add a proof; it never removes a check.
 */
object AudioTrackIdentity {

    enum class Result { IDENTICAL, DIFFERENT, UNAVAILABLE }

    /** The result plus how many packets were compared, for the record ("bit-identical, N packets"). */
    data class Outcome(val result: Result, val packets: Int)

    private const val DEFAULT_SAMPLE_CAPACITY = 1 shl 20

    /**
     * Pure core: compares two ordered packet streams. Both must end together, every packet must
     * match byte for byte, and at least one packet must exist.
     */
    internal fun compare(source: Iterator<ByteArray>, output: Iterator<ByteArray>): Result =
        compareCounted(source, output).result

    internal fun compareCounted(source: Iterator<ByteArray>, output: Iterator<ByteArray>): Outcome {
        var packets = 0
        while (true) {
            val a = source.hasNext()
            val b = output.hasNext()
            if (!a && !b) return Outcome(if (packets > 0) Result.IDENTICAL else Result.UNAVAILABLE, packets)
            if (a != b) return Outcome(Result.DIFFERENT, packets)
            if (!source.next().contentEquals(output.next())) return Outcome(Result.DIFFERENT, packets)
            packets++
        }
    }

    /** Compares the first audio track of [sourceUri] with the first audio track of [outputFile]. */
    fun compare(context: Context, sourceUri: Uri, outputFile: File): Outcome {
        val sourceExtractor = MediaExtractor()
        val outputExtractor = MediaExtractor()
        return try {
            sourceExtractor.setDataSource(context, sourceUri, null)
            outputExtractor.setDataSource(outputFile.absolutePath)
            val sourceTrack = selectAudio(sourceExtractor) ?: return Outcome(Result.UNAVAILABLE, 0)
            val outputTrack = selectAudio(outputExtractor) ?: return Outcome(Result.UNAVAILABLE, 0)
            compareCounted(
                packets(sourceExtractor, capacityOf(sourceExtractor.getTrackFormat(sourceTrack))),
                packets(outputExtractor, capacityOf(outputExtractor.getTrackFormat(outputTrack)))
            )
        } catch (_: Throwable) {
            Outcome(Result.UNAVAILABLE, 0)
        } finally {
            runCatching { sourceExtractor.release() }
            runCatching { outputExtractor.release() }
        }
    }

    private fun selectAudio(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                return i
            }
        }
        return null
    }

    private fun capacityOf(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_SAMPLE_CAPACITY)
        } else {
            DEFAULT_SAMPLE_CAPACITY
        }

    private fun packets(extractor: MediaExtractor, capacity: Int): Iterator<ByteArray> {
        val buffer = ByteBuffer.allocate(capacity)
        return iterator {
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val packet = ByteArray(size)
                buffer.position(0)
                buffer.get(packet, 0, size)
                yield(packet)
                if (!extractor.advance()) break
            }
        }
    }
}
