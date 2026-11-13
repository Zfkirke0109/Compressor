package compress.joshattic.us

import android.app.Application
import compress.joshattic.us.quality.CertificationDecision
import compress.joshattic.us.quality.CertificationGate
import compress.joshattic.us.quality.PairScoreOutcome
import compress.joshattic.us.quality.SaferRungRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * What the candidate veto may and may not do. The veto is the seam SaferRungFallbackDeviceTest
 * uses to make a first full encode fail on demand; these pin that it cannot do anything else and
 * that the app itself never installs one.
 */
class CandidateVetoTest {

    @Test
    fun theAppsVetoRefusesNothing() {
        for (attempt in listOf(0, 1, 2, 50)) for (ratio in listOf(0.0, 0.70, 0.75, 0.80, 0.97, 1.0, Double.NaN)) {
            assertNull("attempt $attempt at $ratio", CandidateVeto.NONE.reject(attempt, ratio))
        }
    }

    @Test
    fun aVetoCanOnlyTakeAcceptanceAway() {
        assertTrue(CandidateVeto.stands(gateAccepted = true, vetoReason = null))
        assertFalse(CandidateVeto.stands(gateAccepted = true, vetoReason = "refused"))
        // No reason, including an empty one, turns a rejection into an acceptance.
        assertFalse(CandidateVeto.stands(gateAccepted = false, vetoReason = null))
        assertFalse(CandidateVeto.stands(gateAccepted = false, vetoReason = "refused"))
        assertFalse(CandidateVeto.stands(gateAccepted = false, vetoReason = ""))
        assertFalse(CandidateVeto.stands(gateAccepted = true, vetoReason = ""))
    }

    @Test
    fun theOutcomeARefusedCandidateIsGivenIsAcceptedOnNoBasis() {
        // A refused candidate is not scored: certification is handed "unavailable" in its place.
        for (basis in CertificationGate.Basis.entries) for (used in listOf(0.70, 0.75, 0.90, 0.97)) {
            val verdict = CertificationGate.evaluate(basis, usedRatio = used, defaultRatio = 0.90, outcome = PairScoreOutcome.Unavailable)
            assertFalse("$basis at $used accepted an unscored candidate", verdict.accepted)
            assertFalse("$basis at $used called an unscored candidate pixel-certified", verdict.pixelCertified)
            assertEquals(CertificationDecision.UNAVAILABLE, verdict.decision)
        }
    }

    @Test
    fun aRefusalIsNotAMeasurementAndIsNeverNamedAsOne() {
        // The decision the retry policy sees for a refused candidate teaches the profile nothing
        // and keeps the original as "could not be verified", not as "would degrade".
        assertFalse(CertificationFailure.teachesProfile(CertificationDecision.UNAVAILABLE))
        assertEquals(BatchTerminalResult.UNEXPECTED_REMUX, CertificationFailure.terminalFor(CertificationDecision.UNAVAILABLE))
        // Its recorded names are its own: no certification decision, stage reason or ran-status.
        assertTrue(CertificationDecision.entries.none { it.wire == CandidateVeto.WIRE })
        assertEquals("test_rejected", CandidateVeto.WIRE)
        assertEquals("cert_test_rejected", StageEvent.Reason.CERT_TEST_REJECTED)
        assertTrue(CertificationStatus.didNotRun(CertificationStatus.SKIPPED_TEST_VETO))
        assertTrue(AttemptLedger().apply { start(token = 30, ratio = 0.75, probeRungId = null, configId = null, atMs = 0) }
            .also { assertTrue(it.finish(CandidateVeto.WIRE, candidateBytes = 725_931_990L, encodeMs = 151_468L)) }
            .wire().single().startsWith("0.75:test_rejected:cand=725931990:encodeMs=151468:n=1:token=30"))
    }

    /**
     * The marginal trial the Secure Folder run selected (job_1e25c8805630: 0.75 marginal, 0.80
     * passed, 957,633,523 source bytes, 151,468 ms for the 0.75 encode, accepted 185,301 ms into
     * the item). There the 0.75 encode certified. The 0.80 size was never predicted in that
     * capture, so [predictedBytes] here is an assumption, not a captured value.
     */
    private fun marginalTrial(decision: CertificationDecision?) = SaferRungRetry.Input(
        enabled = true, decision = decision, usedRatio = 0.75, saferPassingRatio = 0.80, sameSourceAndConfig = true,
        retriesThisItem = 0, retriesThisBatch = 0, predictedBytes = 780_000_000L, worthEncoding = true,
        itemElapsedMs = 185_301L, lastEncodeMs = 151_468L, thermalStatus = 0, freeBytes = 40L shl 30,
        sourceBytes = 957_633_523L, marginalRefinementTrial = true
    )

    private fun denial(i: SaferRungRetry.Input) = (SaferRungRetry.decide(i) as SaferRungRetry.Decision.Denied).reasonCode

    @Test
    fun aRefusedMarginalTrialFallsBackToItsMeasuredSaferRungOnce() {
        val refused = marginalTrial(CertificationDecision.UNAVAILABLE)
        assertEquals(SaferRungRetry.Decision.Allowed(0.80), SaferRungRetry.decide(refused))
        // The retry is the production policy's decision, with every one of its limits.
        assertEquals("experiment_off", denial(refused.copy(enabled = false)))
        assertEquals("item_retry_limit", denial(refused.copy(retriesThisItem = 1)))
        assertEquals("batch_retry_limit", denial(refused.copy(retriesThisBatch = SaferRungRetry.MAX_RETRIES_PER_BATCH)))
        assertEquals("size_check_predicts_no_saving", denial(refused.copy(worthEncoding = false)))
        assertEquals("source_or_config_changed", denial(refused.copy(sameSourceAndConfig = false)))
        assertEquals("thermal", denial(refused.copy(thermalStatus = SaferRungRetry.THERMAL_STATUS_LIMIT)))
        assertEquals("no_measured_safer_rung", denial(refused.copy(saferPassingRatio = null)))
    }

    @Test
    fun aRefusalOutsideAMarginalTrialIsNotRetried() {
        // The retry's second encode is not a marginal trial and has no safer rung left: a second
        // refusal ends the item. Nor does a refusal turn any other plan into a retry.
        assertFalse(SaferRungRetry.failureAllowsRetry(CertificationDecision.UNAVAILABLE, marginalRefinementTrial = false))
        assertEquals(
            "not_a_measured_quality_failure",
            denial(marginalTrial(CertificationDecision.UNAVAILABLE).copy(marginalRefinementTrial = false))
        )
        assertTrue(SaferRungRetry.failureAllowsRetry(CertificationDecision.UNAVAILABLE, marginalRefinementTrial = true))
    }

    private fun mainSources(): List<File> {
        val root = listOf("src/main/java", "app/src/main/java").map(::File).firstOrNull { it.isDirectory }
            ?: error("main source set not found from ${File("").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            .also { check(it.size > 50) { "only ${it.size} main sources under $root" } }
    }

    private fun occurrences(pattern: Regex) = mainSources().flatMap { file ->
        pattern.findAll(file.readText()).map { file.name }.toList()
    }

    @Test
    fun theAppNeverBuildsAVetoThatRefuses() {
        // One implementation in the app: the SAM lambda that is NONE (the other brace is the
        // interface's own declaration). No class or object implements the interface, and nothing
        // wraps a function reference into one.
        assertEquals(listOf("CandidateVeto.kt"), occurrences(Regex("""(?<!interface )\bCandidateVeto\s*\{""")))
        assertEquals(1, occurrences(Regex("""\bfun interface CandidateVeto\s*\{""")).size)
        assertEquals(emptyList<String>(), occurrences(Regex("""\bCandidateVeto\s*\(""")))
        assertEquals(emptyList<String>(), occurrences(Regex("""\b(class|object)\b[^\n]*:[^\n]*\bCandidateVeto\b""")))
        val declaration = mainSources().single { it.name == "CandidateVeto.kt" }.readText()
        assertTrue(declaration.contains("val NONE = CandidateVeto { _, _ -> null }"))
    }

    @Test
    fun theAppBuildsItsViewModelOnlyThroughTheConstructorWithoutAVeto() {
        // The app never constructs the view model itself: the ViewModel factory does, through the
        // (Application) constructor, which passes NONE. The veto-taking constructor is internal.
        assertEquals(emptyList<String>(), occurrences(Regex("""\bBatchCompressorViewModel\s*\(""")))
        val viewModel = mainSources().single { it.name == "BatchCompressorViewModel.kt" }.readText()
        assertEquals(1, Regex("""constructor\(application: Application\) : this\(application, CandidateVeto\.NONE\)""").findAll(viewModel).count())
        assertEquals(1, Regex("""class BatchCompressorViewModel internal constructor\(""").findAll(viewModel).count())
        assertEquals(1, Regex("""private val candidateVeto: CandidateVeto\b""").findAll(viewModel).count())
        // Consulted in one place, and compared with NONE only to mark a test's batch as one.
        assertEquals(1, Regex("""\bcandidateVeto\.reject\(""").findAll(viewModel).count())
        assertEquals(1, Regex("""\bcandidateVeto !== CandidateVeto\.NONE\b""").findAll(viewModel).count())
        assertEquals(2, Regex("""\bcandidateVeto\b(?!:)""").findAll(viewModel).count())
    }

    @Test
    fun theFactoryConstructorIsStillPublic() {
        val constructor = BatchCompressorViewModel::class.java.getConstructor(Application::class.java)
        assertTrue(Modifier.isPublic(constructor.modifiers))
        // The only other constructor is the internal one that takes a veto and a harness scope.
        assertEquals(
            setOf(listOf(Application::class.java), listOf(Application::class.java, CandidateVeto::class.java, HarnessScope::class.java)),
            BatchCompressorViewModel::class.java.declaredConstructors.filterNot { it.isSynthetic }.map { it.parameterTypes.toList() }.toSet()
        )
    }

    @Test
    fun theAppNeverRunsItsBatchesUnderAHarnessScope() {
        // Only an instrumented test makes a scope: the app's batches learn into the user's store and
        // settle the user's rollback copies at start. The factory constructor leaves it null.
        assertEquals(emptyList<String>(), occurrences(Regex("""(?<!class )\bHarnessScope\s*\(""")))
        assertEquals(emptyList<String>(), occurrences(Regex("""(?<!class )\bOverlayProfileStore\s*\(""")))
        val viewModel = mainSources().single { it.name == "BatchCompressorViewModel.kt" }.readText()
        assertEquals(1, Regex("""private val harness: HarnessScope\? = null""").findAll(viewModel).count())
        assertEquals(1, Regex("""if \(harness == null\) recoverInterruptedReplacements\(\)""").findAll(viewModel).count())
    }
}
