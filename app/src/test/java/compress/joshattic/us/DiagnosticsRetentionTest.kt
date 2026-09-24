package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRetentionTest {

    @Test
    fun keepsTheNewestRunsAndNamesTheOldestToPruneFirst() {
        val names = (1..35).map { "batch_${1_790_000_000_000L + it * 1000}" } + "crashes"
        val prune = DiagnosticsRetention.runsToPrune(names, keep = 30)
        assertEquals(5, prune.size)
        assertEquals("batch_1790000001000", prune.first())
        assertTrue("crashes" !in prune)
    }

    @Test
    fun nothingIsPrunedUnderTheCap() {
        assertTrue(DiagnosticsRetention.runsToPrune(listOf("batch_1790000001000", "batch_1790000002000")).isEmpty())
    }
}
