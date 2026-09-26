package compress.joshattic.us

/**
 * The audio half of a Perceptually Lossless claim, kept apart from the video half.
 *
 * "Perceptually Lossless Verified" is decided by structural checks plus sampled VMAF, and VMAF
 * scores pixels. It says nothing about the audio. In this app the audio of a PL output is one of
 * three things, and the record must say which:
 *
 *  - a BIT-IDENTICAL copy of the source's compressed audio, proven by comparing every packet
 *    ([AudioTrackIdentity]). Nothing was decoded or re-encoded, so nothing could have been lost;
 *  - an inferred stream copy: codec, channels and sample rate match and the muxer exposes no
 *    bitrate, the pre-b162 rule. Almost certainly a copy, but not proven packet by packet;
 *  - a RE-ENCODE (a non-AAC source such as Opus, or an AAC source the pipeline chose to
 *    re-encode). A second lossy generation. No listening test or audio metric validates it here,
 *    so it is never called perceptually lossless; it is called what it is.
 *
 * Pure, so the wording is unit-tested.
 */
object AudioPreservation {

    const val NO_AUDIO = "no audio track"
    const val RE_ENCODED_NOT_VALIDATED = "re-encoded (a new lossy generation), not validated as perceptually lossless"
    const val INFERRED_COPY = "stream copy inferred from matching codec, channels and sample rate (packets not compared)"

    fun bitIdentical(packets: Int): String = "bit-identical copy of the source's compressed audio ($packets packets compared)"

    fun describe(
        mode: BatchQualityMode,
        sourceHasAudio: Boolean,
        packetsIdentical: Boolean,
        packetsCompared: Int,
        inferredStreamCopy: Boolean
    ): String? {
        if (!sourceHasAudio) return NO_AUDIO
        return when (mode) {
            BatchQualityMode.REMUX_ONLY -> "stream copy (Remux Only never re-encodes audio)"
            BatchQualityMode.PERCEPTUAL_LOSSLESS -> when {
                packetsIdentical -> bitIdentical(packetsCompared)
                inferredStreamCopy -> INFERRED_COPY
                else -> RE_ENCODED_NOT_VALIDATED
            }
            else -> "re-encoded (lossy mode)"
        }
    }
}
