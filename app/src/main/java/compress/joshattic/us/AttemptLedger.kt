package compress.joshattic.us

import java.util.Locale

/**
 * Every full-encode attempt of one job, append-only, finished exactly once (b177 F3).
 *
 * Before this the job kept a list of summary strings that only the certification and finalize
 * paths wrote. A retry whose encode failed left through `fallBackAfterEncodeFailure` or the item
 * failure handler, whose records carried no attempts at all, so a failed second attempt vanished
 * from the retry denominator; and the stage events' `attempt` was the batch-wide callback token
 * (260, 261, 315 ...), not a retry number.
 *
 * Rules:
 *  - [start] opens attempt n+1 (per job, 1-based) and records the global [token] beside it;
 *  - [finish] writes the open attempt's outcome once; a second finish is ignored, so the first
 *    path that decides an attempt's fate is the one recorded;
 *  - an attempt still open when the job ends is closed by the terminal path ([closeOpen]) with
 *    what ended it (a failure, a fallback, cancellation);
 *  - nothing is ever removed or rewritten.
 *
 * [started] counts every attempt that began, whatever happened to it: the retry denominator.
 */
class AttemptLedger {

    data class Entry(
        val index: Int,
        val token: Int,
        val ratio: Double?,
        /** RungEvidence.id of the probe rung whose evidence this attempt relied on; null when none. */
        val probeRungId: String?,
        /** EncodeConfigIdentity of the full encode's request. */
        val configId: String?,
        val startedAtMs: Long,
        val outcome: String? = null,
        val candidateBytes: Long = 0L,
        val encodeMs: Long = 0L
    ) {
        val finished: Boolean get() = outcome != null

        /** Legacy `attempts` element ("0.85:measured_below_bar:cand=14928138:encodeMs=7680"), extended. */
        fun wire(): String = String.format(
            Locale.US, "%.2f:%s:cand=%d:encodeMs=%d:n=%d:token=%d%s%s",
            ratio ?: 0.0, outcome ?: OPEN, candidateBytes, encodeMs, index, token,
            probeRungId?.let { ":rung=$it" } ?: "", configId?.let { ":cfg=$it" } ?: ""
        )

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "n" to index, "token" to token, "ratio" to ratio, "probeRungId" to probeRungId,
            "configId" to configId, "startedAtMs" to startedAtMs, "outcome" to (outcome ?: OPEN),
            "candidateBytes" to candidateBytes, "encodeMs" to encodeMs
        )
    }

    private val entries = mutableListOf<Entry>()

    val all: List<Entry> get() = entries.toList()
    val started: Int get() = entries.size
    val current: Entry? get() = entries.lastOrNull()

    /** Per-job index of the attempt in progress (or the last one), 0 before any attempt. */
    val currentIndex: Int get() = entries.lastOrNull()?.index ?: 0

    /** Opens the next attempt. An attempt left open is closed as superseded first: never lost. */
    fun start(token: Int, ratio: Double?, probeRungId: String?, configId: String?, atMs: Long): Int {
        closeOpen(SUPERSEDED)
        val entry = Entry(entries.size + 1, token, ratio, probeRungId, configId, atMs)
        entries += entry
        return entry.index
    }

    /** Records the open attempt's outcome. Returns false when there was no open attempt. */
    fun finish(outcome: String, candidateBytes: Long, encodeMs: Long): Boolean {
        val last = entries.lastOrNull() ?: return false
        if (last.finished) return false
        entries[entries.size - 1] = last.copy(outcome = outcome, candidateBytes = candidateBytes, encodeMs = encodeMs)
        return true
    }

    /** Closes an attempt the job is ending with; keeps whatever size/time it already had. */
    fun closeOpen(outcome: String): Boolean {
        val last = entries.lastOrNull() ?: return false
        if (last.finished) return false
        entries[entries.size - 1] = last.copy(outcome = outcome)
        return true
    }

    fun wire(): List<String> = entries.map { it.wire() }

    /** Closes the interrupted encode and records its cancellation once. */
    fun cancelEvent(sourceKey: String, elapsedMs: Long): StageEvent? {
        if (!closeOpen(CANCELLED)) return null
        val cancelled = checkNotNull(current)
        return StageEvent(
            sourceKey = sourceKey, attempt = cancelled.token, attemptIndex = cancelled.index,
            stage = StageEvent.Stage.ENCODE, reasonCode = StageEvent.Reason.ENCODE_CANCELLED,
            elapsedMs = elapsedMs,
            fields = mapOf("attemptsStarted" to started, "attempts" to wire().joinToString(";"))
        )
    }

    fun accepted(): Int = entries.count { it.outcome == ACCEPTED }

    companion object {
        const val OPEN = "open"
        const val EXPORT_FAILED = "export_failed"
        const val STRUCTURAL_FAILED = "structural_failed"
        const val ACCEPTED = "accepted"
        const val CANCELLED = "cancelled"
        const val FAILED = "failed"
        const val SUPERSEDED = "superseded"
        fun notAccepted(terminal: BatchTerminalResult) = "not_accepted:${terminal.name}"
    }
}
