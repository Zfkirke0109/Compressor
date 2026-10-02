package compress.joshattic.us

/**
 * Where an item is in its pipeline, separate from how it ENDED (its [BatchItemStatus] and
 * [BatchTerminalResult]).
 *
 * The b169 recording (row 110, `job_d127463b57d6`) showed why these must be separate. Transformer
 * finished the encode and the row read "Compressing … 100%"; the metadata remux and a 16 s pixel
 * certification were still to run, and certification could still have discarded the output. An
 * encoder's fraction is not the item's completion, and 100 % of an encode is not a result.
 *
 * Only [ENCODING] (Media3's own progress) and [CERTIFYING] (windows scored so far) have an honest
 * fraction; every other phase is shown as indeterminate work with a name.
 */
enum class ItemPhase(val label: String) {
    QUEUED("Queued"),
    PREPARING("Preparing"),
    PROBING("Measuring quality"),
    ENCODING("Encoding"),
    REMUXING("Remuxing"),
    FINALIZING("Finalizing output"),
    VERIFYING("Verifying output"),
    CERTIFYING("Certifying pixels"),
    SAVING("Saving"),
    ENDED("Ended");

    /** True when the phase reports a measured fraction ([ItemProgressModel.fraction] may be set). */
    val hasFraction: Boolean get() = this == ENCODING || this == REMUXING || this == CERTIFYING
}

/**
 * Identity of one attempt at one item. A late Transformer callback, poller tick or error from an
 * earlier attempt (a retry, a fallback encode, or a cancelled batch) carries an older token and is
 * ignored instead of rewriting the row of the attempt that replaced it.
 */
data class AttemptToken(val batchId: String, val index: Int, val attempt: Int) {
    override fun toString(): String = "$batchId#$index.$attempt"
}

/** Pure state transitions for an item's phase. Every mutation checks the attempt token. */
object ItemProgressModel {

    private val ACTIVE = setOf(BatchItemStatus.Compressing)

    /** Whether [token] may still change [item]: the same attempt, and the item is not terminal. */
    fun accepts(item: BatchVideoItem, token: AttemptToken): Boolean =
        item.attempt == token && item.status in ACTIVE

    /** A new attempt owns the row from here on. */
    fun begin(item: BatchVideoItem, token: AttemptToken, provisionalEstimateBytes: Long): BatchVideoItem =
        item.copy(
            status = BatchItemStatus.Compressing,
            attempt = token,
            phase = ItemPhase.PREPARING,
            phaseFraction = null,
            phaseStep = null,
            phaseSteps = null,
            progress = 0f,
            currentOutputSize = 0L,
            candidateOutputSize = 0L,
            targetOutputSize = provisionalEstimateBytes,
            estimateIsProvisional = true
        )

    fun enter(item: BatchVideoItem, token: AttemptToken, phase: ItemPhase): BatchVideoItem {
        if (!accepts(item, token)) return item
        return item.copy(phase = phase, phaseFraction = null, phaseStep = null, phaseSteps = null)
    }

    /**
     * Media3 progress while encoding. `null` = Media3 has no progress to report (WAITING_FOR_
     * AVAILABILITY / UNAVAILABLE): the bar goes indeterminate rather than holding a stale number.
     * The fraction is clamped below 1: an encode is complete only when [finishEncode] says so, and
     * even then the item is not.
     */
    fun encodeProgress(item: BatchVideoItem, token: AttemptToken, fraction: Float?, liveFileBytes: Long): BatchVideoItem {
        if (!accepts(item, token) || (item.phase != ItemPhase.ENCODING && item.phase != ItemPhase.REMUXING)) return item
        return item.copy(
            phaseFraction = fraction?.coerceIn(0f, MAX_RUNNING_FRACTION),
            currentOutputSize = liveFileBytes.coerceAtLeast(0L)
        )
    }

    /**
     * The encoder closed its output. The item moves to [ItemPhase.FINALIZING]: a candidate
     * exists, nothing has been accepted, and the live file length is no longer shown (the muxer
     * may have truncated reserved space, and the metadata remux rewrites the file).
     */
    fun finishEncode(item: BatchVideoItem, token: AttemptToken): BatchVideoItem {
        if (!accepts(item, token)) return item
        return item.copy(
            phase = ItemPhase.FINALIZING,
            phaseFraction = null,
            currentOutputSize = 0L
        )
    }

    /** The finalized candidate's real size, once the file is closed and rewritten. */
    fun candidateReady(item: BatchVideoItem, token: AttemptToken, candidateBytes: Long): BatchVideoItem {
        if (!accepts(item, token)) return item
        return item.copy(candidateOutputSize = candidateBytes.coerceAtLeast(0L), currentOutputSize = 0L)
    }

    /** Windows scored so far, out of the windows actually planned (never a guessed total). */
    fun certifyStep(item: BatchVideoItem, token: AttemptToken, done: Int, total: Int): BatchVideoItem {
        if (!accepts(item, token) || item.phase != ItemPhase.CERTIFYING || total <= 0) return item
        val d = done.coerceIn(0, total)
        return item.copy(phaseStep = d, phaseSteps = total, phaseFraction = d.toFloat() / total)
    }

    /**
     * A new attempt at the same item (the safer-rung retry) takes the row over from [previous].
     * From here, anything still arriving for [previous] is ignored. No-op unless [previous] owns it.
     */
    fun handOver(item: BatchVideoItem, previous: AttemptToken, next: AttemptToken): BatchVideoItem {
        if (!accepts(item, previous)) return item
        return item.copy(
            attempt = next,
            phase = ItemPhase.PREPARING,
            phaseFraction = null,
            phaseStep = null,
            phaseSteps = null,
            currentOutputSize = 0L,
            candidateOutputSize = 0L
        )
    }

    /** The resolved plan's estimate replaces the provisional one. */
    fun planResolved(item: BatchVideoItem, token: AttemptToken, estimateBytes: Long): BatchVideoItem {
        if (!accepts(item, token)) return item
        return item.copy(targetOutputSize = estimateBytes.coerceAtLeast(0L), estimateIsProvisional = false)
    }

    /** Completed items over all items. An active item counts as 0: its encoder fraction is not completion. */
    fun batchFraction(items: List<BatchVideoItem>): Float =
        if (items.isEmpty()) 0f else items.count { isTerminal(it) }.toFloat() / items.size

    fun isTerminal(item: BatchVideoItem): Boolean =
        item.terminalResult != null || item.status in TERMINAL_STATUSES || item.isAlreadyCompressed

    private val TERMINAL_STATUSES = setOf(
        BatchItemStatus.Done, BatchItemStatus.Failed, BatchItemStatus.Replaced,
        BatchItemStatus.SavedCopy, BatchItemStatus.Skipped, BatchItemStatus.Cancelled
    )

    /** An encode reports at most this while it runs; see [encodeProgress]. */
    const val MAX_RUNNING_FRACTION = 0.99f

    /** Row text for the active phase. `Encoding 42%`, `Certifying pixels 1 of 3 windows`, `Finalizing output`. */
    fun describe(item: BatchVideoItem): String {
        val phase = item.phase
        val steps = item.phaseSteps
        return when {
            phase == ItemPhase.CERTIFYING && steps != null -> "${phase.label} ${item.phaseStep ?: 0} of $steps windows"
            phase.hasFraction && item.phaseFraction != null -> "${phase.label} ${(item.phaseFraction * 100f).toInt()}%"
            else -> phase.label
        }
    }
}

/** Where item rows live. The ViewModel backs it with its UI state; tests back it with a list. */
interface ItemBoard {
    fun item(index: Int): BatchVideoItem?
    fun update(index: Int, transform: (BatchVideoItem) -> BatchVideoItem)
}

/**
 * Phase reporting bound to one attempt. Stages call this; they never write phase fields directly,
 * so a stage running for a superseded attempt cannot move the row.
 */
class PhaseReporter(private val board: ItemBoard, val token: AttemptToken) {
    fun enter(phase: ItemPhase) = board.update(token.index) { ItemProgressModel.enter(it, token, phase) }
    fun encodeProgress(fraction: Float?, liveFileBytes: Long) =
        board.update(token.index) { ItemProgressModel.encodeProgress(it, token, fraction, liveFileBytes) }
    fun encodeFinished() = board.update(token.index) { ItemProgressModel.finishEncode(it, token) }
    fun candidateReady(bytes: Long) = board.update(token.index) { ItemProgressModel.candidateReady(it, token, bytes) }
    fun certifyStep(done: Int, total: Int) = board.update(token.index) { ItemProgressModel.certifyStep(it, token, done, total) }
    fun planResolved(estimateBytes: Long) = board.update(token.index) { ItemProgressModel.planResolved(it, token, estimateBytes) }
    /** A message for this attempt only; ignored once the item is terminal or re-attempted. */
    fun message(text: String) = board.update(token.index) {
        if (ItemProgressModel.accepts(it, token)) it.copy(message = text) else it
    }
    fun isCurrent(): Boolean = board.item(token.index)?.let { ItemProgressModel.accepts(it, token) } == true
}

/**
 * The four stages of one item, in order. Each returns false when it ended the item itself (a
 * skip, a keep-original, a fallback), having set a terminal state.
 */
interface ItemStages {
    suspend fun plan(phases: PhaseReporter): Boolean
    suspend fun produce(phases: PhaseReporter): Boolean
    suspend fun verify(phases: PhaseReporter): Boolean
    suspend fun finalize(phases: PhaseReporter)

    /**
     * The reporter of the attempt that owns the item now. A stage may hand the item to a new
     * attempt (the safer-rung retry); later transitions must be made with the new token.
     */
    fun currentPhases(initial: PhaseReporter): PhaseReporter = initial
}

/**
 * Runs [ItemStages] for one attempt and owns the phase transitions between them. Exceptions
 * (including cancellation) propagate unchanged; the caller owns failure handling. The result says
 * whether the item reached a terminal state, which every path must do.
 */
class ItemPipeline(private val board: ItemBoard) {

    enum class Result { TERMINAL, ENDED_WITHOUT_TERMINAL }

    suspend fun run(token: AttemptToken, stages: ItemStages): Result {
        val initial = PhaseReporter(board, token)
        fun now() = stages.currentPhases(initial)
        if (stages.plan(initial)) {
            now().enter(ItemPhase.ENCODING)
            if (stages.produce(now())) {
                now().enter(ItemPhase.VERIFYING)
                if (stages.verify(now())) {
                    now().enter(ItemPhase.SAVING)
                    stages.finalize(now())
                }
            }
        }
        val item = board.item(token.index)
        return if (item != null && ItemProgressModel.isTerminal(item)) Result.TERMINAL else Result.ENDED_WITHOUT_TERMINAL
    }
}
