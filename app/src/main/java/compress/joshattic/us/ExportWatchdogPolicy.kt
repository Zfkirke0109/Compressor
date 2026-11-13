package compress.joshattic.us

/**
 * How long an export may go without writing a muxer sample before Media3 aborts it.
 *
 * Media3 1.9+ runs an export watchdog that 1.5.0 did not have: if no output sample reaches the
 * muxer for [DEFAULT_MEDIA3_LIMIT_MS], `Transformer` raises
 * `ExportException: Muxer error / Abort: no output sample written in the last N milliseconds`.
 *
 * CORRECTION, from batch_1788255807578 on build d72cfee. This constant was first justified as
 * "a little over 2x the longest single-item encode measured (56,127 ms)". That reasoning was
 * wrong, and the device disproved it: the watchdog measures the gap BETWEEN muxer samples, not
 * an item's total wall time. A healthy export writes samples continuously, so its inter-sample
 * gap is milliseconds regardless of how long the whole item takes; the 56 s figure included
 * probing, certification, verification and remux, none of which the watchdog observes. The raised
 * limit therefore did not rescue a slow-but-healthy encode. It only changed what the failure
 * looked like:
 *
 *   a1a186f (10 s limit)   High Quality 4K -> process killed; probe rungs "2x export failed"
 *   d72cfee (120 s limit)  High Quality 4K -> "Abort: no output sample written in the last
 *                          120000 milliseconds"; the same two probe rungs "2x export timed out"
 *
 * Zero samples in 120 seconds is a wedged export, not a slow one. The watchdog was reporting a
 * real failure at 10 s and is reporting the same real failure at 120 s.
 *
 * So why keep the raised value? Because the limit is now doing the only job it can honestly do —
 * bounding a hang — and the larger bound buys two things the smaller one destroyed. It lets the
 * prober's own 60 s [PerceptualQualityProber.PROBE_EXPORT_TIMEOUT_MS] fire first, so a stalled
 * probe returns through the recoverable timeout path and names itself in the record instead of
 * being pre-empted by a muxer error; and it keeps the failure attributable, since "no sample in
 * 120 s" cannot be mistaken for an encoder that was merely busy.
 *
 * What it is NOT: a calibrated value. Nothing in the corpus measures the legitimate worst-case
 * gap before a FIRST muxer sample on 8K content, which is the one healthy case that could
 * plausibly need seconds. Until a capture records that, treat this as a hang bound with a wide
 * margin, not as a threshold anyone has fitted.
 */
object ExportWatchdogPolicy {

    /** Media3's own default, quoted here so the comparison in tests is explicit. */
    const val DEFAULT_MEDIA3_LIMIT_MS = 10_000L

    /**
     * The limit Compressor asks Media3 to use: a hang bound, deliberately above the prober's own
     * 60 s export timeout so a stalled probe reports itself rather than being pre-empted.
     */
    const val MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS = 120_000L

    /** The prober's own per-clip export timeout, which this limit must stay above. */
    const val PROBE_EXPORT_TIMEOUT_MS = 60_000L
}
