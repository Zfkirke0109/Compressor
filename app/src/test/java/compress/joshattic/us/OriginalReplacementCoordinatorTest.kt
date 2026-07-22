package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAFE-001's remaining gate: proving the rollback, not just the size rule.
 *
 * These cover the paths that decide whether a user keeps or loses their only copy and that cannot be
 * injected on real hardware — ENOSPC part-way through the truncating write, a provider that will not
 * report a size afterwards, and a rollback that itself fails.
 *
 * The invariant under test in every case: **the original is never left truncated without either being
 * restored or its bytes being preserved for the caller.**
 */
class OriginalReplacementCoordinatorTest {

    private val originalBytes = 5_000L
    private val outputBytes = 3_000L

    /**
     * Scriptable IO double. [sizeAfterWrite] models what the provider reports back — including the
     * "won't tell us" case (<= 0) that the original SAFE-001 bug accepted as success.
     */
    private class FakeIo(
        val stageResult: Long = 5_000L,
        val writeThrows: Boolean = false,
        val sizeAfterWrite: Long = 3_000L,
        val restoreThrows: Boolean = false,
        val sizeAfterRestore: Long = 5_000L,
        val recoveryBytes: Long = 5_000L
    ) : OriginalReplacementIo {
        var stageCalls = 0; private set
        var writeCalls = 0; private set
        var restoreCalls = 0; private set
        var discardCalls = 0; private set
        private var restored = false

        override fun stageRecoveryCopy(): Long { stageCalls++; return stageResult }
        override fun writeOutputOverOriginal() {
            writeCalls++
            if (writeThrows) throw java.io.IOException("ENOSPC: no space left on device")
        }
        override fun readBackOriginalSize(): Long = if (restored) sizeAfterRestore else sizeAfterWrite
        override fun restoreOriginalFromRecovery() {
            restoreCalls++
            if (restoreThrows) throw java.io.IOException("restore failed")
            restored = true
        }
        override fun recoveryCopyLength(): Long = recoveryBytes
        override fun discardRecoveryCopy() { discardCalls++ }
    }

    @Test
    fun happyPathReplacesAndDiscardsTheRecoveryCopy() {
        val io = FakeIo(sizeAfterWrite = outputBytes)
        val result = OriginalReplacementCoordinator.attempt(io, outputBytes)

        assertEquals(ReplacementAttempt.Replaced, result)
        assertEquals(1, io.writeCalls)
        assertEquals(0, io.restoreCalls)
        assertEquals(1, io.discardCalls) // no recovery file left behind
    }

    @Test
    fun stagingFailureNeverTruncatesTheOriginal() {
        // The most important ordering property: if we cannot stage a rollback copy, we must not
        // begin the destructive write at all.
        val io = FakeIo(stageResult = -1L)
        val result = OriginalReplacementCoordinator.attempt(io, outputBytes)

        assertEquals(
            ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.RECOVERY_STAGING_FAILED),
            result
        )
        assertEquals("must never write after a failed staging", 0, io.writeCalls)
        assertEquals(0, io.restoreCalls)
    }

    @Test
    fun enospcMidWriteRestoresTheOriginal() {
        // The scenario that made the original truncate-then-write dangerous.
        val io = FakeIo(writeThrows = true, sizeAfterRestore = originalBytes, recoveryBytes = originalBytes)
        val result = OriginalReplacementCoordinator.attempt(io, outputBytes)

        assertEquals(
            ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.WRITE_FAILED_RESTORED),
            result
        )
        assertEquals(1, io.restoreCalls)
        // The recovery bytes deliberately SURVIVE a successful rollback: the caller may still run a
        // further destructive step (Shizuku writes with `cat >`, which truncates), so only it knows
        // when they are safe to drop.
        assertEquals(0, io.discardCalls)
    }

    @Test
    fun unknownSizeAfterWriteIsNotSuccessAndTriggersRollback() {
        // THE SAFE-001 regression guard: statSize <= 0 previously short-circuited to "success",
        // leaving an unconfirmed write standing over the user's only copy.
        val io = FakeIo(sizeAfterWrite = -1L, sizeAfterRestore = originalBytes, recoveryBytes = originalBytes)
        val result = OriginalReplacementCoordinator.attempt(io, outputBytes)

        assertEquals(
            ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.WRITE_UNVERIFIED_RESTORED),
            result
        )
        assertEquals("an unconfirmed write must roll back", 1, io.restoreCalls)
    }

    @Test
    fun partialWriteIsNotSuccessAndTriggersRollback() {
        val io = FakeIo(sizeAfterWrite = outputBytes - 1, sizeAfterRestore = originalBytes, recoveryBytes = originalBytes)
        val result = OriginalReplacementCoordinator.attempt(io, outputBytes)

        assertEquals(
            ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.WRITE_UNVERIFIED_RESTORED),
            result
        )
        assertEquals(1, io.restoreCalls)
    }

    @Test
    fun failedRollbackReportsAtRiskAndKEEPSTheRecoveryBytes() {
        // Worst case. The recovery copy is now the only intact copy of the original, so it must NOT
        // be discarded — the caller preserves it before reporting.
        val io = FakeIo(writeThrows = true, restoreThrows = true)
        val result = OriginalReplacementCoordinator.attempt(io, outputBytes)

        assertEquals(ReplacementAttempt.OriginalAtRisk, result)
        assertEquals(1, io.restoreCalls)
        assertEquals("recovery bytes must survive for the caller", 0, io.discardCalls)
    }

    @Test
    fun unverifiableRestoreAlsoCountsAsAtRisk() {
        // Restore did not throw, but the read-back does not match the recovery length: we cannot
        // claim the original is whole, so fail closed rather than reassure the user.
        val io = FakeIo(
            writeThrows = true,
            restoreThrows = false,
            sizeAfterRestore = 12L,
            recoveryBytes = originalBytes
        )
        val result = OriginalReplacementCoordinator.attempt(io, outputBytes)

        assertEquals(ReplacementAttempt.OriginalAtRisk, result)
        assertEquals(0, io.discardCalls)
    }

    @Test
    fun theOriginalIsNeverLeftTruncatedWithoutRestoreOrPreservedBytes() {
        // Sweep the failure space: for every combination, either the write never happened, or the
        // original was restored, or the recovery bytes were kept for the caller. Never "truncated,
        // unrestored, and discarded".
        val stageResults = listOf(-1L, 0L, 5_000L)
        val writeThrowsOptions = listOf(true, false)
        val sizesAfterWrite = listOf(-1L, 0L, 2_999L, 3_000L)
        val restoreThrowsOptions = listOf(true, false)
        val sizesAfterRestore = listOf(-1L, 5_000L)

        for (stage in stageResults) for (wt in writeThrowsOptions) for (sw in sizesAfterWrite)
            for (rt in restoreThrowsOptions) for (sr in sizesAfterRestore) {
                val io = FakeIo(
                    stageResult = stage, writeThrows = wt, sizeAfterWrite = sw,
                    restoreThrows = rt, sizeAfterRestore = sr, recoveryBytes = 5_000L
                )
                val result = OriginalReplacementCoordinator.attempt(io, outputBytes)
                val label = "stage=$stage writeThrows=$wt sizeAfterWrite=$sw restoreThrows=$rt sizeAfterRestore=$sr"

                when (result) {
                    is ReplacementAttempt.Replaced -> {
                        // Only reachable with a byte-exact confirmation.
                        assertEquals(label, outputBytes, sw)
                        assertTrue(label, io.discardCalls == 1)
                    }
                    is ReplacementAttempt.OriginalIntact -> {
                        val neverWrote = io.writeCalls == 0
                        val wasRestored = io.restoreCalls > 0
                        assertTrue("$label: intact requires untouched or restored", neverWrote || wasRestored)
                        // If we truncated and restored, the bytes must still be available to the
                        // caller; only the never-truncated staging failure may drop them.
                        if (wasRestored) {
                            assertEquals("$label: restored must retain recovery bytes", 0, io.discardCalls)
                        }
                    }
                    ReplacementAttempt.OriginalAtRisk -> {
                        assertTrue("$label: at-risk must keep the recovery bytes", io.discardCalls == 0)
                    }
                }
            }
    }
}
