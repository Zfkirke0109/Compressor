package compress.joshattic.us

/**
 * Which discarded Perceptually Lossless attempts may teach the learning engine, and what.
 *
 * The engine learns one thing per profile: how conservative the target ratio must be. A failure
 * raises the ratio ([SmartPerceptualProfileEngine.FAILURE_STEP_UP]), and repeated failures near
 * the maximum ratio latch the profile to "keep original". That is the right response to exactly
 * one kind of evidence: the encode was too starved. Every other rejection reason says nothing
 * about the ratio, and teaching it anyway pushes innocent profiles toward "never compress".
 *
 * batch_1790263711162 (b161) shows the cost. All 13 "audio bitrate fell below the verified safety
 * threshold" rejections were verifier artefacts of the AAC pass-through (see AudioTrackIdentity),
 * and a muxer timeout and an SD colour tag were rejected too. Each one called recordFailure. By
 * the end of the batch, 28 files were kept as-is by "This device profile repeatedly failed
 * perceptually lossless verification", although the batch had started from empty learned state.
 *
 * Pure, so every rule is unit-tested.
 */
object LearningEvidencePolicy {

    enum class Kind {
        /** The encode was too starved: raise the ratio (and count toward the latch). */
        QUALITY,

        /**
         * Everything in scope passed, but the output was not smaller than the source, or grew
         * past the tolerance. Raising the ratio would make the NEXT output bigger still, so the
         * ratio stays; the failure still counts toward the keep-original latch, because a
         * profile that cannot beat its source near the maximum ratio has nothing to gain.
         */
        SIZE,

        /**
         * A pipeline or metadata check failed: audio, colour, frame count, duration, rotation,
         * dates, playability, unreadable fields, or an encoder that crashed. Nothing here says
         * the ratio was wrong, so nothing is learned.
         */
        PIPELINE
    }

    /** Checks whose failure means the encode delivered too few bits for its content. */
    private val QUALITY_CHECKS = setOf("videoBitratePass")

    fun classifyVerificationFailure(failingChecks: List<String>): Kind = when {
        failingChecks.any { it in QUALITY_CHECKS } -> Kind.QUALITY
        failingChecks.isEmpty() -> Kind.SIZE
        else -> Kind.PIPELINE
    }
}
