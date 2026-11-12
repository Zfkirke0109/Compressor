package compress.joshattic.us

import java.security.MessageDigest

/**
 * Whether an output's audio packets present the same audio as the source's, not only the same
 * bytes. Identical requires all of:
 *  - both decoder configurations known and equal (the same payload under another codec config
 *    decodes differently);
 *  - the same number of packets, each with the same payload and the same flags;
 *  - each packet at the same time relative to its own file's video origin, so a uniform origin
 *    shift shared by audio and video is preserved while a shift of audio against video (a sync
 *    change) is not hidden by equal payloads.
 */
object AudioPacketEvidence {
    data class Packet(val payload: ByteArray, val ptsUs: Long, val flags: Int)
    data class Result(val identical: Boolean, val packets: Int, val bytes: Long = 0,
        val sourceHash: String? = null, val outputHash: String? = null,
        val maxSkewUs: Long = 0, val firstMismatch: Int? = null, val reason: String? = null,
        // What a single reason hides (a differing configuration is reported first and used to be
        // the only thing reported): how many packets each side holds, where each starts, what the
        // packet at [firstMismatch] differed in, and whether the two streams are the same packets
        // one position apart. b213 job_6d1173f4c0f9 read "decoder configuration differs" with a
        // skew of 21,334 us, one AAC frame at 48 kHz, and nothing said whether a packet was missing.
        val sourcePackets: Int = 0, val outputPackets: Int = 0,
        val sourceFirstPtsUs: Long? = null, val outputFirstPtsUs: Long? = null,
        /** What the packet at [firstMismatch] differed in, whatever [reason] reported first. */
        val firstMismatchKind: String? = null,
        /**
         * The output is the source without its first packet: it is one packet shorter, every
         * output packet equals the source packet AFTER it, and the two differ when read in step.
         */
        val outputLacksFirstSourcePacket: Boolean = false,
        /** The mirror case: one packet longer, and every source packet equals the output packet after it. */
        val outputHasExtraFirstPacket: Boolean = false)

    /** Largest audio-against-video timing difference accepted as the same presentation. */
    const val SYNC_TOLERANCE_US = 1_000L

    fun compare(source: Iterator<Packet>, output: Iterator<Packet>, sourceConfig: String?, outputConfig: String?,
                sourceVideoOriginUs: Long, outputVideoOriginUs: Long): Result {
        if (sourceConfig == null || outputConfig == null) {
            return Result(false, 0, reason = "decoder configuration unknown")
        }
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        val outputDigest = MessageDigest.getInstance("SHA-256")
        var packets = 0
        var bytes = 0L
        var maxSkew = 0L
        var firstMismatch: Int? = null
        var reason: String? = if (sourceConfig != outputConfig) "decoder configuration differs" else null
        var firstDifference: String? = null
        var sourceFirstPts: Long? = null
        var outputFirstPts: Long? = null
        // The same packets one position apart: each output packet against the NEXT source packet
        // (the output lacks the source's first), and each source packet against the next output one.
        var previousSource: ByteArray? = null
        var previousOutput: ByteArray? = null
        var lacksFirst = true
        var extraFirst = true
        // Packets that all carry the same bytes (digital silence) match at any offset, which says
        // nothing about which one is missing: an offset is claimed only when reading in step differs.
        var payloadDiffered = false
        while (source.hasNext() && output.hasNext()) {
            val a = source.next()
            val b = output.next()
            if (packets == 0) { sourceFirstPts = a.ptsUs; outputFirstPts = b.ptsUs }
            sourceDigest.update(a.payload)
            outputDigest.update(b.payload)
            bytes += a.payload.size
            previousOutput?.let { if (!it.contentEquals(a.payload)) lacksFirst = false }
            previousSource?.let { if (!it.contentEquals(b.payload)) extraFirst = false }
            previousSource = a.payload
            previousOutput = b.payload
            if (!payloadDiffered && !a.payload.contentEquals(b.payload)) payloadDiffered = true
            val skew = kotlin.math.abs((a.ptsUs - sourceVideoOriginUs) - (b.ptsUs - outputVideoOriginUs))
            if (skew > maxSkew) maxSkew = skew
            if (firstMismatch == null) {
                val why = when {
                    !a.payload.contentEquals(b.payload) -> "payload differs"
                    a.flags != b.flags -> "packet flags differ"
                    skew > SYNC_TOLERANCE_US -> "presentation time differs against video by ${skew}us"
                    else -> null
                }
                if (why != null) { firstMismatch = packets; firstDifference = why; if (reason == null) reason = why }
            }
            packets++
        }
        var sourcePackets = packets
        var outputPackets = packets
        if (source.hasNext() || output.hasNext()) {
            if (firstMismatch == null) { firstMismatch = packets; firstDifference = "packet count differs" }
            if (reason == null) reason = "packet count differs"
            // The one packet after the common run decides the offset-by-one question for it.
            if (source.hasNext()) {
                val next = source.next()
                sourcePackets++
                previousOutput?.let { if (!it.contentEquals(next.payload)) lacksFirst = false }
                while (source.hasNext()) { source.next(); sourcePackets++ }
            }
            while (output.hasNext()) {
                val next = output.next()
                if (outputPackets == packets) previousSource?.let { if (!it.contentEquals(next.payload)) extraFirst = false }
                outputPackets++
            }
        }
        if (packets == 0 && reason == null) reason = "no packets"
        // One position apart means something only for streams long enough to show it.
        val offsetMeaningful = packets >= 2 && payloadDiffered
        fun hex(d: MessageDigest) = d.digest().joinToString("") { "%02x".format(it) }
        return Result(reason == null, packets, bytes, hex(sourceDigest), hex(outputDigest), maxSkew, firstMismatch, reason,
            sourcePackets = sourcePackets, outputPackets = outputPackets,
            sourceFirstPtsUs = sourceFirstPts, outputFirstPtsUs = outputFirstPts,
            firstMismatchKind = firstDifference,
            outputLacksFirstSourcePacket = offsetMeaningful && lacksFirst && sourcePackets == outputPackets + 1,
            outputHasExtraFirstPacket = offsetMeaningful && extraFirst && outputPackets == sourcePackets + 1)
    }
}
