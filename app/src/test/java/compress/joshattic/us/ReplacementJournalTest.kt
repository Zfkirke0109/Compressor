package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A rollback copy that outlives its process must say what it belongs to, and may be deleted only
 * when the user's file is proven whole.
 */
class ReplacementJournalTest {

    private val staged = "a".repeat(64)
    private val output = "b".repeat(64)

    private fun entry(state: String = ReplacementJournalEntry.STAGED, name: String = "VID_20261110_101500.mp4") = ReplacementJournalEntry(
        jobId = "job_0123456789ab", state = state, targetUri = "content://media/external/video/media/1234",
        displayName = name, sourceBytes = 104_378_798L, boundSourceSha256 = staged,
        stagedSha256 = staged.takeIf { state == ReplacementJournalEntry.STAGED },
        outputBytes = 79_552_584L, outputSha256 = output, createdAtMs = 1_794_291_512_031L
    )

    private fun inTempDir(block: (File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("replace-journal").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun anEntryRoundTripsIncludingNamesThatAreNotLatin1() {
        for (e in listOf(entry(), entry(ReplacementJournalEntry.STAGING), entry(name = "夏祭り = 2026 #1: \\clip\\.mp4"))) {
            assertEquals(e, ReplacementJournalEntry.decode(e.encode()))
        }
    }

    @Test
    fun textThatIsNotAnEntryOfThisVersionIsNeverGuessedAt() {
        assertNull(ReplacementJournalEntry.decode(""))
        assertNull(ReplacementJournalEntry.decode("not a journal"))
        assertNull(ReplacementJournalEntry.decode(entry().encode().replace("version=1", "version=2")))
        assertNull(ReplacementJournalEntry.decode(entry().encode().replace("state=staged", "state=committed")))
        assertNull(ReplacementJournalEntry.decode(entry().encode().lines().filterNot { it.startsWith("targetUri=") }.joinToString("\n")))
    }

    @Test
    fun theEntryIsReplacedWholeAndDiscardedWithItsCopy() = inTempDir { dir ->
        val copy = ReplacementContentProof.newRecoveryFile(dir, "job_0123456789ab").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(ReplacementJournal.read(copy))
        assertTrue(ReplacementJournal.write(copy, entry(ReplacementJournalEntry.STAGING)))
        assertEquals(ReplacementJournalEntry.STAGING, ReplacementJournal.read(copy)!!.state)
        assertTrue(ReplacementJournal.write(copy, entry()))
        assertEquals(entry(), ReplacementJournal.read(copy))
        assertEquals(listOf(copy.name, copy.name + ReplacementJournal.SUFFIX), dir.list()!!.sorted())
        ReplacementJournal.discard(copy)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aJournalThatCannotBeWrittenSaysSo() {
        val nowhere = File(File("/nonexistent-directory-for-replace-journal-test"), "replace_recovery_job_x.mp4")
        assertFalse(ReplacementJournal.write(nowhere, entry()))
    }

    @Test
    fun leftoversAreEveryCopyWithWhatIsKnownAndStaleEntriesAreRemoved() = inTempDir { dir ->
        val known = ReplacementContentProof.newRecoveryFile(dir, "job_known").apply { writeBytes(byteArrayOf(1)) }
        ReplacementJournal.write(known, entry())
        // Left by a build that wrote no journal: still found, never dropped for lack of an entry.
        val legacy = ReplacementContentProof.newRecoveryFile(dir, "job_legacy").apply { writeBytes(byteArrayOf(2)) }
        // An entry whose copy is gone describes nothing.
        File(dir, "replace_recovery_job_gone_123.mp4" + ReplacementJournal.SUFFIX).writeText(entry().encode())
        File(dir, "unrelated.txt").writeText("x")

        val found = ReplacementJournal.leftovers(dir)
        assertEquals(setOf(known, legacy), found.map { it.recoveryCopy }.toSet())
        assertEquals(entry(), found.first { it.recoveryCopy == known }.journal)
        assertNull(found.first { it.recoveryCopy == legacy }.journal)
        assertFalse(File(dir, "replace_recovery_job_gone_123.mp4" + ReplacementJournal.SUFFIX).exists())
        assertTrue(File(dir, "unrelated.txt").exists())
        assertEquals(emptyList<ReplacementLeftover>(), ReplacementJournal.leftovers(File(dir, "missing")))
    }

    @Test
    fun aCopyIsDeletedOnlyWhenTheUsersFileIsProvenWhole() {
        // The write never began or was rolled back: the file still equals the staged original.
        assertEquals(ReplacementRecovery.Verdict.ORIGINAL_INTACT, ReplacementRecovery.classify(entry(), staged))
        assertEquals(ReplacementRecovery.Verdict.ORIGINAL_INTACT, ReplacementRecovery.classify(entry(), staged.uppercase()))
        // The write finished and only the bookkeeping died: the file equals the verified output.
        assertEquals(ReplacementRecovery.Verdict.REPLACEMENT_COMPLETED, ReplacementRecovery.classify(entry(), output))
        // Truncated, half-written, or changed by something else: the copy may be the last original.
        assertEquals(ReplacementRecovery.Verdict.ORIGINAL_NEEDS_RECOVERY, ReplacementRecovery.classify(entry(), "c".repeat(64)))
        // The file cannot be read at all (deleted, permission revoked): nothing is proven.
        assertEquals(ReplacementRecovery.Verdict.ORIGINAL_NEEDS_RECOVERY, ReplacementRecovery.classify(entry(), null))
        // Died while copying: the truncating write had not begun, whatever the file now holds.
        val staging = entry(ReplacementJournalEntry.STAGING)
        for (target in listOf(staged, output, null)) {
            assertEquals(ReplacementRecovery.Verdict.STAGING_INCOMPLETE, ReplacementRecovery.classify(staging, target))
        }
        // No entry: an older build's leftover, or a lost entry. Kept.
        assertEquals(ReplacementRecovery.Verdict.UNKNOWN_LEFTOVER, ReplacementRecovery.classify(null, staged))
        assertEquals(
            setOf(ReplacementRecovery.Verdict.ORIGINAL_NEEDS_RECOVERY, ReplacementRecovery.Verdict.UNKNOWN_LEFTOVER),
            ReplacementRecovery.Verdict.entries.filter { it.preserveCopy }.toSet()
        )
    }

    @Test
    fun aStagedEntryWithNoDigestsProvesNothing() {
        val bare = entry().copy(stagedSha256 = null, outputSha256 = null)
        assertEquals(ReplacementRecovery.Verdict.ORIGINAL_NEEDS_RECOVERY, ReplacementRecovery.classify(bare, staged))
    }

    @Test
    fun aCopyAReplacementIsStillUsingIsNotALeftover() = inTempDir { dir ->
        // The scan runs when a screen opens. A replacement finishing under an earlier screen may
        // still hold its rollback copy mid-write; taking that for a dead process's remains and
        // disposing of it would remove the only way back.
        val inFlight = ReplacementContentProof.newRecoveryFile(dir, "job_inflight").apply { writeBytes(byteArrayOf(1)) }
        ReplacementJournal.write(inFlight, entry())
        val dead = ReplacementContentProof.newRecoveryFile(dir, "job_dead").apply { writeBytes(byteArrayOf(2)) }
        ReplacementJournal.markActive(inFlight)
        try {
            assertEquals(listOf(dead), ReplacementJournal.leftovers(dir).map { it.recoveryCopy })
            assertTrue("its journal is not swept as stale either", ReplacementJournal.fileFor(inFlight).exists())
        } finally {
            ReplacementJournal.release(inFlight)
        }
        assertEquals(setOf(inFlight, dead), ReplacementJournal.leftovers(dir).map { it.recoveryCopy }.toSet())
    }

    @Test
    fun theUserIsToldWhatWasSavedAndWhatIsStillHeld() {
        assertNull(ReplacementRecovery.notice(0, 0))
        val one = ReplacementRecovery.notice(1, 0)!!
        assertTrue(one, one.startsWith("A replacement was interrupted the last time the app ran, and the file it was replacing may be incomplete."))
        assertTrue(one, one.contains("An intact copy of 1 original was saved to your gallery"))
        assertTrue(one, one.contains("and read back whole"))
        assertFalse(one, one.contains("still held"))
        val held = ReplacementRecovery.notice(0, 2)!!
        assertTrue(held, held.contains("2 originals could not be confirmed as saved to your gallery and are still held inside the app"))
        assertTrue(held, held.contains("do not clear the app's storage"))
        assertFalse(held, held.contains("was saved"))
        val both = ReplacementRecovery.notice(3, 1)!!
        assertTrue(both, both.contains("An intact copy of 3 originals was saved"))
        assertTrue(both, both.contains("1 original could not be confirmed as saved to your gallery and is still held"))
        for (text in listOf(one, held, both)) assertTrue(text, text.endsWith("Please check the file in its original folder."))
    }

    /** A provider whose publishing step answers with [rows], and which keeps what was published. */
    private class Gallery(var rows: Int = 1) : GalleryPublication.Gallery {
        override val hasPendingRows = true
        val published = mutableListOf<ByteArray>()
        private val pending = java.io.ByteArrayOutputStream()
        var deletes = 0
        override fun insert() = "content://media/external/video/media/77".also { pending.reset() }
        override fun openForWriting(uri: String): java.io.OutputStream = pending
        override fun publish(uri: String) = rows.also { if (it == 1) published += pending.toByteArray() }
        override fun openForReading(uri: String): java.io.InputStream = java.io.ByteArrayInputStream(pending.toByteArray())
        override fun delete(uri: String) { deletes++ }
    }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun startUp(dir: File, gallery: Gallery, target: String?, logs: MutableList<String> = mutableListOf()) =
        ReplacementRecovery.recoverLeftovers(
            dir,
            targetSha256 = { target },
            preserve = { leftover, expected -> GalleryPublication.save({ leftover.recoveryCopy.inputStream() }, gallery, expected) },
            log = { logs += it }
        )

    @Test
    fun aCopyIsKeptUntilItsGalleryCopyIsProvenThenDeletedOnce() = inTempDir { dir ->
        val original = ByteArray(70_000) { (it % 251).toByte() }
        val copy = ReplacementContentProof.newRecoveryFile(dir, "job_0123456789ab").apply { writeBytes(original) }
        ReplacementJournal.write(copy, entry().copy(stagedSha256 = sha(original), boundSourceSha256 = sha(original)))
        val gallery = Gallery(rows = 0)
        val logs = mutableListOf<String>()

        // First start: the file needs recovery, and the provider leaves the gallery copy pending.
        assertEquals(ReplacementRecovery.StartupResult(preserved = 0, held = 1), startUp(dir, gallery, "c".repeat(64), logs))
        assertTrue("the copy is kept", copy.exists())
        assertTrue("and so is what it belongs to", ReplacementJournal.fileFor(copy).exists())
        assertEquals("the unpublished row was removed", 1, gallery.deletes)
        assertTrue(logs.joinToString("\n"), logs.any { it.contains("stage=PUBLISH") && it.contains("the rollback copy is kept") })
        assertFalse("no path is logged", logs.any { it.contains(dir.absolutePath) })

        // Second start: the save is published and reads back as the staged bytes.
        gallery.rows = 1
        assertEquals(ReplacementRecovery.StartupResult(preserved = 1, held = 0), startUp(dir, gallery, "c".repeat(64)))
        assertTrue(gallery.published.single().contentEquals(original))
        assertFalse(copy.exists())
        assertFalse(ReplacementJournal.fileFor(copy).exists())

        // Third start: nothing left to do, and nothing is saved twice.
        assertEquals(ReplacementRecovery.StartupResult(preserved = 0, held = 0), startUp(dir, gallery, "c".repeat(64)))
        assertEquals(1, gallery.published.size)
    }

    @Test
    fun aCopyThatNoLongerReadsAsTheStagedOriginalIsKept() = inTempDir { dir ->
        // Same length, one byte changed since it was staged: a gallery copy of it would not be the
        // original, so it is neither published nor counted, and the copy is not deleted.
        val original = ByteArray(4096) { it.toByte() }
        val copy = ReplacementContentProof.newRecoveryFile(dir, "job_0123456789ab")
            .apply { writeBytes(original.copyOf().also { it[100] = 0 }) }
        ReplacementJournal.write(copy, entry().copy(stagedSha256 = sha(original)))
        val gallery = Gallery()
        assertEquals(ReplacementRecovery.StartupResult(preserved = 0, held = 1), startUp(dir, gallery, null))
        assertTrue(gallery.published.isEmpty())
        assertTrue(copy.exists())
    }

    @Test
    fun aCopyWithNoEntryIsSavedAsItIsBeforeItIsDeleted() = inTempDir { dir ->
        // An older build's leftover: nothing says what it was staged as, so the read-back holds the
        // gallery copy to the bytes the copy holds.
        val legacy = ReplacementContentProof.newRecoveryFile(dir, "job_legacy").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val expectations = mutableListOf<String?>()
        val gallery = Gallery()
        val result = ReplacementRecovery.recoverLeftovers(
            dir, targetSha256 = { null },
            preserve = { leftover, expected ->
                expectations += expected
                GalleryPublication.save({ leftover.recoveryCopy.inputStream() }, gallery, expected)
            }
        )
        assertEquals(ReplacementRecovery.StartupResult(preserved = 1, held = 0), result)
        assertEquals(listOf<String?>(null), expectations)
        assertTrue(gallery.published.single().contentEquals(byteArrayOf(9, 8, 7)))
        assertFalse(legacy.exists())
    }

    @Test
    fun aCopyIsDeletedWithoutASaveOnlyWhenTheUsersFileIsProvenWhole() = inTempDir { dir ->
        for ((state, target) in listOf(
            ReplacementJournalEntry.STAGED to staged,       // still the original
            ReplacementJournalEntry.STAGED to output,       // the replacement completed
            ReplacementJournalEntry.STAGING to null         // the truncating write never began
        )) {
            val copy = ReplacementContentProof.newRecoveryFile(dir, "job_whole").apply { writeBytes(byteArrayOf(1)) }
            ReplacementJournal.write(copy, entry(state))
            val saves = mutableListOf<ReplacementLeftover>()
            val result = ReplacementRecovery.recoverLeftovers(dir, targetSha256 = { target }, preserve = { leftover, _ ->
                saves += leftover; GalleryPublication.Result.Failed(GalleryPublication.Stage.INSERT, "unused", false)
            })
            assertEquals("$state/$target", ReplacementRecovery.StartupResult(0, 0), result)
            assertTrue("$state/$target needs no gallery copy", saves.isEmpty())
            assertFalse(copy.exists())
        }
    }

    @Test
    fun aSaveThatThrowsOrATargetThatCannotBeReadKeepsTheCopy() = inTempDir { dir ->
        val copy = ReplacementContentProof.newRecoveryFile(dir, "job_0123456789ab").apply { writeBytes(byteArrayOf(5)) }
        ReplacementJournal.write(copy, entry())
        val result = ReplacementRecovery.recoverLeftovers(
            dir,
            targetSha256 = { throw SecurityException("permission revoked") },
            preserve = { _, _ -> throw IllegalStateException("provider died") }
        )
        assertEquals(ReplacementRecovery.StartupResult(preserved = 0, held = 1), result)
        assertTrue(copy.exists())
        assertTrue(ReplacementJournal.fileFor(copy).exists())
    }

    @Test
    fun aRecoveredOriginalIsNamedForWhatItIs() {
        assertEquals("ORIGINAL_recovered_VID_20261110_101500.mp4", ReplacementRecovery.recoveredName(entry(), 7L))
        assertEquals("ORIGINAL_recovered_video_7.mp4", ReplacementRecovery.recoveredName(null, 7L))
        assertEquals("ORIGINAL_recovered_video_7.mp4", ReplacementRecovery.recoveredName(entry(name = ""), 7L))
    }
}
