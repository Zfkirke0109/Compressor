package compress.joshattic.us

/**
 * Pure presentation-timeline comparison, separate from quality evidence.
 *
 * Two timelines match only frame for frame: the same number of frames, and every output
 * presentation time within [compare]'s tolerance of the source's once each timeline is measured
 * from its own first frame (a uniform origin shift, such as an edit list or a trimmed start, is
 * not frame loss). There is no count tolerance: one missing frame is a mismatch at any length,
 * and a retimed middle frame is a mismatch even when the count and duration agree. A timeline
 * with a repeated or reversed presentation time is ambiguous and never matches.
 */
object MediaTimelineEvidence {
    data class Result(val matches: Boolean, val sourceFrames: Int, val outputFrames: Int,
        val sourceOriginUs: Long? = null, val outputOriginUs: Long? = null,
        val maxSkewUs: Long = 0, val firstMismatch: Int? = null, val reason: String? = null)

    fun compare(source: LongArray, output: LongArray, toleranceUs: Long = 1000): Result {
        val sourceOrigin = source.firstOrNull()
        val outputOrigin = output.firstOrNull()
        fun result(matches: Boolean, maxSkew: Long = 0, first: Int? = null, reason: String? = null) =
            Result(matches, source.size, output.size, sourceOrigin, outputOrigin, maxSkew, first, reason)
        if (source.isEmpty() || output.isEmpty()) return result(false, reason = "empty timeline")
        strictlyIncreasingViolation(source)?.let { return result(false, first = it, reason = "source presentation times repeat or reverse") }
        strictlyIncreasingViolation(output)?.let { return result(false, first = it, reason = "output presentation times repeat or reverse") }
        var maxSkew = 0L
        var firstMismatch: Int? = null
        for (i in 0 until minOf(source.size, output.size)) {
            val skew = kotlin.math.abs((source[i] - sourceOrigin!!) - (output[i] - outputOrigin!!))
            if (skew > maxSkew) maxSkew = skew
            if (skew > toleranceUs && firstMismatch == null) firstMismatch = i
        }
        if (source.size != output.size) {
            return result(false, maxSkew, firstMismatch ?: minOf(source.size, output.size),
                "frame count ${source.size} -> ${output.size}")
        }
        return if (firstMismatch == null) result(true, maxSkew)
        else result(false, maxSkew, firstMismatch, "frame $firstMismatch retimed beyond ${toleranceUs}us")
    }

    private fun strictlyIncreasingViolation(pts: LongArray): Int? =
        (1 until pts.size).firstOrNull { pts[it] <= pts[it - 1] }
}
