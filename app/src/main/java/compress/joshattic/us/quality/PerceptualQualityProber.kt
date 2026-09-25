package compress.joshattic.us.quality

import compress.joshattic.us.DiagLog
import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import compress.joshattic.us.EncoderExperiments
import compress.joshattic.us.ExportInputProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of one probe ladder run. */
data class ProbeDecision(
    val provenRatio: Double?,
    val probedRatios: List<Double>,
    // Window scores of the last MEASURED rung: the proven rung's scores on a pass, or the last
    // measured (failing) rung's scores on a rejection — so captures carry the real numbers that
    // justified the decision either way. Null only when nothing could be measured.
    val windowScores: List<WindowScore>?,
    val detail: String,
    // True when the HIGHEST candidate ratio (the codec default) was measured — not merely
    // unmeasurable — and its windows fell below the acceptance thresholds. This is positive
    // pixel evidence that no allowed ratio can encode the clip transparently, which justifies
    // skipping the item entirely instead of writing a useless stream copy.
    val highestCandidateMeasuredRejected: Boolean = false,
    // How many ladder rungs actually produced window scores, and why the rest did not.
    //
    // Without these the ladder could not say whether "no candidate ratio passed" meant "every
    // rung was measured and rejected" or "nothing was ever measured". Across the five 219-file
    // captures, 84 of 425 ladder runs (19.8%) reported the former while measuring NOTHING —
    // and a reader (or a calibration) that treats those as pixel evidence of incompressibility
    // is reading noise as signal. [detail] now distinguishes them; these carry the counts.
    val rungsMeasured: Int = 0,
    val rungsMisaligned: Int = 0,
    val rungsUnavailable: Int = 0,
    // True when [provenRatio] cleared the certification bar but not the probe selection margin
    // (QualityProbePolicy.PROBE_SELECTION): no safer rung did better, so this one is attempted
    // and certification decides. Recorded so a capture can count how such attempts fare.
    val marginal: Boolean = false,
    // Per-rung probe clip bitrate telemetry (ProbeClipBitrate.compact), in ladder order.
    val rateDiag: String? = null
) {
    /** True when the ladder ran but not one rung yielded a single scored window. */
    val nothingMeasured: Boolean get() = rungsMeasured == 0 && (rungsMisaligned + rungsUnavailable) > 0
}

/**
 * The encoder request both the probes and the real encode share, so a probe is evidence about the
 * encode it vouches for: everything except the bitrate. b161 showed what happens otherwise (probes
 * in VBR vouching for CBR encodes); the keyframe interval and B-frame request are the same kind of
 * thing.
 */
data class ProbeEncodeShape(
    val bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
    val iFrameIntervalSeconds: Float = KeyframeIntervalPolicy.WHEN_UNKNOWN_SECONDS,
    val maxBFrames: Int = 0
) {
    val isCbr: Boolean get() = bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR

    fun compact(): String =
        "mode=${if (isCbr) "CBR" else "VBR"};gop=${KeyframeIntervalPolicy.describe(iFrameIntervalSeconds)}" +
            EncoderExperiments.describe(maxBFrames)
}

/**
 * Probe-based per-clip Perceptually Lossless targeting: encodes short clipped windows of the
 * source at candidate bitrate ratios with the SAME hardware pipeline as real encodes (Media3
 * Transformer, VBR, same output codec), scores each probe against the source windows with
 * on-device VMAF (phone model), and returns the lowest ratio whose windows all pass
 * [QualityProbePolicy] thresholds.
 *
 * SDR-only by contract (VMAF is not calibrated for PQ/HLG) and only for strictly more
 * efficient output codecs — the callers enforce both. Every failure path returns "no
 * evidence" and leaves the conservative gate decision unchanged.
 */
// Every Media3 Transformer entry point this class uses is marked @UnstableApi. The rest of the
// app already opts in per class (BatchCompressorViewModel, CompressorViewModel); this file was
// the one Transformer caller that never did, which is why it carried 33 of the project's 35
// UnsafeOptInUsageError lint errors on its own.
@androidx.annotation.OptIn(UnstableApi::class)
class PerceptualQualityProber(private val context: Context) {

    companion object {
        private const val TAG = "CompressorProbe"
        // Shared with ExportWatchdogPolicy so the two bounds cannot drift: Media3's muxer
        // watchdog must stay ABOVE this, or it pre-empts the recoverable timeout path and a
        // stalled probe is reported as a muxer error instead of naming itself.
        private const val PROBE_EXPORT_TIMEOUT_MS =
            compress.joshattic.us.ExportWatchdogPolicy.PROBE_EXPORT_TIMEOUT_MS
        private const val TOTAL_BUDGET_MS = 150_000L
        private const val EXPORT_TIMEOUT_PREFIX = "export timed out after"
    }

    /**
     * @param targetBitrateForRatio maps a candidate ratio to the exact video bitrate the real
     *   encode would request (the caller supplies the policy computation so probe and encode
     *   can never diverge).
     */
    suspend fun runLadder(
        sourceUri: Uri,
        durationMs: Long,
        outputMime: String,
        candidateRatios: List<Double>,
        targetBitrateForRatio: (Double) -> Int,
        audioBitrate: Int,
        // False for the short above-1080p ladder (ExhaustivePerceptualLosslessPolicy): a passing
        // rung is returned as-is instead of spending one more 4K probe encode on a bisection.
        allowDownwardRefinement: Boolean = true,
        // The request shape the REAL encode will use. A probe is evidence about that encode only
        // if it is encoded the same way. Debug builds ran the full encode in CBR (the experimental
        // ceiling, ExperimentalEncoderControls) while probes were always VBR, so every probe pass
        // in b161 vouched for an encode that was never shipped.
        shape: ProbeEncodeShape = ProbeEncodeShape(),
        // Wall-clock budget; the short ladder gets more (ExhaustivePerceptualLosslessPolicy).
        budgetMs: Long = ExhaustivePerceptualLosslessPolicy.PROBE_BUDGET_MS,
        // Source frame rate, for the probe clip bitrate model (telemetry only).
        sourceFps: Double = 0.0
    ): ProbeDecision {
        if (!VmafNative.isAvailable) return ProbeDecision(null, emptyList(), null, "vmaf unavailable")
        if (QualityProbePolicy.probeWindows(durationMs * 1000L).isEmpty()) {
            return ProbeDecision(null, emptyList(), null, "clip too short to probe")
        }
        val plan = planWindows(sourceUri, durationMs)
        val windows = plan.windows
        if (windows.isEmpty()) {
            return ProbeDecision(
                null, emptyList(), null,
                "no probe window could be placed: " + plan.unplaceable.joinToString("; ") { it.reason }
            )
        }

        val startedAt = System.currentTimeMillis()
        fun overBudget() = System.currentTimeMillis() - startedAt > budgetMs
        val probed = mutableListOf<Double>()
        val highestCandidate = candidateRatios.maxOrNull()
        var highestMeasuredRejected = false
        var highestMarginal = false
        var highestFailedBelow: Double? = null
        var lastMeasuredScores: List<WindowScore>? = null
        // The highest rung that cleared the bar but not the selection margin. Attempted only when
        // no rung clears the margin; see QualityProbePolicy.PROBE_SELECTION.
        var marginalRatio: Double? = null
        var marginalScores: List<WindowScore>? = null
        // Rung accounting, so a ladder that measured nothing can never be reported as one that
        // measured everything and rejected it.
        var measured = 0
        var misaligned = 0
        var unavailable = 0
        val unavailableReasons = linkedMapOf<String, Int>()
        val misalignedReasons = linkedMapOf<String, Int>()
        val rateDiags = mutableListOf<String>()
        fun countRung(ratio: Double, r: RungResult) {
            when (r) {
                is RungResult.Measured -> {
                    measured++
                    r.rateDiag?.let { rateDiags += "%.2f=%s".format(java.util.Locale.US, ratio, it) }
                }
                is RungResult.Misaligned -> {
                    misaligned++
                    misalignedReasons[r.reason] = (misalignedReasons[r.reason] ?: 0) + 1
                }
                is RungResult.Unavailable -> {
                    unavailable++
                    unavailableReasons[r.reason] = (unavailableReasons[r.reason] ?: 0) + 1
                }
            }
        }
        fun rateDiag() = rateDiags.takeIf { it.isNotEmpty() }?.joinToString(";")
        fun scoresOf(r: RungResult) = (r as? RungResult.Measured)?.scores
        fun proven(ratio: Double, scores: List<WindowScore>?, how: String) = ProbeDecision(
            ratio, probed, scores, "windows passed at %.2f%s".format(ratio, how),
            false, measured, misaligned, unavailable, rateDiag = rateDiag()
        )
        fun exhausted(detail: String) = ProbeDecision(
            null, probed, lastMeasuredScores, detail, highestMeasuredRejected,
            measured, misaligned, unavailable, rateDiag = rateDiag()
        )
        // A rung that cleared the bar by less than the selection margin is not thrown away: if no
        // safer rung clears the margin, the highest such rung is attempted and the full-output
        // certification decides. The label says so, so a capture can count how these fare.
        fun marginalFallback(): ProbeDecision? {
            val ratio = marginalRatio ?: return null
            val by = marginalScores?.let { QualityProbePolicy.barMargin(it) } ?: 0.0
            DiagLog.i(
                TAG,
                "no rung cleared the selection margin; attempting the highest marginal pass %.2f (cleared the bar by %.2f); certification decides"
                    .format(ratio, by)
            )
            return ProbeDecision(
                ratio, probed, marginalScores,
                "windows passed at %.2f by only %.2f, below the selection margin (%.1f/%.2f/%.1f); certification decides".format(
                    ratio, by,
                    QualityProbePolicy.PROBE_SELECTION_MARGIN_MEAN,
                    QualityProbePolicy.PROBE_SELECTION_MARGIN_P5,
                    QualityProbePolicy.PROBE_SELECTION_MARGIN_MIN
                ),
                false, measured, misaligned, unavailable, marginal = true, rateDiag = rateDiag()
            )
        }
        for (ratio in candidateRatios) {
            if (overBudget()) {
                val elapsed = System.currentTimeMillis() - startedAt
                when (ExhaustivePerceptualLosslessPolicy.overBudgetAction(ratio, highestCandidate, elapsed, budgetMs)) {
                    ExhaustivePerceptualLosslessPolicy.OverBudget.SKIP_TO_SAFEST -> {
                        DiagLog.i(TAG, "probe budget spent after ${elapsed / 1000}s; skipping %.2f to measure the safest rung".format(ratio))
                        continue
                    }
                    ExhaustivePerceptualLosslessPolicy.OverBudget.PROBE_SAFEST ->
                        DiagLog.i(TAG, "probe budget spent after ${elapsed / 1000}s; measuring the safest rung %.2f so the decision rests on a measurement".format(ratio))
                    ExhaustivePerceptualLosslessPolicy.OverBudget.STOP ->
                        return marginalFallback() ?: exhausted("probe budget exhausted")
                }
            }
            probed += ratio
            val rung = probeOneRatio(sourceUri, outputMime, ratio, targetBitrateForRatio(ratio), audioBitrate, windows, shape, sourceFps)
            countRung(ratio, rung)
            if (rung is RungResult.Unavailable && rung.reason.startsWith(EXPORT_TIMEOUT_PREFIX)) {
                describeKeyframes(sourceUri)?.let { DiagLog.w(TAG, "probe export timed out; source $it") }
                // Every rung exports the same windows, and a clip that took over a minute to cut
                // at one bitrate will not cut faster at another. The two 30-minute sources in
                // batch_1790263711162 spent 60 s on each of three rungs this way before the
                // budget ran out. Stop at the first timeout; nothing was measured either way.
                return marginalFallback() ?: exhausted(
                    QualityProbePolicy.ladderExhaustedDetail(measured, misaligned, unavailable, unavailableReasons, misalignedReasons) +
                        "; remaining rungs skipped after a probe export timeout"
                )
            }
            val scores = scoresOf(rung)
            if (!scores.isNullOrEmpty()) lastMeasuredScores = scores
            when (QualityProbePolicy.rungVerdict(scores)) {
                QualityProbePolicy.RungVerdict.PASSED -> {
                    DiagLog.i(
                        TAG,
                        "ratio %.2f pixel-proven over ${windows.size} windows (cleared the bar by %.2f)"
                            .format(ratio, QualityProbePolicy.barMargin(scores!!))
                    )
                    // One bounded bisection between this pass and the measured failure below it:
                    // an extra probe encode may reclaim up to half the rung gap in real savings.
                    // The passing result above is ALWAYS kept as the fallback; a failed, marginal
                    // or unmeasurable refinement changes nothing.
                    val refined = if (allowDownwardRefinement) {
                        QualityProbePolicy.refinementCandidate(ratio, highestFailedBelow)
                    } else {
                        null
                    }
                    if (refined != null && !overBudget()) {
                        probed += refined
                        val refinedRung = probeOneRatio(
                            sourceUri, outputMime, refined, targetBitrateForRatio(refined), audioBitrate, windows, shape, sourceFps
                        )
                        countRung(refined, refinedRung)
                        val refinedScores = scoresOf(refinedRung)
                        when (QualityProbePolicy.rungVerdict(refinedScores)) {
                            QualityProbePolicy.RungVerdict.PASSED -> {
                                DiagLog.i(TAG, "refinement %.2f pixel-proven (bisection below %.2f)".format(refined, ratio))
                                return proven(refined, refinedScores, " (refined)")
                            }
                            QualityProbePolicy.RungVerdict.MARGINAL -> DiagLog.i(
                                TAG,
                                "refinement %.2f cleared the bar by only %.2f, below the selection margin; keeping proven %.2f"
                                    .format(refined, QualityProbePolicy.barMargin(refinedScores!!), ratio)
                            )
                            else -> DiagLog.i(TAG, "refinement %.2f rejected; keeping proven %.2f".format(refined, ratio))
                        }
                    }
                    return proven(ratio, scores, "")
                }
                QualityProbePolicy.RungVerdict.MARGINAL -> {
                    marginalRatio = ratio
                    marginalScores = scores
                    highestFailedBelow = ratio
                    if (ratio == highestCandidate) highestMarginal = true
                    DiagLog.i(
                        TAG,
                        "ratio %.2f cleared the bar by only %.2f, below the selection margin; trying the next rung"
                            .format(ratio, QualityProbePolicy.barMargin(scores!!))
                    )
                }
                QualityProbePolicy.RungVerdict.FAILED -> {
                    highestFailedBelow = ratio
                    if (ratio == highestCandidate) {
                        // Measured (not merely unmeasurable) rejection at the safest candidate.
                        highestMeasuredRejected = true
                    }
                    DiagLog.i(TAG, "ratio %.2f rejected by probe windows (measured=true)".format(ratio))
                }
                QualityProbePolicy.RungVerdict.UNMEASURED ->
                    DiagLog.i(TAG, "ratio %.2f rejected by probe windows (measured=false)".format(ratio))
            }
        }
        // Upward near-miss refinement: the whole ladder failed, but if the SAFEST rung only just
        // missed, one more probe at the safest useful ceiling (a higher, more conservative rung) may
        // clear it, recovering a genuine near-transparent saving (typically cross-codec H.264->HEVC)
        // that the fixed ladder stopped one step short of. Only spent on an actual near-miss, so a
        // clip that failed by a lot never pays for a doomed extra encode.
        // Also spent when the safest rung was only marginal: the ceiling is the best chance of a
        // pass that clears the selection margin as well as the bar.
        if ((highestMeasuredRejected || highestMarginal) && highestCandidate != null && !overBudget()) {
            val upward = if (highestMeasuredRejected) {
                QualityProbePolicy.upwardRefinementCandidate(highestCandidate, lastMeasuredScores)
            } else {
                QualityProbePolicy.upwardMarginCandidate(highestCandidate)
            }
            if (upward != null && upward !in probed) {
                probed += upward
                val upRung = probeOneRatio(
                    sourceUri, outputMime, upward, targetBitrateForRatio(upward), audioBitrate, windows, shape, sourceFps
                )
                countRung(upward, upRung)
                val upScores = scoresOf(upRung)
                when (QualityProbePolicy.rungVerdict(upScores)) {
                    QualityProbePolicy.RungVerdict.PASSED -> {
                        DiagLog.i(TAG, "upward near-miss refinement %.2f pixel-proven (safest rung %.2f just missed)".format(upward, highestCandidate))
                        return proven(upward, upScores, " (upward near-miss refinement)")
                    }
                    QualityProbePolicy.RungVerdict.MARGINAL -> {
                        marginalRatio = upward
                        marginalScores = upScores
                        DiagLog.i(
                            TAG,
                            "upward near-miss refinement %.2f cleared the bar by only %.2f, below the selection margin"
                                .format(upward, QualityProbePolicy.barMargin(upScores!!))
                        )
                    }
                    else -> {
                        DiagLog.i(TAG, "upward near-miss refinement %.2f rejected; source cannot be transparently re-encoded".format(upward))
                        if (!upScores.isNullOrEmpty()) lastMeasuredScores = upScores
                    }
                }
            }
        }
        return marginalFallback() ?: exhausted(
            QualityProbePolicy.ladderExhaustedDetail(measured, misaligned, unavailable, unavailableReasons, misalignedReasons)
        )
    }

    /** Scores one candidate ratio across all windows; returns a [RungResult] describing the outcome. */
    private suspend fun probeOneRatio(
        sourceUri: Uri,
        outputMime: String,
        ratio: Double,
        videoBitrate: Int,
        audioBitrate: Int,
        windows: List<ProbeWindowPlanner.PlannedWindow>,
        shape: ProbeEncodeShape,
        sourceFps: Double
    ): RungResult {
        val collected = mutableListOf<WindowScore>()
        val rates = mutableListOf<String>()
        fun rateDiag() = rates.takeIf { it.isNotEmpty() }?.joinToString("|")
        for (window in windows) {
            val probeFile = File.createTempFile("probe_${"%.2f".format(ratio)}_", ".mp4", context.cacheDir)
            try {
                // withTimeoutOrNull collapses its own timeout into null, so exportClip must NEVER
                // use null to mean anything itself: a nullable "failure reason" made a successful
                // export (null reason) indistinguishable from a timeout, and every probe would
                // have been reported as timing out. The sealed result keeps the two apart by
                // construction.
                val exported = withTimeoutOrNull(PROBE_EXPORT_TIMEOUT_MS) {
                    exportClip(sourceUri, probeFile, outputMime, videoBitrate, audioBitrate, window, shape)
                }
                if (exported == null) {
                    // What the INPUT side of the export was doing when the clock ran out; see
                    // ExportInputProbeReport for the three answers this separates.
                    lastExportInput?.let { DiagLog.w(TAG, "probe export timed out; ${it.snapshot()}") }
                    return RungResult.Unavailable("$EXPORT_TIMEOUT_PREFIX ${PROBE_EXPORT_TIMEOUT_MS}ms")
                }
                if (exported is ExportOutcome.Failed) return RungResult.Unavailable(exported.reason)
                // Verify, rather than assume, that the clip holds the window we asked for. See
                // ProbeClipGeometry: if the clip does not start at the requested instant, the
                // scorer pairs correct timestamps against the wrong pixels and no downstream
                // record can tell. Diagnostic only — this never changes a decision.
                withContext(Dispatchers.IO) {
                    ProbeClipGeometry.describe(probeFile, window.clipStartUs, window.endUs)
                }?.let { DiagLog.i(TAG, "$it; leadInMs=${window.leadInUs / 1000}; anchor=${window.anchor}") }
                // The clip's sample sizes, as a bitrate model for the full encode (telemetry only;
                // see ProbeClipBitrate). Past the lead-in so rate-control warm-up is excluded.
                withContext(Dispatchers.IO) { ProbeClipBitrate.measure(probeFile, window.leadInUs) }?.let { split ->
                    val line = ProbeClipBitrate.compact(videoBitrate, split, sourceFps, shape.iFrameIntervalSeconds)
                    rates += line
                    DiagLog.i(
                        TAG,
                        "probe rate; ratio=%.2f; window=[%d..%dms]; %s".format(
                            java.util.Locale.US, ratio, window.startUs / 1000, window.endUs / 1000, line
                        )
                    )
                }
                val outcome = withContext(Dispatchers.IO) {
                    VmafPairScorer.score(
                        context,
                        ref = sourceUri,
                        dist = Uri.fromFile(probeFile),
                        // The clip starts at the planned keyframe (its first frame, written at
                        // time 0) and runs through the window. The two first frames are paired
                        // as origins, the lead-in is paired but not scored, and only the window
                        // is fed to VMAF. See ProbeWindowPlanner and ScoreWindow.leadInUs.
                        windows = listOf(window.scoreWindowForProbeClip())
                    )
                }
                val scores = when (outcome) {
                    is PairScoreOutcome.Scored -> outcome.windows
                    // Either way this rung has no pixel evidence: an unalignable PROBE clip
                    // says the probe pipeline broke, not that the source degrades. Reporting it
                    // as its own outcome keeps the conservative gate decision, never counts as a
                    // measured rejection (no probe-skip latch feeding), and — unlike the previous
                    // bare null — lets the ladder tell a capture WHY it has no numbers.
                    is PairScoreOutcome.MisalignmentRejected -> {
                        val why = outcome.reason ?: "cause not reported by the aligner"
                        DiagLog.w(TAG, "probe window rejected: clip/source frames not time-alignable — $why")
                        return RungResult.Misaligned(why)
                    }
                    PairScoreOutcome.Unavailable -> return RungResult.Unavailable("scorer produced no evidence")
                }
                collected += scores
                // Early exit: one failing window already rejects this ratio.
                if (!QualityProbePolicy.windowsPass(scores)) return RungResult.Measured(collected, rateDiag())
            } finally {
                runCatching { probeFile.delete() }
            }
        }
        return RungResult.Measured(collected, rateDiag())
    }

    /**
     * What one ladder rung produced. Replaces a nullable score list, which collapsed three very
     * different situations — measured, frames not time-alignable, and evidence unavailable — into
     * a single `null` that the ladder then reported as "no candidate ratio passed".
     */
    /**
     * Outcome of one probe-clip export. Deliberately NOT a nullable reason string: the caller wraps
     * this in `withTimeoutOrNull`, which already uses null for its own timeout, so any null the
     * export produces would be read as a timeout instead.
     */
    private sealed interface ExportOutcome {
        object Success : ExportOutcome
        data class Failed(val reason: String) : ExportOutcome
    }

    private sealed interface RungResult {
        data class Measured(val scores: List<WindowScore>, val rateDiag: String? = null) : RungResult
        /**
         * The probe clip and the source could not be paired in time. Not a quality result.
         *
         * [reason] distinguishes a probe-pipeline addressing failure ("leading offset not aligned")
         * from real frame loss inside the window ("internal frame misalignment"). Both fail closed,
         * but only the second says anything about the source.
         */
        data class Misaligned(val reason: String) : RungResult
        /**
         * No evidence could be produced at all. [reason] separates the causes, which behave very
         * differently: an export timeout is a budget problem that scales with source length, an
         * export failure is a pipeline problem, and a scorer with no evidence is a decode or
         * geometry problem. The first ee6853b captures showed 28 unavailable rungs concentrated on
         * the four longest sources in the corpus (52, 32, 31 and 28 minutes) — a pattern that is
         * only actionable once the cause is named.
         */
        data class Unavailable(val reason: String) : RungResult
    }

    private suspend fun exportClip(
        sourceUri: Uri,
        outputFile: File,
        outputMime: String,
        videoBitrate: Int,
        audioBitrate: Int,
        window: ProbeWindowPlanner.PlannedWindow,
        shape: ProbeEncodeShape
    ): ExportOutcome = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val videoSettings = VideoEncoderSettings.Builder()
                .setBitrate(videoBitrate)
                .setBitrateMode(shape.bitrateMode)
                // Same keyframe interval as the real encode (KeyframeIntervalPolicy); Media3's
                // 1 s default would make the probe clip spend more of its bits on keyframes than
                // the encode it vouches for.
                .setiFrameIntervalSeconds(shape.iFrameIntervalSeconds)
            if (shape.maxBFrames > 0) videoSettings.setMaxBFrames(shape.maxBFrames)
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                // Mirrors compressOne: the CBR experiment disables Media3's format fallback so an
                // encode is either exactly what was requested or fails.
                .setEnableFallback(!shape.isCbr)
                .setRequestedVideoEncoderSettings(videoSettings.build())
                .build()
            // Counts what the asset loader reads, so a timeout can say whether the input side was
            // still delivering (ExportInputProbeReport).
            val inputProbe = ExportInputProbe(DefaultDataSource.Factory(context))
            lastExportInput = inputProbe
            val mediaItem = MediaItem.Builder()
                .setUri(sourceUri)
                .setClippingConfiguration(
                    // Microsecond precision, NOT setStartPositionMs. The scorer normalises the
                    // reference side by `pts - window.startUs` while the probe clip restarts at 0,
                    // so any difference between the requested and the actual clip start becomes
                    // per-window pairing skew. Millisecond granularity introduced a systematic
                    // `-(startUs % 1000)` offset — up to 999 us, and measured on device as exactly
                    // that: windows at 0.2/0.5/0.8 ms skew for a clip whose window starts ended in
                    // 200/500/800 us. Requesting the same microseconds the scorer normalises by
                    // removes that term at the source instead of widening a tolerance to absorb it.
                    //
                    // This does NOT remove the larger residual that appears when the trimmed clip
                    // lands on a different frame than the requested instant; that is a separate
                    // mechanism and PtsAligner's tolerance still guards it.
                    // The clip starts at the planned KEYFRAME, not at the window: the lead-in
                    // between them is encoded so the scored frames come from steady-state rate
                    // control, and starting on a keyframe means nothing before it is decoded.
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(window.clipStartUs)
                        .setEndPositionUs(window.endUs)
                        .setStartsAtKeyFrame(window.anchor != ProbeWindowPlanner.Anchor.UNINDEXED)
                        .build()
                )
                .build()
            val edited = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(true) // probes judge video pixels only; audio is stream-copied in PL
                .build()
            val transformer = Transformer.Builder(context)
                // See ExportWatchdogPolicy. The prober's own PROBE_EXPORT_TIMEOUT_MS (60s) fires
                // first for probe clips; this only stops the watchdog pre-empting it with a
                // process-killing throw instead of the recoverable timeout path.
                .setMaxDelayBetweenMuxerSamplesMs(
                    compress.joshattic.us.ExportWatchdogPolicy.MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS
                )
                .setVideoMimeType(outputMime)
                .setAssetLoaderFactory(
                    ExoPlayerAssetLoader.Factory(
                        context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT, inputProbe.mediaSourceFactory()
                    )
                )
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        // A "successful" export that wrote nothing is still a failure, and a
                        // different one from an error — say which.
                        val outcome = if (outputFile.length() > 0L) {
                            ExportOutcome.Success
                        } else {
                            ExportOutcome.Failed("export produced an empty file")
                        }
                        if (continuation.isActive) continuation.resume(outcome)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        // Media3 says exactly what went wrong here — the error code separates an
                        // unsupported decode format from an encoder init failure from an I/O
                        // problem, which are three different bugs. Discarding it (the previous
                        // `resume(false)`) is what left 10 of 16 ladders in capture
                        // batch_1788252039055 reporting a bare "export failed" with no way to act.
                        val reason = "export failed: ${exportException.getErrorCodeName()}" +
                            (exportException.message?.take(120)?.let { " — $it" } ?: "")
                        DiagLog.w(TAG, "probe export failed: $reason", exportException)
                        DiagLog.w(TAG, "probe export input; ${inputProbe.snapshot()}")
                        runCatching { outputFile.delete() }
                        if (continuation.isActive) continuation.resume(ExportOutcome.Failed(reason))
                    }
                })
                .build()
            continuation.invokeOnCancellation {
                transformer.cancel()
                runCatching { outputFile.delete() }
            }
            try {
                transformer.start(
                    Composition.Builder(listOf(EditedMediaItemSequence.Builder(edited).build())).build(),
                    outputFile.absolutePath
                )
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }
    }

    /** The last export's input counters, for the timeout path that cannot reach the export itself. */
    @Volatile
    private var lastExportInput: ExportInputProbe? = null

    /**
     * The keyframe interval to request for [sourceUri]: its own typical keyframe gap, bounded
     * (KeyframeIntervalPolicy). Logged so the capture shows what the source had and what was asked.
     */
    suspend fun sourceKeyframeIntervalSeconds(sourceUri: Uri): Float = withContext(Dispatchers.IO) {
        val index = MediaExtractorSyncIndex.open(context, sourceUri)
        val gaps = try {
            index?.keyframeGapsUs().orEmpty()
        } finally {
            index?.close()
        }
        val typical = KeyframeIntervalPolicy.typicalGapUs(gaps)
        val seconds = KeyframeIntervalPolicy.iFrameIntervalSeconds(typical)
        DiagLog.i(
            TAG,
            "keyframe interval; sourceTypicalGapMs=${typical?.let { it / 1000 } ?: "unknown"}; gapsSampled=${gaps.size}; " +
                "requesting gop=${KeyframeIntervalPolicy.describe(seconds)}"
        )
        seconds
    }

    /**
     * Plans the windows for [sourceUri] against its keyframes. Falls back to unindexed windows
     * (clip starts MIN_LEAD_IN_US before the window) when the source cannot be indexed, so a
     * source that cannot be seeked still gets a lead-in, just not a cheap one.
     */
    private suspend fun planWindows(sourceUri: Uri, durationMs: Long): ProbeWindowPlanner.Plan =
        withContext(Dispatchers.IO) {
            val index = MediaExtractorSyncIndex.open(context, sourceUri)
            try {
                val plan = ProbeWindowPlanner.plan(durationMs * 1000L, index)
                DiagLog.i(
                    TAG,
                    "window plan; windows=" + plan.windows.joinToString(",") {
                        "[${it.startUs / 1000}..${it.endUs / 1000}ms clip@${it.clipStartUs / 1000}ms ${it.anchor}]"
                    } + (if (plan.unplaceable.isEmpty()) "" else "; unplaceable=" + plan.unplaceable.joinToString(",") {
                        "${it.wantedStartUs / 1000}ms(${it.reason})"
                    })
                )
                plan
            } finally {
                index?.close()
            }
        }

    /**
     * Keyframe structure of a source, for the record when an export times out. Both 30-minute
     * sources in b163 timed out at 60 s exporting a 1.2 s clip and nothing in the capture said why.
     */
    suspend fun describeKeyframes(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        MediaExtractorSyncIndex.open(context, sourceUri)?.use { it.structure()?.compact() }
    }

    /**
     * Control tests for the measurement path itself, on one file. Three comparisons through the
     * exact decode-and-pair path the ladder and certification use:
     *
     *  1. the source against ITSELF: every scored frame must be 100 (VMAF of identical frames).
     *     Anything less is a defect in decoding, cropping, rotation or pairing, not in any encoder;
     *  2. the source against a stream copy of itself (Remux Only): the same bits in a new
     *     container, so again 100 on every frame. This exercises the container/edit-list and
     *     timestamp path that a probe clip goes through;
     *  3. the source against a generous encode of the first window (twice the source video
     *     bitrate, with the same lead-in as a probe): a ceiling for what this encoder can reach on
     *     this content. If the ladder's safest rung scores far below this, bitrate is the limit;
     *     if this ceiling is itself low, the limit is the encoder or the measurement.
     *
     * The report is written to the decision log and returned for the screen. Nothing here changes
     * a decision.
     */
    suspend fun selfCheck(
        sourceUri: Uri,
        durationMs: Long,
        sourceVideoBitrate: Int,
        outputMime: String
    ): String {
        if (!VmafNative.isAvailable) return "self-check: VMAF is not available on this device build"
        val plan = planWindows(sourceUri, durationMs)
        if (plan.windows.isEmpty()) return "self-check: no window could be placed (clip too short or no keyframe in reach)"
        val certWindows = plan.windows.map { it.scoreWindowForCertification() }
        val lines = mutableListOf<String>()
        fun describe(label: String, outcome: PairScoreOutcome, expectIdentical: Boolean = false) {
            val text = when (outcome) {
                is PairScoreOutcome.Scored -> {
                    // A control that must be identical states its own verdict, so a capture cannot
                    // read 97.43 as a pass. Identical frames with real motion score 100; VMAF v0.6.1
                    // scores identical STATIC frames 97.43 on every frame, which names itself.
                    val verdict = if (!expectIdentical) "" else {
                        val worst = outcome.windows.minOf { it.min }
                        when {
                            worst >= 99.95 -> "PASS "
                            outcome.windows.all { it.mean < 99.0 } -> "STATIC? (VMAF scores identical static frames ~97.43) "
                            else -> "FAIL (min %.2f; a scorer or pairing defect) ".format(java.util.Locale.US, worst)
                        }
                    }
                    verdict + outcome.windows.joinToString("; ") { w ->
                        "%.2f/%.2f/%.2f".format(java.util.Locale.US, w.mean, w.p5, w.min) +
                            (w.frameDiag?.let { " frames[${it.compact()}]" } ?: "") +
                            (w.pairing?.let { " pairing[${it.compact()}]" } ?: "")
                    }
                }
                is PairScoreOutcome.MisalignmentRejected -> "misaligned: ${outcome.reason}"
                PairScoreOutcome.Unavailable -> "unavailable"
            }
            lines += "$label: $text"
            DiagLog.i(TAG, "self-check $label: $text")
        }
        describe("source vs itself (expect 100 on every frame)", withContext(Dispatchers.IO) {
            VmafPairScorer.score(context, sourceUri, sourceUri, certWindows)
        }, expectIdentical = true)
        val remux = File.createTempFile("selfcheck_remux_", ".mp4", context.cacheDir)
        try {
            val remuxed = withContext(Dispatchers.IO) {
                runCatching {
                    compress.joshattic.us.Mp4MetadataRemuxer.remuxSourceWithoutReencode(
                        context, sourceUri, remux, compress.joshattic.us.VideoMetadataSnapshot()
                    )
                }
            }
            if (remuxed.isSuccess) {
                describe("source vs stream copy (expect 100 on every frame)", withContext(Dispatchers.IO) {
                    VmafPairScorer.score(context, sourceUri, Uri.fromFile(remux), certWindows)
                }, expectIdentical = true)
            } else {
                lines += "source vs stream copy: remux not possible for this container (${remuxed.exceptionOrNull()?.message})"
            }
        } finally {
            runCatching { remux.delete() }
        }
        val window = plan.windows.first()
        val clip = File.createTempFile("selfcheck_ceiling_", ".mp4", context.cacheDir)
        try {
            val generous = (sourceVideoBitrate.toLong() * 2L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val exported = withTimeoutOrNull(PROBE_EXPORT_TIMEOUT_MS) {
                exportClip(sourceUri, clip, outputMime, generous, 0, window, ProbeEncodeShape(iFrameIntervalSeconds = sourceKeyframeIntervalSeconds(sourceUri)))
            }
            when (exported) {
                null -> lines += "source vs 2x-bitrate encode: export timed out"
                is ExportOutcome.Failed -> lines += "source vs 2x-bitrate encode: ${exported.reason}"
                ExportOutcome.Success -> describe(
                    "source vs 2x-bitrate encode of window 1 (encoder ceiling; ratio 2.00, lead-in ${window.leadInUs / 1000} ms)",
                    withContext(Dispatchers.IO) {
                        VmafPairScorer.score(context, sourceUri, Uri.fromFile(clip), listOf(window.scoreWindowForProbeClip()))
                    }
                )
            }
        } finally {
            runCatching { clip.delete() }
        }
        return lines.joinToString("\n")
    }

    /**
     * Sampled pixel certification of a completed full encode against its source.
     * The tri-state outcome is load-bearing for the caller: [PairScoreOutcome.Unavailable]
     * keeps the legacy structural fallback at the default ratio, while
     * [PairScoreOutcome.MisalignmentRejected] is measured evidence the OUTPUT's frames are
     * not temporally comparable to the source (frame loss/retiming) and must always fail.
     */
    suspend fun certify(sourceUri: Uri, outputFile: File, durationMs: Long): PairScoreOutcome {
        if (!VmafNative.isAvailable) return PairScoreOutcome.Unavailable
        if (QualityProbePolicy.probeWindows(durationMs * 1000L).isEmpty()) return PairScoreOutcome.Unavailable
        // The SAME windows the ladder scored, so probe and certification scores of one file
        // compare frame for frame; and each window follows a source keyframe, so the reference
        // decode does not have to run from a keyframe minutes earlier.
        val windows = planWindows(sourceUri, durationMs).windows.map { it.scoreWindowForCertification() }
        if (windows.isEmpty()) return PairScoreOutcome.Unavailable
        return withContext(Dispatchers.IO) {
            // Banding telemetry is collected on certification only, never on ladder rungs: it is
            // extra native work per frame, and certification runs once per output while the ladder
            // runs up to four times. Recorded for calibration; no verdict reads it.
            VmafPairScorer.score(
                context, sourceUri, Uri.fromFile(outputFile), windows, collectBanding = true, shadowV1 = true
            )
        }
    }
}
