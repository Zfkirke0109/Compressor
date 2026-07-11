package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTerminalClassifierTest {

    private fun input(
        requested: BatchQualityMode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
        effective: BatchQualityMode = requested,
        streamCopy: Boolean = false,
        verified: Boolean = true,
        replacementSafe: Boolean = true,
        sourceSize: Long = 100_000_000,
        outputSize: Long = 80_000_000,
        skipped: Boolean = false,
        cancelled: Boolean = false,
        hardFailure: Boolean = false,
        nearOptimal: Boolean = false,
        unsupported: Boolean = false,
        encoderFailed: Boolean = false
    ) = BatchTerminalInput(
        requestedMode = requested,
        effectiveMode = effective,
        wasStreamCopy = streamCopy,
        verified = verified,
        replacementSafe = replacementSafe,
        sourceSize = sourceSize,
        outputSize = outputSize,
        skippedAlreadyCompressed = skipped,
        cancelled = cancelled,
        hardFailure = hardFailure,
        preEncodeNearOptimal = nearOptimal,
        unsupportedContainer = unsupported,
        encoderFailed = encoderFailed
    )

    @Test
    fun realVerifiedShrinkIsTheOnlyRealCompression() {
        val r = BatchTerminalClassifier.classify(input())
        assertEquals(BatchTerminalResult.TRANSCODED_SMALLER, r)
        assertTrue(r.countsAsRealCompression)
    }

    @Test
    fun streamCopyIsNeverRealCompression() {
        // Explicit remux, near-optimal remux, and PL verification-fallback remux are all copies.
        val explicit = BatchTerminalClassifier.classify(
            input(requested = BatchQualityMode.REMUX_ONLY, effective = BatchQualityMode.REMUX_ONLY, streamCopy = true, verified = true)
        )
        val nearOptimal = BatchTerminalClassifier.classify(
            input(streamCopy = true, verified = true, effective = BatchQualityMode.REMUX_ONLY, nearOptimal = true)
        )
        val fallback = BatchTerminalClassifier.classify(
            input(streamCopy = true, verified = true, effective = BatchQualityMode.REMUX_ONLY)
        )
        assertEquals(BatchTerminalResult.EXPLICIT_REMUX, explicit)
        assertEquals(BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED, nearOptimal)
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, fallback)
        assertFalse(explicit.countsAsRealCompression)
        assertFalse(nearOptimal.countsAsRealCompression)
        assertFalse(fallback.countsAsRealCompression)
    }

    @Test
    fun sameSizeVerifiedEncodeIsNotCountedAsCompression() {
        // Real encode, verified, but only 1% smaller — below the meaningful-savings threshold.
        val r = BatchTerminalClassifier.classify(input(sourceSize = 100_000_000, outputSize = 99_000_000))
        assertEquals(BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER, r)
        assertFalse(r.countsAsRealCompression)
    }

    @Test
    fun outputLargerThanSourceIsNotCompression() {
        val r = BatchTerminalClassifier.classify(input(sourceSize = 80_000_000, outputSize = 100_000_000))
        assertEquals(BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER, r)
        assertFalse(r.countsAsRealCompression)
    }

    @Test
    fun failedVerificationOnRealEncodeIsAFailure() {
        val r = BatchTerminalClassifier.classify(input(verified = false, streamCopy = false))
        assertEquals(BatchTerminalResult.OUTPUT_VALIDATION_FAILED, r)
        assertTrue(r.isFailure)
        assertFalse(r.countsAsRealCompression)
    }

    @Test
    fun lossyModesReportAsLossyEvenWhenTheyShrink() {
        val hq = BatchTerminalClassifier.classify(
            input(requested = BatchQualityMode.HIGH_QUALITY, effective = BatchQualityMode.HIGH_QUALITY)
        )
        assertEquals(BatchTerminalResult.LOSSY_SMALLER, hq)
        assertTrue(hq.countsAsRealCompression)
    }

    @Test
    fun terminalPreemptionsWin() {
        assertEquals(BatchTerminalResult.CANCELLED, BatchTerminalClassifier.classify(input(cancelled = true)))
        assertEquals(BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED, BatchTerminalClassifier.classify(input(skipped = true)))
        assertEquals(BatchTerminalResult.UNSUPPORTED_CONTAINER, BatchTerminalClassifier.classify(input(unsupported = true)))
        assertEquals(BatchTerminalResult.FAILED, BatchTerminalClassifier.classify(input(hardFailure = true)))
    }

    @Test
    fun encoderFailureFallbackIsDistinctFromNearOptimal() {
        val r = BatchTerminalClassifier.classify(
            input(streamCopy = true, verified = true, effective = BatchQualityMode.REMUX_ONLY, encoderFailed = true)
        )
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, r)
        assertFalse(r.countsAsRealCompression)
    }
}
