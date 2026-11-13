package compress.joshattic.us

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * What one in-flight replacement needs remembered if the process dies during it.
 *
 * The rollback copy of an original is fsynced into app-private storage before the truncating
 * write, so its bytes survive a kill. What did not survive was any record of what the copy was:
 * nothing read the recovery directory on the next start, so an original truncated mid-write stayed
 * truncated while its intact bytes sat inside the app, unlisted and never offered back. This entry
 * is written beside the copy and says which file it belongs to and which digests settle its state.
 *
 * [targetUri] and [displayName] are the user's file. They exist only in this app-private file,
 * which no diagnostics export reads and which backup rules exclude; nothing in a capture carries them.
 */
data class ReplacementJournalEntry(
    val jobId: String,
    /** [STAGING] while the copy is being made; [STAGED] once it is durable and the write may begin. */
    val state: String,
    val targetUri: String,
    val displayName: String,
    val sourceBytes: Long,
    /** SHA-256 of the source when the item began: what the output was made from and verified against. */
    val boundSourceSha256: String?,
    /** SHA-256 of the rollback copy as staged; null until [STAGED]. */
    val stagedSha256: String?,
    val outputBytes: Long,
    val outputSha256: String?,
    val createdAtMs: Long
) {
    fun encode(): String {
        val p = Properties()
        p["version"] = VERSION.toString()
        p["jobId"] = jobId
        p["state"] = state
        p["targetUri"] = targetUri
        p["displayName"] = displayName
        p["sourceBytes"] = sourceBytes.toString()
        boundSourceSha256?.let { p["boundSourceSha256"] = it }
        stagedSha256?.let { p["stagedSha256"] = it }
        p["outputBytes"] = outputBytes.toString()
        outputSha256?.let { p["outputSha256"] = it }
        p["createdAtMs"] = createdAtMs.toString()
        return StringWriter().also { p.store(it, null) }.toString()
    }

    companion object {
        const val VERSION = 1
        const val STAGING = "staging"
        const val STAGED = "staged"

        /** Null for text that is not an entry this build understands: never guessed at. */
        fun decode(text: String): ReplacementJournalEntry? = runCatching {
            val p = Properties().apply { load(StringReader(text)) }
            if (p.getProperty("version")?.toIntOrNull() != VERSION) return null
            val state = p.getProperty("state")?.takeIf { it == STAGING || it == STAGED } ?: return null
            ReplacementJournalEntry(
                jobId = p.getProperty("jobId") ?: return null,
                state = state,
                targetUri = p.getProperty("targetUri") ?: return null,
                displayName = p.getProperty("displayName") ?: "",
                sourceBytes = p.getProperty("sourceBytes")?.toLongOrNull() ?: return null,
                boundSourceSha256 = p.getProperty("boundSourceSha256"),
                stagedSha256 = p.getProperty("stagedSha256"),
                outputBytes = p.getProperty("outputBytes")?.toLongOrNull() ?: return null,
                outputSha256 = p.getProperty("outputSha256"),
                createdAtMs = p.getProperty("createdAtMs")?.toLongOrNull() ?: 0L
            )
        }.getOrNull()
    }
}

/** A rollback copy found on disk, with its journal entry when one could be read. */
data class ReplacementLeftover(val recoveryCopy: File, val journal: ReplacementJournalEntry?)

/** The journal's files: one entry beside each rollback copy, written atomically and durably. */
object ReplacementJournal {
    const val SUFFIX = ".journal"

    // Rollback copies a replacement in THIS process is using right now. A scan for leftovers must
    // not take one of them for the remains of a dead process and dispose of it mid-write.
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun markActive(recoveryCopy: File) { active += recoveryCopy.absolutePath }

    fun release(recoveryCopy: File) { active -= recoveryCopy.absolutePath }

    fun fileFor(recoveryCopy: File): File = File(recoveryCopy.parentFile, recoveryCopy.name + SUFFIX)

    /**
     * Writes [entry] for [recoveryCopy]: to a temporary file, synced, then renamed over the old
     * one, so a reader sees the previous entry or this one and never half of either. False when
     * it could not be made durable; the caller must then not begin a destructive write.
     */
    fun write(recoveryCopy: File, entry: ReplacementJournalEntry): Boolean = runCatching {
        val target = fileFor(recoveryCopy)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { out ->
            out.write(entry.encode().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        true
    }.getOrDefault(false)

    fun read(recoveryCopy: File): ReplacementJournalEntry? = runCatching {
        fileFor(recoveryCopy).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.let(ReplacementJournalEntry::decode)
    }.getOrNull()

    /** Deletes a rollback copy that is no longer needed, and its entry with it. */
    fun discard(recoveryCopy: File) {
        runCatching { recoveryCopy.delete() }
        runCatching { fileFor(recoveryCopy).delete() }
        runCatching { File(recoveryCopy.parentFile, fileFor(recoveryCopy).name + ".tmp").delete() }
    }

    /**
     * Every rollback copy in [directory] that no replacement in this process is using, oldest
     * first, with entries whose copy is gone removed. A copy with no readable entry is still
     * returned: it may be all that is left of an original.
     */
    fun leftovers(directory: File): List<ReplacementLeftover> {
        val files = directory.listFiles()?.toList().orEmpty()
        val copies = files.filter { it.isFile && it.name.startsWith(BatchCacheRetention.RECOVERY_FILE_PREFIX) && !isJournalFile(it) }
        files.filter { isJournalFile(it) }.forEach { entry ->
            val copyName = entry.name.removeSuffix(".tmp").removeSuffix(SUFFIX)
            if (copies.none { it.name == copyName }) runCatching { entry.delete() }
        }
        return copies.filter { it.absolutePath !in active }.sortedBy { it.lastModified() }.map { ReplacementLeftover(it, read(it)) }
    }

    private fun isJournalFile(file: File) = file.name.endsWith(SUFFIX) || file.name.endsWith("$SUFFIX.tmp")
}

/**
 * What a rollback copy found at startup means, decided from digests alone.
 *
 * The copy is deleted only when the user's file is PROVEN to be whole: it still holds the original
 * (the write never began, or was rolled back) or it holds the verified output (the write finished
 * and only the bookkeeping was lost). In every other case the copy may be the only intact original,
 * so it is preserved and the user is told. Nothing here writes to the user's file.
 */
object ReplacementRecovery {
    enum class Verdict(val preserveCopy: Boolean) {
        /** The process died while the copy was being made; the truncating write had not begun. */
        STAGING_INCOMPLETE(false),
        /** The file on disk equals the staged original. */
        ORIGINAL_INTACT(false),
        /** The file on disk equals the verified output: the replacement did complete. */
        REPLACEMENT_COMPLETED(false),
        /** The file on disk is neither, or cannot be read: the copy may be the last intact original. */
        ORIGINAL_NEEDS_RECOVERY(true),
        /** No entry says what this copy belongs to (an older build left it, or the entry was lost). */
        UNKNOWN_LEFTOVER(true)
    }

    /**
     * @param targetSha256 SHA-256 of the user's file as it is now; null when it cannot be read.
     */
    fun classify(journal: ReplacementJournalEntry?, targetSha256: String?): Verdict = when {
        journal == null -> Verdict.UNKNOWN_LEFTOVER
        // STAGED is made durable before the truncating open, and a replacement that cannot write
        // it does not proceed, so an entry still at STAGING means the original was never opened.
        journal.state == ReplacementJournalEntry.STAGING -> Verdict.STAGING_INCOMPLETE
        targetSha256 == null -> Verdict.ORIGINAL_NEEDS_RECOVERY
        journal.stagedSha256 != null && targetSha256.equals(journal.stagedSha256, ignoreCase = true) -> Verdict.ORIGINAL_INTACT
        journal.outputSha256 != null && targetSha256.equals(journal.outputSha256, ignoreCase = true) -> Verdict.REPLACEMENT_COMPLETED
        else -> Verdict.ORIGINAL_NEEDS_RECOVERY
    }

    /** What one start did with the rollback copies it found. */
    data class StartupResult(val preserved: Int, val held: Int)

    /**
     * Settles every rollback copy in [directory] that no replacement in this process is using.
     *
     * A copy is deleted when the user's file is proven whole ([classify]), or once a gallery copy
     * of it is proven: published, and read back as the bytes the copy was staged with (as the
     * copy's own bytes when no entry says what those were). A save that is not proven leaves the
     * copy and its entry where they are, so the next start tries again. A copy is never deleted on
     * the strength of a save that only returned a URI: that proves a row exists, not that the
     * original is in it.
     *
     * @param targetSha256 the user's file as it is now, for an entry that reached STAGED; null when unreadable.
     * @param preserve saves a copy to the gallery, held to the digest given when there is one.
     */
    internal fun recoverLeftovers(
        directory: File,
        targetSha256: (ReplacementJournalEntry) -> String?,
        preserve: (ReplacementLeftover, expectedSha256: String?) -> GalleryPublication.Result,
        log: (String) -> Unit = {}
    ): StartupResult {
        var preserved = 0
        var held = 0
        for (leftover in ReplacementJournal.leftovers(directory)) {
            val journal = leftover.journal
            val target = journal?.takeIf { it.state == ReplacementJournalEntry.STAGED }?.let { runCatching { targetSha256(it) }.getOrNull() }
            val verdict = classify(journal, target)
            log("replacement recovery; job=${journal?.jobId ?: "unknown"}; verdict=${verdict.name}; copyBytes=${leftover.recoveryCopy.length()}")
            if (!verdict.preserveCopy) {
                ReplacementJournal.discard(leftover.recoveryCopy)
                continue
            }
            val saved = runCatching { preserve(leftover, journal?.stagedSha256) }.getOrElse {
                GalleryPublication.Result.Failed(GalleryPublication.Stage.WRITE, it.javaClass.simpleName, cleanupFailed = false)
            }
            when (saved) {
                is GalleryPublication.Result.Published -> {
                    ReplacementJournal.discard(leftover.recoveryCopy)
                    preserved++
                }
                is GalleryPublication.Result.Failed -> {
                    log(
                        "replacement recovery; job=${journal?.jobId ?: "unknown"}; gallery copy not confirmed; stage=${saved.stage}; " +
                            "${saved.detail}; cleanupFailed=${saved.cleanupFailed}; the rollback copy is kept"
                    )
                    held++
                }
            }
        }
        return StartupResult(preserved, held)
    }

    /**
     * What the user is told after a start that found interrupted replacements. Null when there is
     * nothing they need to do. [preserved] originals were saved to the gallery and read back whole;
     * [held] could not be confirmed there and are still inside the app.
     */
    fun notice(preserved: Int, held: Int): String? {
        if (preserved <= 0 && held <= 0) return null
        fun files(n: Int) = if (n == 1) "1 original" else "$n originals"
        val saved = if (preserved > 0) {
            "An intact copy of ${files(preserved)} was saved to your gallery (Movies/Compressor, named ORIGINAL_recovered_…) and read back whole."
        } else ""
        val kept = if (held > 0) {
            " ${files(held).replaceFirstChar { it.uppercase() }} could not be confirmed as saved to your gallery and ${if (held == 1) "is" else "are"} still held " +
                "inside the app: free up space and reopen the app, and do not clear the app's storage."
        } else ""
        return "A replacement was interrupted the last time the app ran, and the file it was replacing may be incomplete. " +
            (saved + kept).trim() + " Please check the file in its original folder."
    }

    /** The name a preserved original is saved under: recognisable, and never the output's name. */
    fun recoveredName(journal: ReplacementJournalEntry?, fallbackStamp: Long): String {
        val base = journal?.displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "video_$fallbackStamp"
        return "ORIGINAL_recovered_$base.mp4"
    }
}
