package compress.joshattic.us

/**
 * Every boolean predicate an output verdict depends on, in one place.
 *
 * Why this exists: `OutputVerifier` previously computed each verdict as a long inline `&&` chain,
 * while the failure diagnostic was reconstructed separately by scanning rendered display strings
 * for an "ok"/"warn" suffix. Those two views disagreed. `remuxVerified` ANDs 17 predicates but
 * only 10 are rendered with a status suffix, so seven could fail with nothing to show for it:
 *
 *  - `durationMatches`, `frameCountMatches` — rendered into no field at all
 *  - `fpsMatches` when the comparison is NOT_EXPOSED — the transition emits no suffix
 *  - `locationMatches`, `mediaStoreDateMatches`, `mp4DateMatches` — their labels read
 *    "unverified", never "warn"
 *
 * The result was observed on a real device (SM-S918U1, build 9d0913f): a
 * "Remux Verification Failed" verdict reporting `failing=none-identified`, which is
 * undiagnosable and indistinguishable from a verifier bug.
 *
 * The fix is structural, not cosmetic. [passes] and [failures] read the SAME named subset, so
 * `passes(scope)` is true exactly when `failures(scope)` is empty — a failing verdict that names
 * no predicate is now unrepresentable rather than merely discouraged.
 *
 * Pure Kotlin, no Android dependencies: fully unit-testable.
 */
data class VerificationChecks(
    val playable: Boolean,
    val criticalFieldsComplete: Boolean,
    val durationMatches: Boolean,
    val frameCountMatches: Boolean,
    val videoMatches: Boolean,
    val fpsMatches: Boolean,
    val videoCodecMatches: Boolean,
    val audioCodecMatches: Boolean,
    val audioShapeMatches: Boolean,
    val audioBitratePass: Boolean,
    val hdrMatches: Boolean,
    val standardMatches: Boolean,
    val rangeMatches: Boolean,
    val rotationMatches: Boolean,
    val locationMatches: Boolean,
    val mediaStoreDateMatches: Boolean,
    val mp4DateMatches: Boolean,
    val videoBitratePass: Boolean
) {

    /** Which verdict a subset of predicates is being evaluated for. */
    enum class Scope(internal val names: List<String>) {
        /**
         * Perceptually Lossless minus the video-bitrate floor. Factored out because that one
         * predicate may be re-judged by sampled pixel certification — measured pixels outrank an
         * inferred floor — while every other failure stays terminal.
         */
        PERCEPTUAL_LOSSLESS_EXCEPT_VIDEO_BITRATE(
            listOf(
                "playable", "criticalFieldsComplete", "durationMatches", "frameCountMatches",
                "videoMatches", "fpsMatches", "hdrMatches", "standardMatches", "rangeMatches",
                "audioCodecMatches", "audioShapeMatches", "audioBitratePass", "rotationMatches",
                "locationMatches", "mediaStoreDateMatches", "mp4DateMatches"
            )
        ),
        PERCEPTUAL_LOSSLESS(
            PERCEPTUAL_LOSSLESS_EXCEPT_VIDEO_BITRATE.names + "videoBitratePass"
        ),

        /**
         * A stream copy additionally requires the video codec to be unchanged — it is a copy, not
         * a re-encode. It does NOT include the video-bitrate floor: a remux keeps the source's
         * bitrate by definition, so that floor is not a meaningful gate for it.
         */
        REMUX(
            PERCEPTUAL_LOSSLESS_EXCEPT_VIDEO_BITRATE.names + "videoCodecMatches"
        );
    }

    private fun valueOf(name: String): Boolean = when (name) {
        "playable" -> playable
        "criticalFieldsComplete" -> criticalFieldsComplete
        "durationMatches" -> durationMatches
        "frameCountMatches" -> frameCountMatches
        "videoMatches" -> videoMatches
        "fpsMatches" -> fpsMatches
        "videoCodecMatches" -> videoCodecMatches
        "audioCodecMatches" -> audioCodecMatches
        "audioShapeMatches" -> audioShapeMatches
        "audioBitratePass" -> audioBitratePass
        "hdrMatches" -> hdrMatches
        "standardMatches" -> standardMatches
        "rangeMatches" -> rangeMatches
        "rotationMatches" -> rotationMatches
        "locationMatches" -> locationMatches
        "mediaStoreDateMatches" -> mediaStoreDateMatches
        "mp4DateMatches" -> mp4DateMatches
        "videoBitratePass" -> videoBitratePass
        // Unreachable for the enum-declared names; throwing beats silently passing an unknown
        // predicate, which would let a typo weaken a verdict.
        else -> throw IllegalArgumentException("unknown verification check: $name")
    }

    /** True when every predicate in [scope] passes. */
    fun passes(scope: Scope): Boolean = scope.names.all(::valueOf)

    /** The predicates in [scope] that did NOT pass, in declaration order. Empty iff [passes]. */
    fun failures(scope: Scope): List<String> = scope.names.filterNot(::valueOf)
}
