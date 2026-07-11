package compress.joshattic.us

/**
 * The single, honest terminal classification every processed video receives.
 *
 * The batch UI previously collapsed everything into "Done", which made a silent stream copy, an
 * already-optimal source, and a real verified transcode look identical. This type forces one
 * unambiguous outcome per item and, critically, records whether that outcome is a *real
 * compression* — so a remux, copy, skip, rejected output, or same-size result can never be
 * counted or reported as a successful compression.
 *
 * [countsAsRealCompression] is true only when actual encoded video bytes replaced the source AND
 * verification proved quality was preserved AND the file genuinely shrank.
 */
enum class BatchTerminalResult(
    val label: String,
    val countsAsRealCompression: Boolean,
    val isFailure: Boolean = false
) {
    /** Real perceptually-lossless (or lossy-mode) transcode that verified and shrank the file. */
    TRANSCODED_SMALLER("Compressed", countsAsRealCompression = true),

    /** A lossy mode (High Quality / Storage Saver) that verified and shrank — honest, but not PL. */
    LOSSY_SMALLER("Compressed (lossy mode)", countsAsRealCompression = true),

    /** Real transcode that verified but did not get meaningfully smaller; original kept. */
    TRANSCODED_NOT_MEANINGFULLY_SMALLER("Re-encoded — no meaningful size win; original kept", countsAsRealCompression = false),

    /** Source was already efficient for its resolution/codec; kept the exact stream copy. */
    ALREADY_HIGHLY_OPTIMIZED("Already efficient — kept original bytes", countsAsRealCompression = false),

    /** User explicitly chose Remux Only: container repackage, never claimed as compression. */
    EXPLICIT_REMUX("Remuxed (container only, not compressed)", countsAsRealCompression = false),

    /** PL attempted a real encode but fell back to a stream copy (encoder/verify failure). */
    UNEXPECTED_REMUX("Kept original — re-encode could not be verified", countsAsRealCompression = false),

    /** The output failed independent validation; the original was retained. */
    OUTPUT_VALIDATION_FAILED("Verification failed — original kept", countsAsRealCompression = false, isFailure = true),

    /** The encoder could not be configured/rejected the source and no remux fallback succeeded. */
    ENCODER_FAILURE("Encoder failed — original kept", countsAsRealCompression = false, isFailure = true),

    /** Container/codec the MP4 remux path cannot carry (e.g. VP9/Opus in WebM/MKV). */
    UNSUPPORTED_CONTAINER("Unsupported container/codec for this device path", countsAsRealCompression = false, isFailure = true),

    /** Input was already a Compressor output; skipped by design. */
    SKIPPED_ALREADY_COMPRESSED("Skipped — already a Compressor output", countsAsRealCompression = false),

    /** User cancelled the batch before this item finished. */
    CANCELLED("Cancelled", countsAsRealCompression = false),

    /** Any other unrecovered failure. */
    FAILED("Failed", countsAsRealCompression = false, isFailure = true);
}

/**
 * Immutable, Android-free description of a finished job, so classification is pure and unit-testable.
 */
data class BatchTerminalInput(
    val requestedMode: BatchQualityMode,
    /** The mode actually used to produce the accepted output (may differ after a remux fallback). */
    val effectiveMode: BatchQualityMode,
    /** True when the effective output is a stream copy rather than a real encode. */
    val wasStreamCopy: Boolean,
    val verified: Boolean,
    val replacementSafe: Boolean,
    val sourceSize: Long,
    val outputSize: Long,
    val skippedAlreadyCompressed: Boolean = false,
    val cancelled: Boolean = false,
    val hardFailure: Boolean = false,
    /** Set when a remux was chosen up-front because the source was already near-optimal. */
    val preEncodeNearOptimal: Boolean = false,
    /** Set when a remux/transcode failed because the container/codec is unsupported. */
    val unsupportedContainer: Boolean = false,
    /** Set when an encoder configuration/runtime error forced a fallback. */
    val encoderFailed: Boolean = false
) {
    /** Meaningful-savings threshold: at least this fraction smaller to call it a size win. */
    val meaningfullySmaller: Boolean
        get() = sourceSize > 0L && outputSize in 1 until sourceSize &&
            (sourceSize - outputSize).toDouble() / sourceSize.toDouble() >= MIN_MEANINGFUL_SAVINGS

    val strictlySmaller: Boolean
        get() = sourceSize > 0L && outputSize in 1 until sourceSize

    companion object {
        const val MIN_MEANINGFUL_SAVINGS = 0.03
    }
}

object BatchTerminalClassifier {
    fun classify(input: BatchTerminalInput): BatchTerminalResult {
        // Terminal states that pre-empt any output reasoning.
        if (input.cancelled) return BatchTerminalResult.CANCELLED
        if (input.skippedAlreadyCompressed) return BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED
        if (input.unsupportedContainer) return BatchTerminalResult.UNSUPPORTED_CONTAINER
        if (input.hardFailure) return BatchTerminalResult.FAILED

        // A real, verified encode that genuinely shrank the file is the only "real compression".
        if (!input.wasStreamCopy && input.verified) {
            return when {
                !input.meaningfullySmaller -> BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER
                input.effectiveMode == BatchQualityMode.PERCEPTUAL_LOSSLESS -> BatchTerminalResult.TRANSCODED_SMALLER
                input.effectiveMode == BatchQualityMode.HIGH_QUALITY ||
                    input.effectiveMode == BatchQualityMode.STORAGE_SAVER -> BatchTerminalResult.LOSSY_SMALLER
                else -> BatchTerminalResult.TRANSCODED_SMALLER
            }
        }

        // A real encode that ran but failed verification (and was not stream-copied afterwards).
        if (!input.wasStreamCopy && !input.verified) {
            if (input.encoderFailed) return BatchTerminalResult.ENCODER_FAILURE
            return BatchTerminalResult.OUTPUT_VALIDATION_FAILED
        }

        // Stream-copy outcomes: never real compression. Distinguish WHY.
        return when {
            input.encoderFailed -> BatchTerminalResult.UNEXPECTED_REMUX
            input.requestedMode == BatchQualityMode.REMUX_ONLY -> BatchTerminalResult.EXPLICIT_REMUX
            input.preEncodeNearOptimal -> BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED
            // PL requested but ended as a stream copy without a clear near-optimal/encoder reason:
            // a verification-driven fallback.
            input.requestedMode == BatchQualityMode.PERCEPTUAL_LOSSLESS -> BatchTerminalResult.UNEXPECTED_REMUX
            else -> BatchTerminalResult.EXPLICIT_REMUX
        }
    }
}
