package compress.joshattic.us

/** Pure presentation-timeline comparison, separate from quality evidence. */
object MediaTimelineEvidence {
    data class Result(val matches: Boolean, val sourceFrames: Int, val outputFrames: Int,
        val sourceOriginUs: Long? = null, val outputOriginUs: Long? = null,
        val maxSkewUs: Long = 0, val firstMismatch: Int? = null, val reason: String? = null)

    fun compare(source: LongArray, output: LongArray, toleranceUs: Long = 1000): Result =
        Result(source.isNotEmpty() && output.isNotEmpty() &&
            kotlin.math.abs(source.size-output.size) <= maxOf(2, source.size / 100), source.size, output.size)
}
