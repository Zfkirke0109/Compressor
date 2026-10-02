package compress.joshattic.us

object AudioPacketEvidence {
    data class Packet(val payload: ByteArray, val ptsUs: Long, val flags: Int)
    data class Result(val identical: Boolean, val packets: Int, val bytes: Long = 0,
        val sourceHash: String? = null, val outputHash: String? = null,
        val maxSkewUs: Long = 0, val firstMismatch: Int? = null, val reason: String? = null)
    fun compare(source: Iterator<Packet>, output: Iterator<Packet>, sourceConfig: String?, outputConfig: String?,
                sourceVideoOriginUs: Long, outputVideoOriginUs: Long): Result {
        val a = source.asSequence().map { it.payload }.iterator()
        val b = output.asSequence().map { it.payload }.iterator()
        val r = AudioTrackIdentity.compareCounted(a, b)
        return Result(r.result == AudioTrackIdentity.Result.IDENTICAL, r.packets)
    }
}
