package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Test

class SaferRungRetryTest {

    /** b169 job_478c2fa19100 after its 0.85 certification failed: 0.90 passed in the same ladder. */
    private fun job478c() = SaferRungRetry.Input(
        enabled = true,
        decision = CertificationDecision.MEASURED_FAILURE,
        usedRatio = 0.85,
        saferPassingRatio = 0.90,
        sameSourceAndConfig = true,
        retriesThisItem = 0,
        retriesThisBatch = 0,
        predictedBytes = 15_600_000L,
        worthEncoding = true,
        itemElapsedMs = 33_293L,
        lastEncodeMs = 9_000L,
        thermalStatus = 0,
        freeBytes = 40L shl 30,
        sourceBytes = 16_753_345L
    )

    private fun denial(i: SaferRungRetry.Input) = (SaferRungRetry.decide(i) as SaferRungRetry.Decision.Denied).reasonCode

    @Test
    fun theLowFrameRateNearMissMayRetryAtItsMeasuredSaferRung() {
        assertEquals(SaferRungRetry.Decision.Allowed(0.90), SaferRungRetry.decide(job478c()))
    }

    @Test
    fun offByDefault() {
        assertEquals("experiment_off", denial(job478c().copy(enabled = false)))
    }

    @Test
    fun c92aHasNoMeasuredSaferRung() {
        // 0.97 selected marginally, nothing measured above it: no blind retry.
        assertEquals("no_measured_safer_rung", denial(job478c().copy(usedRatio = 0.97, saferPassingRatio = null)))
        assertEquals("safer_rung_not_higher", denial(job478c().copy(saferPassingRatio = 0.85)))
    }

    @Test
    fun onlyAMeasuredQualityFailureIsEligible() {
        for (d in listOf(CertificationDecision.INSUFFICIENT_EVIDENCE, CertificationDecision.UNAVAILABLE, CertificationDecision.MISALIGNED, CertificationDecision.PASSED)) {
            assertEquals(d.name, "not_a_measured_quality_failure", denial(job478c().copy(decision = d)))
        }
    }

    @Test
    fun atMostOneRetryPerFileAndFivePerBatch() {
        assertEquals("item_retry_limit", denial(job478c().copy(retriesThisItem = 1)))
        assertEquals("batch_retry_limit", denial(job478c().copy(retriesThisBatch = SaferRungRetry.MAX_RETRIES_PER_BATCH)))
    }

    @Test
    fun budgetsAndTheSizeCheckCanDeny() {
        assertEquals("size_check_predicts_no_saving", denial(job478c().copy(worthEncoding = false)))
        assertEquals("item_time_budget", denial(job478c().copy(itemElapsedMs = SaferRungRetry.ITEM_BUDGET_MS - 1_000L)))
        assertEquals("thermal", denial(job478c().copy(thermalStatus = SaferRungRetry.THERMAL_STATUS_LIMIT)))
        assertEquals("storage", denial(job478c().copy(freeBytes = 16_753_345L)))
        assertEquals("source_or_config_changed", denial(job478c().copy(sameSourceAndConfig = false)))
    }

    @Test
    fun unknownThermalAndStorageDoNotBlock() {
        assertEquals(SaferRungRetry.Decision.Allowed(0.90), SaferRungRetry.decide(job478c().copy(thermalStatus = null, freeBytes = null)))
    }
}
