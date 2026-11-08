package compress.joshattic.us

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DebugTraceUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Turns on Media3's own per-component export trace.
 *
 * The watchdog abort that ended the 2026-09-01 High Quality run reads:
 *
 *   Abort: no output sample written in the last 120000 milliseconds. DebugTrace: "Tracing disabled"
 *
 * Media3 builds that message from [androidx.media3.effect.DebugTraceUtil.generateTraceSummary],
 * which — when tracing is on — reports the last events seen by each stage of the pipeline: asset
 * loader, video decoder, frame processor, video encoder, muxer. That is exactly the missing fact.
 * A stall that writes zero muxer samples in two minutes could be a decoder that never produced a
 * frame, a frame processor wedged on a GL surface, an encoder that accepted input and returned
 * nothing, or a muxer that refused the samples — and the diagnosis for each is different.
 * Without the trace the message says only that nothing arrived.
 *
 * Tracing is NOT enabled unconditionally. Media3's DebugTraceUtil is debugging instrumentation,
 * not a bounded ring: on a long or high-frame-rate export it can accumulate substantial event data
 * and allocation overhead, potentially causing the same process loss it was intended to diagnose.
 * [armNextBatch] schedules a single diagnostic batch. [tryRunExclusive] scopes the flag and
 * releases the accumulated trace when that batch ends.
 *
 * The trace reaches captures for free: the summary is embedded in the `ExportException` message,
 * and that message is already recorded by the batch's failure handler under `CompressorBatch`.
 */
object ExportDebugTracing {

    enum class State { IDLE, ARMED, ACTIVE }

    private val _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()
    private val operationLock = Mutex()

    fun armNextBatch() {
        _state.compareAndSet(State.IDLE, State.ARMED)
    }

    /** Serialize batches, scorer self-checks and profile resets across app windows. */
    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun tryRunExclusive(consumeTraceArm: Boolean = false, operation: suspend () -> Unit): Boolean {
        if (!operationLock.tryLock()) return false
        try {
            val traceThisBatch = consumeTraceArm && _state.compareAndSet(State.ARMED, State.ACTIVE)
            try {
                if (traceThisBatch) DebugTraceUtil.enableTracing = true
                operation()
            } finally {
                if (traceThisBatch) {
                    DebugTraceUtil.enableTracing = false
                    DebugTraceUtil.reset()
                    _state.value = State.IDLE
                }
            }
            return true
        } finally {
            operationLock.unlock()
        }
    }
}
