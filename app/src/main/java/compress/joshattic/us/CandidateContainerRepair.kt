package compress.joshattic.us

/**
 * When a Perceptually Lossless candidate is rewritten by the platform's muxer before it is judged,
 * and on what evidence. A rewrite copies every sample and changes none: it can only put right what
 * the container says about them.
 *
 * Two things a Media3 export gets wrong are put right this way, and nothing else is:
 *
 *  - Media3's muxer moves the first video timestamp to zero when the file has audio. The timeline
 *    comparator names that exact pattern ([MediaTimelineEvidence.Result.firstVideoPtsToRestoreUs]).
 *  - Media3's muxer writes the edit of a copied AAC track on a movie timescale of 1/10,000 s, which
 *    the platform reads as end padding the source does not have
 *    ([AudioTrackIdentity.Outcome.differsOnlyInEndPadding]). The platform's own muxer writes the
 *    track without it: on the S26 the padding read 2 samples in Media3's file and 0, the source's
 *    value, after the metadata remux.
 *
 * Neither is a tolerance. The rewritten file goes back through every check unchanged, and one that
 * still differs keeps the original.
 *
 * Decided November 12 (the owner delegated the call): a copy that differs from its source only in
 * end padding does not pass as it is. Identical audio means the packets and the configuration the
 * platform reads, padding included, and a decoder that honours the padding plays such a copy that
 * many samples short. It is repaired instead, as a shifted first timestamp is.
 */
internal object CandidateContainerRepair {

    /** What the audio comparison lets a rewrite start from. */
    enum class AudioBasis {
        /** Already the source's audio: the rewrite has to keep it so. */
        IDENTICAL,
        /** The source's audio in everything but the end padding, which the rewrite is to put right. */
        END_PADDING
    }

    /**
     * The basis [audio] gives, or null when a rewrite cannot help. [platformMuxed] says the
     * platform's muxer already wrote the candidate (the metadata remux ran): its end padding is
     * then what that muxer writes, and writing the file again would not change it.
     */
    fun audioBasis(audio: AudioTrackIdentity.Outcome?, platformMuxed: Boolean): AudioBasis? = when {
        audio == null -> null
        audio.result == AudioTrackIdentity.Result.IDENTICAL -> AudioBasis.IDENTICAL
        audio.differsOnlyInEndPadding && !platformMuxed -> AudioBasis.END_PADDING
        else -> null
    }

    /**
     * A candidate whose whole shortfall is the end padding of its copied audio: the video timeline
     * matches frame for frame and the audio differs in nothing else.
     */
    fun endPaddingAlone(timeline: MediaTimelineEvidence.Result?, audio: AudioTrackIdentity.Outcome?, platformMuxed: Boolean): Boolean =
        timeline?.matches == true && audioBasis(audio, platformMuxed) == AudioBasis.END_PADDING

    /** What the user is told the rewrite did. */
    fun message(restoredFirstVideoTimestamp: Boolean, basis: AudioBasis): String = when {
        restoredFirstVideoTimestamp && basis == AudioBasis.END_PADDING -> "Restored source video start time and audio end padding"
        restoredFirstVideoTimestamp -> "Restored source video start time"
        else -> "Restored source audio end padding"
    }

    /** The operation named in the session record. The first is the name earlier builds wrote. */
    fun operation(restoredFirstVideoTimestamp: Boolean): String =
        if (restoredFirstVideoTimestamp) "restore_first_video_timestamp" else "restore_audio_end_padding"
}
