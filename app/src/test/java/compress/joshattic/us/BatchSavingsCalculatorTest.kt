package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchSavingsCalculatorTest {
    @Test
    fun failedItemsDoNotCountAsSavedBytes() {
        val saved = BatchSavingsCalculator.savedBytes(
            listOf(
                BatchSavingsInput(
                    originalSize = 3_800_000_000L,
                    outputSize = 0L,
                    hasOutput = false
                )
            )
        )

        assertEquals(0L, saved)
    }

    @Test
    fun completedOutputsCountActualSavingsOnly() {
        val saved = BatchSavingsCalculator.savedBytes(
            listOf(
                BatchSavingsInput(
                    originalSize = 1_000L,
                    outputSize = 700L,
                    hasOutput = true
                ),
                BatchSavingsInput(
                    originalSize = 1_000L,
                    outputSize = 0L,
                    hasOutput = false
                )
            )
        )

        assertEquals(300L, saved)
    }
}
