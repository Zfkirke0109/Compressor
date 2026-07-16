package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The inter-item throughput optimization is a TIMING-ONLY gate: the post-item thermal cooldown is
 * applied only after an item that actually ran a full hardware encode. These tests pin that gate;
 * the encode-vs-remux decision itself is unchanged and lives in the algorithm code.
 */
class ThermalBatchGovernorTest {

    private fun snapshot(postMs: Long) = ThermalBatchSnapshot(
        mode = ThermalBatchMode.BALANCED,
        thermalLabel = "normal",
        batteryPercent = 80,
        isCharging = true,
        shouldPause = false,
        shouldSlowDown = false,
        preItemDelayMs = 0L,
        postItemDelayMs = postMs,
        summary = "test"
    )

    @Test
    fun cooldownIsAppliedInFullAfterAFullEncode() {
        // An item that ran a full encode keeps the governor's entire cooldown (thermal recovery).
        assertEquals(10_000L, ThermalBatchGovernor.postItemCooldownMs(ranFullEncode = true, snapshot(10_000L)))
        assertEquals(30_000L, ThermalBatchGovernor.postItemCooldownMs(ranFullEncode = true, snapshot(30_000L)))
        assertEquals(0L, ThermalBatchGovernor.postItemCooldownMs(ranFullEncode = true, snapshot(0L)))
    }

    @Test
    fun noCooldownAfterAnItemThatRanNoEncode() {
        // Stream-copy / remux / already-optimized items generate no encoder heat -> no cooldown,
        // no matter how large the governor's nominal cooldown is. This is the ~25-minute win on the
        // 172-file batch (116 no-encode items previously waited ~10-30 s each for zero benefit).
        assertEquals(0L, ThermalBatchGovernor.postItemCooldownMs(ranFullEncode = false, snapshot(10_000L)))
        assertEquals(0L, ThermalBatchGovernor.postItemCooldownMs(ranFullEncode = false, snapshot(30_000L)))
        assertEquals(0L, ThermalBatchGovernor.postItemCooldownMs(ranFullEncode = false, snapshot(0L)))
    }

    @Test
    fun cooldownGateNeverInventsDelay() {
        // The gate can only reduce or preserve the governor cooldown, never increase it.
        for (post in listOf(0L, 5_000L, 20_000L, 120_000L)) {
            for (ran in listOf(true, false)) {
                val applied = ThermalBatchGovernor.postItemCooldownMs(ran, snapshot(post))
                assert(applied in 0L..post) { "applied $applied out of range for post=$post ran=$ran" }
            }
        }
    }
}
