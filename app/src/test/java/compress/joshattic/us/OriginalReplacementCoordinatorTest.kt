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
        val recoveryBytes: Long = 5_000L,
        val outputContentMatches: Boolean = true, val recoveryContentMatches: Boolean = true
    ) : OriginalReplacementIo {
        var stageCalls = 0; private set
        var writeCalls = 0; private set
        var restoreCalls = 0; private set
        var discardCalls = 0; private set
        private var restored = false

        override fun writtenOutputMatches() = outputContentMatches
        override fun restoredOriginalMatches() = recoveryContentMatches
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
    @Test fun equalLengthCorruptedWriteMustRestoreTheOriginal() {
        val io = FakeIo(outputContentMatches = false)
        assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.WRITE_UNVERIFIED_RESTORED),
            OriginalReplacementCoordinator.attempt(io, outputBytes, originalBytes))
        assertEquals(1, io.restoreCalls)
    }
    @Test fun equalLengthCorruptedRestoreMustKeepTheRecoveryBytes() {
        val io = FakeIo(writeThrows = true, recoveryContentMatches = false)
        assertEquals(ReplacementAttempt.OriginalAtRisk, OriginalReplacementCoordinator.attempt(io, outputBytes, originalBytes))
        assertEquals(0, io.discardCalls)
    }
    @Test fun partialRecoveryCannotAuthorizeATruncatingWrite() {
        val io = FakeIo(stageResult = 3000)
        assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.RECOVERY_STAGING_FAILED),
            OriginalReplacementCoordinator.attempt(io, outputBytes, originalBytes))
        assertEquals(0, io.writeCalls)
    }

    @Test fun retriesAllocateUniqueRecoveryFilesAndPreserveOlderBytes() {
        val dir = java.nio.file.Files.createTempDirectory("recovery-test").toFile()
        try {
            val prior = ReplacementContentProof.newRecoveryFile(dir, "same-job")
            prior.writeBytes(byteArrayOf(1,2,3))
            val next = ReplacementContentProof.newRecoveryFile(dir, "same-job")
            assertFalse(prior == next)
            assertTrue(prior.readBytes().contentEquals(byteArrayOf(1,2,3)))
            assertFalse(BatchCacheRetention.isDeletable(prior.absolutePath, emptySet()))
        } finally { dir.deleteRecursively() }
    }
    @Test fun sameLengthMutationChangesReplacementContentProof() {
        assertFalse(ReplacementContentProof.sha256(byteArrayOf(1,2,3).inputStream()) ==
            ReplacementContentProof.sha256(byteArrayOf(1,2,4).inputStream()))
    }

    // ---- A replacement is bound to the source the output was made from ---------------------

    /**
     * A provider holding real bytes, so a test can change them between certification and the
     * write. [swapBeforeStaging] models the reviewed gap: different bytes of the SAME length appear
     * after the item's source was read for the encode and before its rollback copy is taken.
     */
    private class ProviderIo(
        var file: ByteArray,
        val output: ByteArray,
        val swapBeforeStaging: ByteArray? = null,
        /** Another app's write after the rollback copy was staged and before the truncating open. */
        val swapAfterStaging: ByteArray? = null,
        val corruptWrittenByte: Boolean = false,
        val sizeUnknownAfterWrite: Boolean = false,
        val contentUnreadableAfterWrite: Boolean = false,
        val writeThrowsAfterTruncating: Boolean = false
    ) : OriginalReplacementIo {
        var recovery: ByteArray? = null
        var opensForWriting = 0; private set
        var discarded = false; private set
        private var restored = false

        private fun sha(bytes: ByteArray) = ReplacementContentProof.sha256(bytes.inputStream())

        override fun stageRecoveryCopy(): Long {
            swapBeforeStaging?.let { file = it }
            recovery = file.copyOf()
            return file.size.toLong()
        }
        override fun stagedSourceDigest(): String? = recovery?.let(::sha)
        override fun writeOutputOverOriginal() {
            swapAfterStaging?.let { file = it.copyOf() }
            opensForWriting++
            file = ByteArray(0) // the truncating open
            if (writeThrowsAfterTruncating) throw java.io.IOException("provider died mid-write")
            file = output.copyOf().also { if (corruptWrittenByte) it[0] = (it[0] + 1).toByte() }
        }
        override fun readBackOriginalSize(): Long = if (sizeUnknownAfterWrite && !restored) -1L else file.size.toLong()
        override fun restoreOriginalFromRecovery() { opensForWriting++; file = recovery!!.copyOf(); restored = true }
        override fun recoveryCopyLength(): Long = recovery?.size?.toLong() ?: 0L
        override fun discardRecoveryCopy() { discarded = true; recovery = null }
        override fun writtenOutputProof(): ContentProof = when {
            contentUnreadableAfterWrite && !restored -> ContentProof.UNAVAILABLE
            sha(file) == sha(output) -> ContentProof.MATCH
            else -> ContentProof.MISMATCH
        }
        override fun restoredOriginalProof(): ContentProof =
            if (recovery != null && sha(file) == sha(recovery!!)) ContentProof.MATCH else ContentProof.MISMATCH
        override fun writtenOutputMatches() = writtenOutputProof() == ContentProof.MATCH
        override fun restoredOriginalMatches() = restoredOriginalProof() == ContentProof.MATCH
    }

    private val verifiedSource = ByteArray(5_000) { (it % 251).toByte() }
    private val smallerOutput = ByteArray(3_000) { (it % 13).toByte() }
    private fun digest(bytes: ByteArray) = ReplacementContentProof.sha256(bytes.inputStream())
    private fun request(bound: String? = digest(verifiedSource)) =
        ReplacementRequest(smallerOutput.size.toLong(), verifiedSource.size.toLong(), bound)

    @Test fun aSourceThatChangedToOtherBytesOfTheSameLengthIsNeverOverwritten() {
        // The encode and its certificate are of `verifiedSource`. Before the rollback copy is
        // staged, the provider holds a different file of exactly the same length: the size check
        // passes, and without the bound digest the output would be written over the newer file.
        val newer = verifiedSource.copyOf().also { it[2_500] = (it[2_500] + 1).toByte() }
        val io = ProviderIo(verifiedSource.copyOf(), smallerOutput, swapBeforeStaging = newer)
        val tx = OriginalReplacementCoordinator.replace(io, request())

        assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.SOURCE_CHANGED), tx.attempt)
        assertEquals(ReplacementTransaction.REFUSED_SOURCE_CHANGED, tx.outcome)
        assertEquals(0, io.opensForWriting)
        assertTrue("the newer file is exactly as the provider left it", io.file.contentEquals(newer))
        assertEquals(false, tx.stagedMatchesBoundSource)
        assertFalse(tx.originalWasOpenedForWriting)
        assertTrue("nothing was at risk, so no rollback copy is kept", io.discarded)
        // The unbound core, which only compares lengths, is what would have gone on to write.
        val unbound = ProviderIo(verifiedSource.copyOf(), smallerOutput, swapBeforeStaging = newer)
        assertEquals(ReplacementAttempt.Replaced, OriginalReplacementCoordinator.attempt(unbound, smallerOutput.size.toLong(), verifiedSource.size.toLong()))
    }

    @Test fun anUnchangedBoundSourceIsReplacedAndConfirmedByContent() {
        val io = ProviderIo(verifiedSource.copyOf(), smallerOutput)
        val tx = OriginalReplacementCoordinator.replace(io, request())
        assertEquals(ReplacementAttempt.Replaced, tx.attempt)
        assertEquals(ReplacementTransaction.COMMITTED, tx.outcome)
        assertEquals(true, tx.stagedMatchesBoundSource)
        assertEquals(ReadBack.CONFIRMED, tx.writeReadBack)
        assertEquals(null, tx.restoreReadBack)
        assertEquals(digest(verifiedSource), tx.stagedDigest)
        assertTrue(io.file.contentEquals(smallerOutput))
        assertTrue(io.discarded)
        // The digest is compared as text, whatever its case.
        val upper = ProviderIo(verifiedSource.copyOf(), smallerOutput)
        assertEquals(ReplacementAttempt.Replaced, OriginalReplacementCoordinator.replace(upper, request(digest(verifiedSource).uppercase())).attempt)
    }

    @Test fun aWriteByAnotherAppAfterStagingIsOverwrittenAndNotKept() {
        // The limit of what a replacement through a content provider can promise. Another app
        // writes the file after the rollback copy was staged and checked, and before the truncating
        // open. Nothing in the transaction can see it: the staged copy matched, the write lands,
        // and the read-back confirms the output. The other app's bytes are gone, and the staged
        // copy, which is discarded once the write is confirmed, held the earlier original anyway.
        val newer = verifiedSource.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val io = ProviderIo(verifiedSource.copyOf(), smallerOutput, swapAfterStaging = newer)
        val tx = OriginalReplacementCoordinator.replace(io, request())

        assertEquals(ReplacementAttempt.Replaced, tx.attempt)
        assertEquals(ReadBack.CONFIRMED, tx.writeReadBack)
        assertTrue(io.file.contentEquals(smallerOutput))
        assertEquals("what was staged is the verified original, not the newer write", digest(verifiedSource), tx.stagedDigest)
        assertFalse(digest(newer) == tx.stagedDigest)
        assertTrue("and it is discarded once the write is confirmed", io.discarded)
    }

    @Test fun aSourceWithNoBoundDigestIsNeverStagedOrOpened() {
        for (bound in listOf<String?>(null, "", "  ")) {
            val io = ProviderIo(verifiedSource.copyOf(), smallerOutput)
            val tx = OriginalReplacementCoordinator.replace(io, request(bound))
            assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.SOURCE_NOT_BOUND), tx.attempt)
            assertEquals(ReplacementTransaction.REFUSED_SOURCE_NOT_BOUND, tx.outcome)
            assertEquals(0, io.opensForWriting)
            assertEquals(null, io.recovery)
            assertTrue(io.file.contentEquals(verifiedSource))
        }
    }

    @Test fun aStagedCopyWhoseDigestCannotBeTakenProvesNothingAndWritesNothing() {
        val io = object : OriginalReplacementIo by ProviderIo(verifiedSource.copyOf(), smallerOutput) {
            override fun stagedSourceDigest(): String? = null
        }
        val tx = OriginalReplacementCoordinator.replace(io, request())
        assertEquals(ReplacementTransaction.REFUSED_SOURCE_CHANGED, tx.outcome)
        assertFalse(tx.originalWasOpenedForWriting)
    }

    @Test fun anEqualLengthCorruptReadBackIsRestoredAndNamedAMismatch() {
        val io = ProviderIo(verifiedSource.copyOf(), smallerOutput, corruptWrittenByte = true)
        val tx = OriginalReplacementCoordinator.replace(io, request())
        assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.WRITE_UNVERIFIED_RESTORED), tx.attempt)
        assertEquals(ReadBack.CONTENT_MISMATCH, tx.writeReadBack)
        assertEquals(ReadBack.CONFIRMED, tx.restoreReadBack)
        assertEquals(ReplacementTransaction.RESTORED_READBACK_MISMATCH, tx.outcome)
        assertTrue("the original's bytes are back", io.file.contentEquals(verifiedSource))
        assertFalse("the caller decides when the rollback copy may go", io.discarded)
    }

    @Test fun aProviderThatWillNotLetTheWriteBeReadBackIsRestoredAndNamedUnavailable() {
        for (io in listOf(
            ProviderIo(verifiedSource.copyOf(), smallerOutput, sizeUnknownAfterWrite = true),
            ProviderIo(verifiedSource.copyOf(), smallerOutput, contentUnreadableAfterWrite = true)
        )) {
            val tx = OriginalReplacementCoordinator.replace(io, request())
            assertEquals(ReplacementTransaction.RESTORED_READBACK_UNAVAILABLE, tx.outcome)
            assertTrue(tx.writeReadBack!!.unavailable)
            assertTrue(tx.originalWasOpenedForWriting)
            assertTrue(io.file.contentEquals(verifiedSource))
        }
    }

    @Test fun aWriteThatDiesAfterTruncatingIsRestoredFromTheStagedBytes() {
        val io = ProviderIo(verifiedSource.copyOf(), smallerOutput, writeThrowsAfterTruncating = true)
        val tx = OriginalReplacementCoordinator.replace(io, request())
        assertEquals(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.WRITE_FAILED_RESTORED), tx.attempt)
        assertEquals(ReplacementTransaction.RESTORED_WRITE_FAILED, tx.outcome)
        assertEquals(ReadBack.WRITE_THREW, tx.writeReadBack)
        assertTrue(io.file.contentEquals(verifiedSource))
    }

    @Test fun aJournalThatCannotBeMadeDurableStopsTheReplacementBeforeTheOriginalIsOpened() {
        // A rollback copy the next start cannot identify protects nothing after a process death,
        // so the truncating write does not begin without its record.
        for (failAt in listOf("staging", "staged")) {
            val io = ProviderIo(verifiedSource.copyOf(), smallerOutput)
            val calls = mutableListOf<String>()
            val journal = object : ReplacementJournalSink {
                override fun staging(): Boolean { calls += "staging"; return failAt != "staging" }
                override fun staged(stagedDigest: String?, stagedBytes: Long): Boolean { calls += "staged"; return failAt != "staged" }
            }
            val tx = OriginalReplacementCoordinator.replace(io, request(), journal)
            assertEquals(failAt, ReplacementTransaction.REFUSED_STAGING_FAILED, tx.outcome)
            assertEquals(failAt, 0, io.opensForWriting)
            assertTrue(failAt, io.file.contentEquals(verifiedSource))
            assertEquals(failAt, if (failAt == "staging") listOf("staging") else listOf("staging", "staged"), calls)
        }
    }

    @Test fun theJournalIsWrittenBeforeTheCopyAndAgainBeforeTheDestructiveOpen() {
        val events = mutableListOf<String>()
        val provider = ProviderIo(verifiedSource.copyOf(), smallerOutput)
        val io = object : OriginalReplacementIo by provider {
            override fun stageRecoveryCopy(): Long { events += "stage"; return provider.stageRecoveryCopy() }
            override fun writeOutputOverOriginal() { events += "write"; provider.writeOutputOverOriginal() }
        }
        val journal = object : ReplacementJournalSink {
            override fun staging(): Boolean { events += "journal:staging"; return true }
            override fun staged(stagedDigest: String?, stagedBytes: Long): Boolean {
                events += "journal:staged:${stagedDigest == digest(verifiedSource)}:$stagedBytes"; return true
            }
        }
        assertEquals(ReplacementAttempt.Replaced, OriginalReplacementCoordinator.replace(io, request(), journal).attempt)
        assertEquals(listOf("journal:staging", "stage", "journal:staged:true:5000", "write"), events)
    }

    @Test fun everyOutcomeIsADistinctStableCode() {
        val codes = listOf(
            ReplacementTransaction.COMMITTED, ReplacementTransaction.AT_RISK, ReplacementTransaction.REFUSED_SOURCE_NOT_BOUND,
            ReplacementTransaction.REFUSED_SOURCE_CHANGED, ReplacementTransaction.REFUSED_STAGING_FAILED,
            ReplacementTransaction.RESTORED_WRITE_FAILED, ReplacementTransaction.RESTORED_READBACK_UNAVAILABLE,
            ReplacementTransaction.RESTORED_READBACK_MISMATCH, ReplacementOutcome.NOT_REQUESTED,
            ReplacementOutcome.REFUSED_OUTPUT_MISSING, ReplacementOutcome.REFUSED_NOT_REPLACEMENT_SAFE,
            ReplacementOutcome.REFUSED_OVERWRITE_GUARD, ReplacementOutcome.REFUSED_BACKUP_FAILED,
            ReplacementOutcome.REFUSED_LOW_SPACE, ReplacementOutcome.REFUSED_RECOVERY_UNAVAILABLE,
            ReplacementOutcome.COMMITTED_SHIZUKU, ReplacementOutcome.AT_RISK_ORIGINAL_PRESERVED,
            ReplacementOutcome.AT_RISK_RECOVERY_RETAINED
        )
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.startsWith("replace_") })
        // A record carries a digest only as a 12-hex prefix: enough to compare roles, not a content key.
        assertEquals("0123456789ab", ReplacementOutcome.redact("0123456789abcdef".repeat(4)))
        assertEquals(null, ReplacementOutcome.redact(null))
    }

}
