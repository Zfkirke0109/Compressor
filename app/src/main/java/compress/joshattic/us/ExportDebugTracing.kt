package compress.joshattic.us

import androidx.media3.common.util.UnstableApi

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
 * Enabled unconditionally rather than behind a debug guard because this app's release builds are
 * what the calibration corpus runs on; a diagnosis available only in a configuration nobody
 * measures is not a diagnosis. The cost is a bounded in-memory ring of event records per export,
 * written on events the pipeline already emits, and it is read only when a trace summary is
 * generated — i.e. when something has already gone wrong.
 *
 * The trace reaches captures for free: the summary is embedded in the `ExportException` message,
 * and that message is already recorded by the batch's failure handler under `CompressorBatch`.
 */
object ExportDebugTracing {

    @Volatile
    private var enabled = false

    /** Idempotent; safe to call from any export entry point. */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Synchronized
    fun enable() {
        if (enabled) return
        androidx.media3.effect.DebugTraceUtil.enableTracing = true
        enabled = true
    }
}
