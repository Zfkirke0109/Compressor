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
 * An unconfirmed write is treated exactly like a failed one. That is deliberate and is the SAFE-001
 * defect: the previous implementation accepted an unknown size (`statSize <= 0`) as success, which
 * could leave an unverified write standing over the user's only copy.
 */
object OriginalReplacementCoordinator {

    fun attempt(io: OriginalReplacementIo, expectedOutputBytes: Long): ReplacementAttempt {
        val staged = runCatching { io.stageRecoveryCopy() }.getOrDefault(-1L)
        if (staged <= 0L) {
            // Nothing was truncated — the original is untouched. Clean up any partial recovery file.
            runCatching { io.discardRecoveryCopy() }
            return ReplacementAttempt.OriginalIntact(ReplacementAttempt.Reason.RECOVERY_STAGING_FAILED)
        }

        var writeThrew = false
        val confirmed = runCatching {
            io.writeOutputOverOriginal()
            ReplacementSizeCheck.verified(io.readBackOriginalSize(), expectedOutputBytes)
        }.onFailure { writeThrew = true }.getOrDefault(false)

        if (confirmed) {
            runCatching { io.discardRecoveryCopy() }
            return ReplacementAttempt.Replaced
        }

        // Failed or unconfirmed: the source may now be truncated. Put the original bytes back.
        val restored = runCatching {
            io.restoreOriginalFromRecovery()
            ReplacementSizeCheck.verified(io.readBackOriginalSize(), io.recoveryCopyLength())
        }.getOrDefault(false)

        if (!restored) {
            // Keep the recovery copy: it is now the only intact copy of the original.
            return ReplacementAttempt.OriginalAtRisk
        }
        // Deliberately do NOT discard here. The original is whole again, but the caller may still run
        // a further destructive step (the Shizuku path writes with `cat >`, which truncates on open).
        // If that step truncates and then fails, these bytes are the only way back — so disposal is
        // the caller's decision once no destructive work remains.
        return ReplacementAttempt.OriginalIntact(
            if (writeThrew) ReplacementAttempt.Reason.WRITE_FAILED_RESTORED
            else ReplacementAttempt.Reason.WRITE_UNVERIFIED_RESTORED
        )
    }
}
