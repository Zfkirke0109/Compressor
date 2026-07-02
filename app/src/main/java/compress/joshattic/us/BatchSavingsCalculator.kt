package compress.joshattic.us

internal data class BatchSavingsInput(
    val originalSize: Long,
    val outputSize: Long,
    val hasOutput: Boolean
)

internal object BatchSavingsCalculator {
    fun savedBytes(items: List<BatchSavingsInput>): Long {
        return items.sumOf { item ->
            if (item.hasOutput && item.outputSize > 0L) {
                (item.originalSize - item.outputSize).coerceAtLeast(0L)
            } else {
                0L
            }
        }
    }
}
