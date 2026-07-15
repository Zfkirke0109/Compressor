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
        encoderFailed: Boolean = false,
        evidencePreferred: Boolean = false
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
        preEncodeSourceAlreadyEfficient = nearOptimal,
        unsupportedContainer = unsupported,
        encoderFailed = encoderFailed,
        preEncodeEvidencePreferredRemux = evidencePreferred
    )

    @Test
    fun realVerifiedShrinkIsTheOnlyRealCompression() {
        val r = BatchTerminalClassifier.classify(input())
        assertEquals(BatchTerminalResult.TRANSCODED_SMALLER, r)
        assertTrue(r.countsAsRealCompression)
        assertTrue(r.allowsOriginalReplacement)
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
        assertTrue(explicit.allowsOriginalReplacement)
        assertFalse(nearOptimal.allowsOriginalReplacement)
        assertFalse(fallback.allowsOriginalReplacement)
    }

    @Test
    fun evidenceLatchedRemuxIsExpectedNotUnexpected() {
        // The learning engine's measured remux-preference latch (repeated near-max-ratio
        // failures) stream-copies up-front. That is an EXPECTED, evidence-driven outcome:
        // batch_20260715_103237 measured 28 such items mislabeled UNEXPECTED_REMUX.
        val latched = BatchTerminalClassifier.classify(
            input(streamCopy = true, verified = true, effective = BatchQualityMode.REMUX_ONLY, evidencePreferred = true)
        )
        assertEquals(BatchTerminalResult.REMUX_PREFERRED_BY_EVIDENCE, latched)
        assertFalse(latched.countsAsRealCompression)
        assertFalse(latched.isFailure)

        // Without the flag the same shape remains a verification-driven UNEXPECTED_REMUX.
        val unflagged = BatchTerminalClassifier.classify(
            input(streamCopy = true, verified = true, effective = BatchQualityMode.REMUX_ONLY)
        )
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, unflagged)

        // Precedence: an explicit Remux Only request stays EXPLICIT_REMUX; encoder failure
        // stays UNEXPECTED_REMUX; source-efficiency inference outranks the latch flag.
        assertEquals(
            BatchTerminalResult.EXPLICIT_REMUX,
            BatchTerminalClassifier.classify(
                input(requested = BatchQualityMode.REMUX_ONLY, effective = BatchQualityMode.REMUX_ONLY, streamCopy = true, evidencePreferred = true)
            )
        )
        assertEquals(
            BatchTerminalResult.UNEXPECTED_REMUX,
            BatchTerminalClassifier.classify(
                input(streamCopy = true, verified = true, effective = BatchQualityMode.REMUX_ONLY, encoderFailed = true, evidencePreferred = true)
            )
        )
        assertEquals(
            BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED,
            BatchTerminalClassifier.classify(
                input(streamCopy = true, verified = true, effective = BatchQualityMode.REMUX_ONLY, nearOptimal = true, evidencePreferred = true)
            )
        )

        // An UNVERIFIED evidence-latched copy must still fail closed, never look friendly.
        assertEquals(
            BatchTerminalResult.OUTPUT_VALIDATION_FAILED,
            BatchTerminalClassifier.classify(
                input(streamCopy = true, verified = false, replacementSafe = false, effective = BatchQualityMode.REMUX_ONLY, evidencePreferred = true)
            )
        )
    }

    @Test
    fun sameSizeVerifiedEncodeIsNotCountedAsCompression() {
        // Real encode, verified, but only 1% smaller — below the meaningful-savings threshold.
        val r = BatchTerminalClassifier.classify(input(sourceSize = 100_000_000, outputSize = 99_000_000))
        assertEquals(BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER, r)
        assertFalse(r.countsAsRealCompression)
        assertFalse(r.allowsOriginalReplacement)
    }

    @Test
    fun meaningfulSavingsThresholdIsExactlyThreePercent() {
        val justBelow = BatchTerminalClassifier.classify(
            input(sourceSize = 100_000_000, outputSize = 97_010_000)
        )
        val atThreshold = BatchTerminalClassifier.classify(
            input(sourceSize = 100_000_000, outputSize = 97_000_000)
        )
        assertEquals(BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER, justBelow)
        assertEquals(BatchTerminalResult.TRANSCODED_SMALLER, atThreshold)
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
    fun failedVerificationOnStreamCopyIsAFailureAndCannotReplace() {
        val unverified = BatchTerminalClassifier.classify(
            input(
                requested = BatchQualityMode.REMUX_ONLY,
                effective = BatchQualityMode.REMUX_ONLY,
                streamCopy = true,
                verified = false,
                replacementSafe = false
            )
        )
        assertEquals(BatchTerminalResult.OUTPUT_VALIDATION_FAILED, unverified)
        assertTrue(unverified.isFailure)
        assertFalse(unverified.allowsOriginalReplacement)
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

    @Test
    fun mixedAccountingCountsOnlyVerifiedEncodedSavings() {
        val summary = BatchTerminalAccounting.summarize(
            listOf(
                BatchTerminalAccountingEntry(BatchTerminalResult.TRANSCODED_SMALLER, 100, 80),
                // A materially smaller remux is still a stream copy, never compression savings.
                BatchTerminalAccountingEntry(BatchTerminalResult.EXPLICIT_REMUX, 100, 80),
                BatchTerminalAccountingEntry(BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED, 100, 0),
                BatchTerminalAccountingEntry(BatchTerminalResult.FAILED, 100, 0),
                BatchTerminalAccountingEntry(BatchTerminalResult.CANCELLED, 100, 0)
            )
        )

        assertEquals(5, summary.processedCount)
        assertEquals(1, summary.realCompressionCount)
        assertEquals(1, summary.nonCompressionCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, summary.skippedCount)
        assertEquals(1, summary.cancelledCount)
        assertEquals(20, summary.totalBytesSaved)
        assertEquals(100, summary.realCompressionInputBytes)
        assertEquals(80, summary.realCompressionOutputBytes)
    }
}
