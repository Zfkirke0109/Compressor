package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * When a Perceptually Lossless attempt is discarded, the original is now kept instead of a full
 * stream copy of it being written. Only the wasted copy goes; the verdict must not change. It
 * reads as "re-encode could not be verified", exactly as the stream-copy fallback did, and never
 * as "already efficient".
 */
class RetainedAfterFailedAttemptTest {

    private fun retained(
        readable: Boolean = true,
        encoderFailed: Boolean = false,
        sourceEfficient: Boolean = false,
        evidencePreferred: Boolean = false
    ) = BatchTerminalClassifier.classify(
        BatchTerminalInput(
            requestedMode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            effectiveMode = BatchQualityMode.REMUX_ONLY,
            wasStreamCopy = false,
            verified = readable,
            replacementSafe = false,
            sourceSize = 1_000_000_000,
            outputSize = 1_000_000_000,
            preEncodeSourceAlreadyEfficient = sourceEfficient,
            preEncodeEvidencePreferredRemux = evidencePreferred,
            encoderFailed = encoderFailed,
            retainedOriginalNoOutput = true,
            retainedAfterFailedAttempt = true
        )
    )

    @Test
    fun aDiscardedAttemptReadsAsUnverifiedNotAsAlreadyEfficient() {
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, retained())
        // Even if the plan once called the source efficient, the attempt that ran was not accepted.
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, retained(sourceEfficient = true))
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, retained(evidencePreferred = true))
    }

    @Test
    fun anEncoderFailureIsTheSameUnverifiedOutcome() {
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, retained(encoderFailed = true))
    }

    @Test
    fun itIsNeverCompressionAndNeverReplacesTheOriginal() {
        val r = retained()
        assertFalse(r.countsAsRealCompression)
        assertFalse(r.allowsOriginalReplacement)
        assertFalse(r.isFailure)
    }

    @Test
    fun anUnreadableSourceStillFailsClosed() {
        assertEquals(BatchTerminalResult.OUTPUT_VALIDATION_FAILED, retained(readable = false))
    }

    @Test
    fun theUpFrontFastPathIsUnchanged() {
        val upFront = BatchTerminalClassifier.classify(
            BatchTerminalInput(
                requestedMode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
                effectiveMode = BatchQualityMode.REMUX_ONLY,
                wasStreamCopy = false,
                verified = true,
                replacementSafe = false,
                sourceSize = 1_000_000_000,
                outputSize = 1_000_000_000,
                preEncodeSourceAlreadyEfficient = true,
                retainedOriginalNoOutput = true
            )
        )
        assertEquals(BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED, upFront)
    }
}
