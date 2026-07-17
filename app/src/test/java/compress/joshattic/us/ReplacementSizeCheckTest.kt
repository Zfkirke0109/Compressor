package compress.joshattic.us

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-place original overwrite is destructive (truncate-then-write). Its success may only be
 * asserted when the resulting on-disk size EXACTLY equals the intended output — the prior code
 * treated an unknown size (statSize <= 0) as success, which could accept an unconfirmed/partial
 * write over the user's only copy. These tests pin the strict rule so that regression cannot recur.
 */
class ReplacementSizeCheckTest {

    @Test
    fun exactMatchIsTheOnlySuccess() {
        assertTrue(ReplacementSizeCheck.verified(writtenSizeBytes = 1_000L, expectedSizeBytes = 1_000L))
    }

    @Test
    fun unknownOrUnreportedSizeFailsClosed() {
        // The old bug: verifiedSize <= 0L was short-circuited to "success". It must now fail.
        assertFalse(ReplacementSizeCheck.verified(writtenSizeBytes = -1L, expectedSizeBytes = 1_000L))
        assertFalse(ReplacementSizeCheck.verified(writtenSizeBytes = 0L, expectedSizeBytes = 1_000L))
    }

    @Test
    fun partialWriteFails() {
        assertFalse(ReplacementSizeCheck.verified(writtenSizeBytes = 512L, expectedSizeBytes = 1_000L))
        assertFalse(ReplacementSizeCheck.verified(writtenSizeBytes = 999L, expectedSizeBytes = 1_000L))
    }

    @Test
    fun overshootAlsoFails() {
        assertFalse(ReplacementSizeCheck.verified(writtenSizeBytes = 1_001L, expectedSizeBytes = 1_000L))
    }

    @Test
    fun nonPositiveExpectedNeverSucceeds() {
        // A zero/negative expected length is not a meaningful target and must never read as success,
        // even if the written size "matches" it.
        assertFalse(ReplacementSizeCheck.verified(writtenSizeBytes = 0L, expectedSizeBytes = 0L))
        assertFalse(ReplacementSizeCheck.verified(writtenSizeBytes = -1L, expectedSizeBytes = -1L))
    }
}
