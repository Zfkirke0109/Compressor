package compress.joshattic.us

/**
 * The IO primitives a destructive in-place replacement needs, abstracted so the
 * stage -> write -> verify -> rollback state machine can be exercised without a device.
 *
 * This seam exists because the cases that matter most are the ones that are effectively impossible
 * to inject on real hardware: ENOSPC part-way through the truncating write, a provider that will not
 * report a size afterwards, and a rollback that itself fails. Those are exactly the paths that decide
 * whether a user keeps or loses their only copy, so they must be provable.
 */
interface OriginalReplacementIo {
    /**
     * Content proof after the write: the original's bytes read back equal the output's. A size
     * match alone cannot catch an equal-length corruption. Defaults to true only for doubles that
     * do not model content; the production IO compares SHA-256 digests.
     */
    fun writtenOutputMatches(): Boolean

    /** Content proof after a restore: the original's bytes read back equal the recovery copy's. */
    fun restoredOriginalMatches(): Boolean

    /** Copies the ORIGINAL bytes to a private recovery file. Returns staged bytes; <= 0 means failed. */
    fun stageRecoveryCopy(): Long

    /** Truncating write of the verified output over the original. Throws if the write fails. */
    fun writeOutputOverOriginal()

    /** Size of the original read back after a write; <= 0 when the provider will not report one. */
    fun readBackOriginalSize(): Long

    /** Truncating write of the recovery bytes back over the original. Throws if the restore fails. */
    fun restoreOriginalFromRecovery()

    /** Bytes currently held in the recovery copy. */
    fun recoveryCopyLength(): Long

    /** Deletes the recovery copy. */
    fun discardRecoveryCopy()

    /**
     * SHA-256 of the recovery copy as staged: the original's bytes at the moment they were secured.
     * Null when it was not computed. The coordinator compares it with the digest the item's source
     * had when it was read for the encode ([ReplacementRequest.boundSourceDigest]).
     */
    fun stagedSourceDigest(): String? = null

    /**
     * [writtenOutputMatches] with the reason kept: a provider that would not let the written bytes
     * be read back ([ContentProof.UNAVAILABLE]) is a different event from bytes that read back
     * wrong ([ContentProof.MISMATCH]). Neither confirms the write.
     */
    fun writtenOutputProof(): ContentProof = if (writtenOutputMatches()) ContentProof.MATCH else ContentProof.MISMATCH

    /** [restoredOriginalMatches] with the reason kept. */
    fun restoredOriginalProof(): ContentProof = if (restoredOriginalMatches()) ContentProof.MATCH else ContentProof.MISMATCH
}

/** Whether bytes read back through the provider equal the bytes they should be. */
enum class ContentProof { MATCH, MISMATCH, UNAVAILABLE }

/**
 * How a write (or a restore) stood when it was read back. Only [CONFIRMED] lets it stand; every
 * other value is treated as a failed write. The value is kept so the record can say WHY: a
 * provider that reports no size or lets nothing be read back is not the same finding as one that
 * returns the wrong bytes.
 */
enum class ReadBack(val wire: String) {
    CONFIRMED("confirmed"),
    WRITE_THREW("write_threw"),
    SIZE_UNAVAILABLE("size_unavailable"),
    SIZE_MISMATCH("size_mismatch"),
    CONTENT_UNAVAILABLE("content_unavailable"),
    CONTENT_MISMATCH("content_mismatch");

    /** The provider gave nothing to compare, as opposed to something that compared unequal. */
    val unavailable: Boolean get() = this == SIZE_UNAVAILABLE || this == CONTENT_UNAVAILABLE
}

/**
 * What a replacement is allowed to overwrite.
 *
 * [boundSourceDigest] is the SHA-256 the source had when the item began, before anything read it
 * for the encode and the verification. The output was made from, and verified against, THOSE
 * bytes. The original is staged for rollback only after certification, tens of seconds or minutes
 * later, and a provider can hand back different bytes of the same length by then: the length
 * check passes, and an output derived from the earlier file would overwrite the newer one. With
 * the digest bound, the staged copy must equal it or nothing is written. Null means no digest was
 * taken, and that also means nothing is written: continuity that was not established is not
 * assumed.
 */
data class ReplacementRequest(
    val expectedOutputBytes: Long,
    val expectedSourceBytes: Long?,
    val boundSourceDigest: String?
)

/**
 * The durable record a process death would otherwise lose (ReplacementJournal): written before the
 * rollback copy is made, and again, with the copy's digest, before the truncating write. Each
 * returns false when the record could not be made durable, and the replacement then does not go
 * on: a rollback copy the next start cannot identify protects nothing.
 */
interface ReplacementJournalSink {
    /** Before any byte of the original is copied. */
    fun staging(): Boolean = true

    /** The copy is durable and equals the bound source; the destructive open comes next. */
    fun staged(stagedDigest: String?, stagedBytes: Long): Boolean = true

    companion object {
        val NONE = object : ReplacementJournalSink {}
    }
}

/**
 * One replacement, start to finish: what was decided ([attempt]) and the evidence for each step.
 * Every field is a state or a count; nothing here names a file.
 */
data class ReplacementTransaction(
    val attempt: ReplacementAttempt,
    /** Bytes secured in the recovery copy; <= 0 when staging failed or never ran. */
    val stagedBytes: Long,
    val stagedDigest: String?,
    /** Staged original equals the digest bound at item start. Null when nothing was staged or bound. */
    val stagedMatchesBoundSource: Boolean?,
    /** Null when the truncating write never began. */
    val writeReadBack: ReadBack?,
    /** Null when no restore was needed. */
    val restoreReadBack: ReadBack?
) {
    /** Stable machine state of the transaction, for the `replace` stage record. */
    val outcome: String
        get() = when (val a = attempt) {
            ReplacementAttempt.Replaced -> COMMITTED
            ReplacementAttempt.OriginalAtRisk -> AT_RISK
            is ReplacementAttempt.OriginalIntact -> when (a.reason) {
                ReplacementAttempt.Reason.SOURCE_NOT_BOUND -> REFUSED_SOURCE_NOT_BOUND
                ReplacementAttempt.Reason.SOURCE_CHANGED -> REFUSED_SOURCE_CHANGED
                ReplacementAttempt.Reason.RECOVERY_STAGING_FAILED -> REFUSED_STAGING_FAILED
                ReplacementAttempt.Reason.WRITE_FAILED_RESTORED -> RESTORED_WRITE_FAILED
                ReplacementAttempt.Reason.WRITE_UNVERIFIED_RESTORED ->
                    if (writeReadBack?.unavailable == true) RESTORED_READBACK_UNAVAILABLE else RESTORED_READBACK_MISMATCH
            }
        }

    /** True when the destructive open happened, whatever followed. */
    val originalWasOpenedForWriting: Boolean get() = writeReadBack != null

    companion object {
        const val COMMITTED = "replace_committed"
        const val AT_RISK = "replace_at_risk"
        const val REFUSED_SOURCE_NOT_BOUND = "replace_refused_source_not_bound"
        const val REFUSED_SOURCE_CHANGED = "replace_refused_source_changed"
        const val REFUSED_STAGING_FAILED = "replace_refused_staging_failed"
        const val RESTORED_WRITE_FAILED = "replace_restored_write_failed"
        const val RESTORED_READBACK_UNAVAILABLE = "replace_restored_readback_unavailable"
        const val RESTORED_READBACK_MISMATCH = "replace_restored_readback_mismatch"
    }
}

/**
 * Outcome of a replacement attempt. The variants describe the state of the USER'S ORIGINAL FILE,
 * because that — not whether the feature "worked" — is what the caller must report honestly.
 */
sealed interface ReplacementAttempt {
    /** The output is in place; the original was replaced as intended. */
    object Replaced : ReplacementAttempt

    /**
     * The original was never modified, or was modified and then fully restored — safe either way.
     *
     * When [reason] is a RESTORED variant the recovery copy is intentionally still on disk: the
     * caller may have further destructive steps to run, and only it knows when the bytes are no
     * longer needed. [Reason.RECOVERY_STAGING_FAILED] leaves nothing to dispose of.
     */
    data class OriginalIntact(val reason: Reason) : ReplacementAttempt

    /**
     * The original may be incomplete AND could not be restored. The recovery copy is deliberately
     * NOT discarded in this state — it holds the only intact copy of the original bytes, and the
     * caller is expected to preserve them (e.g. to the gallery) before reporting.
     */
    object OriginalAtRisk : ReplacementAttempt

    enum class Reason {
        /** Never truncated: no digest of the source was bound when the item began, so continuity is unknown. */
        SOURCE_NOT_BOUND,
        /** Never truncated: the original no longer holds the bytes the output was made from and verified against. */
        SOURCE_CHANGED,
        /** Never truncated: the rollback copy could not be staged, so the destructive write never began. */
        RECOVERY_STAGING_FAILED,
        /** The truncating write threw; the original was restored from the recovery copy. */
        WRITE_FAILED_RESTORED,
        /** The write completed but could not be confirmed byte-for-byte; the original was restored. */
        WRITE_UNVERIFIED_RESTORED
    }
}

/**
 * Decides the destructive replacement, in the only order that keeps the original recoverable:
 * stage a rollback copy FIRST, only then truncate; require a byte-exact confirmation; restore on any
 * failure or unconfirmed result.
 *
 * Confirmation is size AND content: the read-back size must match and the read-back bytes must equal
 * the output (after the write) or the recovery copy (after a restore), so an equal-length
 * corruption is restored, or, if the restore itself is corrupt, reported at risk with the recovery
 * bytes kept. A recovery copy shorter than the known source size never authorizes the write.
 *
 * An unconfirmed write is treated exactly like a failed one. That is deliberate and is the SAFE-001
 * defect: the previous implementation accepted an unknown size (`statSize <= 0`) as success, which
 * could leave an unverified write standing over the user's only copy.
 */
object OriginalReplacementCoordinator {

    /**
     * The stage -> write -> verify -> rollback core with no source binding. Kept for the tests
     * that prove that core; a replacement of a user's file goes through [replace], which refuses
     * to write when the staged original is not the one the output was verified against.
     */
    internal fun attempt(io: OriginalReplacementIo, expectedOutputBytes: Long, expectedSourceBytes: Long? = null): ReplacementAttempt =
        run(io, expectedOutputBytes, expectedSourceBytes, requireBoundSource = false, boundSourceDigest = null, ReplacementJournalSink.NONE).attempt

    /**
     * Replaces the original with the verified output, or leaves it exactly as it was.
     *
     * Order, and why:
     *  1. no bound digest: nothing is staged and nothing is written;
     *  2. stage the original's bytes for rollback (the copy must hold all of them);
     *  3. the staged copy must equal the bound digest. It is the bytes the destination held at
     *     this moment, read once, so this proves the file is still the one that was encoded and
     *     certified; a same-length change stops here with the original untouched;
     *  4. tell the journal, durably, before the truncating open;
     *  5. write, then read back size and content; anything short of a confirmed match is restored
     *     from the staged copy, and a restore that cannot be confirmed is reported at risk with
     *     the staged bytes kept.
     *
     * Between step 3 and the open in step 5 another writer could still change the file. No digest
     * or size check can close that through a content provider; what this guarantees is that such
     * a change is never lost silently: the staged copy holds the verified original, and the write
     * is confirmed against the output afterwards.
     */
    fun replace(io: OriginalReplacementIo, request: ReplacementRequest, journal: ReplacementJournalSink = ReplacementJournalSink.NONE): ReplacementTransaction =
        run(io, request.expectedOutputBytes, request.expectedSourceBytes, requireBoundSource = true, request.boundSourceDigest, journal)

    private fun run(
        io: OriginalReplacementIo,
        expectedOutputBytes: Long,
        expectedSourceBytes: Long?,
        requireBoundSource: Boolean,
        boundSourceDigest: String?,
        journal: ReplacementJournalSink
    ): ReplacementTransaction {
        fun done(
            attempt: ReplacementAttempt, staged: Long = -1L, digest: String? = null, matches: Boolean? = null,
            write: ReadBack? = null, restore: ReadBack? = null
        ) = ReplacementTransaction(attempt, staged, digest, matches, write, restore)

        if (requireBoundSource && boundSourceDigest.isNullOrBlank()) {
            return done(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.SOURCE_NOT_BOUND))
        }
        if (!runCatching { journal.staging() }.getOrDefault(false)) {
            runCatching { io.discardRecoveryCopy() }
            return done(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.RECOVERY_STAGING_FAILED))
        }

        val staged = runCatching { io.stageRecoveryCopy() }.getOrDefault(-1L)
        // A partial recovery copy cannot restore the original, so it cannot authorize the
        // truncating write: when the source size is known, the copy must hold all of it.
        if (staged <= 0L || (expectedSourceBytes != null && expectedSourceBytes > 0L && staged != expectedSourceBytes)) {
            // Nothing was truncated — the original is untouched. Clean up any partial recovery file.
            runCatching { io.discardRecoveryCopy() }
            return done(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.RECOVERY_STAGING_FAILED), staged)
        }

        val stagedDigest = runCatching { io.stagedSourceDigest() }.getOrNull()
        val matchesBound = if (requireBoundSource) stagedDigest != null && stagedDigest.equals(boundSourceDigest, ignoreCase = true) else null
        if (matchesBound == false) {
            // The file is not the one that was encoded and verified (or its staged bytes could not
            // be digested, which proves nothing). It has not been opened for writing.
            runCatching { io.discardRecoveryCopy() }
            return done(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.SOURCE_CHANGED), staged, stagedDigest, false)
        }

        // Durable before the destructive open: from here a process death can leave the original
        // truncated, and the next start must be able to find this copy and know what it belongs
        // to. If that record cannot be written, the original is not opened.
        if (!runCatching { journal.staged(stagedDigest, staged) }.getOrDefault(false)) {
            runCatching { io.discardRecoveryCopy() }
            return done(ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.RECOVERY_STAGING_FAILED), staged, stagedDigest, matchesBound)
        }

        val written = readBack({ expectedOutputBytes }, { io.writeOutputOverOriginal() }, { io.readBackOriginalSize() }, { io.writtenOutputProof() })
        if (written == ReadBack.CONFIRMED) {
            runCatching { io.discardRecoveryCopy() }
            return done(ReplacementAttempt.Replaced, staged, stagedDigest, matchesBound, written)
        }

        // Failed or unconfirmed: the source may now be truncated. Put the original bytes back.
        val restored = readBack({ io.recoveryCopyLength() }, { io.restoreOriginalFromRecovery() }, { io.readBackOriginalSize() }, { io.restoredOriginalProof() })
        if (restored != ReadBack.CONFIRMED) {
            // Keep the recovery copy: it is now the only intact copy of the original.
            return done(ReplacementAttempt.OriginalAtRisk, staged, stagedDigest, matchesBound, written, restored)
        }
        // Deliberately do NOT discard here. The original is whole again, but the caller may still run
        // a further destructive step (the Shizuku path writes with `cat >`, which truncates on open).
        // If that step truncates and then fails, these bytes are the only way back — so disposal is
        // the caller's decision once no destructive work remains.
        return done(
            ReplacementAttempt.OriginalIntact(
                if (written == ReadBack.WRITE_THREW) ReplacementAttempt.Reason.WRITE_FAILED_RESTORED
                else ReplacementAttempt.Reason.WRITE_UNVERIFIED_RESTORED
            ),
            staged, stagedDigest, matchesBound, written, restored
        )
    }

    /**
     * One truncating write and its confirmation: size first, then content. Never throws: a
     * failure anywhere in it, the write or the reading back, is [ReadBack.WRITE_THREW].
     */
    private fun readBack(expectedBytes: () -> Long, write: () -> Unit, size: () -> Long, content: () -> ContentProof): ReadBack =
        runCatching {
            write()
            val actual = size()
            when {
                actual <= 0L -> ReadBack.SIZE_UNAVAILABLE
                !ReplacementSizeCheck.verified(actual, expectedBytes()) -> ReadBack.SIZE_MISMATCH
                else -> when (content()) {
                    ContentProof.MATCH -> ReadBack.CONFIRMED
                    ContentProof.MISMATCH -> ReadBack.CONTENT_MISMATCH
                    ContentProof.UNAVAILABLE -> ReadBack.CONTENT_UNAVAILABLE
                }
            }
        }.getOrDefault(ReadBack.WRITE_THREW)
}

/**
 * Machine states of a replacement request beyond those the coordinator decides
 * ([ReplacementTransaction.outcome]): requests that were turned away before any byte was staged,
 * and what the caller did after the coordinator returned. Together they are the reason codes of
 * the `replace` stage.
 */
object ReplacementOutcome {
    /** The output could have replaced its original; the batch's switch was off. */
    const val NOT_REQUESTED = "replace_not_requested"
    const val REFUSED_OUTPUT_MISSING = "replace_refused_output_missing"
    const val REFUSED_NOT_REPLACEMENT_SAFE = "replace_refused_not_replacement_safe"
    const val REFUSED_OVERWRITE_GUARD = "replace_refused_overwrite_guard"
    const val REFUSED_BACKUP_FAILED = "replace_refused_backup_failed"
    const val REFUSED_LOW_SPACE = "replace_refused_low_space"
    const val REFUSED_RECOVERY_UNAVAILABLE = "replace_refused_recovery_unavailable"
    /** The direct write did not stand; the Shizuku path wrote the output and it read back equal. */
    const val COMMITTED_SHIZUKU = "replace_committed_shizuku"
    /** The original may be incomplete; its staged bytes were saved to the gallery. */
    const val AT_RISK_ORIGINAL_PRESERVED = "replace_at_risk_original_preserved"
    /** The original may be incomplete and its staged bytes are held only inside the app. */
    const val AT_RISK_RECOVERY_RETAINED = "replace_at_risk_recovery_retained"

    /**
     * A digest as a record carries it: its first 12 hex digits, the length the recorder already
     * uses for job and name hashes. Enough to compare the roles inside one record (source before,
     * staged, output, read back); not a content identifier.
     */
    fun redact(sha256: String?): String? = sha256?.take(12)
}

/** SHA-256 of a stream, for the replacement's content proof. */
object ReplacementContentProof {
    /** Atomic unique allocation: retries never truncate or discard an older recovery. */
    fun newRecoveryFile(directory: java.io.File, jobId: String): java.io.File =
        java.io.File.createTempFile("${BatchCacheRetention.RECOVERY_FILE_PREFIX}${jobId}_", ".mp4", directory)

    fun sha256(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
