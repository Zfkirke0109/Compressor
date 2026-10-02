package compress.joshattic.us

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Packet, decoder-configuration and A/V-timing evidence. Unknown evidence never proves PL audio. */
object AudioTrackIdentity {

    enum class Result { IDENTICAL, DIFFERENT, UNAVAILABLE }

    /** The result plus how many packets were compared, for the record ("bit-identical, N packets"). */
    data class Outcome(val result: Result, val packets: Int,
        val evidence: AudioPacketEvidence.Result? = null,
        val sourceConfigHash: String? = null, val outputConfigHash: String? = null)

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

    /** With video origins supplied, proves decoder configuration and presentation as well as bytes. */
    fun compare(context: Context, sourceUri: Uri, outputFile: File,
                sourceVideoOriginUs: Long? = null, outputVideoOriginUs: Long? = null,
                requirePresentationProof: Boolean = false): Outcome {
        if (requirePresentationProof && (sourceVideoOriginUs == null || outputVideoOriginUs == null))
            return Outcome(Result.UNAVAILABLE, 0)
        val sourceExtractor = MediaExtractor()
        val outputExtractor = MediaExtractor()
        return try {
            sourceExtractor.setDataSource(context, sourceUri, null)
            outputExtractor.setDataSource(outputFile.absolutePath)
            val sourceTrack = selectAudio(sourceExtractor) ?: return Outcome(Result.UNAVAILABLE, 0)
            val outputTrack = selectAudio(outputExtractor) ?: return Outcome(Result.UNAVAILABLE, 0)
            val sf = sourceExtractor.getTrackFormat(sourceTrack)
            val of = outputExtractor.getTrackFormat(outputTrack)
            if (sourceVideoOriginUs == null || outputVideoOriginUs == null) {
                // Legacy lossy-mode diagnostic only; this branch cannot prove PL audio.
                compareCounted(packets(sourceExtractor, capacityOf(sf)), packets(outputExtractor, capacityOf(of)))
            } else {
                val sc = configHash(sf); val oc = configHash(of)
                val evidence = AudioPacketEvidence.compare(
                    timedPackets(sourceExtractor, capacityOf(sf)), timedPackets(outputExtractor, capacityOf(of)),
                    sc, oc, sourceVideoOriginUs, outputVideoOriginUs)
                Outcome(if (evidence.identical) Result.IDENTICAL else if (sc == null || oc == null)
                    Result.UNAVAILABLE else Result.DIFFERENT, evidence.packets, evidence, sc, oc)
            }
        } catch (_: Exception) { Outcome(Result.UNAVAILABLE, 0) }
        finally {
            runCatching { sourceExtractor.release() }
            runCatching { outputExtractor.release() }
        }
    }

    private fun selectAudio(extractor: MediaExtractor): Int? {
        val tracks = (0 until extractor.trackCount).filter {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (tracks.size != 1) return null // No proof for an unexamined second track.
        return tracks.single().also { extractor.selectTrack(it) }
    }

    private fun configHash(format: MediaFormat): String? {
        val csd = (0..2).map { format.getByteBuffer("csd-$it")?.duplicate()?.let { b ->
            ByteArray(b.remaining()).also { b.get(it) }
        } }
        if ((csd[0] == null || csd[0]!!.isEmpty())) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (key in listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_SAMPLE_RATE,
                MediaFormat.KEY_ENCODER_DELAY, MediaFormat.KEY_ENCODER_PADDING)) {
            val value = if (key == MediaFormat.KEY_MIME) format.getString(key)
                else if (format.containsKey(key)) format.getInteger(key).toString()
                else if (key == MediaFormat.KEY_ENCODER_DELAY || key == MediaFormat.KEY_ENCODER_PADDING) "0" else "unknown"
            digest.update("$key=$value;".toByteArray(Charsets.UTF_8))
        }
        for (bytes in csd) { digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array()); bytes?.let(digest::update) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun timedPackets(extractor: MediaExtractor, capacity: Int): Iterator<AudioPacketEvidence.Packet> {
        val buffer = ByteBuffer.allocate(capacity)
        val started = System.nanoTime()
        return iterator {
            var count = 0
            while (true) {
                check(count++ < 2_000_000 && System.nanoTime() - started < 30_000_000_000L) { "audio evidence budget exceeded" }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(size <= capacity)
                val data = ByteArray(size); buffer.position(0); buffer.get(data)
                yield(AudioPacketEvidence.Packet(data, extractor.sampleTime, extractor.sampleFlags))
                if (!extractor.advance()) break
            }
        }
    }

    private fun capacityOf(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(DEFAULT_SAMPLE_CAPACITY, 16 shl 20)
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
