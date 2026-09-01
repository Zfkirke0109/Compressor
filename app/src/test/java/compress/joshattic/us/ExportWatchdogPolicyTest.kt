package compress.joshattic.us

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media3 1.9+ added an export watchdog that 1.5.0 did not have. Its 10 s default is calibrated for
 * ordinary phone-camera clips, not for this project's corpus, and when it fires it does not arrive
 * through `Transformer.Listener.onError` in a form the batch can absorb — the 2026-09-01 High
 * Quality runs died as an uncaught main-thread exception and took every diagnostic record with
 * them.
 *
 * These pin the limit to the measurement it came from, so a future edit cannot quietly return it
 * to a value the app's own heaviest content is known to exceed.
 */
class ExportWatchdogPolicyTest {

    @Test
    fun limitExceedsTheLongestEncodeThisProjectHasMeasured() {
        // Job fba2a7b1814a (batch_1788168857780): 7680x4320 HEVC, 56,127 ms of wall time on a
        // thermally warm device. A healthy export of the heaviest content in the corpus must not
        // be able to trip the watchdog.
        assertTrue(
            "watchdog limit must exceed the longest measured healthy encode",
            ExportWatchdogPolicy.MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS >
                ExportWatchdogPolicy.LONGEST_MEASURED_ITEM_ENCODE_MS
        )
    }

    @Test
    fun limitKeepsAtLeastTwiceTheMeasuredHeadroom() {
        // The 56 s measurement is a sample, not a ceiling: thermal state, a larger file, or a
        // slower encoder can all push past it. 2x is the margin the constant was chosen for, and
        // stating it here is what makes a later reduction a deliberate recalibration.
        assertTrue(
            "watchdog limit must keep >=2x headroom over the longest measured encode",
            ExportWatchdogPolicy.MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS >=
                2 * ExportWatchdogPolicy.LONGEST_MEASURED_ITEM_ENCODE_MS
        )
    }

    @Test
    fun media3DefaultWouldAbortAHealthyEncodeOnThisCorpus() {
        // The regression this constant exists for: Media3's own default is a fifth of a single
        // measured item. Keeping the comparison in a test means the reason stays visible even if
        // the crash captures are forgotten.
        assertTrue(
            "Media3's default is below the longest measured healthy encode — that is the bug",
            ExportWatchdogPolicy.DEFAULT_MEDIA3_LIMIT_MS <
                ExportWatchdogPolicy.LONGEST_MEASURED_ITEM_ENCODE_MS
        )
    }

    @Test
    fun watchdogStaysBoundedRatherThanDisabled() {
        // Disabling the watchdog outright would leave the batch path with no time bound at all,
        // which is what 1.5.0 had. A wedged encoder must still be caught, just not a slow one.
        assertTrue(
            "a genuinely wedged export must still be bounded",
            ExportWatchdogPolicy.MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS in 1..600_000L
        )
    }
}
