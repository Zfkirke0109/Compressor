package compress.joshattic.us.quality

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
    val rungsUnavailable: Int = 0
) {
    /** True when the ladder ran but not one rung yielded a single scored window. */
    val nothingMeasured: Boolean get() = rungsMeasured == 0 && (rungsMisaligned + rungsUnavailable) > 0
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
        private const val PROBE_EXPORT_TIMEOUT_MS = 60_000L
        private const val TOTAL_BUDGET_MS = 150_000L
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
        audioBitrate: Int
    ): ProbeDecision {
        if (!VmafNative.isAvailable) return ProbeDecision(null, emptyList(), null, "vmaf unavailable")
        val windows = QualityProbePolicy.probeWindows(durationMs * 1000L)
        if (windows.isEmpty()) return ProbeDecision(null, emptyList(), null, "clip too short to probe")

        val startedAt = System.currentTimeMillis()
        val probed = mutableListOf<Double>()
        val highestCandidate = candidateRatios.maxOrNull()
        var highestMeasuredRejected = false
        var highestFailedBelow: Double? = null
        var lastMeasuredScores: List<WindowScore>? = null
        // Rung accounting, so a ladder that measured nothing can never be reported as one that
        // measured everything and rejected it.
        var measured = 0
        var misaligned = 0
        var unavailable = 0
        val unavailableReasons = linkedMapOf<String, Int>()
        fun countRung(r: RungResult) = when (r) {
            is RungResult.Measured -> measured++
            RungResult.Misaligned -> misaligned++
            is RungResult.Unavailable -> {
                unavailable++
                unavailableReasons[r.reason] = (unavailableReasons[r.reason] ?: 0) + 1
            }
        }
        fun scoresOf(r: RungResult) = (r as? RungResult.Measured)?.scores
        for (ratio in candidateRatios) {
            if (System.currentTimeMillis() - startedAt > TOTAL_BUDGET_MS) {
                return ProbeDecision(
                    null, probed, lastMeasuredScores, "probe budget exhausted", highestMeasuredRejected,
                    measured, misaligned, unavailable
                )
            }
            probed += ratio
            val rung = probeOneRatio(sourceUri, outputMime, ratio, targetBitrateForRatio(ratio), audioBitrate, windows)
            countRung(rung)
            val scores = scoresOf(rung)
            if (!scores.isNullOrEmpty()) lastMeasuredScores = scores
            if (QualityProbePolicy.windowsPass(scores)) {
                Log.i(TAG, "ratio %.2f pixel-proven over ${windows.size} windows".format(ratio))
                // One bounded bisection between this pass and the measured failure below it:
                // an extra probe encode may reclaim up to half the rung gap in real savings.
                // The passing result above is ALWAYS kept as the fallback — a failed or
                // unmeasurable refinement changes nothing.
                val refined = QualityProbePolicy.refinementCandidate(ratio, highestFailedBelow)
                if (refined != null && System.currentTimeMillis() - startedAt <= TOTAL_BUDGET_MS) {
                    probed += refined
                    val refinedRung = probeOneRatio(
                        sourceUri, outputMime, refined, targetBitrateForRatio(refined), audioBitrate, windows
                    )
                    countRung(refinedRung)
                    val refinedScores = scoresOf(refinedRung)
                    if (QualityProbePolicy.windowsPass(refinedScores)) {
                        Log.i(TAG, "refinement %.2f pixel-proven (bisection below %.2f)".format(refined, ratio))
                        return ProbeDecision(
                            refined, probed, refinedScores, "windows passed at %.2f (refined)".format(refined),
                            false, measured, misaligned, unavailable
                        )
                    }
                    Log.i(TAG, "refinement %.2f rejected; keeping proven %.2f".format(refined, ratio))
                }
                return ProbeDecision(
                    ratio, probed, scores, "windows passed at %.2f".format(ratio),
                    false, measured, misaligned, unavailable
                )
            }
            if (!scores.isNullOrEmpty()) {
                highestFailedBelow = ratio
                if (ratio == highestCandidate) {
                    // Measured (not merely unmeasurable) rejection at the safest candidate.
                    highestMeasuredRejected = true
                }
            }
            Log.i(TAG, "ratio %.2f rejected by probe windows (measured=${!scores.isNullOrEmpty()})".format(ratio))
        }
        // Upward near-miss refinement: the whole ladder failed, but if the SAFEST rung only just
        // missed, one more probe at the safest useful ceiling (a higher, more conservative rung) may
        // clear it — recovering a genuine near-transparent saving (typically cross-codec H.264->HEVC)
        // that the fixed ladder stopped one step short of. Only spent on an actual near-miss, so a
        // clip that failed by a lot never pays for a doomed extra encode.
        if (highestMeasuredRejected && highestCandidate != null &&
            System.currentTimeMillis() - startedAt <= TOTAL_BUDGET_MS
        ) {
            val upward = QualityProbePolicy.upwardRefinementCandidate(highestCandidate, lastMeasuredScores)
            if (upward != null && upward !in probed) {
                probed += upward
                val upRung = probeOneRatio(
                    sourceUri, outputMime, upward, targetBitrateForRatio(upward), audioBitrate, windows
                )
                countRung(upRung)
                val upScores = scoresOf(upRung)
                if (QualityProbePolicy.windowsPass(upScores)) {
                    Log.i(TAG, "upward near-miss refinement %.2f pixel-proven (safest rung %.2f just missed)".format(upward, highestCandidate))
                    return ProbeDecision(
                        upward, probed, upScores, "windows passed at %.2f (upward near-miss refinement)".format(upward),
                        false, measured, misaligned, unavailable
                    )
                }
                Log.i(TAG, "upward near-miss refinement %.2f rejected; source cannot be transparently re-encoded".format(upward))
                if (!upScores.isNullOrEmpty()) lastMeasuredScores = upScores
            }
        }
        return ProbeDecision(
            null, probed, lastMeasuredScores,
            QualityProbePolicy.ladderExhaustedDetail(measured, misaligned, unavailable, unavailableReasons),
            highestMeasuredRejected, measured, misaligned, unavailable
        )
    }

    /** Scores one candidate ratio across all windows; null = evidence unavailable/failed. */
    private suspend fun probeOneRatio(
        sourceUri: Uri,
        outputMime: String,
        ratio: Double,
        videoBitrate: Int,
        audioBitrate: Int,
        windows: List<ScoreWindow>
    ): RungResult {
        val collected = mutableListOf<WindowScore>()
        for (window in windows) {
            val probeFile = File.createTempFile("probe_${"%.2f".format(ratio)}_", ".mp4", context.cacheDir)
            try {
                // withTimeoutOrNull collapses its own timeout into null, so exportClip must NEVER
                // use null to mean anything itself: a nullable "failure reason" made a successful
                // export (null reason) indistinguishable from a timeout, and every probe would
                // have been reported as timing out. The sealed result keeps the two apart by
                // construction.
                val exported = withTimeoutOrNull(PROBE_EXPORT_TIMEOUT_MS) {
                    exportClip(sourceUri, probeFile, outputMime, videoBitrate, audioBitrate, window)
                }
                if (exported == null) {
                    return RungResult.Unavailable("export timed out after ${PROBE_EXPORT_TIMEOUT_MS}ms")
                }
                if (exported is ExportOutcome.Failed) return RungResult.Unavailable(exported.reason)
                val outcome = withContext(Dispatchers.IO) {
                    VmafPairScorer.score(
                        context,
                        ref = sourceUri,
                        dist = Uri.fromFile(probeFile),
                        // The probe file contains ONLY the window, starting at 0.
                        windows = listOf(ScoreWindow(window.startUs, window.endUs, distStartUs = 0L))
                    )
                }
                val scores = when (outcome) {
                    is PairScoreOutcome.Scored -> outcome.windows
                    // Either way this rung has no pixel evidence: an unalignable PROBE clip
                    // says the probe pipeline broke, not that the source degrades. Reporting it
                    // as its own outcome keeps the conservative gate decision, never counts as a
                    // measured rejection (no probe-skip latch feeding), and — unlike the previous
                    // bare null — lets the ladder tell a capture WHY it has no numbers.
                    PairScoreOutcome.MisalignmentRejected -> {
                        Log.w(TAG, "probe window rejected: clip/source frames not time-alignable")
                        return RungResult.Misaligned
                    }
                    PairScoreOutcome.Unavailable -> return RungResult.Unavailable("scorer produced no evidence")
                }
                collected += scores
                // Early exit: one failing window already rejects this ratio.
                if (!QualityProbePolicy.windowsPass(scores)) return RungResult.Measured(collected)
            } finally {
                runCatching { probeFile.delete() }
            }
        }
        return RungResult.Measured(collected)
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
        data class Measured(val scores: List<WindowScore>) : RungResult
        /** The probe clip and the source could not be paired in time. Not a quality result. */
        object Misaligned : RungResult
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
        window: ScoreWindow
    ): ExportOutcome = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(videoBitrate)
                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        .build()
                )
                .build()
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
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(window.startUs)
                        .setEndPositionUs(window.endUs)
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
                        Log.w(TAG, "probe export failed: $reason", exportException)
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

    /**
     * Sampled pixel certification of a completed full encode against its source.
     * The tri-state outcome is load-bearing for the caller: [PairScoreOutcome.Unavailable]
     * keeps the legacy structural fallback at the default ratio, while
     * [PairScoreOutcome.MisalignmentRejected] is measured evidence the OUTPUT's frames are
     * not temporally comparable to the source (frame loss/retiming) and must always fail.
     */
    suspend fun certify(sourceUri: Uri, outputFile: File, durationMs: Long): PairScoreOutcome {
        if (!VmafNative.isAvailable) return PairScoreOutcome.Unavailable
        val windows = QualityProbePolicy.probeWindows(durationMs * 1000L)
        if (windows.isEmpty()) return PairScoreOutcome.Unavailable
        return withContext(Dispatchers.IO) {
            // Banding telemetry is collected on certification only, never on ladder rungs: it is
            // extra native work per frame, and certification runs once per output while the ladder
            // runs up to four times. Recorded for calibration; no verdict reads it.
            VmafPairScorer.score(
                context, sourceUri, Uri.fromFile(outputFile), windows, collectBanding = true
            )
        }
    }
}
