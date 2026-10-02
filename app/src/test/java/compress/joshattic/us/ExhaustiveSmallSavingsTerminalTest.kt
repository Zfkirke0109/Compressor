package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive Perceptually Lossless admits any encode predicted to be smaller than its source, so
 * the terminal classifier must not then throw a verified small saving away under the 3 % "meaningful"
 * bar. These pin that only the SAVINGS bar moves: verification, mode and strictness are untouched.
 */
class ExhaustiveSmallSavingsTerminalTest {

    private fun pl(
        outputSize: Long,
        verified: Boolean = true,
        acceptAny: Boolean,
        mode: BatchQualityMode = BatchQualityMode.PERCEPTUAL_LOSSLESS
    ) = BatchTerminalClassifier.classify(
        BatchTerminalInput(
            requestedMode = mode,
            effectiveMode = mode,
            wasStreamCopy = false,
            verified = verified,
            replacementSafe = verified,
            sourceSize = 100_000_000,
            outputSize = outputSize,
            acceptAnyVerifiedSaving = acceptAny
        )
    )

    @Test
    fun aVerifiedOnePercentSavingIsARealCompressionInExhaustiveMode() {
        val r = pl(outputSize = 99_000_000, acceptAny = true)
        assertEquals(BatchTerminalResult.TRANSCODED_SMALLER, r)
        assertTrue(r.countsAsRealCompression)
    }

    @Test
    fun theSameSavingOutsideExhaustiveModeKeepsTheThreePercentBar() {
        assertEquals(
            BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER,
            pl(outputSize = 99_000_000, acceptAny = false)
        )
    }

    @Test
    fun oneByteSmallerCountsButEqualSizeNeverDoes() {
        assertEquals(BatchTerminalResult.TRANSCODED_SMALLER, pl(outputSize = 99_999_999, acceptAny = true))
        assertEquals(
            BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER,
            pl(outputSize = 100_000_000, acceptAny = true)
        )
    }

    @Test
    fun anUnverifiedOutputIsNeverRescuedByTheSmallerBar() {
        val r = pl(outputSize = 50_000_000, verified = false, acceptAny = true)
        assertFalse(r.countsAsRealCompression)
        assertTrue(r.isFailure)
    }

    @Test
    fun lossyModesKeepTheThreePercentBarEvenWithTheFlagSet() {
        assertEquals(
            BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER,
            pl(outputSize = 99_000_000, acceptAny = true, mode = BatchQualityMode.HIGH_QUALITY)
        )
    }
}
