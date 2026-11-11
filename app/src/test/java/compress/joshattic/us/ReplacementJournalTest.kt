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
        assertFalse(one, one.contains("still held"))
        val held = ReplacementRecovery.notice(0, 2)!!
        assertTrue(held, held.contains("2 originals could not be saved to your gallery and are still held inside the app"))
        assertTrue(held, held.contains("do not clear the app's storage"))
        assertFalse(held, held.contains("was saved"))
        val both = ReplacementRecovery.notice(3, 1)!!
        assertTrue(both, both.contains("An intact copy of 3 originals was saved"))
        assertTrue(both, both.contains("1 original could not be saved to your gallery and is still held"))
        for (text in listOf(one, held, both)) assertTrue(text, text.endsWith("Please check the file in its original folder."))
    }

    @Test
    fun aRecoveredOriginalIsNamedForWhatItIs() {
        assertEquals("ORIGINAL_recovered_VID_20261110_101500.mp4", ReplacementRecovery.recoveredName(entry(), 7L))
        assertEquals("ORIGINAL_recovered_video_7.mp4", ReplacementRecovery.recoveredName(null, 7L))
        assertEquals("ORIGINAL_recovered_video_7.mp4", ReplacementRecovery.recoveredName(entry(name = ""), 7L))
    }
}
