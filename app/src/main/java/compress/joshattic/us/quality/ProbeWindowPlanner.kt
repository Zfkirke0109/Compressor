package compress.joshattic.us.quality

/**
 * Where to score, and where the probe clip that carries a window must START.
 *
 * Two facts from the b163 capture (batch_1790270611232) shape this:
 *
 *  1. A 1.2 s probe clip scores the encoder's warm-up, not its steady state. The clip's first
 *     frame is an I-frame coded before rate control has adapted, and the frames after it are
 *     coded while it adapts. Those frames do not respond to the target bitrate: in 140 of 175
 *     windows scored at two or more ratios, the worst frame's score was identical to two decimals
 *     across the ratios. The same three windows of job_be917e463748 scored min 91.65 / 90.0 /
 *     89.96 inside the clip and 96.04 / 96.13 / 96.00 in the full encode at the same ratio, where
 *     they sit mid-stream after convergence. So every probe window is now preceded by a LEAD-IN
 *     of at least [MIN_LEAD_IN_US] that is encoded and decoded but never scored. The scored frames
 *     then come from the encoder's steady state, as they do in the full encode.
 *
 *  2. Decoding to a window costs whatever lies between the previous keyframe and the window. Four
 *     ladders and both 30-minute files timed out at 60 s exporting a 1.2 s clip, which means the
 *     previous keyframe was minutes earlier. So the clip starts AT a keyframe, and the window is
 *     placed after it. If the nearest keyframe before the wanted position is more than
 *     [MAX_LEAD_IN_US] back, the window moves forward to the next keyframe instead; only when no
 *     keyframe is within reach is the window given up, with the reason recorded.
 *
 * Certification uses the same windows (without a clip, so without a lead-in). That keeps the
 * probe and certification scores of one file directly comparable, frame for frame, and it means
 * the reference decode for certification also starts at a keyframe.
 *
 * Pure: the keyframe index is injected, so the placement rules are unit-tested.
 */
object ProbeWindowPlanner {

    /** Keyframe lookup on the source. Both return a sample presentation time, or null when none. */
    interface SyncSampleIndex {
        fun previousSyncUs(targetUs: Long): Long?
        fun nextSyncUs(targetUs: Long): Long?
    }

    /**
     * One planned window. [clipStartUs] is the keyframe the probe clip starts at; the lead-in is
     * `startUs - clipStartUs`. [anchor] says how the window was placed, for the record.
     */
    data class PlannedWindow(
        val clipStartUs: Long,
        val startUs: Long,
        val endUs: Long,
        val anchor: Anchor
    ) {
        val leadInUs: Long get() = startUs - clipStartUs
        fun scoreWindowForCertification(): ScoreWindow =
            ScoreWindow(startUs, endUs, contextUs = MotionContext.CERTIFICATION_CONTEXT_US)
        fun scoreWindowForProbeClip(): ScoreWindow =
            ScoreWindow(startUs, endUs, distStartUs = 0L, alignFirstFrames = true, leadInUs = leadInUs)
    }

    enum class Anchor {
        /** The previous keyframe was within reach; the window stayed where it was wanted. */
        PREVIOUS_KEYFRAME,

        /** The previous keyframe was too far back; the window moved to just after the next one. */
        NEXT_KEYFRAME,

        /** No keyframe index was available; the clip starts [MIN_LEAD_IN_US] before the window. */
        UNINDEXED
    }

    /** A window that could not be placed, and why. */
    data class Unplaceable(val wantedStartUs: Long, val reason: String)

    data class Plan(val windows: List<PlannedWindow>, val unplaceable: List<Unplaceable>)

    /**
     * Encoded and decoded but not scored: long enough for the encoder's rate control to leave its
     * initial state and for at least one full GOP at Media3's default 1 s I-frame interval to
     * pass before the first scored frame.
     */
    const val MIN_LEAD_IN_US = 2_000_000L

    /**
     * Longest lead-in worth decoding before giving up on the wanted position. At 12 s the extra
     * decode and encode is still a few seconds of work on this device class; a keyframe further
     * back than that is the long-GOP case, where the window moves forward instead.
     */
    const val MAX_LEAD_IN_US = 12_000_000L

    fun plan(
        durationUs: Long,
        index: SyncSampleIndex?,
        windowUs: Long = 1_200_000L
    ): Plan {
        val wanted = QualityProbePolicy.probeWindows(durationUs, windowUs)
        if (wanted.isEmpty()) return Plan(emptyList(), emptyList())
        val windows = mutableListOf<PlannedWindow>()
        val unplaceable = mutableListOf<Unplaceable>()
        for (w in wanted) {
            val placed = place(w.startUs, durationUs, windowUs, index)
            if (placed != null) windows += placed
            else unplaceable += Unplaceable(w.startUs, "no keyframe within ${MAX_LEAD_IN_US / 1_000_000} s before or after the window")
        }
        // Two wanted positions can collapse onto one keyframe (a long-GOP source); score it once.
        return Plan(windows.distinctBy { it.startUs }, unplaceable)
    }

    internal fun place(wantedStartUs: Long, durationUs: Long, windowUs: Long, index: SyncSampleIndex?): PlannedWindow? {
        val latestStart = (durationUs - windowUs).coerceAtLeast(0L)
        if (index == null) {
            val clipStart = (wantedStartUs - MIN_LEAD_IN_US).coerceAtLeast(0L)
            return PlannedWindow(clipStart, wantedStartUs, wantedStartUs + windowUs, Anchor.UNINDEXED)
        }
        val target = (wantedStartUs - MIN_LEAD_IN_US).coerceAtLeast(0L)
        val previous = index.previousSyncUs(target)
        if (previous != null && previous >= 0L && wantedStartUs - previous <= MAX_LEAD_IN_US) {
            // Short clips: the window may have to sit closer to the keyframe than MIN_LEAD_IN_US
            // allows, because the file is not long enough. The lead-in is whatever fits.
            val start = (previous + MIN_LEAD_IN_US).coerceAtMost(latestStart).coerceAtLeast(previous)
            val startFinal = maxOf(start, wantedStartUs.coerceAtMost(latestStart)).coerceAtMost(latestStart)
            return PlannedWindow(previous, startFinal, startFinal + windowUs, Anchor.PREVIOUS_KEYFRAME)
        }
        val next = index.nextSyncUs(target)
        if (next != null && next >= 0L && next + MIN_LEAD_IN_US <= latestStart) {
            val start = next + MIN_LEAD_IN_US
            return PlannedWindow(next, start, start + windowUs, Anchor.NEXT_KEYFRAME)
        }
        // Neither the previous nor the next keyframe is usable (a single-keyframe file, or a
        // window near the end). The keyframe at the head of the file is always reachable, so a
        // long-GOP file still gets one measured window instead of none. Several wanted windows
        // collapse onto it; the plan scores it once.
        val head = index.previousSyncUs(0L) ?: 0L
        if (head + MIN_LEAD_IN_US <= latestStart) {
            val start = head + MIN_LEAD_IN_US
            return PlannedWindow(head, start, start + windowUs, Anchor.NEXT_KEYFRAME)
        }
        return null
    }
}
