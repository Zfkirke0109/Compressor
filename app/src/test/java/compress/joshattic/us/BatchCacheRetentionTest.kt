package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Starting a run used to wipe the whole batch cache directory, which could delete outputs that the
 * still-displayed previous results referenced — leaving share/save pointing at files that no longer
 * exist (silent loss from the user's point of view). The retention rule keeps anything currently
 * referenced and reclaims only genuine orphans.
 */
class BatchCacheRetentionTest {

    private val a = "/data/user/0/pkg/cache/batch_compressed_videos/a.mp4"
    private val b = "/data/user/0/pkg/cache/batch_compressed_videos/b.mp4"
    private val orphan = "/data/user/0/pkg/cache/batch_compressed_videos/stale.mp4"

    @Test
    fun referencedFilesAreNeverDeleted() {
        assertFalse(BatchCacheRetention.isDeletable(a, setOf(a, b)))
        assertFalse(BatchCacheRetention.isDeletable(b, setOf(a, b)))
    }

    @Test
    fun unreferencedFilesAreReclaimed() {
        assertTrue(BatchCacheRetention.isDeletable(orphan, setOf(a, b)))
    }

    @Test
    fun emptyPreserveSetWipesEverything() {
        // The explicit user-initiated "clear" preserves nothing — except a retained rollback copy,
        // covered separately below.
        listOf(a, b, orphan).forEach { assertTrue(BatchCacheRetention.isDeletable(it, emptySet())) }
    }

    @Test
    fun deletablePathsPartitionsCorrectly() {
        assertEquals(
            listOf(orphan),
            BatchCacheRetention.deletablePaths(listOf(a, b, orphan), preservePaths = setOf(a, b))
        )
        assertEquals(
            emptyList<String>(),
            BatchCacheRetention.deletablePaths(listOf(a, b), preservePaths = setOf(a, b))
        )
    }

    @Test
    fun matchingIsExactSoASimilarNameIsStillAnOrphan() {
        // Guard against a prefix/substring style rule creeping in: only an exact path is preserved.
        val lookalike = a.dropLast(4) + "_old.mp4"
        assertTrue(BatchCacheRetention.isDeletable(lookalike, setOf(a)))
    }

    // --- retained rollback copies -------------------------------------------------------------
    // A replace_recovery_* file is retained only when the original on disk may be incomplete and
    // the copy could not be preserved anywhere else — at that moment it is the user's last intact
    // original. If any cleanup path could reclaim it, the "an intact copy is still held inside the
    // app — do not clear the app's storage" message would be a lie.

    private val recovery =
        "/data/user/0/pkg/cache/batch_compressed_videos/${BatchCacheRetention.RECOVERY_FILE_PREFIX}job42.mp4"

    @Test
    fun recoveryCopySurvivesTheExplicitUserInitiatedClear() {
        // The empty preserve set is the "clear everything" case — and even it must not take the
        // last intact copy of a user's original.
        assertFalse(BatchCacheRetention.isDeletable(recovery, emptySet()))
    }

    @Test
    fun recoveryCopySurvivesOrphanReclamationToo() {
        assertFalse(BatchCacheRetention.isDeletable(recovery, setOf(a, b)))
        assertEquals(
            listOf(orphan),
            BatchCacheRetention.deletablePaths(listOf(a, recovery, orphan), preservePaths = setOf(a))
        )
    }

    @Test
    fun recoveryPrefixIsMatchedOnTheFileNameNotTheWholePath() {
        // Only the basename decides: a directory that happens to contain the prefix string does not
        // shield ordinary files, and a Windows-style separator still isolates the name correctly.
        val prefixInDirOnly =
            "/data/user/0/pkg/cache/${BatchCacheRetention.RECOVERY_FILE_PREFIX}dir/ordinary.mp4"
        assertTrue(BatchCacheRetention.isDeletable(prefixInDirOnly, emptySet()))
        val windowsStyle =
            "C:\\cache\\batch_compressed_videos\\${BatchCacheRetention.RECOVERY_FILE_PREFIX}job7.mp4"
        assertFalse(BatchCacheRetention.isDeletable(windowsStyle, emptySet()))
    }
}
