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
        val maxSkewUs: Long = 0, val firstMismatch: Int? = null, val reason: String? = null)

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
        while (source.hasNext() && output.hasNext()) {
            val a = source.next()
            val b = output.next()
            sourceDigest.update(a.payload)
            outputDigest.update(b.payload)
            bytes += a.payload.size
            val skew = kotlin.math.abs((a.ptsUs - sourceVideoOriginUs) - (b.ptsUs - outputVideoOriginUs))
            if (skew > maxSkew) maxSkew = skew
            if (firstMismatch == null) {
                val why = when {
                    !a.payload.contentEquals(b.payload) -> "payload differs"
                    a.flags != b.flags -> "packet flags differ"
                    skew > SYNC_TOLERANCE_US -> "presentation time differs against video by ${skew}us"
                    else -> null
                }
                if (why != null) { firstMismatch = packets; if (reason == null) reason = why }
            }
            packets++
        }
        val countDiffers = source.hasNext() || output.hasNext()
        // Keep payload digests bound to the full packet streams even when count differs. Prefix-only
        // hashes lose provenance for the unmatched tail and can make two distinct mismatches look equal.
        while (source.hasNext()) sourceDigest.update(source.next().payload)
        while (output.hasNext()) outputDigest.update(output.next().payload)
        if (countDiffers) {
            if (firstMismatch == null) firstMismatch = packets
            if (reason == null) reason = "packet count differs"
        }
        if (packets == 0 && reason == null) reason = "no packets"
        fun hex(d: MessageDigest) = d.digest().joinToString("") { "%02x".format(it) }
        return Result(reason == null, packets, bytes, hex(sourceDigest), hex(outputDigest), maxSkew, firstMismatch, reason)
    }
}
