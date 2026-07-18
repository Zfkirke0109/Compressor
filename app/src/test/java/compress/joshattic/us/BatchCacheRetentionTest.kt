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
        // The explicit user-initiated "clear" preserves nothing.
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
}
