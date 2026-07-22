package compress.joshattic.us.quality

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
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
    val highestCandidateMeasuredRejected: Boolean = false
)

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
        for (ratio in candidateRatios) {
            if (System.currentTimeMillis() - startedAt > TOTAL_BUDGET_MS) {
                return ProbeDecision(null, probed, lastMeasuredScores, "probe budget exhausted", highestMeasuredRejected)
            }
            probed += ratio
            val scores = probeOneRatio(sourceUri, outputMime, ratio, targetBitrateForRatio(ratio), audioBitrate, windows)
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
                    val refinedScores = probeOneRatio(
                        sourceUri, outputMime, refined, targetBitrateForRatio(refined), audioBitrate, windows
                    )
                    if (QualityProbePolicy.windowsPass(refinedScores)) {
                        Log.i(TAG, "refinement %.2f pixel-proven (bisection below %.2f)".format(refined, ratio))
                        return ProbeDecision(refined, probed, refinedScores, "windows passed at %.2f (refined)".format(refined))
                    }
                    Log.i(TAG, "refinement %.2f rejected; keeping proven %.2f".format(refined, ratio))
                }
                return ProbeDecision(ratio, probed, scores, "windows passed at %.2f".format(ratio))
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
                val upScores = probeOneRatio(
                    sourceUri, outputMime, upward, targetBitrateForRatio(upward), audioBitrate, windows
                )
                if (QualityProbePolicy.windowsPass(upScores)) {
                    Log.i(TAG, "upward near-miss refinement %.2f pixel-proven (safest rung %.2f just missed)".format(upward, highestCandidate))
                    return ProbeDecision(upward, probed, upScores, "windows passed at %.2f (upward near-miss refinement)".format(upward))
                }
                Log.i(TAG, "upward near-miss refinement %.2f rejected; source cannot be transparently re-encoded".format(upward))
                if (!upScores.isNullOrEmpty()) lastMeasuredScores = upScores
            }
        }
        return ProbeDecision(null, probed, lastMeasuredScores, "no candidate ratio passed", highestMeasuredRejected)
    }

    /** Scores one candidate ratio across all windows; null = evidence unavailable/failed. */
    private suspend fun probeOneRatio(
        sourceUri: Uri,
        outputMime: String,
        ratio: Double,
        videoBitrate: Int,
        audioBitrate: Int,
        windows: List<ScoreWindow>
    ): List<WindowScore>? {
        val collected = mutableListOf<WindowScore>()
        for (window in windows) {
            val probeFile = File.createTempFile("probe_${"%.2f".format(ratio)}_", ".mp4", context.cacheDir)
            try {
                val exported = withTimeoutOrNull(PROBE_EXPORT_TIMEOUT_MS) {
                    exportClip(sourceUri, probeFile, outputMime, videoBitrate, audioBitrate, window)
                } ?: return null
                if (!exported) return null
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
                    // says the probe pipeline broke, not that the source degrades. Returning
                    // null keeps the conservative gate decision AND never counts as a
                    // measured rejection (no probe-skip latch feeding).
                    PairScoreOutcome.MisalignmentRejected -> {
                        Log.w(TAG, "probe window rejected: clip/source frames not time-alignable")
                        return null
                    }
                    PairScoreOutcome.Unavailable -> return null
                }
                collected += scores
                // Early exit: one failing window already rejects this ratio.
                if (!QualityProbePolicy.windowsPass(scores)) return collected
            } finally {
                runCatching { probeFile.delete() }
            }
        }
        return collected
    }

    private suspend fun exportClip(
        sourceUri: Uri,
        outputFile: File,
        outputMime: String,
        videoBitrate: Int,
        audioBitrate: Int,
        window: ScoreWindow
    ): Boolean = withContext(Dispatchers.Main) {
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
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(window.startUs / 1000)
                        .setEndPositionMs(window.endUs / 1000)
                        .build()
                )
                .build()
            val edited = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(true) // probes judge video pixels only; audio is stream-copied in PL
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(outputMime)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(outputFile.length() > 0L)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        runCatching { outputFile.delete() }
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
                .build()
            continuation.invokeOnCancellation {
                transformer.cancel()
                runCatching { outputFile.delete() }
            }
            try {
                transformer.start(
                    Composition.Builder(listOf(EditedMediaItemSequence(edited))).build(),
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
            VmafPairScorer.score(context, sourceUri, Uri.fromFile(outputFile), windows)
        }
    }
}
