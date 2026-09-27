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
 * The lossy modes (High Quality, Storage Saver) are described by what was OBSERVED, never by the
 * mode's name. b177 F5: both HDR High Quality jobs requested `audio=copy(source=256000bps)` in
 * their resolved plan and were recorded as "re-encoded (lossy mode)", because every lossy mode got
 * that text unconditionally. What was requested is recorded separately (`audioRequested`, from
 * ResolvedEncodePlan); this says what the output shows, at the strength it was shown.
 *
 * Pure, so the wording is unit-tested.
 */
object AudioPreservation {

    const val NO_AUDIO = "no audio track"
    const val RE_ENCODED_NOT_VALIDATED = "re-encoded (a new lossy generation), not validated as perceptually lossless"
    const val INFERRED_COPY = "stream copy inferred from matching codec, channels and sample rate (packets not compared)"
    const val LOSSY_PACKETS_DIFFER = "re-encoded or altered (lossy mode): the output's audio packets differ from the source's"
    const val LOSSY_NOT_SHOWN_AS_COPY =
        "not shown to be a copy (lossy mode): codec, channels, sample rate or bitrate differ from the source's, or packets could not be compared"

    fun bitIdentical(packets: Int): String = "bit-identical copy of the source's compressed audio ($packets packets compared)"

    fun describe(
        mode: BatchQualityMode,
        sourceHasAudio: Boolean,
        packetsIdentical: Boolean,
        packetsCompared: Int,
        inferredStreamCopy: Boolean,
        // AudioTrackIdentity compared the packets and found a difference.
        packetsDiffer: Boolean = false
    ): String? {
        if (!sourceHasAudio) return NO_AUDIO
        return when (mode) {
            BatchQualityMode.REMUX_ONLY -> "stream copy (Remux Only never re-encodes audio)"
            BatchQualityMode.PERCEPTUAL_LOSSLESS -> when {
                packetsIdentical -> bitIdentical(packetsCompared)
                inferredStreamCopy -> INFERRED_COPY
                else -> RE_ENCODED_NOT_VALIDATED
            }
            else -> when {
                packetsIdentical -> bitIdentical(packetsCompared)
                inferredStreamCopy -> INFERRED_COPY
                packetsDiffer -> LOSSY_PACKETS_DIFFER
                else -> LOSSY_NOT_SHOWN_AS_COPY
            }
        }
    }

    /** The resolved plan's audio request, for the record's `audioRequested`: copy, reencode or none. */
    fun requested(plan: ResolvedEncodePlan.AudioPlan?): String? = when (plan) {
        null -> null
        is ResolvedEncodePlan.AudioPlan.Copy -> "copy"
        is ResolvedEncodePlan.AudioPlan.Reencode -> "reencode"
        ResolvedEncodePlan.AudioPlan.None -> "none"
    }
}
