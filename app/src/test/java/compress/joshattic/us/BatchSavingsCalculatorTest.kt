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

    @Test
    fun largerFallbackOutputsDoNotReportSavings() {
        val saved = BatchSavingsCalculator.savedBytes(
            listOf(
                BatchSavingsInput(
                    originalSize = 4_131_195_352L,
                    outputSize = 4_234_288_900L,
                    hasOutput = true
                )
            )
        )

        assertEquals(0L, saved)
    }
}
