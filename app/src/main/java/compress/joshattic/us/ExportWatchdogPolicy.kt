package compress.joshattic.us

/**
 * How long an export may go without writing a muxer sample before Media3 aborts it.
 *
 * Media3 1.9+ runs an export watchdog that Media3 1.5.0 did not have: if no output sample reaches
 * the muxer for [DEFAULT_MEDIA3_LIMIT_MS], `Transformer` throws
 * `ExportException: Muxer error / Abort: no output sample written in the last 10000 milliseconds`.
 * It does not arrive through `Transformer.Listener.onError` — it surfaces as an uncaught
 * exception on the main thread, so it kills the process and takes every diagnostic record for the
 * batch with it.
 *
 * That is exactly what happened when this project moved 1.5.0 -> 1.11.0, and the captures make it
 * a clean before/after rather than a guess:
 *
 *   build 469684b (Media3 1.5.0)   High Quality, 13 files incl. 8K -> 13/13 exported, summary written
 *   builds ee6853b / a1a186f (1.11.0)  High Quality, 3 files x4 runs -> 0 job records, no summary,
 *                                      process killed with the watchdog stack trace above
 *
 * 10 seconds is a reasonable default for ordinary phone-camera clips. It is not reasonable for
 * this app's workload: the same corpus contains 7680x4320 HEVC at ~57 Mbps, where single items
 * measured 40.8 s, 56.1 s and 35.6 s of wall time under thermal throttling.
 *
 * [MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS] is therefore set from that measurement rather than picked:
 * a little over 2x the longest single-item encode this project has recorded (56.1 s, job
 * fba2a7b1814a in batch_1788168857780). A healthy export of the heaviest content we have seen
 * cannot reach it, while a genuinely wedged encoder is still bounded — the watchdog is a hang
 * detector worth keeping, and disabling it outright would leave the batch path with no time bound
 * at all, which is what 1.5.0 had.
 *
 * Pure Kotlin, no Android dependencies, so the reasoning above is unit-testable.
 */
object ExportWatchdogPolicy {

    /** Media3's own default, quoted here so the comparison in tests is explicit. */
    const val DEFAULT_MEDIA3_LIMIT_MS = 10_000L

    /** Longest single-item encode this project has measured on device (8K HEVC, thermally warm). */
    const val LONGEST_MEASURED_ITEM_ENCODE_MS = 56_127L

    /** The limit Compressor asks Media3 to use. */
    const val MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS = 120_000L
}
