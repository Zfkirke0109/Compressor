package compress.joshattic.us

import compress.joshattic.us.quality.CertificationDecision
import compress.joshattic.us.quality.ExhaustivePerceptualLosslessPolicy
import compress.joshattic.us.quality.MeasuredOvershoot
import compress.joshattic.us.quality.QualityProbePolicy
import compress.joshattic.us.quality.SaferRungRetry
import compress.joshattic.us.quality.WindowScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARTIAL OBSERVATIONAL replay of b169 (`batch_1790428395761`) through the production Kotlin
 * policies, for the 44 target cases of the review pack (resources/replay/b169_targets.tsv, made by
 * scripts/diagnostics/make_replay_fixture.py).
 *
 * What the records support, and what they do not:
 *  - scores are three-decimal roundings: a bar comparison within 0.0005 is not decidable;
 *  - certification window frame counts were never recorded, so frame adequacy is not replayed;
 *  - the learned state the batch started from was never captured, only its identity hash. The
 *    size-gate replay uses the learned value each gate LOGGED, which is the state it actually saw;
 *  - no unmeasured rung, unattempted encode or source byte is invented. The safer-rung retry is
 *    replayed only as eligibility; whether a 0.90 encode of job_478c2fa19100 would certify is
 *    unobserved.
 */
class B169ObservationalReplayTest {

    private data class Row(val f: Map<String, String>) {
        operator fun get(k: String): String = f[k].orEmpty()
        fun d(k: String): Double? = this[k].takeIf { it.isNotEmpty() }?.toDouble()
        fun l(k: String): Long? = this[k].takeIf { it.isNotEmpty() }?.toDouble()?.toLong()
        fun i(k: String): Int? = this[k].takeIf { it.isNotEmpty() }?.toDouble()?.toInt()
        val job get() = this["jobId"]
        val terminal get() = BatchTerminalResult.valueOf(this["terminal"])
        fun source() = VideoSourceInfo(
            width = i("w")!!, height = i("h")!!, frameRate = d("fps")!!.toFloat(), durationMs = l("durationMs")!!,
            totalBitrate = i("sourceTotalBitrate")!!, audioBitrate = i("audioBitrate") ?: 0,
            videoMime = this["sourceMime"].ifEmpty { null }, audioMime = this["audioMime"].ifEmpty { null },
            audioPresent = this["audioMime"].isNotEmpty()
        )
        fun scores(k: String): List<Triple<Double, Double, Double>> = this[k].split(";").filter { it.isNotBlank() }.map {
            val (a, b, c) = it.split("/").map(String::toDouble); Triple(a, b, c)
        }
        fun factors(): List<Double> = this["gateFactors"].split(",").filter { it.isNotBlank() }.map(String::toDouble)
    }

    private val rows: List<Row> by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("replay/b169_targets.tsv")) { "fixture missing" }
        val lines = stream.bufferedReader().readLines().filter { !it.startsWith("#") && it.isNotBlank() }
        val header = lines.first().split("\t")
        lines.drop(1).map { line -> Row(header.zip(line.split("\t", limit = header.size)).toMap()) }
    }

    private val winners get() = rows.filter { it.terminal == BatchTerminalResult.TRANSCODED_SMALLER }

    /** Bar margin of rounded scores; null when within the rounding and so not decidable. */
    private fun roundedMargin(scores: List<Triple<Double, Double, Double>>): Double? {
        // barMargin reads only mean/p5/min; frame adequacy is not recorded and is not replayed here.
        val m = QualityProbePolicy.barMargin(scores.map { (a, b, c) -> WindowScore(0, a, b, c) })
        return m.takeIf { kotlin.math.abs(it) > 0.0005 }
    }

    @Test
    fun theFixtureReproducesTheRecordedBaseline() {
        assertEquals(44, rows.size)
        assertEquals(23, winners.size)
        // The 23 wins of the complete PL run hold all of its savings.
        assertEquals(818_443_848L, winners.sumOf { it.l("savedBytes")!! })
        assertEquals(setOf("job_478c2fa19100", "job_c92a4ca7e1be"), rows.filter { it["caseClass"] == "final_certification_rejected" }.map { it.job }.toSet())
    }

    @Test
    fun theResolvedPlanRequestsWhatEveryEncodeRecorded() {
        val encoded = rows.filter { it.d("plannedTargetVideoBitrate") != null && it.d("plannedTargetRatio") != null && it["plannedOutputMime"].isNotEmpty() }
        var checked = 0
        for (r in encoded) {
            val plan = ResolvedEncodePlan.resolve(
                source = r.source(), mode = BatchQualityMode.PERCEPTUAL_LOSSLESS, outputMime = r["plannedOutputMime"],
                outputFps = null, outputHeight = r.i("h")!!, targetRatio = r.d("plannedTargetRatio"),
                pixelProvenRatioFloor = r.d("pixelProvenRatio")
            )
            assertEquals(r.job, r.i("plannedTargetVideoBitrate"), plan.requestedVideoBitrate)
            checked++
        }
        // Coverage: every winner, both certification failures and the two structural/size rejections.
        assertTrue("checked $checked", checked >= 27)
        assertTrue(winners.all { w -> encoded.any { it.job == w.job } })
    }

    @Test
    fun everyLoggedSizeGateReplaysWithTheLearnedValueItSaw() {
        val gated = rows.filter { it["gateVerdict"].isNotEmpty() && it.d("pixelProvenRatio") != null }
        assertEquals(29, rows.count { it["gateVerdict"].isNotEmpty() })
        for (r in gated) {
            val proven = r.d("pixelProvenRatio")!!
            val overshoot = MeasuredOvershoot.forPrediction(r.d("gateLearned")!!, r.factors())
            val predicted = BatchQualityBitratePolicy.predictedPerceptualLosslessBytes(
                source = r.source(), outputMimeType = r["plannedOutputMime"].ifEmpty { "video/hevc" },
                learnedTargetRatio = proven, expectedOvershootFactor = overshoot, pixelProvenRatioFloor = proven
            )
            // Factors and the learned value are logged to three decimals.
            assertEquals(r.job, r.d("gatePredicted")!!, predicted.toDouble(), r.d("gatePredicted")!! * 0.002)
            val worth = ExhaustivePerceptualLosslessPolicy.worthEncoding(
                r.l("sourceSize")!!, predicted, exhaustive = true,
                meetsNoiseThreshold = BatchQualityBitratePolicy.meetsMinimumUsefulSavings(r.l("sourceSize")!!, predicted)
            )
            assertEquals(r.job, r["gateVerdict"] == "encode", worth)
        }
        // Every one of the 23 winners passes its gate, including the restored 458a; 732f stays skipped.
        assertTrue(winners.all { w -> gated.any { it.job == w.job && it["gateVerdict"] == "encode" } })
        assertEquals("encode", rows.single { it.job == "job_458aa0663c3e" }["gateVerdict"])
        assertEquals("keep original", rows.single { it.job == "job_732f7ecfb699" }["gateVerdict"])
    }

    @Test
    fun certificationBarReplayMatchesTheRecordsAndProtectsEveryWinner() {
        val certified = rows.filter { it["certificationStatus"] == "ran_scored" && it["certWindowScores"].isNotEmpty() }
        for (r in certified) {
            val margin = roundedMargin(r.scores("certWindowScores"))
            assertNotNull("${r.job} is within rounding of the bar", margin)
            assertEquals(r.job, r["pixelCertified"] == "true", margin!! > 0)
        }
        // No new rule can turn a winner's passing certification into a skip: each clears the bar.
        assertTrue(winners.all { w -> certified.any { it.job == w.job } })
        // The two failures are measured (frame adequacy aside, which was not recorded).
        assertEquals(-0.077, roundedMargin(rows.single { it.job == "job_478c2fa19100" }.scores("certWindowScores"))!!, 1e-9)
        assertEquals(-0.279, roundedMargin(rows.single { it.job == "job_c92a4ca7e1be" }.scores("certWindowScores"))!!, 1e-9)
    }

    @Test
    fun finalAcceptanceRejectsTheTwoContradictoryRecordsAndKeepsEveryWin() {
        var accepted = 0
        var saved = 0L
        for (r in rows) {
            val structural = r["verdict"].takeIf { it.isNotEmpty() }?.let {
                OutputVerificationReport(
                    verdict = it, playability = "", video = "", fps = "", videoBitrate = "", videoCodec = "",
                    audioCodec = "", audioDetails = "", audioBitrate = "", hdr = "", colorStandard = "", colorRange = "",
                    mediaStoreDate = "", mp4Date = "", location = "", rotation = "", fileSize = "",
                    replacementSafe = r["replacementSafe"] == "true", verified = r["verified"] == "true"
                )
            }
            val a = FinalAcceptance.of(r.terminal, structural, r.l("outputSize") ?: 0L, null)
            if (r.job == "job_478c2fa19100" || r.job == "job_c92a4ca7e1be") {
                // The b169 records say verified=true, replacementSafe=true.
                assertEquals("true", r["verified"])
                assertEquals("true", r["replacementSafe"])
                assertFalse(a.accepted)
                assertFalse(a.replacementSafe)
                assertTrue(a.verdict!!.startsWith("Rejected"))
            }
            if (a.accepted && r.terminal.countsAsRealCompression) {
                accepted++
                saved += r.l("savedBytes")!!
                assertTrue(r.job, a.replacementSafe)
            }
        }
        assertEquals(23, accepted)
        assertEquals(818_443_848L, saved)
    }

    @Test
    fun theRetryIsReplayedAsEligibilityOnlyAndOnlyFor478c() {
        fun saferPassing(r: Row): Double? {
            // The ladder refines below a rung only after that rung PASSED with the margin, so for a
            // "(refined)" result the rung probed just before the refined one is a measured pass.
            if (!r["probeDetail"].endsWith("(refined)")) return null
            val probed = r["probedRatios"].split(",").map(String::toDouble)
            return probed.getOrNull(probed.size - 2)
        }
        val failures = rows.filter { it["caseClass"] == "final_certification_rejected" }
        val decisions = failures.associate { r ->
            val safer = saferPassing(r)
            val learned = r.d("gateLearned") ?: 1.0
            // Fresh size check at the safer rung with that rung's own probe factors.
            val factors = safer?.let { s ->
                Regex("""${"%.2f".format(java.util.Locale.US, s)}=([^;]*)""").find(r["probeRateDiag"])?.groupValues?.get(1)
                    ?.let { Regex(""",x([0-9.]+)]""").findAll(it).map { m -> m.groupValues[1].toDouble() }.toList() }
            }.orEmpty()
            val predicted = safer?.let { s ->
                BatchQualityBitratePolicy.predictedPerceptualLosslessBytes(
                    r.source(), r["plannedOutputMime"], s, MeasuredOvershoot.forPrediction(learned, factors), s
                )
            } ?: 0L
            r.job to SaferRungRetry.decide(
                SaferRungRetry.Input(
                    enabled = true, decision = CertificationDecision.MEASURED_FAILURE, usedRatio = r.d("pixelProvenRatio")!!,
                    saferPassingRatio = safer, sameSourceAndConfig = true, retriesThisItem = 0, retriesThisBatch = 0,
                    predictedBytes = predicted,
                    worthEncoding = safer != null && predicted < r.l("sourceSize")!! &&
                        BatchQualityBitratePolicy.meetsMinimumUsefulSavings(r.l("sourceSize")!!, predicted),
                    itemElapsedMs = r.l("elapsedMs") ?: 0L, lastEncodeMs = 0L, thermalStatus = null, freeBytes = null,
                    sourceBytes = r.l("sourceSize")!!
                )
            )
        }
        // Replay-derived: 478c is eligible at 0.90 (its outcome is unobserved); c92a has nothing above 0.97.
        assertEquals(SaferRungRetry.Decision.Allowed(0.90), decisions["job_478c2fa19100"])
        assertEquals(SaferRungRetry.Decision.Denied("no_measured_safer_rung"), decisions["job_c92a4ca7e1be"])
        assertNull(saferPassing(rows.single { it.job == "job_c92a4ca7e1be" }))
    }
}
