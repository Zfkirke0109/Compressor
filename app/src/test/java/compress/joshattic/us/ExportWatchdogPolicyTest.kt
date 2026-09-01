package compress.joshattic.us

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media3 1.9+ added an export watchdog that 1.5.0 did not have, and it bounds the gap BETWEEN
 * muxer samples — not an item's total wall time.
 *
 * These originally pinned the limit to "2x the longest single-item encode (56,127 ms)". The
 * 2026-09-01 captures disproved that reasoning: at the raised limit the High Quality 4K export
 * still aborted, now reporting "no output sample written in the last 120000 milliseconds". Zero
 * samples in two minutes is a wedged export, not a slow one, so the raised value never rescued a
 * healthy encode and the total-wall-time figure was never the right quantity to size it from.
 *
 * What the constant legitimately guarantees is narrower, so that is what these assert.
 */
class ExportWatchdogPolicyTest {

    @Test
    fun theWatchdogStaysAboveTheProbersOwnExportTimeout() {
        // The load-bearing property. If Media3 aborts first, a stalled probe surfaces as a muxer
        // error instead of returning through the recoverable timeout path that names it in the
        // record — which is exactly how build a1a186f reported "2x export failed" for the same
        // two clips that d72cfee reported as "2x export timed out after 60000ms".
        assertTrue(
            "the muxer watchdog must not pre-empt the prober's own timeout",
            ExportWatchdogPolicy.MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS >
                ExportWatchdogPolicy.PROBE_EXPORT_TIMEOUT_MS
        )
    }

    @Test
    fun aWedgedExportIsStillBounded() {
        // Disabling the watchdog would leave the batch path with no time bound at all, which is
        // what 1.5.0 had. The point is not to stop it firing — on this corpus it fires on a real
        // stall — but to keep the bound wide enough to be unambiguous and finite enough to end.
        assertTrue(
            "a wedged export must still be bounded",
            ExportWatchdogPolicy.MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS in 1..600_000L
        )
    }

    @Test
    fun theLimitIsNotDerivedFromTotalItemWallTime() {
        // Guards the corrected reasoning rather than a number. LONGEST_MEASURED_ITEM_ENCODE_MS is
        // kept only as the figure the mistaken justification rested on; if a future edit ever
        // makes the limit a multiple of it again, that edit has re-adopted the falsified premise.
        val ratio =
            ExportWatchdogPolicy.MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS.toDouble() /
                ExportWatchdogPolicy.LONGEST_MEASURED_ITEM_ENCODE_MS
        assertTrue(
            "the limit must not be a clean multiple of total item wall time — that quantity is" +
                " not what the watchdog measures",
            kotlin.math.abs(ratio - Math.round(ratio)) > 0.05
        )
    }
}
