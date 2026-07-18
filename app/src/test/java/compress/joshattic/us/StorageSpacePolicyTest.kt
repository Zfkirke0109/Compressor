package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destructive replace path stages a FULL recovery copy of the original before truncating it, so
 * running out of space mid-stage is precisely how the user's only copy would be endangered. This
 * policy is the cheap early exit.
 *
 * Its most important property is the asymmetry: it blocks ONLY on positive evidence of insufficient
 * space. An unmeasurable filesystem must not brick the feature on every device whose provider will
 * not report free bytes — the staged recovery + rollback (SAFE-001) remain the real backstop.
 */
class StorageSpacePolicyTest {

    private val mb = 1024L * 1024L
    private val headroom = StorageSpacePolicy.DEFAULT_HEADROOM_BYTES

    @Test
    fun plentyOfSpaceIsSufficient() {
        assertEquals(
            SpaceVerdict.SUFFICIENT,
            StorageSpacePolicy.verdict(requiredBytes = 100 * mb, availableBytes = 10_000 * mb)
        )
        assertFalse(StorageSpacePolicy.blocks(100 * mb, 10_000 * mb))
    }

    @Test
    fun knownInsufficientSpaceBlocks() {
        assertEquals(
            SpaceVerdict.INSUFFICIENT,
            StorageSpacePolicy.verdict(requiredBytes = 1_000 * mb, availableBytes = 200 * mb)
        )
        assertTrue(StorageSpacePolicy.blocks(1_000 * mb, 200 * mb))
    }

    @Test
    fun unmeasurableFilesystemIsUnknownAndNeverBlocks() {
        // The key asymmetry: we do not brick the feature just because free space is unreadable.
        assertEquals(SpaceVerdict.UNKNOWN, StorageSpacePolicy.verdict(1_000 * mb, availableBytes = -1L))
        assertFalse(StorageSpacePolicy.blocks(1_000 * mb, -1L))
    }

    @Test
    fun headroomIsRequiredOnTopOfTheFileItself() {
        // Exactly enough for the file but nothing spare must NOT pass: driving the volume to zero
        // free degrades the whole device.
        assertTrue(StorageSpacePolicy.blocks(requiredBytes = 500 * mb, availableBytes = 500 * mb))
        // File + full headroom exactly is acceptable.
        assertFalse(StorageSpacePolicy.blocks(requiredBytes = 500 * mb, availableBytes = 500 * mb + headroom))
        // One byte under the headroom boundary is not.
        assertTrue(StorageSpacePolicy.blocks(requiredBytes = 500 * mb, availableBytes = 500 * mb + headroom - 1))
    }

    @Test
    fun nothingToWriteIsAlwaysSufficient() {
        assertEquals(SpaceVerdict.SUFFICIENT, StorageSpacePolicy.verdict(0L, 10 * mb))
        assertFalse(StorageSpacePolicy.blocks(0L, 10 * mb))
    }

    @Test
    fun shortfallMessageIsHonestAndOnlyPresentWhenBlocking() {
        assertEquals("", StorageSpacePolicy.shortfallMessage(100 * mb, 10_000 * mb))
        val msg = StorageSpacePolicy.shortfallMessage(1_000 * mb, 200 * mb)
        assertTrue(msg.contains("needs about"))
        assertTrue(msg.contains("only 200 MB"))
    }
}
