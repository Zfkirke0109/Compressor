package compress.joshattic.us

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/** Packet, decoder-configuration and A/V-timing evidence. Unknown evidence never proves PL audio. */
object AudioTrackIdentity {

    enum class Result { IDENTICAL, DIFFERENT, UNAVAILABLE }

    /** The result plus how many packets were compared, for the record ("bit-identical, N packets"). */
    data class Outcome(val result: Result, val packets: Int,
        val evidence: AudioPacketEvidence.Result? = null,
        val sourceConfigHash: String? = null, val outputConfigHash: String? = null,
        // What each hash was taken over, so "decoder configuration differs" can say in what:
        // codec, channels, sample rate, encoder delay and padding, and the codec-specific data.
        val sourceConfig: Map<String, Any?>? = null, val outputConfig: Map<String, Any?>? = null,
        /** The two configurations differ, and in nothing but the encoder padding ([Config.differOnlyInEncoderPadding]). */
        val configDiffersOnlyInEncoderPadding: Boolean = false) {

        /**
         * The copy is the source's audio in everything but its end padding: both tracks hold the
         * same number of packets, every one equal in payload, flags and time against the video,
         * and the configurations agree except in how many samples the decoder drops at the end.
         *
         * Such a copy is still not identical audio, and the verifier does not pass it (decided
         * November 12; no tolerance was added). It is the one audio difference a rewrite of the
         * container can put right ([CandidateContainerRepair]): Media3's muxer writes the edit of a
         * copied AAC track on a movie timescale of 1/10,000 s, which the platform reads as end
         * padding the source does not have, and the platform's own muxer writes it without.
         */
        val differsOnlyInEndPadding: Boolean
            get() = result == Result.DIFFERENT && configDiffersOnlyInEncoderPadding && evidence != null &&
                evidence.packets > 0 && evidence.firstMismatch == null &&
                evidence.sourcePackets == evidence.packets && evidence.outputPackets == evidence.packets

        /** Why the audio is not identical, in the terms of what differs. Null when it is, or nothing was compared. */
        val failureReason: String?
            get() = if (differsOnlyInEndPadding) {
                "end padding differs (source ${sourceConfig?.get("encoderPadding") ?: 0}, output " +
                    "${outputConfig?.get("encoderPadding") ?: 0} samples); the packets and the rest of the configuration match"
            } else evidence?.reason
    }

    /**
     * What a decoder is configured from, as the platform reports it for one audio track: the
     * fields the configuration hash covers. Pure, so the hash and the end-padding comparison are
     * tested without a device.
     */
    internal class Config(
        val mime: String?, val channels: Int?, val sampleRate: Int?,
        val encoderDelay: Int?, val encoderPadding: Int?,
        /** csd-0, csd-1 and csd-2; null where the track has none. */
        val csd: List<ByteArray?>
    ) {
        /**
         * SHA-256 over every field, or null without codec-specific data: nothing is proven then.
         * An absent delay or padding reads as 0, which is what it means to a decoder. With
         * [withEncoderPadding] false the end padding is left out, so two tracks whose full hashes
         * differ and whose hashes without it agree are configured alike in everything else.
         */
        fun hash(withEncoderPadding: Boolean = true): String? {
            if (csd.firstOrNull().let { it == null || it.isEmpty() }) return null
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            fun field(key: String, value: String?) = digest.update("$key=$value;".toByteArray(Charsets.UTF_8))
            field(MediaFormat.KEY_MIME, mime)
            field(MediaFormat.KEY_CHANNEL_COUNT, channels?.toString() ?: "unknown")
            field(MediaFormat.KEY_SAMPLE_RATE, sampleRate?.toString() ?: "unknown")
            field(MediaFormat.KEY_ENCODER_DELAY, (encoderDelay ?: 0).toString())
            if (withEncoderPadding) field(MediaFormat.KEY_ENCODER_PADDING, (encoderPadding ?: 0).toString())
            for (bytes in csd) { digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array()); bytes?.let(digest::update) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * The fields [hash] covers, readable. Codec parameters only: an AAC AudioSpecificConfig
         * is a few bytes naming the object type, sample rate and channel layout. Other codecs' data
         * can embed a digest of the audio (FLAC STREAMINFO), so only its size is given for them.
         */
        fun describe(): Map<String, Any?> = linkedMapOf(
            "mime" to mime,
            "channels" to channels,
            "sampleRate" to sampleRate,
            "encoderDelay" to encoderDelay,
            "encoderPadding" to encoderPadding,
            "csdSizes" to csd.map { it?.size },
            "csd0Hex" to csd.firstOrNull()?.takeIf { mime == MediaFormat.MIMETYPE_AUDIO_AAC && it.size <= 64 }?.joinToString("") { "%02x".format(it) }
        )

        companion object {
            /** True when both configurations are known, differ, and differ in nothing but the encoder padding. */
            fun differOnlyInEncoderPadding(source: Config, output: Config): Boolean {
                val a = source.hash() ?: return false
                val b = output.hash() ?: return false
                return a != b && source.hash(withEncoderPadding = false) == output.hash(withEncoderPadding = false)
            }
        }
    }

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

    /**
     * Pure core of the comparison with video origins: the outcome for two timed packet streams and
     * the configurations of their tracks.
     */
    internal fun compareTimed(
        source: Iterator<AudioPacketEvidence.Packet>, output: Iterator<AudioPacketEvidence.Packet>,
        sourceConfig: Config, outputConfig: Config, sourceVideoOriginUs: Long, outputVideoOriginUs: Long
    ): Outcome {
        val sc = sourceConfig.hash(); val oc = outputConfig.hash()
        val evidence = AudioPacketEvidence.compare(source, output, sc, oc, sourceVideoOriginUs, outputVideoOriginUs)
        return Outcome(if (evidence.identical) Result.IDENTICAL else if (sc == null || oc == null)
            Result.UNAVAILABLE else Result.DIFFERENT, evidence.packets, evidence, sc, oc,
            // Only when it did not match: the passing record stays as small as it was.
            if (evidence.identical) null else sourceConfig.describe(), if (evidence.identical) null else outputConfig.describe(),
            configDiffersOnlyInEncoderPadding = Config.differOnlyInEncoderPadding(sourceConfig, outputConfig))
    }

    /** With video origins supplied, proves decoder configuration and presentation as well as bytes. */
    fun compare(context: Context, sourceUri: Uri, outputFile: File,
                sourceVideoOriginUs: Long? = null, outputVideoOriginUs: Long? = null,
                requirePresentationProof: Boolean = false,
                cancellationCheck: () -> Unit = {}): Outcome {
        cancellationCheck()
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
                compareCounted(
                    packets(sourceExtractor, capacityOf(sf), cancellationCheck),
                    packets(outputExtractor, capacityOf(of), cancellationCheck)
                )
            } else {
                compareTimed(
                    timedPackets(sourceExtractor, capacityOf(sf), cancellationCheck),
                    timedPackets(outputExtractor, capacityOf(of), cancellationCheck),
                    configOf(sf), configOf(of), sourceVideoOriginUs, outputVideoOriginUs)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Outcome(Result.UNAVAILABLE, 0) }
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

    private fun configOf(format: MediaFormat): Config {
        fun int(key: String) = if (format.containsKey(key)) format.getInteger(key) else null
        return Config(
            mime = format.getString(MediaFormat.KEY_MIME),
            channels = int(MediaFormat.KEY_CHANNEL_COUNT),
            sampleRate = int(MediaFormat.KEY_SAMPLE_RATE),
            encoderDelay = int(MediaFormat.KEY_ENCODER_DELAY),
            encoderPadding = int(MediaFormat.KEY_ENCODER_PADDING),
            csd = (0..2).map { format.getByteBuffer("csd-$it")?.duplicate()?.let { b -> ByteArray(b.remaining()).also { b.get(it) } } }
        )
    }

    private fun timedPackets(
        extractor: MediaExtractor, capacity: Int, cancellationCheck: () -> Unit
    ): Iterator<AudioPacketEvidence.Packet> {
        val buffer = ByteBuffer.allocate(capacity)
        val started = System.nanoTime()
        return iterator {
            var count = 0
            while (true) {
                if (count % 256 == 0) cancellationCheck()
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

    private fun packets(extractor: MediaExtractor, capacity: Int, cancellationCheck: () -> Unit): Iterator<ByteArray> {
        val buffer = ByteBuffer.allocate(capacity)
        return iterator {
            var count = 0
            while (true) {
                if (count++ % 256 == 0) cancellationCheck()
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
