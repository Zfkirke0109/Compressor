package compress.joshattic.us

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import compress.joshattic.us.quality.CertificationDecision
import compress.joshattic.us.quality.PairScoreOutcome
import compress.joshattic.us.quality.SaferRungRetry
import compress.joshattic.us.quality.PerceptualQualityProber
import compress.joshattic.us.quality.QualityProbePolicy
import compress.joshattic.us.quality.ExhaustivePerceptualLosslessPolicy
import compress.joshattic.us.quality.KeyframeIntervalPolicy
import compress.joshattic.us.quality.MeasuredOvershoot
import compress.joshattic.us.quality.ProbeEncodeShape
import compress.joshattic.us.quality.WindowScore
import compress.joshattic.us.quality.VmafNative
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Encoder-undershoot tolerance applied to a pixel-proven ratio before it becomes the
// verification bitrate floor: field capture batch_20260714_150649 measured deliveries as low
// as ~0.94x of the request on this device class for near-floor targets.
// Measured on batch_20260715_084112 (n=11 discarded encodes): the QTI HEVC VBR encoder delivered
// 0.86-0.93x of the requested bitrate (mean 0.887, sigma 0.019) on easy/high-bpp content — quality
// saturation, not degradation. The old 0.06 tolerance sat INSIDE that range and discarded all 11
// structurally-perfect encodes (7.8 wasted minutes). 0.15 covers the measured worst case with
// margin; sampled pixel certification remains the real quality gate for every pixel-proven encode.
private const val PIXEL_PROVEN_UNDERSHOOT_TOLERANCE = 0.15

private enum class BatchQualityPreset(val label: String, val targetRatio: Float) {
    REMUX_ONLY("Remux Only", 1.0f),
    ORIGINAL("Perceptually Lossless", 0.85f),
    HIGH("High Quality", 0.70f),
    MEDIUM("Storage Saver", 0.40f),
    LOW("Low", 0.22f)
}

private enum class BatchFrameRateOption(val label: String, val targetFps: Int?) {
    ORIGINAL("Original", null),
    FPS120("120 fps", 120),
    FPS60("60 fps", 60),
    FPS30("30 fps", 30),
    FPS24("24 fps", 24)
}

private enum class BatchCodecOption(val label: String) {
    AUTO("Auto"),
    AV1("AV1"),
    HEVC("HEVC"),
    H264("H.264")
}

enum class BatchItemStatus {
    Pending,
    Compressing,
    Done,
    Failed,
    Replaced,
    SavedCopy,
    Skipped,
    Cancelled
}

data class BatchVideoItem(
    val sourceUri: Uri,
    val originalName: String,
    val originalSize: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalBitrate: Int,
    val originalBitrateWasMeasured: Boolean = false,
    val originalAudioBitrate: Int,
    val originalFps: Float,
    val durationMs: Long,
    val metadataSnapshot: VideoMetadataSnapshot = VideoMetadataSnapshot(),
    // Source track details probed once at load time so encode planning (HDR-safe codec choice,
    // capability checks, learning profile keys) sees real color/codec data before any encode.
    val sourceVideoMime: String? = null,
    val sourceAudioMime: String? = null,
    val sourceColorTransfer: Int? = null,
    val sourceColorStandard: Int? = null,
    val sourceColorRange: Int? = null,
    val sourceAudioChannels: Int? = null,
    val sourceAudioSampleRate: Int? = null,
    val isAlreadyCompressed: Boolean = false,
    val status: BatchItemStatus = BatchItemStatus.Pending,
    val progress: Float = 0f,
    val currentOutputSize: Long = 0L,
    val targetOutputSize: Long = 0L,
    val outputUri: Uri? = null,
    val outputPath: String? = null,
    val outputSize: Long = 0L,
    val outputMode: String? = null,
    val recommendation: CompressionRecommendation? = null,
    val verificationReport: OutputVerificationReport? = null,
    val metrics: BatchItemMetrics? = null,
    val terminalResult: BatchTerminalResult? = null,
    val message: String? = null,
    // Pipeline phase of the current attempt (ItemPhase). Separate from [status] (how the item
    // ended) and from [progress] (item completion: 1 only once terminal).
    val attempt: AttemptToken? = null,
    val phase: ItemPhase = ItemPhase.QUEUED,
    // Measured fraction of [phase] (Media3 export progress, or certification windows scored);
    // null = no honest fraction, shown as indeterminate.
    val phaseFraction: Float? = null,
    val phaseStep: Int? = null,
    val phaseSteps: Int? = null,
    // The finalized candidate's size (closed, metadata-remuxed), before it is accepted or not.
    // [currentOutputSize] is only the live file length while the muxer writes, a temporary footprint.
    val candidateOutputSize: Long = 0L,
    // True while [targetOutputSize] predates the resolved plan (before probes chose a ratio).
    val estimateIsProvisional: Boolean = true
) {
    val displaySize: String get() = formatFileSize(originalSize)
    val outputDisplaySize: String get() = formatFileSize(outputSize)
    val currentOutputDisplaySize: String get() = formatFileSize(currentOutputSize)
    val targetOutputDisplaySize: String get() = formatFileSize(targetOutputSize)
    val progressPercent: Int get() = (progress.coerceIn(0f, 1f) * 100f).toInt()
}

data class BatchCompressorUiState(
    val items: List<BatchVideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val isCompressing: Boolean = false,
    // A scorer self-check is running (runScorerSelfCheck). One at a time; a batch start cancels it.
    val isSelfChecking: Boolean = false,
    val qualityPreset: String = BatchQualityPreset.ORIGINAL.label,
    val frameRateOption: String = BatchFrameRateOption.ORIGINAL.label,
    val codecOption: String = BatchCodecOption.AUTO.label,
    val selectedPreset: String? = null,
    val metadataPrivacyMode: String = MetadataPrivacyMode.PRESERVE_ALL.label,
    // Exhaustive Perceptually Lossless: measure every eligible file instead of skipping on
    // class-level shortcuts. See quality.ExhaustivePerceptualLosslessPolicy. On by default —
    // the quality bar is unchanged; it only spends more probe encodes to find real savings.
    val exhaustivePerceptualLossless: Boolean = true,
    val thermalMode: String = ThermalBatchMode.BALANCED.label,
    val cooldownSeconds: Int = 10,
    val thermalStatus: String = "Thermal: not checked",
    val replaceOriginals: Boolean = false,
    val backupBeforeReplace: Boolean = true,
    val useShizukuFallback: Boolean = false,
    val shizukuStatus: String = "Unavailable",
    val batchMetrics: BatchMetricsSummary? = null,
    val deviceProfile: String = DeviceCapabilityProfiles.current().name,
    val hasHardwareAv1Encoder: Boolean = false,
    // Source URIs of items a just-finished Perceptually Lossless batch honestly kept at original
    // size, but that High Quality could actually shrink (see HighQualityRetryPolicy). Drives the
    // post-batch "shrink these with High Quality?" offer. Empty except right after a PL batch.
    val highQualityRetryCandidates: List<Uri> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val highQualityRetryCount: Int get() = highQualityRetryCandidates.size
    val doneCount: Int get() = items.count { it.status == BatchItemStatus.Done || it.status == BatchItemStatus.Replaced || it.status == BatchItemStatus.SavedCopy }
    val failedCount: Int get() = items.count { it.status == BatchItemStatus.Failed }
    val skippedCount: Int get() = items.count { it.status == BatchItemStatus.Skipped || it.isAlreadyCompressed }
    val cancelledCount: Int get() = items.count { it.terminalResult == BatchTerminalResult.CANCELLED }
    val compressibleCount: Int get() = items.count { !it.isAlreadyCompressed && it.status != BatchItemStatus.Skipped }
    val activeIndex: Int get() = items.indexOfFirst { it.status == BatchItemStatus.Compressing }
    val activeItem: BatchVideoItem? get() = items.getOrNull(activeIndex)
    val hasOutputs: Boolean get() = items.any { it.outputPath != null }
    val totalOriginalBytes: Long get() = items.sumOf { it.originalSize }
    val totalOutputBytes: Long get() = items.sumOf { it.outputSize }
    val totalCurrentOutputBytes: Long get() = items.sumOf { if (it.outputSize > 0L) it.outputSize else it.currentOutputSize }
    val totalTargetOutputBytes: Long get() = items.sumOf { it.targetOutputSize }
    val terminalAccounting: BatchTerminalAccountingSummary
        get() = BatchTerminalAccounting.summarize(
            items.mapNotNull { item ->
                item.terminalResult?.let { terminal ->
                    BatchTerminalAccountingEntry(terminal, item.originalSize, item.outputSize)
                }
            }
        )
    val realCompressionCount: Int get() = terminalAccounting.realCompressionCount
    val nonCompressionCount: Int get() = terminalAccounting.nonCompressionCount
    val totalSavedBytes: Long get() = terminalAccounting.totalBytesSaved
    // Items that reached a terminal state, over all items. An encoder's fraction is not completion.
    val currentBatchProgress: Float get() = ItemProgressModel.batchFraction(items)
    val formattedTotalOriginal: String get() = formatFileSize(totalOriginalBytes)
    val formattedTotalOutput: String get() = formatFileSize(totalOutputBytes)
    val formattedTotalCurrentOutput: String get() = formatFileSize(totalCurrentOutputBytes)
    val formattedTotalTargetOutput: String get() = formatFileSize(totalTargetOutputBytes)
    val formattedTotalSaved: String get() = formatFileSize(totalSavedBytes)
}

@OptIn(UnstableApi::class)
class BatchCompressorViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(BatchCompressorUiState())
    val uiState = _uiState.asStateFlow()

    private var compressionJob: Job? = null
    private var selfCheckJob: Job? = null

    /**
     * Keeps a failed batch from killing the process.
     *
     * The per-item and outer handlers below deliberately rethrow: a run that failed must complete
     * as a *failed* Job, not quietly report success. But `viewModelScope.launch` has no consumer
     * for that throw, so without a handler here kotlinx routes it to the platform's uncaught
     * handler and the process dies.
     *
     * That is not an abstract worry — it is what the 2026-09-01 High Quality captures were:
     *
     *   FATAL EXCEPTION: main
     *   androidx.media3.transformer.ExportException: Muxer error
     *     at Transformer.lambda$maybeInitializeExportWatchdogTimer$0(Transformer.java:1301)
     *   Suppressed: DiagnosticCoroutineContextException: [StandaloneCoroutine{Cancelling}, Dispatchers.Main]
     *
     * A crash is the worst possible failure mode for a measurement app: the batch's structured
     * records live in this process's log buffer, so killing the process destroys the evidence for
     * the run that would have explained the failure. The run's own `finally` has already written
     * its records and returned the UI to idle by the time this runs, so this handler only has to
     * make sure the process survives to be read.
     */
    private val compressionExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        DiagLog.e("CompressorBatch", "Batch run ended with an unhandled failure", throwable)
        _uiState.update { state ->
            state.copy(
                isCompressing = false,
                errorMessage = state.errorMessage
                    ?: "Batch stopped: ${throwable.message ?: throwable.javaClass.simpleName}"
            )
        }
    }
    private var activeTransformer: Transformer? = null

    // Brackets a running batch with foreground protection + a CPU wake lock (see PERF-001). Holds
    // application context only (via AndroidViewModel.getApplication), so it is safe as a field and
    // reused across runs, which keeps its idempotency state coherent. Never starts work itself.
    private val batchGuard: BatchExecutionGuard by lazy {
        BatchExecutionGuard(AndroidBatchExecutionSink(getApplication()))
    }

    // Local-only encode calibration; recommends Perceptually Lossless targets but never bypasses
    // verification. Stored in app SharedPreferences, nothing leaves the device.
    private val learningEngine by lazy {
        SmartPerceptualProfileEngine(
            // Every learned-state write goes to the running batch's capture, in order, so the
            // batch-start snapshot plus these updates reconstructs the state at any point.
            RecordingProfileStore(SmartPerceptualProfileEngine.SharedPreferencesProfileStore(getApplication())) { key, before, after ->
                activeDiagnostics?.learnedStateUpdate(key, before, after)
            }
        )
    }

    // The recorder of the batch now running, for learned-state updates. Null between batches.
    @Volatile private var activeDiagnostics: DiagnosticsRecorder? = null

    // Monotonic attempt counter for AttemptToken: unique per attempt within this process.
    private val attemptCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // UI rows as an ItemBoard, for ItemPipeline and PhaseReporter.
    private val itemBoard = object : ItemBoard {
        override fun item(index: Int): BatchVideoItem? = _uiState.value.items.getOrNull(index)
        override fun update(index: Int, transform: (BatchVideoItem) -> BatchVideoItem) = updateItem(index, transform)
    }

    private val qualityProber by lazy { PerceptualQualityProber(getApplication()) }

    private data class PerceptualLosslessPlan(
        val profileKey: SmartPerceptualProfileEngine.EncodeProfileKey,
        val targetRatio: Double,
        val floorRatio: Double,
        val preferRemux: Boolean,
        val remuxReason: String?,
        val remuxWasSourceEfficient: Boolean,
        // True when the remux preference came from the learning engine's measured latch
        // (repeated near-max-ratio failures for this profile) rather than a source-efficiency
        // inference — classified as REMUX_PREFERRED_BY_EVIDENCE, never UNEXPECTED_REMUX.
        val remuxWasEvidencePreferred: Boolean = false,
        // Tier-1 experimental encoder-ceiling diagnostics (debug builds only): request CBR so the
        // QTI encoder cannot apply its VBR quality-boost overshoot. Judged by OutputVerifier only.
        val useCbrCeiling: Boolean = false,
        val expectedOvershootFactor: Double = 1.0,
        // Pixel-probe eligibility and outcome. Eligible = SDR + non-downgrade output codec;
        // VMAF is not calibrated for HDR. Same-codec sources and remux-latched profiles stay
        // remux-preferred by INFERENCE but may earn probes — per-clip pixel evidence (probe
        // windows + post-encode certification) outranks class-level inference in both
        // directions. Codec downgrades (e.g. AV1 -> HEVC) never probe: a fake bitrate delta
        // cannot be pixel-justified.
        val probeEligible: Boolean = false,
        // True when the ladder must run in its short form: an above-1080p source that exhaustive
        // mode made probe-eligible (see ExhaustivePerceptualLosslessPolicy.trimToShortLadder).
        val shortProbeLadder: Boolean = false,
        // Whether the finished output can be pixel-scored against its source at all. Strictly wider
        // than [probeEligible]: 4K-class sources are certifiable but too expensive to run a ladder
        // on. Only this flag may gate post-encode certification — gating it on probe eligibility is
        // what previously made the pixel-proven label unreachable for every source above 1080p.
        val pixelCertifiable: Boolean = false,
        // Which gate closed when [pixelCertifiable] is false, so a capture can say WHY
        // certification never ran instead of leaving the reader to guess from a null field.
        val pixelCertifiableBlockReason: String? = null,
        val defaultRatio: Double = targetRatio,
        // True when probes in exhaustive mode overturned a heuristic "keep original" decision.
        // Such a plan must be certified by MEASURED windows; see ExhaustivePerceptualLosslessPolicy.
        val requiresMeasuredCertification: Boolean = false,
        // VMAF v1 shadow scores of the probe windows (telemetry only; see VmafNativeV1).
        val probeV1Scores: String? = null,
        // Probe clip bitrate model per rung (telemetry only; see ProbeClipBitrate).
        val probeRateDiag: String? = null,
        // Ratio proven by on-device VMAF probe windows for THIS clip. May sit ABOVE the
        // learned/default target when only a safer retreat rung passed its windows.
        val pixelProvenRatio: Double? = null,
        // The overshoot the size gate multiplied the proven ratio's request by, BEFORE the
        // prediction's clamp to 1.0-2.0 (MeasuredOvershoot.forPrediction). Null when no gate ran.
        val sizeGateOvershoot: Double? = null,
        // A HIGHER ratio the probes also measured as passing (the ladder refined below it), kept as
        // the only candidate for the opt-in safer-rung retry (SaferRungRetry). Null when none.
        val saferPassingRatio: Double? = null,
        val saferPassingRateFactors: List<Double> = emptyList(),
        // Set when pixel measurement PROVED that no candidate ratio (including the safest)
        // can encode this clip transparently: the item is skipped entirely — original
        // untouched, no stream-copy written. Inference-only remux decisions never set this.
        val skipReason: String? = null,
        // Full probe trace for the structured diagnostics record: every ratio attempted (in
        // order) and the prober's decision detail. Answers "was a trial performed and what
        // exactly did it measure" without needing unstructured logcat tags.
        val probedRatios: List<Double> = emptyList(),
        val probeDetail: String? = null,
        // Compact per-window "mean/p5/min" scores of the last measured rung (pass or fail):
        // the raw numbers behind the probe decision, for threshold calibration from captures.
        val probeWindowScores: String? = null,
        // Per-window frame-pairing diagnostics of the same rung (counts + decode-order pts skew):
        // distinguishes "windows measured real quality" from "windows scored misaligned frames".
        val probePairDiag: String? = null,
        // Set when a rung PASSED its windows but the output at that rung is predicted to be no
        // smaller than the source, so the size prediction, not a quality measurement, kept the
        // original. The basis sentence (KeepOriginalMessages) must say exactly that.
        val sizeGateBasis: String? = null,
        // Set when the probe exports stopped because Media3 cannot parse the input they read
        // (SourceParseFailure): the full encode would stop the same way, so it is not started on
        // that input.
        val sourceParseFailure: SourceParseFailure? = null
    )

    private data class EncodeAttemptResult(
        val file: File,
        val requestedVideoBitrate: Int,
        val requestedBitrateModeLabel: String,
        val videoEncoderName: String?,
        val reportedAverageVideoBitrate: Int,
        // Requested vs actual encoder configuration. Media3 format fallback substitutes an
        // unsupported MIME or resolution and still reports success, and a resolution substitution
        // would trip videoMatches inside the PL verification scope - so a rejection could be
        // caused by the encoder quietly producing something else. Recorded, never acted on:
        // OutputVerifier measuring the finished file remains the only verdict.
        val configDelta: EncoderConfigDelta? = null
    )

    fun refreshShizukuStatus(context: Context) {
        val installed = ShizukuSupport.isShizukuPackageInstalled(context)
        val binder = ShizukuSupport.isBinderAvailable()
        val granted = ShizukuSupport.hasPermission()
        val label = when {
            granted -> "Authorized (${ShizukuSupport.backendLabel()})"
            binder -> "Running, permission needed"
            installed -> "Installed, not running"
            else -> "Not installed"
        }
        _uiState.update { it.copy(shizukuStatus = label) }
    }

    fun requestShizukuPermission(context: Context) {
        refreshShizukuStatus(context)
        ShizukuSupport.requestPermission()
        refreshShizukuStatus(context)
    }

    fun setQuality(label: String) {
        _uiState.update { state ->
            val quality = qualityFromLabel(label)
            val codec = codecFromLabel(state.codecOption)
            val frameRate = frameRateFromLabel(state.frameRateOption)
            state.copy(
                qualityPreset = label,
                selectedPreset = null,
                items = state.items.map { item ->
                    if (item.isAlreadyCompressed) item else item.copy(targetOutputSize = estimateOutputSize(item, quality, codec, frameRate))
                }
            )
        }
    }

    fun setFrameRate(label: String) {
        _uiState.update { state ->
            val quality = qualityFromLabel(state.qualityPreset)
            val codec = codecFromLabel(state.codecOption)
            val frameRate = frameRateFromLabel(label)
            state.copy(
                frameRateOption = label,
                selectedPreset = null,
                items = state.items.map { item ->
                    if (item.isAlreadyCompressed) item else item.copy(targetOutputSize = estimateOutputSize(item, quality, codec, frameRate))
                }
            )
        }
    }

    fun setCodec(label: String) {
        _uiState.update { state ->
            val quality = qualityFromLabel(state.qualityPreset)
            val codec = codecFromLabel(label)
            val frameRate = frameRateFromLabel(state.frameRateOption)
            state.copy(
                codecOption = label,
                selectedPreset = null,
                items = state.items.map { item ->
                    if (item.isAlreadyCompressed) item else item.copy(targetOutputSize = estimateOutputSize(item, quality, codec, frameRate))
                }
            )
        }
    }

    fun applyPreset(label: String) {
        val preset = BatchPresetOption.fromLabel(label) ?: return
        _uiState.update { state ->
            val largest = state.items.maxOfOrNull { it.originalSize } ?: 0L
            val highFps = state.items.any { it.originalFps >= 50f }
            val qualityLabel = when (preset) {
                BatchPresetOption.S23_BEST -> BatchQualityPreset.ORIGINAL.label
                BatchPresetOption.S23_STORAGE -> if (largest >= 900L * 1024L * 1024L) BatchQualityPreset.MEDIUM.label else BatchQualityPreset.HIGH.label
                BatchPresetOption.SOCIAL -> BatchQualityPreset.MEDIUM.label
                BatchPresetOption.ARCHIVE -> BatchQualityPreset.ORIGINAL.label
                BatchPresetOption.HDR_SAFE -> BatchQualityPreset.ORIGINAL.label
            }
            val codecLabel = when (preset) {
                BatchPresetOption.SOCIAL -> BatchCodecOption.H264.label
                else -> BatchCodecOption.HEVC.label
            }
            val fpsLabel = when (preset) {
                BatchPresetOption.SOCIAL -> BatchFrameRateOption.FPS30.label
                BatchPresetOption.S23_STORAGE -> if (largest >= 900L * 1024L * 1024L && highFps) BatchFrameRateOption.FPS30.label else BatchFrameRateOption.ORIGINAL.label
                else -> BatchFrameRateOption.ORIGINAL.label
            }
            val quality = qualityFromLabel(qualityLabel)
            val codec = codecFromLabel(codecLabel)
            val frameRate = frameRateFromLabel(fpsLabel)
            state.copy(
                qualityPreset = qualityLabel,
                codecOption = codecLabel,
                frameRateOption = fpsLabel,
                selectedPreset = label,
                items = state.items.map { item ->
                    if (item.isAlreadyCompressed) item else item.copy(targetOutputSize = estimateOutputSize(item, quality, codec, frameRate))
                },
                statusMessage = "$label applied. You can still adjust mode, codec, and FPS manually."
            )
        }
    }

    fun applyRecommendation(sourceUri: Uri) {
        _uiState.update { state ->
            val recommendation = state.items.firstOrNull { it.sourceUri == sourceUri }?.recommendation ?: return@update state
            val quality = qualityFromLabel(recommendation.qualityPreset)
            val codec = codecFromLabel(recommendation.codecOption)
            val frameRate = frameRateFromLabel(recommendation.frameRateOption)
            state.copy(
                qualityPreset = recommendation.qualityPreset,
                codecOption = recommendation.codecOption,
                frameRateOption = recommendation.frameRateOption,
                selectedPreset = null,
                items = state.items.map { item ->
                    if (item.isAlreadyCompressed) item else item.copy(targetOutputSize = estimateOutputSize(item, quality, codec, frameRate))
                },
                statusMessage = "${recommendation.title} applied. Manual controls remain available."
            )
        }
    }

    fun setThermalMode(label: String) {
        _uiState.update { it.copy(thermalMode = ThermalBatchGovernor.modeFromLabel(label).label) }
    }

    fun setCooldownSeconds(seconds: Int) {
        _uiState.update { it.copy(cooldownSeconds = seconds.coerceIn(0, 60)) }
    }

    fun refreshThermalStatus(context: Context) {
        val snapshot = ThermalBatchGovernor.snapshot(context, _uiState.value.thermalMode, _uiState.value.cooldownSeconds)
        _uiState.update { it.copy(thermalStatus = snapshot.summary) }
    }

    fun toggleReplaceOriginals() {
        _uiState.update { it.copy(replaceOriginals = !it.replaceOriginals) }
    }

    fun toggleBackupBeforeReplace() {
        _uiState.update { it.copy(backupBeforeReplace = !it.backupBeforeReplace) }
    }

    fun toggleShizukuFallback() {
        _uiState.update { it.copy(useShizukuFallback = !it.useShizukuFallback) }
    }

    fun setExhaustivePerceptualLossless(enabled: Boolean) {
        _uiState.update { it.copy(exhaustivePerceptualLossless = enabled) }
    }

    fun setMetadataPrivacyMode(label: String) {
        _uiState.update { it.copy(metadataPrivacyMode = MetadataPrivacyMode.fromLabel(label).label) }
    }

    fun clear() {
        val runningJob = compressionJob?.takeIf { !it.isCompleted }
        if (runningJob != null) {
            cancelCompression()
            viewModelScope.launch {
                // Preserve the selected items until the cancelled run records one terminal
                // diagnostic for each of them. Clearing early would produce an incomplete session.
                runningJob.join()
                clearBatchState()
            }
            return
        }
        clearBatchState()
    }

    private fun clearBatchState() {
        clearBatchCache()
        _uiState.update {
            it.copy(
                items = emptyList(),
                statusMessage = null,
                errorMessage = null,
                isLoading = false,
                isCompressing = false,
                batchMetrics = null
            )
        }
    }

    fun loadUris(context: Context, uris: List<Uri>) {
        val distinct = uris.distinct()
        if (distinct.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = "Reading ${distinct.size} video${if (distinct.size == 1) "" else "s"}…") }
            val quality = qualityFromLabel(_uiState.value.qualityPreset)
            val codec = codecFromLabel(_uiState.value.codecOption)
            val frameRate = frameRateFromLabel(_uiState.value.frameRateOption)
            var unreadable = 0
            val items = distinct.mapNotNull { uri ->
                try {
                    val item = readMetadata(context, uri)
                    val alreadyCompressed = isLikelyCompressorOutput(item.originalName)
                    if (alreadyCompressed) {
                        item.copy(
                            isAlreadyCompressed = true,
                            status = BatchItemStatus.Skipped,
                            progress = 1f,
                            targetOutputSize = 0L,
                            terminalResult = BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED,
                            message = "Already compressed by Compressor — skipped."
                        )
                    } else {
                        item.copy(
                            targetOutputSize = estimateOutputSize(item, quality, codec, frameRate),
                            recommendation = recommendFor(item)
                        )
                    }
                } catch (e: Exception) {
                    unreadable++
                    DiagLog.w("CompressorBatch", "could not read ${DiagnosticsRecorder.redactedJobId(uri.toString())}", e)
                    null
                }
            }
            val skipped = items.count { it.isAlreadyCompressed }
            val unreadableNote = if (unreadable > 0) {
                " $unreadable file${if (unreadable == 1) "" else "s"} could not be read and ${if (unreadable == 1) "was" else "were"} left out."
            } else {
                ""
            }
            _uiState.update {
                it.copy(
                    items = items,
                    isLoading = false,
                    highQualityRetryCandidates = emptyList(),
                    deviceProfile = DeviceCapabilityProfiles.current().name,
                    hasHardwareAv1Encoder = hasEncoder(MimeTypes.VIDEO_AV1),
                    statusMessage = when {
                        items.isEmpty() -> "No readable videos found."
                        skipped > 0 -> "Ready: ${items.size} selected. $skipped already compressed item${if (skipped == 1) "" else "s"} will be skipped."
                        else -> "Ready: ${items.size} video${if (items.size == 1) "" else "s"} selected."
                    } + unreadableNote,
                    errorMessage = null
                )
            }
            refreshShizukuStatus(context)
        }
    }

    fun testReplacementAccess(context: Context) {
        val items = _uiState.value.items
        if (items.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "Select a duplicate test video first, then run replacement access test.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            refreshShizukuStatus(context)
            val reports = items.take(5).map { item ->
                if (item.isAlreadyCompressed) {
                    return@map "${item.originalName}: already compressed — skipped"
                }
                val writable = runCatching {
                    context.contentResolver.openFileDescriptor(item.sourceUri, "rw")?.use { true } ?: false
                }.getOrDefault(false)
                val path = resolveFilesystemPath(context, item.sourceUri)
                val shizukuReady = path != null && ShizukuSupport.hasPermission()
                val method = when {
                    writable -> "Android writable document"
                    shizukuReady -> "Shizuku path fallback"
                    path == null -> "No direct path; use in-app picker for best replacement"
                    else -> "Needs Shizuku permission or writable document access"
                }
                val metadata = when {
                    item.metadataSnapshot.hasDate && item.metadataSnapshot.hasLocation -> "date + location detected"
                    item.metadataSnapshot.hasDate -> "date detected"
                    item.metadataSnapshot.hasLocation -> "location detected"
                    else -> "no source date/location exposed"
                }
                "${item.originalName}: $method • $metadata"
            }
            _uiState.update { it.copy(statusMessage = reports.joinToString("\n")) }
        }
    }

    /**
     * Source URIs of items the just-finished batch honestly kept at original size but that High
     * Quality could actually shrink — feeds the post-batch "shrink these with High Quality?" offer.
     * Only populated when the batch ran in Perceptually Lossless; every other mode returns empty.
     * Pure and cheap (no codec queries), so it is safe to call inline on the completion update.
     */
    private fun highQualityRetryCandidatesFor(state: BatchCompressorUiState): List<Uri> {
        val wasPerceptualLossless = qualityFromLabel(state.qualityPreset) == BatchQualityPreset.ORIGINAL
        if (!wasPerceptualLossless) return emptyList()
        return state.items.filter { item ->
            HighQualityRetryPolicy.isEligible(
                batchWasPerceptualLossless = true,
                terminal = item.terminalResult,
                sourceVideoBitrate = item.toSourceInfo().videoBitrate,
                sourceHeight = item.originalHeight,
                sourceSizeBytes = item.originalSize
            )
        }.map { it.sourceUri }
    }

    /**
     * Re-run only the kept-original videos from the last Perceptually Lossless batch in High
     * Quality — the honest lossy lever that trades a little quality for a real size reduction.
     * Reuses the existing pipeline: it narrows the batch to the offered sources, switches the mode
     * to High Quality, and starts a fresh run (which resets and processes them). Originals are never
     * touched here; only new output files are produced. A no-op while a batch is running.
     */
    fun retryUnshrunkAsHighQuality(context: Context) {
        val current = _uiState.value
        if (current.isCompressing || compressionJob?.isCompleted == false) return
        val candidateUris = current.highQualityRetryCandidates.toSet()
        if (candidateUris.isEmpty()) return
        val subset = current.items.filter { it.sourceUri in candidateUris }
        if (subset.isEmpty()) return
        _uiState.update {
            it.copy(
                items = subset,
                qualityPreset = BatchQualityPreset.HIGH.label,
                selectedPreset = null,
                highQualityRetryCandidates = emptyList(),
                batchMetrics = null,
                errorMessage = null,
                statusMessage = "Re-running ${subset.size} video${if (subset.size == 1) "" else "s"} in High Quality…"
            )
        }
        startCompression(context)
    }

    /**
     * Runs the measurement-path control tests (PerceptualQualityProber.selfCheck) on the first
     * selected video and shows the result. Writes to a decision log of its own so the numbers are
     * in the next export. Never runs alongside a batch.
     */
    fun runScorerSelfCheck(callerContext: Context) {
        val context = callerContext.applicationContext
        val current = _uiState.value
        if (current.isCompressing || compressionJob?.isCompleted == false) return
        // One at a time: b167 ran two overlapping self-checks and lost the second one's results
        // (see DiagLog). The button is disabled while one runs; this guards a double tap.
        if (selfCheckJob?.isActive == true) return
        val item = current.items.firstOrNull { !it.isAlreadyCompressed } ?: run {
            _uiState.update { it.copy(statusMessage = "Select a video first, then run the scorer self-check.") }
            return
        }
        selfCheckJob = viewModelScope.launch(Dispatchers.Main) {
            _uiState.update { it.copy(isSelfChecking = true, statusMessage = "Scorer self-check running on ${item.originalName}…") }
            val stamp = System.currentTimeMillis()
            val log = DiagLog.attach(context, "selfcheck_$stamp")
            try {
                val report = try {
                    val mime = runCatching { chooseOutputMime(BatchCodecOption.AUTO, item, BatchQualityPreset.ORIGINAL) }
                        .getOrDefault(MimeTypes.VIDEO_H265)
                    DiagLog.i("CompressorProbe", "self-check start; job=${diagnosticJobId(item)}; source=${item.originalWidth}x${item.originalHeight}@${item.originalFps}; mime=${item.sourceVideoMime}")
                    qualityProber.selfCheck(item.sourceUri, item.durationMs, item.toSourceInfo().videoBitrate, mime)
                } catch (e: CancellationException) {
                    DiagLog.i("CompressorProbe", "self-check cancelled; a batch is starting")
                    throw e
                } catch (e: Exception) {
                    "self-check failed: ${e.message ?: e.javaClass.simpleName}"
                }
                _uiState.update { it.copy(statusMessage = "Scorer self-check (${item.originalName}):\n$report") }
            } finally {
                DiagLog.detach(log)
                _uiState.update { it.copy(isSelfChecking = false) }
            }
        }
    }

    fun startCompression(callerContext: Context) {
        // The batch outlives the screen that started it: it keeps running through rotation, a
        // theme change, or the activity being recreated in the background. Holding the caller's
        // Activity for the whole run would pin that dead Activity and its view tree in memory for
        // hours. Nothing in the batch needs an Activity, so it runs on the application context.
        val context = callerContext.applicationContext
        val current = _uiState.value
        if (current.items.isEmpty() || current.isCompressing || compressionJob?.isCompleted == false) return
        if (current.compressibleCount == 0) {
            _uiState.update { it.copy(statusMessage = "All selected videos already look compressed by Compressor, so nothing was recompressed.") }
            return
        }

        compressionJob = viewModelScope.launch(Dispatchers.Main + compressionExceptionHandler) {
            // A running self-check would share the encoder, the scorer and the decision log with
            // the batch. The batch wins: the self-check stops first, at its next suspension point.
            selfCheckJob?.takeIf { it.isActive }?.cancelAndJoin()
            runBatch(context)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The batch run, one stage per function.
    //
    // Until b163 the whole run was one suspend lambda. Kotlin compiles a suspend lambda into a
    // single state machine, and this one had grown to 215,736 dex code units, 27x the next
    // largest method in the app and 20x ART's "huge method" threshold, above which ART never
    // JIT-compiles a method and always interprets it. On Android 17 that method died with
    //   Check failed: throw_dex_pc < accessor.InsnsSizeInCodeUnits()
    //   (throw_dex_pc=404595, accessor.InsnsSizeInCodeUnits()=215736)
    // inside ART's interpreter while it tried to raise a NullPointerException from the method:
    // the dex pc ART computed lay outside the method's own code, so instead of a catchable
    // exception the process aborted, and whatever the NullPointerException was is lost.
    //
    // Each stage below is its own method with its own, much smaller, state machine. The state
    // that used to live in the lambda's locals lives in [ItemRun], where it can be read by
    // every stage and by tests. Behaviour is unchanged; only the shape is.
    // ---------------------------------------------------------------------------------------

    /** What one batch run holds constant from start to finish. */
    private class BatchRun(
        val context: Context,
        val diagnostics: DiagnosticsRecorder,
        val batchStartedAt: Long,
        val quality: BatchQualityPreset,
        val frameRate: BatchFrameRateOption,
        val codec: BatchCodecOption,
        val privacyMode: MetadataPrivacyMode,
        val exhaustivePerceptualLossless: Boolean
    ) {
        // Safer-rung retries spent in this batch (SaferRungRetry.MAX_RETRIES_PER_BATCH).
        var saferRungRetries = 0
    }

    /**
     * The state of one item as it moves through the stages. The `diagnostic*` fields are what
     * the structured record and the failure handler need to stay honest after a fallback: they
     * remember what was planned and why it was discarded, even when verification is re-run on a
     * stream copy.
     */
    private class ItemRun(
        val run: BatchRun,
        val index: Int,
        val item: BatchVideoItem,
        val thermalWindow: ThermalBatchSnapshot,
        val itemStartedAt: Long,
        // The cooldown applied after the PREVIOUS item, for this item's record. Timing only.
        val precedingHandoffCooldownMs: Long,
        val plannedFps: Int?
    ) {
        var diagnosticEffectiveQuality: BatchQualityPreset = run.quality
        var diagnosticResolvedMime: String? = null
        var diagnosticTargetRatio: Double? = null
        var diagnosticTargetVideoBitrate: Int? = null
        var diagnosticDecisionReason: String? = null
        var diagnosticSourceAlreadyEfficient = false
        var diagnosticEvidencePreferredRemux = false
        // The plan, kept so the failure handler can still retain the original when the stream
        // copy that should carry it turns out impossible.
        var diagnosticPlan: PerceptualLosslessPlan? = null
        var diagnosticEncoderFailed = false
        // When a PL encode is attempted then discarded, WHY (and the discarded encode's measured
        // video bitrate), so the record stays honest after verification is re-run on the remux.
        var diagnosticFallbackReason: String? = null
        var diagnosticDiscardedVideoBitrate: Int? = null
        // Why the keep-original fast path declined to reuse the source (enum name).
        var diagnosticReuseBlockReason: String? = null
        // Compact per-window scores of the final output's certification, pass OR fail.
        var diagnosticCertWindowScores: String? = null
        var diagnosticCertBandingDiag: String? = null
        var diagnosticCertV1Scores: String? = null
        // Why certification did or did not run. Never left null on a PL job.
        var diagnosticCertStatus: String? = null
        // True ONLY once sampled VMAF has measured this output and passed.
        var pixelCertifiedThisRun = false
        val candidateFiles = linkedSetOf<File>()
        var itemOutputAccepted = false

        // Planning stage.
        var resolvedMime: String? = null
        var codecLabel: String = "H.264"
        var perceptualPlan: PerceptualLosslessPlan? = null
        var effectiveQuality: BatchQualityPreset = run.quality
        var preEncodeRemuxNote: String? = null
        // Keyframe interval both the probes and the encode request for this source
        // (KeyframeIntervalPolicy). Set in planItem for every item that may be encoded.
        var iFrameIntervalSeconds: Float = KeyframeIntervalPolicy.WHEN_UNKNOWN_SECONDS

        // The platform-normalised copy Media3 reads instead of the source after a
        // SourceParseFailure (Media3InputNormalizer); null while Media3 reads the source itself.
        // Only the Transformer's input: every verdict is still taken against the original.
        var media3Input: File? = null
        var media3InputAttempted = false
        // What happened on that path, for the job record ("media3Input").
        var diagnosticMedia3Input: String? = null
        val transformerInputUri: Uri get() = media3Input?.let { Uri.fromFile(it) } ?: item.sourceUri

        fun releaseMedia3Input() {
            media3Input?.let { runCatching { it.delete() } }
            media3Input = null
        }

        // Output stage.
        var encodeAttempt: EncodeAttemptResult? = null
        var remuxResult: Mp4MetadataRemuxResult? = null
        var outputFile: File? = null
        var outputUri: Uri? = null
        var outputSize: Long = 0L

        // Verification stage.
        var verification: OutputVerificationReport? = null
        var measuredOvershoot: Double? = null
        var floorRecoveryCertScores: List<WindowScore>? = null
        var failedFloorRecoveryStatus: String? = null

        /** The cooldown applied after this item, carried into the next item's record. */
        var cooldownForNextItemMs = 0L

        // Phase reporting bound to this item's attempt (ItemPipeline sets it before planning).
        lateinit var phases: PhaseReporter
        // The encode plan the running encode resolved (ResolvedEncodePlan), for the record.
        var resolvedPlan: ResolvedEncodePlan? = null
        // The size gate's overshoot, before its clamp, when the gate ran (for the resolved plan).
        val sizeGateOvershoot: Double? get() = perceptualPlan?.sizeGateOvershoot
        // Every full encode of this job, in order ("0.85:cert_measured_failure:14620ms;…").
        val attemptLog = mutableListOf<String>()
        var certificationDecision: compress.joshattic.us.quality.CertificationDecision? = null
        // Safer-rung retry (SaferRungRetry): retries used, and the size check's overshoot for it.
        var retriesThisItem = 0
        var retryOvershoot: Double? = null
        // When the running encode started, for the attempt log and the retry's time budget.
        var encodeStartedAt = 0L
        var lastEncodeMs = 0L

        /** Output-stage state of a discarded attempt, cleared before a retry encodes again. */
        fun resetForRetry() {
            encodeAttempt = null
            remuxResult = null
            outputFile = null
            outputUri = null
            outputSize = 0L
            verification = null
            measuredOvershoot = null
            floorRecoveryCertScores = null
            failedFloorRecoveryStatus = null
            pixelCertifiedThisRun = false
            certificationDecision = null
            diagnosticCertWindowScores = null
            diagnosticCertBandingDiag = null
            diagnosticCertV1Scores = null
            diagnosticCertStatus = null
            diagnosticFallbackReason = null
            diagnosticDiscardedVideoBitrate = null
        }

        val elapsedMs: Long get() = System.currentTimeMillis() - itemStartedAt

        fun deleteCandidatesUnlessAccepted() {
            if (!itemOutputAccepted) candidateFiles.forEach { runCatching { it.delete() } }
        }
    }

    private suspend fun runBatch(context: Context) {
        val batchStartedAt = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isCompressing = true,
                errorMessage = null,
                batchMetrics = null,
                highQualityRetryCandidates = emptyList(),
                statusMessage = "Compressing with thermal-safe batch pacing."
            )
        }

        val quality = qualityFromLabel(_uiState.value.qualityPreset)
        val frameRate = frameRateFromLabel(_uiState.value.frameRateOption)
        val codec = codecFromLabel(_uiState.value.codecOption)
        val privacyMode = MetadataPrivacyMode.fromLabel(_uiState.value.metadataPrivacyMode)
        val exhaustivePerceptualLossless = _uiState.value.exhaustivePerceptualLossless
        // Reclaim orphaned cache files, but keep anything the CURRENT results still reference:
        // re-running while previous results are still on screen must not delete files that
        // share/save are still offering. Those entries are replaced by this run's own outputs.
        clearBatchCache(preservePaths = _uiState.value.items.mapNotNull { it.outputPath }.toSet())
        resetItemsForRun(quality, codec, frameRate)

        // Package, version, build commit, Android user id, and profile kind are resolved inside
        // start() from the context + BuildConfig so every record self-identifies its environment.
        // Mirror the decision lines into a file this app owns. See DiagLog: the structured
        // records survive every capture because they are written to filesDir, while the
        // human-readable "why" lines lived only in a device-wide ring buffer that has now
        // been evicted out from under three consecutive rounds of evidence.
        val decisionLog = DiagLog.attach(context, "batch_$batchStartedAt")
        val diagnostics = DiagnosticsRecorder.start(
            context = context,
            batchId = "batch_$batchStartedAt",
            mode = quality.label,
            selectedCount = _uiState.value.items.size,
            // Captured BEFORE the batch mutates it, so the record describes the state the run
            // actually started from rather than the state it ended in.
            learnedStateIdentity = runCatching { learningEngine.learnedStateIdentity() }.getOrNull(),
            exhaustivePerceptualLossless = exhaustivePerceptualLossless
        )
        // The state itself, not only its identity: exact replay needs the starting state.
        runCatching { diagnostics.learnedStateSnapshot(LearnedStateSnapshot.of(learningEngine.snapshotLearnedState())) }
        activeDiagnostics = diagnostics
        val run = BatchRun(
            context = context,
            diagnostics = diagnostics,
            batchStartedAt = batchStartedAt,
            quality = quality,
            frameRate = frameRate,
            codec = codec,
            privacyMode = privacyMode,
            exhaustivePerceptualLossless = exhaustivePerceptualLossless
        )
        _uiState.value.items.filter { it.isAlreadyCompressed }.forEach { skippedItem ->
            recordDiagnosticJob(
                diagnostics = diagnostics,
                item = skippedItem,
                requestedQuality = quality,
                effectiveQuality = quality,
                resolvedMime = null,
                plannedTargetRatio = null,
                plannedTargetVideoBitrate = null,
                wasStreamCopy = false,
                verification = null,
                outputSize = 0L,
                terminal = BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED,
                elapsedMs = 0L
            )
        }
        var runCancelled = false
        var runFailed = false
        var sessionFailReason = "unknown"

        try {
            // Enter foreground protection + CPU wake lock as the FIRST action inside the try, so
            // begin() and the end() in this try's finally are a symmetric bracket that no setup or
            // encode throw can leak. The app is foreground here (the user just tapped Start), which
            // satisfies the Android 12+ background-start restriction; the guard is idempotent and
            // its effects are best-effort, so this can never abort the batch. Only cheap pre-try
            // setup (cache clear, state init) runs before this — never an encode.
            batchGuard.begin()
            // Post-item thermal cooldown that was applied BEFORE the current item started (i.e. the
            // cooldown after the previous item). Carried across iterations so each item's structured
            // record shows the handoff delay that preceded it. Timing telemetry only.
            var precedingCooldownMs = 0L
            val items = _uiState.value.items
            for ((index, item) in items.withIndex()) {
                if (item.isAlreadyCompressed) continue
                precedingCooldownMs = processItem(run, index, item, precedingCooldownMs)
            }
            publishBatchFinished(batchStartedAt)
        } catch (e: CancellationException) {
            runCancelled = true
            throw e
        } catch (e: Throwable) {
            runFailed = true
            sessionFailReason = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            // Release foreground protection + wake lock FIRST, before any other finally work, so a
            // throw in the rest of this block cannot leak them. Idempotent — safe even if begin()
            // never fired (e.g. a pre-try setup failure).
            batchGuard.end()
            DiagLog.detach(decisionLog)
            if (runCancelled) publishBatchCancelled(run)
            // Emit the honest session terminal record: cancelled batches get session_cancelled,
            // a completed run gets session_summary. A hard-failure path is reported below.
            val sessionElapsed = System.currentTimeMillis() - batchStartedAt
            when {
                runCancelled -> diagnostics.sessionCancelled(sessionElapsed, reason = "user_cancelled")
                runFailed -> diagnostics.sessionFailed(sessionElapsed, reason = sessionFailReason)
                else -> diagnostics.sessionSummary(sessionElapsed)
            }
            activeTransformer = null
            compressionJob = null
            activeDiagnostics = null
        }
    }

    private fun resetItemsForRun(quality: BatchQualityPreset, codec: BatchCodecOption, frameRate: BatchFrameRateOption) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.isAlreadyCompressed) {
                        item.copy(
                            status = BatchItemStatus.Skipped,
                            progress = 1f,
                            currentOutputSize = 0L,
                            targetOutputSize = 0L,
                            outputUri = null,
                            outputPath = null,
                            outputSize = 0L,
                            outputMode = null,
                            verificationReport = null,
                            metrics = null,
                            terminalResult = BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED,
                            message = "Already compressed by Compressor — skipped."
                        )
                    } else {
                        item.copy(
                            status = BatchItemStatus.Pending,
                            progress = 0f,
                            // A new run starts every row over: no attempt owns it until processItem.
                            attempt = null,
                            phase = ItemPhase.QUEUED,
                            phaseFraction = null,
                            phaseStep = null,
                            phaseSteps = null,
                            candidateOutputSize = 0L,
                            estimateIsProvisional = true,
                            currentOutputSize = 0L,
                            targetOutputSize = estimateOutputSize(item, quality, codec, frameRate),
                            outputUri = null,
                            outputPath = null,
                            outputSize = 0L,
                            outputMode = null,
                            verificationReport = null,
                            metrics = null,
                            terminalResult = null,
                            message = null
                        )
                    }
                }
            )
        }
    }

    private fun publishBatchFinished(batchStartedAt: Long) {
        _uiState.update {
            val accounting = it.terminalAccounting
            val metrics = BatchMetricsSummary(
                totalElapsedMs = System.currentTimeMillis() - batchStartedAt,
                totalCooldownMs = it.items.sumOf { item -> item.metrics?.cooldownMs ?: 0L },
                processedCount = accounting.processedCount,
                realCompressionCount = accounting.realCompressionCount,
                nonCompressionCount = accounting.nonCompressionCount,
                failedCount = accounting.failedCount,
                skippedCount = accounting.skippedCount,
                cancelledCount = accounting.cancelledCount,
                totalSavedBytes = accounting.totalBytesSaved
            )
            it.copy(
                isCompressing = false,
                batchMetrics = metrics,
                highQualityRetryCandidates = highQualityRetryCandidatesFor(it),
                statusMessage = "Finished ${it.doneCount} output${if (it.doneCount == 1) "" else "s"}. " +
                    "Real compressions: ${accounting.realCompressionCount}. " +
                    "Saved ${formatFileSize(accounting.totalBytesSaved)} by real compression.",
                errorMessage = if (accounting.failedCount > 0) {
                    "${accounting.failedCount} item${if (accounting.failedCount == 1) "" else "s"} failed. Tap each item for details."
                } else {
                    null
                }
            )
        }
    }

    private fun publishBatchCancelled(run: BatchRun) {
        val cancelledItems = _uiState.value.items.filter { it.terminalResult == null }
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.terminalResult == null) {
                        item.copy(
                            status = BatchItemStatus.Cancelled,
                            terminalResult = BatchTerminalResult.CANCELLED,
                            message = "Cancelled — no compression result accepted."
                        )
                    } else {
                        item
                    }
                }
            )
        }
        cancelledItems.forEach { item ->
            recordDiagnosticJob(
                diagnostics = run.diagnostics,
                item = item,
                requestedQuality = run.quality,
                effectiveQuality = run.quality,
                resolvedMime = null,
                plannedTargetRatio = null,
                plannedTargetVideoBitrate = null,
                wasStreamCopy = false,
                verification = null,
                outputSize = 0L,
                terminal = BatchTerminalResult.CANCELLED,
                elapsedMs = 0L
            )
        }
        _uiState.update {
            val accounting = it.terminalAccounting
            it.copy(
                isCompressing = false,
                batchMetrics = BatchMetricsSummary(
                    totalElapsedMs = System.currentTimeMillis() - run.batchStartedAt,
                    totalCooldownMs = it.items.sumOf { item -> item.metrics?.cooldownMs ?: 0L },
                    processedCount = accounting.processedCount,
                    realCompressionCount = accounting.realCompressionCount,
                    nonCompressionCount = accounting.nonCompressionCount,
                    failedCount = accounting.failedCount,
                    skippedCount = accounting.skippedCount,
                    cancelledCount = accounting.cancelledCount,
                    totalSavedBytes = accounting.totalBytesSaved
                ),
                statusMessage = "Compression canceled. ${accounting.cancelledCount} item${if (accounting.cancelledCount == 1) "" else "s"} canceled.",
                errorMessage = null
            )
        }
    }

    /**
     * One item, start to finish. Returns the cooldown applied after it, for the next item's
     * record (0 when no cooldown was owed).
     */
    private suspend fun processItem(
        run: BatchRun,
        index: Int,
        item: BatchVideoItem,
        precedingHandoffCooldownMs: Long
    ): Long {
        val thermalWindow = waitForThermalWindow(run.context, item.originalName)
        val s = ItemRun(
            run = run,
            index = index,
            item = item,
            thermalWindow = thermalWindow,
            itemStartedAt = System.currentTimeMillis(),
            precedingHandoffCooldownMs = precedingHandoffCooldownMs,
            plannedFps = outputFpsFor(item, run.frameRate, run.quality)
        )
        val quality = run.quality
        val codec = run.codec
        val token = AttemptToken("batch_${run.batchStartedAt}", index, attemptCounter.incrementAndGet())
        val provisional = estimateOutputSize(item, quality, codec, run.frameRate)
        updateItem(index) {
            ItemProgressModel.begin(it, token, provisional).copy(
                message = if (quality == BatchQualityPreset.REMUX_ONLY) {
                    "Remuxing: video/audio copied unchanged • no re-encode • ${thermalWindow.thermalLabel}"
                } else {
                    "Preparing • est ~${formatFileSize(provisional)} (provisional, before measurement) • ${codec.label}${s.plannedFps?.let { fps -> " • ${fps}fps" } ?: " • source FPS"} • ${thermalWindow.thermalLabel}"
                }
            )
        }
        try {
            val result = ItemPipeline(itemBoard).run(token, object : ItemStages {
                override suspend fun plan(phases: PhaseReporter): Boolean {
                    s.phases = phases
                    return traced("plan", phases.token) { planItem(s) }
                }
                override suspend fun produce(phases: PhaseReporter): Boolean = traced("produce", phases.token) { produceOutput(s) }
                override suspend fun verify(phases: PhaseReporter): Boolean = traced("verify", phases.token) { verifyOutput(s) }
                override suspend fun finalize(phases: PhaseReporter) = traced("finalize", phases.token) { finalizeItem(s) }
                override fun currentPhases(initial: PhaseReporter): PhaseReporter = s.phases
            })
            if (result == ItemPipeline.Result.ENDED_WITHOUT_TERMINAL) {
                DiagLog.w("CompressorBatch", "item ended without a terminal state; job=${diagnosticJobId(item)}; attempt=$token")
            }
        } catch (e: CancellationException) {
            s.deleteCandidatesUnlessAccepted()
            throw e
        } catch (e: Exception) {
            s.deleteCandidatesUnlessAccepted()
            handleItemFailure(s, e)
        } finally {
            s.releaseMedia3Input()
        }
        return s.cooldownForNextItemMs
    }

    /**
     * Stage 1: choose the codec, build (and probe) the Perceptually Lossless plan, and act on
     * every decision that ends the item before anything is written. Returns false when the item
     * is finished.
     */
    private suspend fun planItem(s: ItemRun): Boolean {
        val run = s.run
        val item = s.item
        val quality = run.quality
        val resolvedMime = if (quality == BatchQualityPreset.REMUX_ONLY) {
            null
        } else {
            chooseOutputMime(run.codec, item, quality)
        }
        s.resolvedMime = resolvedMime
        s.diagnosticResolvedMime = resolvedMime
        s.codecLabel = when (resolvedMime) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_AV1 -> "AV1"
            else -> "H.264"
        }
        if (resolvedMime != null) {
            s.iFrameIntervalSeconds = qualityProber.sourceKeyframeIntervalSeconds(item.sourceUri)
        }
        val perceptualPlan = if (quality == BatchQualityPreset.ORIGINAL && resolvedMime != null) {
            val basePlan = buildPerceptualLosslessPlan(item, resolvedMime, run.exhaustivePerceptualLossless)
            if (basePlan.probeEligible) {
                s.phases.enter(ItemPhase.PROBING)
                s.phases.message("Probing quality: sampling windows with on-device VMAF…")
                val probed = refinePlanWithPixelProbes(
                    item, resolvedMime, basePlan, run.exhaustivePerceptualLossless, s.iFrameIntervalSeconds, s.transformerInputUri
                )
                val parseFailure = probed.sourceParseFailure
                // Media3 cannot read this file. Measure again on a copy it may be able to read;
                // the windows and the reference stay on the original (Media3InputNormalizer).
                val measured = if (parseFailure != null && normaliseMedia3Input(s, parseFailure)) {
                    s.phases.message("Probing quality on the rewritten copy: sampling windows with on-device VMAF…")
                    refinePlanWithPixelProbes(
                        item, resolvedMime, basePlan, run.exhaustivePerceptualLossless, s.iFrameIntervalSeconds, s.transformerInputUri
                    )
                } else {
                    probed
                }
                if (measured.sourceParseFailure != null) {
                    // Media3 cannot read this file (or its copy) and nothing was measured. Say so
                    // in the record, and send the item through the encode-failure path ("re-encode
                    // could not be verified"), which never starts an encode, rather than a
                    // keep-original that would read "already efficient": nothing established that.
                    measured.copy(
                        preferRemux = false,
                        remuxReason = null,
                        remuxWasSourceEfficient = false,
                        remuxWasEvidencePreferred = false,
                        probeDetail = listOfNotNull(measured.probeDetail, s.diagnosticMedia3Input).joinToString("; ")
                    )
                } else {
                    measured
                }
            } else {
                basePlan
            }
        } else {
            null
        }
        s.perceptualPlan = perceptualPlan
        s.diagnosticTargetRatio = perceptualPlan?.targetRatio
        s.diagnosticDecisionReason = perceptualPlan?.skipReason ?: perceptualPlan?.remuxReason
        s.diagnosticTargetVideoBitrate = resolvedMime?.let {
            calculateVideoBitrate(
                item, quality, it,
                perceptualPlan?.targetRatio,
                perceptualPlan?.pixelProvenRatio
            )
        }
        s.diagnosticPlan = perceptualPlan
        s.diagnosticSourceAlreadyEfficient = perceptualPlan?.remuxWasSourceEfficient == true
        s.diagnosticEvidencePreferredRemux = perceptualPlan?.remuxWasEvidencePreferred == true
        if (perceptualPlan?.skipReason != null) {
            skipWouldDegrade(s, perceptualPlan)
            return false
        }
        if (perceptualPlan?.preferRemux == true) {
            s.effectiveQuality = BatchQualityPreset.REMUX_ONLY
            s.preEncodeRemuxNote = perceptualPlan.remuxReason
        }
        if (skipDoomedLossyEncode(s)) return false
        if (perceptualPlan?.preferRemux == true && quality != BatchQualityPreset.REMUX_ONLY &&
            retainOriginalUpFront(s, perceptualPlan)
        ) {
            return false
        }
        s.diagnosticEffectiveQuality = s.effectiveQuality
        val encodePlan = if (s.effectiveQuality == BatchQualityPreset.REMUX_ONLY || resolvedMime == null) {
            null
        } else {
            resolveEncodePlan(s, resolvedMime)
        }
        s.resolvedPlan = encodePlan
        encodePlan?.let {
            // The record's planned bitrate is the one the encoder will be given.
            s.diagnosticTargetVideoBitrate = it.requestedVideoBitrate
            s.phases.planResolved(it.estimatedBytes)
        }
        logEncoderPlan(item, s.effectiveQuality, run.codec, encodePlan, s.plannedFps)
        encodePlan?.let {
            run.diagnostics.stage(
                StageEvent(
                    sourceKey = item.sourceUri.toString(), attempt = s.phases.token.attempt,
                    stage = StageEvent.Stage.PLAN, reasonCode = StageEvent.Reason.PLAN_RESOLVED,
                    elapsedMs = s.elapsedMs, fields = mapOf("encodePlan" to it.describe())
                )
            )
        }
        return true
    }

    /**
     * The encode this item will run, resolved from the plan the probes left (ResolvedEncodePlan).
     * The encoder request, the row's estimate and the plan log all read this one value. [ratio]
     * overrides the plan's ratio for a retry at a different, already-measured rung.
     */
    private fun resolveEncodePlan(s: ItemRun, mime: String, ratio: Double? = null): ResolvedEncodePlan {
        val plan = s.perceptualPlan?.takeIf { !it.preferRemux }
        val retry = ratio != null
        return ResolvedEncodePlan.resolve(
            source = s.item.toSourceInfo(),
            mode = s.run.quality.toMode(),
            outputMime = mime,
            outputFps = s.plannedFps,
            outputHeight = targetHeightFor(s.item, s.run.quality),
            targetRatio = ratio ?: plan?.targetRatio,
            pixelProvenRatioFloor = ratio ?: plan?.pixelProvenRatio,
            // A retry's own size check supplies its overshoot; the plan's is for the plan's ratio.
            overshootPreClamp = if (retry) s.retryOvershoot else plan?.sizeGateOvershoot
        )
    }

    /** Positive pixel evidence says compression would visibly degrade this clip: write nothing. */
    private fun skipWouldDegrade(s: ItemRun, plan: PerceptualLosslessPlan) {
        val skipReason = checkNotNull(plan.skipReason) { "skipWouldDegrade without a skip reason" }
        recordDiagnosticJob(
            diagnostics = s.run.diagnostics,
            item = s.item,
            requestedQuality = s.run.quality,
            effectiveQuality = s.run.quality,
            resolvedMime = s.resolvedMime,
            plannedTargetRatio = plan.targetRatio,
            plannedTargetVideoBitrate = s.diagnosticTargetVideoBitrate,
            plannedDecisionReason = skipReason,
            wasStreamCopy = false,
            verification = null,
            outputSize = 0L,
            terminal = BatchTerminalResult.SKIPPED_WOULD_DEGRADE,
            elapsedMs = s.elapsedMs,
            probedRatios = plan.probedRatios,
            pixelProvenRatio = plan.pixelProvenRatio,
            probeDetail = plan.probeDetail,
            probeWindowScores = plan.probeWindowScores,
            probePairDiag = plan.probePairDiag,
            probeV1Scores = plan.probeV1Scores,
            probeRateDiag = plan.probeRateDiag,
            precedingCooldownMs = s.precedingHandoffCooldownMs
        )
        updateItem(s.index) {
            it.copy(
                status = BatchItemStatus.Skipped,
                progress = 1f,
                currentOutputSize = 0L,
                targetOutputSize = 0L,
                terminalResult = BatchTerminalResult.SKIPPED_WOULD_DEGRADE,
                message = skipReason
            )
        }
    }

    /**
     * Doomed-encode guard for the LOSSY modes. When the source already sits at or below its
     * resolution's bitrate floor, the floor clamps the target back up to the source bitrate, so
     * the encode cannot produce a smaller file — it burns full encode time and yields a
     * same-or-larger output that is then discarded. Reaching that same honest verdict up front
     * keeps the original and skips the wasted work (measured: 11 clips, 7.9 min, +25 MB of
     * discarded output on the 2026-07-31 S23 batch). Fails open on unknown bitrate; PL/Remux are
     * excluded inside the policy, so this can never divert a Perceptually Lossless decision.
     * Only a PURE BITRATE re-encode may be skipped. If the user explicitly chose an output
     * codec, the transcode itself is the thing they asked for and its output is delivered to
     * them as a copy even when it is not smaller — so skipping it would silently withhold a
     * requested result. Under Auto the app picks the codec itself, so nothing the user asked for
     * is lost. (FPS caps and resolution changes are excluded inside lossyTargetHasNoHeadroom for
     * the same reason.)
     */
    private fun skipDoomedLossyEncode(s: ItemRun): Boolean {
        val run = s.run
        val item = s.item
        val resolvedMime = s.resolvedMime
        val doomed = run.codec == BatchCodecOption.AUTO &&
            s.effectiveQuality != BatchQualityPreset.REMUX_ONLY && resolvedMime != null &&
            BatchQualityBitratePolicy.lossyTargetHasNoHeadroom(
                source = item.toSourceInfo(),
                mode = s.effectiveQuality.toMode(),
                outputMimeType = resolvedMime,
                outputFps = s.plannedFps,
                outputHeight = targetHeightFor(item, s.effectiveQuality)
            )
        if (!doomed) return false
        // Keep the original and write NOTHING — deliberately the same no-output shape as the
        // SKIPPED_WOULD_DEGRADE path, NOT a switch to Remux Only. Routing these into the remux
        // stream copy would expose non-MP4 sources (e.g. the MKV/AV1 clips in real libraries) to
        // a muxer that cannot carry them, turning a merely-wasteful encode into an outright
        // failure. Nothing is re-encoded, copied, or replaced here.
        val skipMessage =
            "Already efficient for ${run.quality.label}: this video's bitrate is already at or " +
                "below the quality floor for its resolution, so re-encoding could not make it " +
                "smaller. Original kept unchanged."
        DiagLog.i(
            "CompressorBatch",
            "doomed-encode skip; job=${diagnosticJobId(item)}; mode=${run.quality.label}; " +
                "sourceVideoBitrate=${item.toSourceInfo().videoBitrate}; encode skipped, original kept"
        )
        recordDiagnosticJob(
            diagnostics = run.diagnostics,
            item = item,
            requestedQuality = run.quality,
            effectiveQuality = run.quality,
            resolvedMime = resolvedMime,
            plannedTargetRatio = null,
            plannedTargetVideoBitrate = s.diagnosticTargetVideoBitrate,
            plannedDecisionReason = "lossy target has no headroom below the source bitrate",
            wasStreamCopy = false,
            verification = null,
            outputSize = 0L,
            terminal = BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED,
            elapsedMs = s.elapsedMs,
            precedingCooldownMs = s.precedingHandoffCooldownMs
        )
        updateItem(s.index) {
            it.copy(
                status = BatchItemStatus.Skipped,
                progress = 1f,
                currentOutputSize = 0L,
                targetOutputSize = 0L,
                outputSize = 0L,
                terminalResult = BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED,
                message = skipMessage
            )
        }
        return true
    }

    /**
     * Keep-original remux FAST PATH (perf/remux-keep-original-fast-path): when the pipeline has
     * already DECIDED to keep the original bytes, and the audited policy proves the copy would
     * be a pure no-op (no privacy strip, compatible container, readable source, user did not
     * choose Remux Only), surface the original directly — no copy written, no copy verified,
     * original never opened for write. Any guard failing falls through to the unchanged full
     * remux. Evidence: 40.3 min/172-file batch spent stream-copying keep-original items (max
     * 267 s to save 0 bytes) — docs/pr23/REMUX_ACCELERATION_INVESTIGATION.md.
     *
     * @return true when the original was retained and the item is finished.
     */
    private fun retainOriginalUpFront(s: ItemRun, plan: PerceptualLosslessPlan): Boolean {
        val run = s.run
        val item = s.item
        val context = run.context
        val resolvedContainerMime = runCatching {
            context.contentResolver.getType(item.sourceUri)
        }.getOrNull()
        val sourceReadableNow = runCatching {
            context.contentResolver.openFileDescriptor(item.sourceUri, "r")?.use { true } == true
        }.getOrDefault(false)
        val reuse = OriginalReusePolicy.evaluate(
            isKeepOriginalDecision = true,
            userRequestedRemuxOnly = false,
            privacyMode = run.privacyMode,
            resolvedContainerMime = resolvedContainerMime,
            sourceReadableNow = sourceReadableNow
        )
        if (reuse is OriginalReuseDecision.Blocked) {
            DiagLog.i(
                "CompressorBatch",
                "keep-original fast path blocked; job=${diagnosticJobId(item)}; " +
                    "reason=${reuse.reason}; falling through to full remux"
            )
            s.diagnosticReuseBlockReason = reuse.reason.name
            return false
        }
        reuse as OriginalReuseDecision.Eligible
        // Honest typed validation: records ONLY what was actually checked (read-open at decision
        // time). Never an OutputVerificationReport — no output exists and no output verification
        // ran.
        val retention = OriginalReusePolicy.retainedSourceValidation(
            sourceReadable = sourceReadableNow,
            sourceSizeBytes = item.originalSize,
            containerMime = reuse.containerMime,
            nowEpochMs = System.currentTimeMillis()
        )
        val terminal = BatchTerminalClassifier.classify(
            BatchTerminalInput(
                requestedMode = run.quality.toMode(),
                effectiveMode = BatchQualityMode.REMUX_ONLY,
                wasStreamCopy = false,
                verified = retention.readableAtDecisionTime,
                replacementSafe = false,
                sourceSize = item.originalSize,
                outputSize = item.originalSize,
                preEncodeSourceAlreadyEfficient = plan.remuxWasSourceEfficient,
                preEncodeEvidencePreferredRemux = plan.remuxWasEvidencePreferred,
                retainedOriginalNoOutput = true
            )
        )
        DiagLog.i(
            "CompressorBatch",
            "keep-original fast path; job=${diagnosticJobId(item)}; " +
                "materialization=REUSED_SOURCE; copyAvoidedBytes=${item.originalSize}; " +
                "container=$resolvedContainerMime; terminal=$terminal"
        )
        recordDiagnosticJob(
            diagnostics = run.diagnostics,
            item = item,
            requestedQuality = run.quality,
            effectiveQuality = BatchQualityPreset.REMUX_ONLY,
            resolvedMime = null,
            plannedTargetRatio = plan.targetRatio,
            plannedTargetVideoBitrate = s.diagnosticTargetVideoBitrate,
            plannedDecisionReason = plan.remuxReason,
            decisionBasis = KeepOriginalMessages.basis(
                evidencePreferred = plan.remuxWasEvidencePreferred,
                probedRatios = plan.probedRatios,
                probeDetail = plan.probeDetail,
                pixelCertifiableBlockReason = plan.pixelCertifiableBlockReason,
                sizeGateBasis = plan.sizeGateBasis
            ),
            media3Input = s.diagnosticMedia3Input,
            wasStreamCopy = false,
            verification = null,
            retainedValidation = retention,
            outputSize = item.originalSize,
            terminal = terminal,
            elapsedMs = s.elapsedMs,
            probedRatios = plan.probedRatios,
            pixelProvenRatio = plan.pixelProvenRatio,
            probeDetail = plan.probeDetail,
            probeWindowScores = plan.probeWindowScores,
            probePairDiag = plan.probePairDiag,
            probeV1Scores = plan.probeV1Scores,
            probeRateDiag = plan.probeRateDiag,
            precedingCooldownMs = s.precedingHandoffCooldownMs,
            materializationMode = "REUSED_SOURCE",
            copyAvoidedBytes = item.originalSize
        )
        val elapsed = s.elapsedMs
        val thermalWindow = s.thermalWindow
        updateItem(s.index) {
            it.copy(
                status = if (terminal.isFailure) BatchItemStatus.Failed else BatchItemStatus.Done,
                progress = 1f,
                currentOutputSize = item.originalSize,
                outputUri = if (terminal.isFailure) null else item.sourceUri,
                outputPath = null,
                outputSize = if (terminal.isFailure) 0L else item.originalSize,
                outputMode = BatchQualityPreset.REMUX_ONLY.label,
                // No OutputVerificationReport exists for a retained source — the honest record is
                // the typed RetainedSourceValidation in diagnostics; the item message carries the
                // user-facing truth.
                verificationReport = null,
                terminalResult = terminal,
                metrics = BatchItemMetrics(
                    operationLabel = "Retained",
                    elapsedMs = elapsed,
                    outputBytes = 0L,
                    savedBytes = 0L,
                    thermalStart = thermalWindow.thermalLabel,
                    thermalEnd = thermalWindow.thermalLabel,
                    batteryStart = thermalWindow.batteryPercent,
                    batteryEnd = thermalWindow.batteryPercent,
                    cooldownMs = 0L
                ),
                message = if (terminal.isFailure) {
                    "Original retention failed: source became unreadable — nothing was modified."
                } else {
                    KeepOriginalMessages.upFront(
                        reason = plan.remuxReason,
                        evidencePreferred = plan.remuxWasEvidencePreferred,
                        probedRatios = plan.probedRatios,
                        probeDetail = plan.probeDetail,
                        pixelCertifiableBlockReason = plan.pixelCertifiableBlockReason,
                        sizeGateBasis = plan.sizeGateBasis
                    )
                }
            )
        }
        return true
    }

    /**
     * Stage 2: produce the output file — a stream copy for Remux, otherwise the encode plus the
     * metadata remux. Returns false when the item finished here (an encoder failure whose
     * original was retained).
     */
    private suspend fun produceOutput(s: ItemRun): Boolean {
        val run = s.run
        val item = s.item
        val context = run.context
        val remuxResult = if (s.effectiveQuality == BatchQualityPreset.REMUX_ONLY) {
            s.candidateFiles += item.cacheOutputFile(context, BatchQualityPreset.REMUX_ONLY)
            remuxOnlyOne(context, item, s.phases, run.privacyMode)
        } else {
            val safeResolvedMime = s.resolvedMime
                ?: throw IllegalStateException("Encoder selection failed before export planning. Use Remux Only or choose a different codec.")
            try {
                s.candidateFiles += item.cacheOutputFile(context, run.quality)
                val attempt = encodeReadingSource(s, safeResolvedMime)
                s.encodeAttempt = attempt
                s.phases.message("Finalizing: writing the source's metadata into the output…")
                withContext(Dispatchers.IO) {
                    val remuxContext = currentCoroutineContext()
                    Mp4MetadataRemuxer.remuxWithSourceMetadata(
                        context,
                        attempt.file,
                        item.metadataSnapshot.filteredForPrivacy(run.privacyMode),
                        cancellationCheck = { remuxContext.ensureActive() }
                    )
                }
            } catch (e: ExportException) {
                fallBackAfterEncodeFailure(
                    s, e, "encoder export failed (${e.errorCodeName})",
                    muxingTimeout = e.errorCode == ExportException.ERROR_CODE_MUXING_TIMEOUT
                ) ?: return false
            } catch (e: SourceParseException) {
                fallBackAfterEncodeFailure(
                    s, e,
                    "encoder could not read the source (${e.failure.describe()})" +
                        (s.diagnosticMedia3Input?.let { "; $it" } ?: ""),
                    muxingTimeout = false
                ) ?: return false
            }
        }
        s.remuxResult = remuxResult
        s.outputFile = remuxResult.outputFile
        s.outputUri = Uri.fromFile(remuxResult.outputFile)
        // The closed, rewritten file: the first size that means anything. Still only a candidate.
        s.outputSize = remuxResult.outputFile.length()
        s.phases.candidateReady(s.outputSize)
        s.resolvedPlan?.let { plan ->
            run.diagnostics.stage(
                StageEvent(
                    sourceKey = item.sourceUri.toString(), attempt = s.phases.token.attempt,
                    stage = StageEvent.Stage.FINALIZE, reasonCode = StageEvent.Reason.CANDIDATE_FINALIZED,
                    elapsedMs = s.elapsedMs,
                    fields = mapOf(
                        "candidateBytes" to s.outputSize,
                        "predictedBytes" to plan.estimatedBytes,
                        "requestedVideoBitrate" to plan.requestedVideoBitrate,
                        "reportedVideoBitrate" to s.encodeAttempt?.reportedAverageVideoBitrate,
                        "encoderName" to s.encodeAttempt?.videoEncoderName
                    )
                )
            )
        }
        return true
    }

    /**
     * The full encode. Media3 reads the source, or the normalised copy the probes already moved to.
     * After a SourceParseFailure it reads once more from a platform-normalised copy
     * (Media3InputNormalizer) when one can be made. The copy is deleted as soon as the encode ends:
     * verification, certification and the metadata remux only use the original and the output.
     */
    private suspend fun encodeReadingSource(s: ItemRun, mime: String): EncodeAttemptResult {
        // The probes already found that Media3 cannot read its input (and planItem has already
        // tried the copy, if one could be made): starting the encode would only wait it out.
        s.perceptualPlan?.sourceParseFailure?.let { throw SourceParseException(it) }
        val run = s.run
        val plan = s.perceptualPlan
        val encodePlan = s.resolvedPlan ?: resolveEncodePlan(s, mime).also { s.resolvedPlan = it }
        suspend fun encode() = compressOne(
            run.context,
            s.item,
            s.index,
            run.quality,
            run.frameRate,
            mime,
            plan = encodePlan,
            phases = s.phases,
            useCbrCeiling = plan?.useCbrCeiling == true,
            iFrameIntervalSeconds = s.iFrameIntervalSeconds,
            transformerInputUri = s.transformerInputUri
        )
        s.encodeStartedAt = System.currentTimeMillis()
        return try {
            try {
                encode().also { s.lastEncodeMs = System.currentTimeMillis() - s.encodeStartedAt }
            } catch (e: SourceParseException) {
                if (s.media3Input != null) {
                    s.diagnosticMedia3Input = "platform-normalised copy also unreadable by Media3 (${e.failure.describe()})"
                    throw e
                }
                if (!normaliseMedia3Input(s, e.failure)) throw e
                s.phases.message("Encoding from the rewritten copy…")
                try {
                    encode()
                } catch (again: SourceParseException) {
                    s.diagnosticMedia3Input = "platform-normalised copy also unreadable by Media3 (${again.failure.describe()})"
                    throw again
                }
            }
        } finally {
            s.releaseMedia3Input()
        }
    }

    /**
     * After Media3 could not parse what it read for [s]: write the platform-normalised copy it
     * will read instead (Media3InputNormalizer). False, with the reason recorded, when no copy can
     * be made. At most one attempt per item.
     */
    private suspend fun normaliseMedia3Input(s: ItemRun, failure: SourceParseFailure): Boolean {
        if (s.media3InputAttempted) return s.media3Input != null
        s.media3InputAttempted = true
        val item = s.item
        val context = s.run.context
        val job = diagnosticJobId(item)
        if (failure.inputPosition >= 0L) {
            withContext(Dispatchers.IO) { SourceBytes.hexAround(context, item.sourceUri, failure.inputPosition) }
                ?.let { DiagLog.w("CompressorBatch", "source parse failure; job=$job; ${failure.describe()}; $it") }
            // Missing data is not malformed data: no copy can restore video that is not in the file.
            val zeros = withContext(Dispatchers.IO) { SourceBytes.zeroFraction(context, item.sourceUri, failure.inputPosition) }
            if (zeros != null && zeros >= SourceBytes.DAMAGED_ZERO_FRACTION) {
                s.diagnosticMedia3Input = "not normalised: the source is damaged: " +
                    "${String.format(java.util.Locale.US, "%.1f", zeros * 100)} % of the ${SourceBytes.ZERO_SCAN_SPAN / 1024} KiB " +
                    "around byte ${failure.inputPosition} are zero bytes, so there is no video there for any reader to recover"
                DiagLog.w("CompressorBatch", "media3 input; job=$job; ${failure.describe()}; ${s.diagnosticMedia3Input}")
                return false
            }
        }
        val decline = Media3InputNormalizer.declineReason(
            isHdr = item.toSourceInfo().isHdr,
            sourceBytes = item.originalSize,
            usableBytes = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L)
        )
        if (decline != null) {
            s.diagnosticMedia3Input = "not normalised: $decline"
            DiagLog.w("CompressorBatch", "media3 input; job=$job; ${failure.describe()}; ${s.diagnosticMedia3Input}")
            return false
        }
        s.phases.message("The encoder cannot read this file as stored; rewriting its container (no re-encode)…")
        val target = File(context.cacheDir, "media3input_${System.nanoTime()}.mp4")
        val result = withContext(Dispatchers.IO) {
            val ioContext = currentCoroutineContext()
            Media3InputNormalizer.normalise(
                context,
                item.sourceUri,
                target,
                item.metadataSnapshot.rotationDegrees,
                sourceBytes = item.originalSize,
                cancellationCheck = { ioContext.ensureActive() }
            )
        }
        return when (result) {
            is Media3InputNormalizer.Result.Normalised -> {
                s.media3Input = result.file
                s.diagnosticMedia3Input = "platform-normalised copy; ${result.compact()}"
                DiagLog.i("CompressorBatch", "media3 input; job=$job; after ${failure.describe()}; ${s.diagnosticMedia3Input}")
                true
            }
            is Media3InputNormalizer.Result.Declined -> {
                s.diagnosticMedia3Input = "normalisation failed: ${result.reason}"
                DiagLog.w("CompressorBatch", "media3 input; job=$job; after ${failure.describe()}; ${s.diagnosticMedia3Input}")
                false
            }
        }
    }

    /**
     * An encode that failed before verification. For a Perceptually Lossless item, the honest
     * answer is the untouched original (or, when that cannot be reused, the verified stream copy);
     * null means the original was retained and the item is finished. Other modes rethrow into the
     * item failure path, with [reason] recorded.
     */
    private suspend fun fallBackAfterEncodeFailure(
        s: ItemRun,
        e: Exception,
        reason: String,
        muxingTimeout: Boolean
    ): Mp4MetadataRemuxResult? {
        val run = s.run
        val item = s.item
        val context = run.context
        s.diagnosticEncoderFailed = true
        s.diagnosticFallbackReason = reason
        // A rejected encoder configuration or export failure must never strand a
        // Perceptually Lossless item as "Failed": the honest production answer is the
        // untouched original (or, when that cannot be reused, the verified stream copy).
        val perceptualPlan = s.perceptualPlan ?: throw e
        DiagLog.w(
            "CompressorBatch",
            "Perceptually lossless encode failed before verification for ${diagnosticJobId(item)}: $reason; falling back to remux"
        )
        if (muxingTimeout) {
            // The two 30-minute sources in b161/b163 timed out here with nothing in the
            // record to explain it. The keyframe structure is the first thing to know.
            qualityProber.describeKeyframes(item.sourceUri)?.let {
                DiagLog.w("CompressorBatch", "muxing timeout; job=${diagnosticJobId(item)}; source $it")
            }
        }
        // An encoder or muxer failure says nothing about the ratio (see
        // LearningEvidencePolicy.Kind.PIPELINE), so it teaches nothing.
        DiagLog.i(
            "CompressorLearning",
            "result=pipeline_failure; profileKey=${perceptualPlan.profileKey.asKey()}; usedRatio=${perceptualPlan.targetRatio}; " +
                "reason=$reason; learned state unchanged"
        )
        if (retainOriginalInsteadOfCopy(
                context = context,
                diagnostics = run.diagnostics,
                item = item,
                index = s.index,
                quality = run.quality,
                privacyMode = run.privacyMode,
                plan = perceptualPlan,
                resolvedMime = s.diagnosticResolvedMime,
                plannedTargetVideoBitrate = s.diagnosticTargetVideoBitrate,
                plannedDecisionReason = s.diagnosticDecisionReason,
                evidence = DiscardedAttemptEvidence(
                    fallbackReason = reason,
                    encoderFailed = true,
                    media3Input = s.diagnosticMedia3Input
                ),
                itemStartedAt = s.itemStartedAt,
                thermalStart = s.thermalWindow,
                precedingCooldownMs = s.precedingHandoffCooldownMs
            )
        ) {
            s.candidateFiles.forEach { runCatching { it.delete() } }
            // The encoder failed without producing an output (encodeAttempt is null), so
            // no cooldown is owed. That matches the stream-copy path this replaces.
            return null
        }
        s.effectiveQuality = BatchQualityPreset.REMUX_ONLY
        s.diagnosticEffectiveQuality = s.effectiveQuality
        s.preEncodeRemuxNote = "Remux Fallback Kept: the encoder rejected the perceptually lossless attempt ($reason)"
        s.candidateFiles += item.cacheOutputFile(context, BatchQualityPreset.REMUX_ONLY)
        return remuxOnlyOne(context, item, s.phases, run.privacyMode)
    }

    /**
     * Stage 3: structural verification, floor recovery, sampled pixel certification and the
     * Perceptually Lossless fallback. Returns false when the item finished here.
     */
    private enum class CertStep { CONTINUE, ENDED, RETRY }

    private suspend fun verifyOutput(s: ItemRun): Boolean {
        verifyStructurally(s)
        when (certifyPixels(s)) {
            CertStep.CONTINUE -> Unit
            CertStep.ENDED -> return false
            CertStep.RETRY -> return retrySaferRung(s)
        }
        if (!applyPerceptualVerdict(s)) return false
        return true
    }

    private suspend fun verifyStructurally(s: ItemRun) {
        val run = s.run
        val item = s.item
        val context = run.context
        val outputFile = checkNotNull(s.outputFile) { "verification before an output exists" }
        val perceptualPlan = s.perceptualPlan
        // A pixel-proven ratio replaces the class-level verification floor with the proven one
        // minus the encoder-undershoot tolerance measured in the field (requests land within ~6%
        // on this device class); certification below re-checks the real pixels regardless.
        val pixelProvenVerifierFloor = perceptualPlan?.pixelProvenRatio?.let { proven ->
            ((proven - PIXEL_PROVEN_UNDERSHOOT_TOLERANCE) *
                item.toSourceInfo().videoBitrate).toInt().coerceAtLeast(1)
        }
        var verification = withContext(Dispatchers.IO) {
            OutputVerifier.verify(
                context, item, outputFile, s.effectiveQuality.label, run.privacyMode,
                pixelProvenVideoBitrateFloor = pixelProvenVerifierFloor
            )
        }
        // Measured request-vs-actual encoder behavior for this attempt. Prefer Media3's own
        // reported average; fall back to the size/duration measurement.
        val outputSize = s.outputSize
        s.measuredOvershoot = s.encodeAttempt?.let { attempt ->
            when {
                attempt.requestedVideoBitrate <= 0 -> null
                attempt.reportedAverageVideoBitrate > 0 ->
                    attempt.reportedAverageVideoBitrate.toDouble() / attempt.requestedVideoBitrate
                item.durationMs > 0 && outputSize > 0 ->
                    ((outputSize * 8000.0 / item.durationMs) - item.originalAudioBitrate.coerceAtLeast(0)) /
                        attempt.requestedVideoBitrate
                else -> null
            }
        }
        // Floor recovery: when the ONLY verification failure is the inferred video bitrate floor
        // (structure, color, audio, timing, metadata all passed) and the output is strictly
        // smaller, sampled pixel certification gets the final word — a VBR encoder undershooting
        // its request on easy content is quality saturation, not necessarily degradation, and
        // only pixels can tell which. A measured PASS re-verifies with the certified bitrate as
        // the pixel-proven floor, so OutputVerifier remains the sole source of the final verdict;
        // a failed or unmeasurable certification changes nothing and the encode falls back
        // exactly as before.
        if (s.effectiveQuality == BatchQualityPreset.ORIGINAL &&
            perceptualPlan != null && perceptualPlan.pixelCertifiable &&
            verification.failedOnlyOnVideoBitrateFloor &&
            item.originalSize > 0L && outputSize in 1 until item.originalSize
        ) {
            s.phases.enter(ItemPhase.CERTIFYING)
            s.phases.message("Certifying pixels: encoder undershot the bitrate floor, checking real quality…")
            val recoveryOutcome = qualityProber.certify(
                item.sourceUri, outputFile, item.durationMs, item.originalFps.toDouble(),
                onWindowScored = { done, total -> s.phases.certifyStep(done, total) }
            )
            val recoveryScores = (recoveryOutcome as? PairScoreOutcome.Scored)?.windows
            s.diagnosticCertWindowScores = compactWindowScores(recoveryScores)
            s.diagnosticCertBandingDiag = compactBandingDiag(recoveryScores)
            if (QualityProbePolicy.windowsPass(recoveryScores)) {
                s.floorRecoveryCertScores = recoveryScores
                val certifiedVideoBitrate = s.encodeAttempt?.reportedAverageVideoBitrate?.takeIf { it > 0 }
                    ?: if (item.durationMs > 0 && outputSize > 0) {
                        ((outputSize * 8000.0 / item.durationMs) - item.originalAudioBitrate.coerceAtLeast(0))
                            .toInt().coerceAtLeast(1)
                    } else {
                        1
                    }
                DiagLog.i(
                    "CompressorProbe",
                    "floor recovery; job=${diagnosticJobId(item)}; sampled windows passed; " +
                        "re-verifying with pixel-certified floor $certifiedVideoBitrate"
                )
                verification = withContext(Dispatchers.IO) {
                    OutputVerifier.verify(
                        context, item, outputFile, s.effectiveQuality.label, run.privacyMode,
                        pixelProvenVideoBitrateFloor = certifiedVideoBitrate
                    )
                }
            } else {
                // Same evidence typing as final certification (CertificationDecision). The fallback
                // that follows is the structural bitrate-floor rule, unchanged; only the record
                // now says whether the recovery measured anything.
                val cause = when (CertificationDecision.of(recoveryOutcome)) {
                    CertificationDecision.INSUFFICIENT_EVIDENCE -> "undecided (a window held too few frames)"
                    CertificationDecision.MISALIGNED -> "rejected (output frames not time-alignable)"
                    CertificationDecision.UNAVAILABLE -> "unavailable"
                    else -> "failed"
                }
                s.failedFloorRecoveryStatus = CertificationStatus.forFailedRecoveryOutcome(recoveryOutcome)
                DiagLog.i(
                    "CompressorProbe",
                    "floor recovery; job=${diagnosticJobId(item)}; certification $cause; fallback proceeds"
                )
            }
        }
        s.verification = verification
        s.phases.enter(ItemPhase.VERIFYING)
        // Record WHY certification will not run before the gate, so a null certWindowScores is
        // never unexplained. Two 219-job captures had null certification fields for all 438 jobs
        // with nothing saying which gate closed. Overwritten with the real outcome when it runs.
        s.diagnosticCertStatus = when {
            // A recovery attempt that ran and measured windows outranks every skip reason
            // below: those describe certification that never started.
            s.failedFloorRecoveryStatus != null -> s.failedFloorRecoveryStatus
            s.effectiveQuality != BatchQualityPreset.ORIGINAL ->
                CertificationStatus.SKIPPED_NOT_PL_MODE
            perceptualPlan == null -> CertificationStatus.SKIPPED_NO_PLAN
            !perceptualPlan.pixelCertifiable ->
                perceptualPlan.pixelCertifiableBlockReason ?: CertificationStatus.SKIPPED_NO_PLAN
            PerceptualLosslessVerifier.shouldFallbackToRemux(
                verification, item.originalSize, outputSize
            ) -> CertificationStatus.SKIPPED_FELL_BACK_TO_REMUX
            else -> null
        }
    }

    /**
     * Sampled pixel certification of the full output (any SDR, non-downgrade encode whose
     * geometry the scorer can handle — NOT just ladder-eligible ones). Measured-bad always fails;
     * a certification failure SKIPS the item — pixel evidence just proved the encode degrades
     * this clip, so the honest outcome is the untouched original, not a stream-copy that saves
     * nothing.
     *
     * Unmeasurable evidence is handled by two different rules depending on what justified the
     * target. When a ladder ran, a sub-default ratio rests on pixel evidence alone and must fail
     * closed without it. When no ladder ran (4K-class), the target never depended on pixels, so
     * the structural verdict stands exactly as it does today — certification can only ADD proof
     * for these sources.
     *
     * @return false when the item finished here.
     */
    private suspend fun certifyPixels(s: ItemRun): CertStep {
        val run = s.run
        val item = s.item
        val perceptualPlan = s.perceptualPlan
        val verification = checkNotNull(s.verification) { "certification before verification" }
        val outputFile = checkNotNull(s.outputFile) { "certification before an output exists" }
        if (!(s.effectiveQuality == BatchQualityPreset.ORIGINAL &&
                perceptualPlan != null && perceptualPlan.pixelCertifiable &&
                !PerceptualLosslessVerifier.shouldFallbackToRemux(verification, item.originalSize, s.outputSize))
        ) {
            return CertStep.CONTINUE
        }
        s.phases.enter(ItemPhase.CERTIFYING)
        s.phases.message("Certifying pixels: sampled VMAF check of the final output…")
        val certOutcome = s.floorRecoveryCertScores?.let { PairScoreOutcome.Scored(it) }
            ?: qualityProber.certify(
                item.sourceUri, outputFile, item.durationMs, item.originalFps.toDouble(),
                onWindowScored = { done, total -> s.phases.certifyStep(done, total) }
            )
        val certScores = (certOutcome as? PairScoreOutcome.Scored)?.windows
        s.diagnosticCertWindowScores = compactWindowScores(certScores)
        s.diagnosticCertBandingDiag = compactBandingDiag(certScores)
        s.diagnosticCertV1Scores = compactV1Scores(certScores)
        val certOk = if (perceptualPlan.requiresMeasuredCertification) {
            ExhaustivePerceptualLosslessPolicy.measuredCertificationPasses(certOutcome)
        } else if (perceptualPlan.probeEligible) {
            QualityProbePolicy.certificationOutcomePasses(
                usedRatio = perceptualPlan.targetRatio,
                defaultRatio = perceptualPlan.defaultRatio,
                outcome = certOutcome
            )
        } else {
            QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(certOutcome)
        }
        // What the outcome is evidence of (CertificationDecision). It changes no acceptance: certOk
        // above is exactly the rule it was. It decides what a non-pass is called and taught.
        val decision = CertificationDecision.of(certOutcome)
        s.certificationDecision = decision
        // Pixel proof requires BOTH a pass AND measured windows — see
        // QualityProbePolicy.isPixelCertified (pure + unit-tested) for why certOk alone is
        // insufficient.
        s.diagnosticCertStatus = CertificationStatus.forOutcome(certOutcome)
        s.pixelCertifiedThisRun = QualityProbePolicy.isPixelCertified(certOk, certOutcome)
        DiagLog.i(
            "CompressorProbe",
            "certification; job=${diagnosticJobId(item)}; usedRatio=${perceptualPlan.targetRatio}; " +
                "windows=${certScores?.size ?: 0}; pass=$certOk; decision=${decision.wire}; " +
                "scores=${certScores?.joinToString { "%.1f/%.1f/%.1f".format(java.util.Locale.US, it.mean, it.p5, it.min) } ?: "unmeasured"}"
        )
        run.diagnostics.stage(
            StageEvent(
                sourceKey = item.sourceUri.toString(), attempt = s.phases.token.attempt,
                stage = StageEvent.Stage.CERTIFY,
                reasonCode = when (decision) {
                    CertificationDecision.PASSED -> StageEvent.Reason.CERT_PASSED
                    CertificationDecision.MEASURED_FAILURE -> StageEvent.Reason.CERT_MEASURED_FAILURE
                    CertificationDecision.INSUFFICIENT_EVIDENCE -> StageEvent.Reason.CERT_INSUFFICIENT
                    CertificationDecision.UNAVAILABLE -> StageEvent.Reason.CERT_UNAVAILABLE
                    CertificationDecision.MISALIGNED -> StageEvent.Reason.CERT_MISALIGNED
                },
                elapsedMs = s.elapsedMs,
                fields = mapOf(
                    "usedRatio" to perceptualPlan.targetRatio,
                    "accepted" to certOk,
                    "pixelCertified" to s.pixelCertifiedThisRun,
                    "floorRecoveryScores" to (s.floorRecoveryCertScores != null)
                ) + StageEvent.windowFields("cert", certScores)
            )
        )
        if (certOk) return CertStep.CONTINUE
        // Only a certification that measured this output below the bar (or measured its frames
        // out of time) may say the encode loses quality and teach the profile so. "Unavailable"
        // and "too few frames in a window" are the absence of a decision: the original is still
        // kept (the plan needed pixel proof and did not get it), but it is not labelled "would
        // visibly lose quality" and the learning engine is not told anything.
        val certMeasured = decision.isMeasuredNegative
        val certReason = when {
            decision == CertificationDecision.MISALIGNED ->
                "pixel certification rejected: output frames could not be " +
                    "time-aligned with the source (frame loss or retiming)"
            decision == CertificationDecision.INSUFFICIENT_EVIDENCE ->
                "pixel certification undecided: a window held ${CertificationDecision.fewestFrames(certOutcome)} " +
                    "compared frames, fewer than ${QualityProbePolicy.MIN_COMPARED_FRAMES_PER_WINDOW}, and no " +
                    "adequately sampled window was below the bar"
            certScores == null && perceptualPlan.requiresMeasuredCertification ->
                "pixel certification could not measure this output, and this encode " +
                    "overturned a keep-original decision, so it needs measured proof"
            certScores == null ->
                "pixel certification unavailable for a sub-default-ratio encode"
            else ->
                "pixel certification failed (sampled VMAF below thresholds)"
        }
        val certTerminal = CertificationFailure.terminalFor(decision)
        s.diagnosticFallbackReason = certReason
        s.diagnosticDiscardedVideoBitrate = s.encodeAttempt?.reportedAverageVideoBitrate?.takeIf { it > 0 }
        // Learning, once per attempt: this attempt's measured failure is recorded here and nowhere
        // else, whether or not a retry follows. A retry is a separate attempt at a separate ratio.
        val learned = CertificationFailure.learn(
            learningEngine, decision, perceptualPlan.profileKey, perceptualPlan.targetRatio,
            certReason, perceptualPlan.floorRatio, s.measuredOvershoot
        )
        if (learned != null) {
            DiagLog.i(
                "CompressorLearning",
                "result=failure; profileKey=${perceptualPlan.profileKey.asKey()}; usedRatio=${perceptualPlan.targetRatio}; " +
                    "reason=$certReason; nextRatio=${learned.nextTargetRatio}; preferRemux=${learned.preferRemux}"
            )
        } else {
            DiagLog.i(
                "CompressorLearning",
                "result=unmeasured; profileKey=${perceptualPlan.profileKey.asKey()}; usedRatio=${perceptualPlan.targetRatio}; " +
                    "decision=${decision.wire}; reason=$certReason; learned state unchanged (no evidence)"
            )
        }
        s.attemptLog += attemptSummary(s, perceptualPlan.targetRatio, decision.wire)
        runCatching { outputFile.delete() }
        if (saferRungRetryAllowed(s, decision)) return CertStep.RETRY
        recordDiagnosticJob(
            diagnostics = run.diagnostics,
            item = item,
            requestedQuality = run.quality,
            effectiveQuality = run.quality,
            resolvedMime = s.diagnosticResolvedMime,
            plannedTargetRatio = s.diagnosticTargetRatio,
            plannedTargetVideoBitrate = s.diagnosticTargetVideoBitrate,
            plannedDecisionReason = certReason,
            wasStreamCopy = false,
            verification = verification,
            outputSize = 0L,
            candidateBytes = s.outputSize,
            terminal = certTerminal,
            elapsedMs = s.elapsedMs,
            fallbackReason = certReason,
            discardedVideoBitrate = s.diagnosticDiscardedVideoBitrate,
            probedRatios = perceptualPlan.probedRatios,
            pixelProvenRatio = perceptualPlan.pixelProvenRatio,
            probeDetail = perceptualPlan.probeDetail,
            probeWindowScores = perceptualPlan.probeWindowScores,
            probePairDiag = perceptualPlan.probePairDiag,
            probeV1Scores = perceptualPlan.probeV1Scores,
            probeRateDiag = perceptualPlan.probeRateDiag,
            certWindowScores = s.diagnosticCertWindowScores,
            certBandingDiag = s.diagnosticCertBandingDiag,
            certV1Scores = s.diagnosticCertV1Scores,
            certificationStatus = s.diagnosticCertStatus,
            certificationDecision = decision,
            encoderConfig = s.encodeAttempt?.configDelta?.compact(),
            media3Input = s.diagnosticMedia3Input,
            precedingCooldownMs = s.precedingHandoffCooldownMs,
            encodePlan = s.resolvedPlan,
            attempts = s.attemptLog
        )
        updateItem(s.index) {
            it.copy(
                status = BatchItemStatus.Skipped,
                progress = 1f,
                phase = ItemPhase.ENDED,
                phaseFraction = null,
                currentOutputSize = 0L,
                candidateOutputSize = 0L,
                outputUri = null,
                outputPath = null,
                outputSize = 0L,
                terminalResult = certTerminal,
                message = if (certMeasured) {
                    "Skipped: $certReason — original left untouched."
                } else {
                    "Kept original: $certReason — original left untouched."
                }
            )
        }
        return CertStep.ENDED
    }

    /** "0.85:measured_below_bar:cand=15123456:encodeMs=14620" — one full-encode attempt, for the record. */
    private fun attemptSummary(s: ItemRun, ratio: Double, outcome: String): String =
        String.format(
            java.util.Locale.US, "%.2f:%s:cand=%d:encodeMs=%d", ratio, outcome, s.outputSize, s.lastEncodeMs
        )

    /**
     * Whether the opt-in safer-rung retry runs after this failed certification (SaferRungRetry),
     * with a fresh size check at the safer rung. Records its decision either way.
     */
    private fun saferRungRetryAllowed(s: ItemRun, decision: CertificationDecision): Boolean {
        val plan = s.perceptualPlan ?: return false
        val safer = plan.saferPassingRatio
        val context = s.run.context
        if (decision != CertificationDecision.MEASURED_FAILURE || safer == null) {
            // Nothing to decide: not a candidate. Still logged when enabled, so a capture can count it.
            if (EncoderExperiments.isSaferRungRetryEnabled(context) && decision == CertificationDecision.MEASURED_FAILURE) {
                DiagLog.i("CompressorProbe", "safer-rung retry denied; job=${diagnosticJobId(s.item)}; reason=no_measured_safer_rung")
                s.run.diagnostics.stage(
                    StageEvent(
                        sourceKey = s.item.sourceUri.toString(), attempt = s.phases.token.attempt,
                        stage = StageEvent.Stage.RETRY, reasonCode = StageEvent.Reason.RETRY_DENIED,
                        fields = mapOf("denial" to "no_measured_safer_rung", "usedRatio" to plan.targetRatio)
                    )
                )
            }
            return false
        }
        val mime = s.resolvedMime ?: return false
        val overshoot = MeasuredOvershoot.forPrediction(plan.expectedOvershootFactor, plan.saferPassingRateFactors)
        val predicted = BatchQualityBitratePolicy.predictedPerceptualLosslessBytes(
            source = s.item.toSourceInfo(),
            outputMimeType = mime,
            learnedTargetRatio = safer,
            expectedOvershootFactor = overshoot,
            pixelProvenRatioFloor = safer
        )
        val worth = ExhaustivePerceptualLosslessPolicy.worthEncoding(
            sourceBytes = s.item.originalSize,
            predictedBytes = predicted,
            exhaustive = s.run.exhaustivePerceptualLossless,
            meetsNoiseThreshold = BatchQualityBitratePolicy.meetsMinimumUsefulSavings(s.item.originalSize, predicted)
        ) && BatchQualityBitratePolicy.meetsMinimumUsefulSavings(s.item.originalSize, predicted)
        val thermal = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus
            } else {
                null
            }
        }.getOrNull()
        val free = runCatching { context.cacheDir.usableSpace }.getOrNull()
        val result = SaferRungRetry.decide(
            SaferRungRetry.Input(
                enabled = EncoderExperiments.isSaferRungRetryEnabled(context),
                decision = decision,
                usedRatio = plan.targetRatio,
                saferPassingRatio = safer,
                sameSourceAndConfig = s.media3Input == null && s.diagnosticMedia3Input == null &&
                    plan.sourceParseFailure == null,
                retriesThisItem = s.retriesThisItem,
                retriesThisBatch = s.run.saferRungRetries,
                predictedBytes = predicted,
                worthEncoding = worth,
                itemElapsedMs = s.elapsedMs,
                lastEncodeMs = s.lastEncodeMs,
                thermalStatus = thermal,
                freeBytes = free,
                sourceBytes = s.item.originalSize
            )
        )
        val allowed = result is SaferRungRetry.Decision.Allowed
        val denial = (result as? SaferRungRetry.Decision.Denied)?.reasonCode
        if (EncoderExperiments.isSaferRungRetryEnabled(context)) {
            DiagLog.i(
                "CompressorProbe",
                "safer-rung retry ${if (allowed) "allowed" else "denied"}; job=${diagnosticJobId(s.item)}; " +
                    "usedRatio=${plan.targetRatio}; saferRatio=$safer; predictedBytes=$predicted; sourceBytes=${s.item.originalSize}; " +
                    "${MeasuredOvershoot.describe(plan.expectedOvershootFactor, plan.saferPassingRateFactors)}" +
                    (denial?.let { "; reason=$it" } ?: "")
            )
            s.run.diagnostics.stage(
                StageEvent(
                    sourceKey = s.item.sourceUri.toString(), attempt = s.phases.token.attempt,
                    stage = StageEvent.Stage.RETRY,
                    reasonCode = if (allowed) StageEvent.Reason.RETRY_ALLOWED else StageEvent.Reason.RETRY_DENIED,
                    elapsedMs = s.elapsedMs,
                    fields = mapOf(
                        "denial" to denial, "usedRatio" to plan.targetRatio, "saferRatio" to safer,
                        "predictedBytes" to predicted, "overshootPreClamp" to overshoot,
                        "thermalStatus" to thermal, "freeBytes" to free, "lastEncodeMs" to s.lastEncodeMs
                    )
                )
            )
        }
        if (allowed) s.retryOvershoot = overshoot
        return allowed
    }

    /**
     * The safer-rung retry: a new attempt (its own token, so the failed attempt's late callbacks
     * cannot touch the row) that encodes at the safer, previously measured ratio and goes through
     * production, structural verification, certification and the verdict exactly as the first one
     * did. A second failure keeps the original; there is never a third encode.
     */
    private suspend fun retrySaferRung(s: ItemRun): Boolean {
        val plan = checkNotNull(s.perceptualPlan) { "retry without a plan" }
        val ratio = checkNotNull(plan.saferPassingRatio) { "retry without a safer rung" }
        val mime = checkNotNull(s.resolvedMime) { "retry without an output codec" }
        s.retriesThisItem++
        s.run.saferRungRetries++
        val previous = s.phases.token
        val next = AttemptToken(previous.batchId, s.index, attemptCounter.incrementAndGet())
        updateItem(s.index) { ItemProgressModel.handOver(it, previous, next) }
        s.phases = PhaseReporter(itemBoard, next)
        s.perceptualPlan = plan.copy(
            targetRatio = ratio,
            pixelProvenRatio = ratio,
            saferPassingRatio = null,
            saferPassingRateFactors = emptyList(),
            probeDetail = (plan.probeDetail ?: "") +
                String.format(java.util.Locale.US, "; safer-rung retry at %.2f after a measured certification failure at %.2f", ratio, plan.targetRatio)
        )
        s.diagnosticPlan = s.perceptualPlan
        s.diagnosticTargetRatio = ratio
        s.resetForRetry()
        val encodePlan = resolveEncodePlan(s, mime, ratio)
        s.resolvedPlan = encodePlan
        s.diagnosticTargetVideoBitrate = encodePlan.requestedVideoBitrate
        s.phases.planResolved(encodePlan.estimatedBytes)
        logEncoderPlan(s.item, s.effectiveQuality, s.run.codec, encodePlan, s.plannedFps)
        s.phases.message(String.format(java.util.Locale.US, "Retrying at the safer measured ratio %.2f…", ratio))
        s.phases.enter(ItemPhase.ENCODING)
        if (!produceOutput(s)) return false
        s.phases.enter(ItemPhase.VERIFYING)
        return verifyOutput(s)
    }

    /**
     * The Perceptually Lossless verdict on a structurally verified output: fall back to the
     * original (or a stream copy) when verification rejected the encode, otherwise let the
     * learning engine record the verified success. Returns false when the item finished here.
     */
    private suspend fun applyPerceptualVerdict(s: ItemRun): Boolean {
        val run = s.run
        val item = s.item
        val context = run.context
        val perceptualPlan = s.perceptualPlan
        val verification = checkNotNull(s.verification) { "verdict before verification" }
        val outputFile = checkNotNull(s.outputFile) { "verdict before an output exists" }
        val outputSize = s.outputSize
        if (s.effectiveQuality != BatchQualityPreset.ORIGINAL) return true
        if (!PerceptualLosslessVerifier.shouldFallbackToRemux(verification, item.originalSize, outputSize)) {
            if (verification.verified && perceptualPlan != null) {
                val sizeRatio = if (item.originalSize > 0L) {
                    outputSize.toDouble() / item.originalSize.toDouble()
                } else {
                    1.0
                }
                val learned = learningEngine.recordVerifiedSuccess(
                    perceptualPlan.profileKey,
                    perceptualPlan.targetRatio,
                    sizeRatio,
                    perceptualPlan.floorRatio,
                    s.measuredOvershoot,
                    // Only a pixel-certified success may lower the next target. A structural-only
                    // pass keeps the strict no-step-down behavior, because the structural verifier
                    // cannot see perceptual damage.
                    pixelCertified = s.pixelCertifiedThisRun
                )
                DiagLog.i(
                    "CompressorLearning",
                    "result=verified; profileKey=${perceptualPlan.profileKey.asKey()}; usedRatio=${perceptualPlan.targetRatio}; " +
                        "pixelCertified=${s.pixelCertifiedThisRun}; " +
                        "bitrateMode=${s.encodeAttempt?.requestedBitrateModeLabel ?: "unknown"}; encoderName=${s.encodeAttempt?.videoEncoderName ?: "unknown"}; " +
                        "measuredOvershoot=${s.measuredOvershoot ?: "unknown"}; learnedOvershoot=${learned.measuredOvershootFactor ?: "none"}; " +
                        "sizeRatio=$sizeRatio; nextRatio=${learned.nextTargetRatio}"
                )
            }
            return true
        }
        val failureReason = verification.replacementBlockReason ?: verification.verdict
        s.diagnosticFallbackReason = failureReason
        // Measured video bitrate of the DISCARDED encode (Media3's own report, else size/duration),
        // captured before the file is deleted, so the structured record shows whether the encode
        // undershot the floor or simply was not smaller.
        s.diagnosticDiscardedVideoBitrate = s.encodeAttempt?.reportedAverageVideoBitrate?.takeIf { it > 0 }
            ?: if (item.durationMs > 0 && outputSize > 0) {
                ((outputSize * 8000.0 / item.durationMs) - item.originalAudioBitrate.coerceAtLeast(0))
                    .toInt().coerceAtLeast(0)
            } else {
                null
            }
        DiagLog.w(
            "CompressorBatch",
            "Perceptually lossless fallback to remux for ${diagnosticJobId(item)}: $failureReason"
        )
        // Log the full field-by-field report of the discarded attempt so device logs show exactly
        // which check failed or which field was not exposed.
        verification.summaryLines.forEach { line ->
            DiagLog.w("CompressorVerification", "discarded PL attempt; $line")
        }
        if (perceptualPlan != null) {
            // Only a starved encode moves the ratio; see LearningEvidencePolicy.
            val evidence = LearningEvidencePolicy.classifyVerificationFailure(verification.failingChecks())
            if (evidence == LearningEvidencePolicy.Kind.PIPELINE) {
                DiagLog.i(
                    "CompressorLearning",
                    "result=pipeline_failure; profileKey=${perceptualPlan.profileKey.asKey()}; usedRatio=${perceptualPlan.targetRatio}; " +
                        "failing=${verification.failingChecks().joinToString(",")}; reason=$failureReason; learned state unchanged"
                )
            } else {
                val learned = learningEngine.recordFailure(
                    perceptualPlan.profileKey,
                    perceptualPlan.targetRatio,
                    failureReason,
                    perceptualPlan.floorRatio,
                    s.measuredOvershoot,
                    stepUp = evidence == LearningEvidencePolicy.Kind.QUALITY
                )
                DiagLog.i(
                    "CompressorLearning",
                    "result=failure; evidence=$evidence; profileKey=${perceptualPlan.profileKey.asKey()}; usedRatio=${perceptualPlan.targetRatio}; " +
                        "bitrateMode=${s.encodeAttempt?.requestedBitrateModeLabel ?: "unknown"}; encoderName=${s.encodeAttempt?.videoEncoderName ?: "unknown"}; " +
                        "measuredOvershoot=${s.measuredOvershoot ?: "unknown"}; learnedOvershoot=${learned.measuredOvershootFactor ?: "none"}; " +
                        "reason=$failureReason; nextRatio=${learned.nextTargetRatio}; preferRemux=${learned.preferRemux}"
                )
            }
        }
        runCatching { outputFile.delete() }
        if (perceptualPlan != null && retainOriginalInsteadOfCopy(
                context = context,
                diagnostics = run.diagnostics,
                item = item,
                index = s.index,
                quality = run.quality,
                privacyMode = run.privacyMode,
                plan = perceptualPlan,
                resolvedMime = s.diagnosticResolvedMime,
                plannedTargetVideoBitrate = s.diagnosticTargetVideoBitrate,
                plannedDecisionReason = s.diagnosticDecisionReason,
                evidence = DiscardedAttemptEvidence(
                    fallbackReason = failureReason,
                    encoderFailed = false,
                    discardedVideoBitrate = s.diagnosticDiscardedVideoBitrate,
                    certWindowScores = s.diagnosticCertWindowScores,
                    certBandingDiag = s.diagnosticCertBandingDiag,
                    certV1Scores = s.diagnosticCertV1Scores,
                    certificationStatus = s.diagnosticCertStatus,
                    encoderConfig = s.encodeAttempt?.configDelta?.compact()
                ),
                itemStartedAt = s.itemStartedAt,
                thermalStart = s.thermalWindow,
                precedingCooldownMs = s.precedingHandoffCooldownMs
            )
        ) {
            s.candidateFiles.forEach { runCatching { it.delete() } }
            // A full encode DID run here, so the encoder's heat still earns its cooldown before
            // the next video, exactly as on the stream-copy path.
            s.cooldownForNextItemMs = applyPostItemCooldown(context, s.index, ranFullEncode = s.encodeAttempt != null)
            return false
        }
        s.candidateFiles += item.cacheOutputFile(context, BatchQualityPreset.REMUX_ONLY)
        val remuxResult = remuxOnlyOne(context, item, s.phases, run.privacyMode)
        s.remuxResult = remuxResult
        s.outputFile = remuxResult.outputFile
        s.outputUri = Uri.fromFile(remuxResult.outputFile)
        s.outputSize = remuxResult.outputFile.length()
        s.effectiveQuality = BatchQualityPreset.REMUX_ONLY
        s.diagnosticEffectiveQuality = s.effectiveQuality
        s.preEncodeRemuxNote = "Remux Fallback Kept: perceptually lossless could not be verified ($failureReason)"
        s.verification = withContext(Dispatchers.IO) {
            OutputVerifier.verify(context, item, remuxResult.outputFile, s.effectiveQuality.label, run.privacyMode)
        }
        return true
    }

    /** Stage 4: classify, record, publish, replace the original if asked, and cool down. */
    private suspend fun finalizeItem(s: ItemRun) {
        val run = s.run
        val item = s.item
        val context = run.context
        val outputFile = checkNotNull(s.outputFile) { "finalize before an output exists" }
        val outputUri = checkNotNull(s.outputUri) { "finalize before an output exists" }
        val remuxResult = checkNotNull(s.remuxResult) { "finalize before an output exists" }
        val outputSize = s.outputSize
        // QUAL-001 — label honesty. OutputVerifier decides "Perceptually Lossless Verified" from
        // STRUCTURAL checks alone, and it runs BEFORE pixel certification, so on its own that
        // wording would imply pixel proof even when no pixels were ever scored (source above the
        // VMAF geometry cap, VMAF unavailable, HDR/codec-downgrade, or a certification that
        // returned no measured evidence and was accepted structurally). Record what was actually
        // proven and qualify the wording when it was structural only. Nothing here relaxes
        // acceptance: a measured cert FAILURE already skipped this item.
        val verification = checkNotNull(s.verification) { "finalize before verification" }
            .withCertificationBasis(s.pixelCertifiedThisRun)
        s.verification = verification
        logVerificationResult(item, s.effectiveQuality, verification, outputSize)
        val thermalEnd = ThermalBatchGovernor.snapshot(context, _uiState.value.thermalMode, _uiState.value.cooldownSeconds)
        val terminal = BatchTerminalClassifier.classify(
            BatchTerminalInput(
                requestedMode = run.quality.toMode(),
                effectiveMode = s.effectiveQuality.toMode(),
                wasStreamCopy = s.effectiveQuality == BatchQualityPreset.REMUX_ONLY,
                verified = verification.verified,
                replacementSafe = verification.replacementSafe,
                sourceSize = item.originalSize,
                outputSize = outputSize,
                preEncodeSourceAlreadyEfficient = s.diagnosticSourceAlreadyEfficient,
                preEncodeEvidencePreferredRemux = s.diagnosticEvidencePreferredRemux,
                encoderFailed = s.diagnosticEncoderFailed,
                acceptAnyVerifiedSaving = run.exhaustivePerceptualLossless
            )
        )
        val terminalSavedBytes = BatchTerminalAccounting.savedBytes(
            BatchTerminalAccountingEntry(terminal, item.originalSize, outputSize)
        )
        val metrics = BatchItemMetrics(
            operationLabel = if (s.effectiveQuality == BatchQualityPreset.REMUX_ONLY) "Remux" else "Encode",
            elapsedMs = s.elapsedMs,
            outputBytes = outputSize,
            savedBytes = terminalSavedBytes,
            thermalStart = s.thermalWindow.thermalLabel,
            thermalEnd = thermalEnd.thermalLabel,
            batteryStart = s.thermalWindow.batteryPercent,
            batteryEnd = thermalEnd.batteryPercent,
            cooldownMs = 0L
        )
        val perceptualPlan = s.perceptualPlan
        // A job that retried records both attempts; the kept one is the last.
        if (s.attemptLog.isNotEmpty() && s.encodeAttempt != null) {
            s.attemptLog += attemptSummary(
                s, perceptualPlan?.targetRatio ?: 0.0,
                if (terminal.countsAsRealCompression) "accepted" else "not_accepted:${terminal.name}"
            )
        }
        run.diagnostics.stage(
            StageEvent(
                sourceKey = item.sourceUri.toString(), attempt = s.phases.token.attempt,
                stage = StageEvent.Stage.ACCEPT,
                reasonCode = if (!terminal.isFailure && outputSize > 0L) StageEvent.Reason.ACCEPTED else StageEvent.Reason.REJECTED,
                elapsedMs = s.elapsedMs,
                fields = mapOf(
                    "terminal" to terminal.name,
                    "candidateBytes" to outputSize,
                    "predictedBytes" to s.resolvedPlan?.estimatedBytes,
                    "pixelCertified" to s.pixelCertifiedThisRun,
                    "retries" to s.retriesThisItem
                )
            )
        )
        recordDiagnosticJob(
            diagnostics = run.diagnostics,
            item = item,
            requestedQuality = run.quality,
            effectiveQuality = s.effectiveQuality,
            resolvedMime = s.diagnosticResolvedMime,
            plannedTargetRatio = s.diagnosticTargetRatio,
            plannedTargetVideoBitrate = s.diagnosticTargetVideoBitrate,
            plannedDecisionReason = s.diagnosticDecisionReason,
            wasStreamCopy = s.effectiveQuality == BatchQualityPreset.REMUX_ONLY,
            verification = verification,
            outputSize = outputSize,
            terminal = terminal,
            elapsedMs = metrics.elapsedMs,
            fallbackReason = s.diagnosticFallbackReason,
            discardedVideoBitrate = s.diagnosticDiscardedVideoBitrate,
            probedRatios = perceptualPlan?.probedRatios ?: emptyList(),
            pixelProvenRatio = perceptualPlan?.pixelProvenRatio,
            probeDetail = perceptualPlan?.probeDetail,
            probeWindowScores = perceptualPlan?.probeWindowScores,
            probePairDiag = perceptualPlan?.probePairDiag,
            probeV1Scores = perceptualPlan?.probeV1Scores,
            probeRateDiag = perceptualPlan?.probeRateDiag,
            certWindowScores = s.diagnosticCertWindowScores,
            certBandingDiag = s.diagnosticCertBandingDiag,
            certV1Scores = s.diagnosticCertV1Scores,
            certificationStatus = s.diagnosticCertStatus,
            encoderConfig = s.encodeAttempt?.configDelta?.compact(),
            media3Input = s.diagnosticMedia3Input,
            thermalStart = metrics.thermalStart,
            thermalEnd = metrics.thermalEnd,
            precedingCooldownMs = s.precedingHandoffCooldownMs,
            materializationMode = "GENERATED_FILE",
            originalReuseBlockReason = s.diagnosticReuseBlockReason,
            candidateBytes = outputSize,
            certificationDecision = s.certificationDecision,
            encodePlan = s.resolvedPlan,
            attempts = s.attemptLog
        )

        if (terminal.isFailure) {
            // An unverified remux/encode is evidence, not an output. Keep its measured size in
            // diagnostics, then remove the cache file and expose no share/save/replacement path
            // to the UI.
            runCatching { outputFile.delete() }
            updateItem(s.index) {
                it.copy(
                    status = BatchItemStatus.Failed,
                    progress = 1f,
                    currentOutputSize = 0L,
                    outputUri = null,
                    outputPath = null,
                    outputSize = 0L,
                    outputMode = s.effectiveQuality.label,
                    verificationReport = verification,
                    metrics = metrics,
                    terminalResult = terminal,
                    message = buildString {
                        append(terminal.label)
                        verification.replacementBlockReason?.let { append(": ").append(it) }
                    }
                )
            }
        } else {
            val muxerMessage = s.preEncodeRemuxNote?.let { note -> "${remuxResult.message} • $note" } ?: remuxResult.message
            updateItem(s.index) {
                it.copy(
                    status = BatchItemStatus.Done,
                    progress = 1f,
                    currentOutputSize = outputSize,
                    outputUri = outputUri,
                    outputPath = outputFile.absolutePath,
                    outputSize = outputSize,
                    outputMode = s.effectiveQuality.label,
                    verificationReport = verification,
                    metrics = metrics,
                    terminalResult = terminal,
                    message = completionMessage(
                        it,
                        s.effectiveQuality,
                        outputSize,
                        s.plannedFps,
                        s.codecLabel,
                        muxerMessage,
                        verification,
                        run.privacyMode
                    )
                )
            }
            s.itemOutputAccepted = true
        }

        if (terminal.allowsOriginalReplacement && _uiState.value.replaceOriginals) {
            // Once destructive replacement starts, finish it and publish its disposition
            // atomically before honoring cancellation. This prevents an original from changing
            // while the UI remains stuck at a pre-replacement Done state.
            withContext(NonCancellable) {
                val replacement = replaceOriginalSafely(
                    context = context,
                    item = item,
                    outputFile = outputFile,
                    useShizukuFallback = _uiState.value.useShizukuFallback,
                    quality = s.effectiveQuality,
                    verification = verification,
                    backupBeforeReplace = _uiState.value.backupBeforeReplace,
                    privacyMode = run.privacyMode
                )
                updateItem(s.index) {
                    it.copy(
                        status = if (replacement.success) BatchItemStatus.Replaced else BatchItemStatus.SavedCopy,
                        message = replacement.message
                    )
                }
            }
        }

        if (s.index < _uiState.value.items.lastIndex) {
            // Apply the thermal cooldown ONLY after an item that actually ran a full hardware
            // encode (encodeAttempt != null). Stream-copy/remux and already-optimized items
            // generate no encoder heat, so cooling down after them is pure idle time.
            // Timing-only: no compression/verification/learning decision is affected.
            s.cooldownForNextItemMs = applyPostItemCooldown(context, s.index, ranFullEncode = s.encodeAttempt != null)
        }
    }

    /** The per-item failure handler: record the failure and let the batch continue. */
    private suspend fun handleItemFailure(s: ItemRun, e: Exception) {
        val run = s.run
        val item = s.item
        val unsupported = e.message?.contains(Mp4MetadataRemuxer.REMUX_ONLY_UNSUPPORTED_MESSAGE) == true
        // A Perceptually Lossless item that was headed for "keep the original" failed only because
        // the stream copy carrying it is impossible for this container. The original is untouched
        // and was the decision, so retain it rather than report a failure. Privacy stripping and
        // unreadable sources still fail as before (OriginalReusePolicy).
        val keepOriginalPlan = s.diagnosticPlan
        if (unsupported && run.quality == BatchQualityPreset.ORIGINAL &&
            s.diagnosticEffectiveQuality == BatchQualityPreset.REMUX_ONLY && keepOriginalPlan != null &&
            retainOriginalInsteadOfCopy(
                context = run.context,
                diagnostics = run.diagnostics,
                item = item,
                index = s.index,
                quality = run.quality,
                privacyMode = run.privacyMode,
                plan = keepOriginalPlan,
                resolvedMime = s.diagnosticResolvedMime,
                plannedTargetVideoBitrate = s.diagnosticTargetVideoBitrate,
                plannedDecisionReason = s.diagnosticDecisionReason,
                evidence = DiscardedAttemptEvidence(
                    fallbackReason = s.diagnosticFallbackReason
                        ?: "stream copy impossible: ${e.message ?: "unsupported container"}",
                    encoderFailed = s.diagnosticEncoderFailed
                ),
                itemStartedAt = s.itemStartedAt,
                thermalStart = s.thermalWindow,
                precedingCooldownMs = s.precedingHandoffCooldownMs,
                afterFailedAttempt = s.diagnosticFallbackReason != null,
                containerCannotBeCopied = true
            )
        ) {
            return
        }
        val encoderFailure = s.diagnosticEncoderFailed || e is ExportException
        val terminal = BatchTerminalClassifier.classify(
            BatchTerminalInput(
                requestedMode = run.quality.toMode(),
                effectiveMode = s.diagnosticEffectiveQuality.toMode(),
                wasStreamCopy = false,
                verified = false,
                replacementSafe = false,
                sourceSize = item.originalSize,
                outputSize = 0L,
                hardFailure = !unsupported && !encoderFailure,
                unsupportedContainer = unsupported,
                encoderFailed = encoderFailure
            )
        )
        DiagLog.w("CompressorBatch", "item failed; job=${diagnosticJobId(item)}; terminal=$terminal", e)
        val elapsedMs = s.elapsedMs
        updateItem(s.index) {
            it.copy(
                status = BatchItemStatus.Failed,
                progress = 1f,
                currentOutputSize = 0L,
                outputUri = null,
                outputPath = null,
                outputSize = 0L,
                terminalResult = terminal,
                message = e.message ?: "Compression failed"
            )
        }
        recordDiagnosticJob(
            diagnostics = run.diagnostics,
            item = item,
            requestedQuality = run.quality,
            effectiveQuality = s.diagnosticEffectiveQuality,
            resolvedMime = s.diagnosticResolvedMime,
            plannedTargetRatio = s.diagnosticTargetRatio,
            plannedTargetVideoBitrate = s.diagnosticTargetVideoBitrate,
            plannedDecisionReason = s.diagnosticDecisionReason,
            wasStreamCopy = false,
            verification = null,
            outputSize = 0L,
            terminal = terminal,
            elapsedMs = elapsedMs,
            fallbackReason = s.diagnosticFallbackReason,
            media3Input = s.diagnosticMedia3Input
        )
    }

    override fun onCleared() {
        // Last-resort teardown: if the ViewModel is destroyed while a batch's finally has not yet
        // run (process/config edge), still release foreground protection + wake lock. Idempotent.
        batchGuard.end()
        super.onCleared()
    }

    fun cancelCompression() {
        activeTransformer?.cancel()
        compressionJob?.cancel()
        // The coroutine's finally block owns the transition to idle and final accounting. Leaving
        // isCompressing true prevents a second run from racing the cancelled run's cleanup.
        _uiState.update {
            val cancellationPending = compressionJob?.isCompleted == false
            it.copy(
                isCompressing = cancellationPending,
                statusMessage = if (cancellationPending) {
                    "Canceling compression…"
                } else {
                    "Compression canceled."
                }
            )
        }
    }

    fun saveAllCopiesToGallery(context: Context) {
        val outputs = _uiState.value.items.mapNotNull { item ->
            val path = item.outputPath ?: return@mapNotNull null
            item to File(path)
        }
        if (outputs.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val privacyMode = MetadataPrivacyMode.fromLabel(_uiState.value.metadataPrivacyMode)
            var saved = 0
            outputs.forEach { (item, file) ->
                if (file.exists()) {
                    val savedUri = saveFileToGallery(
                        context = context,
                        file = file,
                        targetName = item.outputName(qualityFromLabel(item.outputMode ?: _uiState.value.qualityPreset)),
                        metadata = item.metadataSnapshot,
                        privacyMode = privacyMode
                    )
                    if (savedUri != null) saved++
                }
            }
            _uiState.update { it.copy(statusMessage = "Saved $saved output cop${if (saved == 1) "y" else "ies"} to Movies/Compressor with ${privacyMode.summary}.") }
        }
    }

    /** What a discarded Perceptually Lossless attempt leaves behind for the item's record. */
    private data class DiscardedAttemptEvidence(
        val fallbackReason: String,
        val encoderFailed: Boolean,
        val discardedVideoBitrate: Int? = null,
        val certWindowScores: String? = null,
        val certBandingDiag: String? = null,
        val certV1Scores: String? = null,
        val certificationStatus: String? = null,
        val encoderConfig: String? = null,
        val media3Input: String? = null
    )

    /**
     * After a Perceptually Lossless attempt was discarded, keep the untouched original instead of
     * writing a full stream copy of it.
     *
     * The copy bought nothing. For a PL request it classifies as UNEXPECTED_REMUX, which may not
     * replace the original, so the user got a byte-for-byte duplicate of a file they still had.
     * Making it cost a full read and write of the source (gigabytes for 4K camera clips), a second
     * verification pass, and the same amount of cache space. The keep-original fast path already
     * avoids exactly this cost when the plan decides up front. This extends it to the decision
     * made after an attempt, under the same audited [OriginalReusePolicy]: no privacy strip
     * requested, an MP4-family container, and a source readable now. When any guard fails, this
     * returns false and the caller writes the verified stream copy exactly as before.
     *
     * The label does not change: the terminal is still UNEXPECTED_REMUX ("Kept original —
     * re-encode could not be verified"), never "already efficient", and nothing counts as
     * compression.
     *
     * @return true when the original was retained and the item is fully recorded.
     */
    private suspend fun retainOriginalInsteadOfCopy(
        context: Context,
        diagnostics: DiagnosticsRecorder,
        item: BatchVideoItem,
        index: Int,
        quality: BatchQualityPreset,
        privacyMode: MetadataPrivacyMode,
        plan: PerceptualLosslessPlan,
        resolvedMime: String?,
        plannedTargetVideoBitrate: Int?,
        plannedDecisionReason: String?,
        evidence: DiscardedAttemptEvidence,
        itemStartedAt: Long,
        thermalStart: ThermalBatchSnapshot,
        precedingCooldownMs: Long,
        // False when there was no attempt: the plan decided up front to keep the original, and
        // the stream copy that would have carried it was impossible (containerCannotBeCopied).
        afterFailedAttempt: Boolean = true,
        containerCannotBeCopied: Boolean = false
    ): Boolean {
        val (containerMime, readable) = withContext(Dispatchers.IO) {
            val mime = runCatching { context.contentResolver.getType(item.sourceUri) }.getOrNull()
            val canRead = runCatching {
                context.contentResolver.openFileDescriptor(item.sourceUri, "r")?.use { true } == true
            }.getOrDefault(false)
            mime to canRead
        }
        val reuse = OriginalReusePolicy.evaluate(
            isKeepOriginalDecision = true,
            userRequestedRemuxOnly = false,
            privacyMode = privacyMode,
            resolvedContainerMime = containerMime,
            sourceReadableNow = readable,
            containerCannotBeCopied = containerCannotBeCopied
        )
        if (reuse !is OriginalReuseDecision.Eligible) {
            DiagLog.i(
                "CompressorBatch",
                "keep-original fallback blocked; job=${diagnosticJobId(item)}; " +
                    "reason=${(reuse as OriginalReuseDecision.Blocked).reason}; writing a verified stream copy instead"
            )
            return false
        }
        val retention = OriginalReusePolicy.retainedSourceValidation(
            sourceReadable = readable,
            sourceSizeBytes = item.originalSize,
            containerMime = reuse.containerMime,
            nowEpochMs = System.currentTimeMillis()
        )
        val terminal = BatchTerminalClassifier.classify(
            BatchTerminalInput(
                requestedMode = quality.toMode(),
                effectiveMode = BatchQualityMode.REMUX_ONLY,
                wasStreamCopy = false,
                verified = retention.readableAtDecisionTime,
                replacementSafe = false,
                sourceSize = item.originalSize,
                outputSize = item.originalSize,
                preEncodeSourceAlreadyEfficient = plan.remuxWasSourceEfficient,
                preEncodeEvidencePreferredRemux = plan.remuxWasEvidencePreferred,
                encoderFailed = evidence.encoderFailed,
                retainedOriginalNoOutput = true,
                retainedAfterFailedAttempt = afterFailedAttempt
            )
        )
        val thermalEnd = ThermalBatchGovernor.snapshot(context, _uiState.value.thermalMode, _uiState.value.cooldownSeconds)
        val elapsedMs = System.currentTimeMillis() - itemStartedAt
        DiagLog.i(
            "CompressorBatch",
            "keep-original fallback; job=${diagnosticJobId(item)}; materialization=REUSED_SOURCE; " +
                "copyAvoidedBytes=${item.originalSize}; reason=${evidence.fallbackReason}; terminal=$terminal"
        )
        recordDiagnosticJob(
            diagnostics = diagnostics,
            item = item,
            requestedQuality = quality,
            effectiveQuality = BatchQualityPreset.REMUX_ONLY,
            resolvedMime = resolvedMime,
            plannedTargetRatio = plan.targetRatio,
            plannedTargetVideoBitrate = plannedTargetVideoBitrate,
            plannedDecisionReason = plannedDecisionReason,
            wasStreamCopy = false,
            verification = null,
            retainedValidation = retention,
            outputSize = item.originalSize,
            terminal = terminal,
            elapsedMs = elapsedMs,
            fallbackReason = evidence.fallbackReason,
            discardedVideoBitrate = evidence.discardedVideoBitrate,
            probedRatios = plan.probedRatios,
            pixelProvenRatio = plan.pixelProvenRatio,
            probeDetail = plan.probeDetail,
            probeWindowScores = plan.probeWindowScores,
            probePairDiag = plan.probePairDiag,
            probeV1Scores = plan.probeV1Scores,
            probeRateDiag = plan.probeRateDiag,
            certWindowScores = evidence.certWindowScores,
            certBandingDiag = evidence.certBandingDiag,
            certV1Scores = evidence.certV1Scores,
            certificationStatus = evidence.certificationStatus,
            encoderConfig = evidence.encoderConfig,
            media3Input = evidence.media3Input,
            thermalStart = thermalStart.thermalLabel,
            thermalEnd = thermalEnd.thermalLabel,
            precedingCooldownMs = precedingCooldownMs,
            materializationMode = "REUSED_SOURCE",
            copyAvoidedBytes = item.originalSize
        )
        updateItem(index) {
            it.copy(
                status = if (terminal.isFailure) BatchItemStatus.Failed else BatchItemStatus.Done,
                progress = 1f,
                currentOutputSize = item.originalSize,
                outputUri = if (terminal.isFailure) null else item.sourceUri,
                outputPath = null,
                outputSize = if (terminal.isFailure) 0L else item.originalSize,
                outputMode = BatchQualityPreset.REMUX_ONLY.label,
                verificationReport = null,
                terminalResult = terminal,
                metrics = BatchItemMetrics(
                    operationLabel = "Retained",
                    elapsedMs = elapsedMs,
                    outputBytes = 0L,
                    savedBytes = 0L,
                    thermalStart = thermalStart.thermalLabel,
                    thermalEnd = thermalEnd.thermalLabel,
                    batteryStart = thermalStart.batteryPercent,
                    batteryEnd = thermalEnd.batteryPercent,
                    cooldownMs = 0L
                ),
                message = if (afterFailedAttempt) {
                    "Kept original — the Perceptually Lossless re-encode could not be verified " +
                        "(${evidence.fallbackReason}). No copy was written; your original is untouched."
                } else {
                    "Kept original, no copy written: Perceptually Lossless had already decided not to " +
                        "re-encode this video, and its container cannot be stream-copied to MP4. Your " +
                        "original is untouched."
                }
            )
        }
        return true
    }

    /**
     * The post-item thermal cooldown before the next video, as applied ms. Called at the end of
     * an item, and from any early exit that follows a real encode, so that no path skips the
     * cooldown the encoder's heat calls for.
     */
    private suspend fun applyPostItemCooldown(context: Context, index: Int, ranFullEncode: Boolean): Long {
        if (index >= _uiState.value.items.lastIndex) return 0L
        val cooldown = ThermalBatchGovernor.snapshot(context, _uiState.value.thermalMode, _uiState.value.cooldownSeconds)
        val appliedCooldownMs = ThermalBatchGovernor.postItemCooldownMs(ranFullEncode, cooldown)
        _uiState.update {
            it.copy(
                thermalStatus = cooldown.summary,
                statusMessage = if (appliedCooldownMs > 0L)
                    "Cooling down ${appliedCooldownMs / 1000}s before next video."
                else
                    "Preparing next video…"
            )
        }
        updateItem(index) {
            it.copy(metrics = it.metrics?.copy(cooldownMs = appliedCooldownMs))
        }
        if (appliedCooldownMs > 0L) delay(appliedCooldownMs)
        return appliedCooldownMs
    }

    private suspend fun waitForThermalWindow(context: Context, itemName: String): ThermalBatchSnapshot {
        var snapshot = ThermalBatchGovernor.snapshot(context, _uiState.value.thermalMode, _uiState.value.cooldownSeconds)

        while (snapshot.shouldPause) {
            _uiState.update {
                it.copy(
                    thermalStatus = snapshot.summary,
                    statusMessage = "Phone is ${snapshot.thermalLabel} or battery is too low — cooling before $itemName."
                )
            }
            delay(30_000L)
            snapshot = ThermalBatchGovernor.snapshot(context, _uiState.value.thermalMode, _uiState.value.cooldownSeconds)
        }

        if (snapshot.preItemDelayMs > 0L) {
            _uiState.update {
                it.copy(
                    thermalStatus = snapshot.summary,
                    statusMessage = "Phone warm — slowing batch before $itemName."
                )
            }
            delay(snapshot.preItemDelayMs)
        }

        _uiState.update { it.copy(thermalStatus = snapshot.summary) }
        return snapshot
    }

    private fun updateItem(index: Int, transform: (BatchVideoItem) -> BatchVideoItem) {
        _uiState.update { state ->
            val mutable = state.items.toMutableList()
            if (index in mutable.indices) {
                mutable[index] = transform(mutable[index])
            }
            state.copy(items = mutable)
        }
    }

    private fun readMetadata(context: Context, uri: Uri): BatchVideoItem {
        val retriever = MediaMetadataRetriever()
        var name = "Video_${System.currentTimeMillis()}.mp4"
        var size = 0L
        var width = 0
        var height = 0
        var bitrate = 0
        var bitrateWasMeasured = false
        var fps = 30f
        var duration = 0L
        var metadataSnapshot = VideoMetadataSnapshot()
        var trackProbe = OutputVerifier.TrackProbe(null, null, 0, 0, null, null, null, null, null)

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                size = descriptor.statSize
            }
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                }
            }

            trackProbe = OutputVerifier.probeTracks(context, uri)
            retriever.setDataSource(context, uri)
            metadataSnapshot = VideoMetadataPreserver.capture(context, uri, retriever)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val oldWidth = width
                width = height
                height = oldWidth
            }
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // Downloaded/social videos frequently omit an overall-bitrate tag. Rather than let the
            // policy fall back to a camera-class assumption (which inflates the "source" bitrate and
            // then blocks compression), derive the real average total bitrate from the actual file
            // size and duration when the container hides it. This is the honest measured value.
            if (bitrate <= 0 && size > 0L && duration > 0L) {
                bitrate = (size * 8000.0 / duration.toDouble())
                    .toLong().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                bitrateWasMeasured = true
                DiagLog.i(
                    "CompressorEncoderPlan",
                    "measured source bitrate from size/duration for ${DiagnosticsRecorder.redactedJobId(uri.toString())}: ${bitrate} bps " +
                        "(container exposed no overall bitrate)"
                )
            }
            fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                ?: trackProbe.videoFrameRate
            if (fps <= 0f && trackProbe.videoFrameRate > 0f) fps = trackProbe.videoFrameRate
            if (fps <= 0f) fps = 30f
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        return BatchVideoItem(
            sourceUri = uri,
            originalName = name,
            originalSize = size.coerceAtLeast(0L),
            originalWidth = width,
            originalHeight = height,
            originalBitrate = bitrate,
            originalBitrateWasMeasured = bitrateWasMeasured,
            originalAudioBitrate = trackProbe.audioBitrate,
            originalFps = fps,
            durationMs = duration,
            metadataSnapshot = metadataSnapshot,
            sourceVideoMime = trackProbe.videoCodec,
            sourceAudioMime = trackProbe.audioCodec,
            sourceColorTransfer = trackProbe.colorTransfer,
            sourceColorStandard = trackProbe.colorStandard,
            sourceColorRange = trackProbe.colorRange,
            sourceAudioChannels = trackProbe.audioChannelCount,
            sourceAudioSampleRate = trackProbe.audioSampleRate
        )
    }

    private fun chooseOutputMime(
        codec: BatchCodecOption,
        item: BatchVideoItem,
        quality: BatchQualityPreset
    ): String {
        val sourceInfo = item.toSourceInfo()
        val supported = buildList {
            add(MimeTypes.VIDEO_H264)
            if (hasEncoder(MimeTypes.VIDEO_H265, sourceInfo)) add(MimeTypes.VIDEO_H265)
            if (hasEncoder(MimeTypes.VIDEO_AV1, sourceInfo)) add(MimeTypes.VIDEO_AV1)
        }
        val resolved = when (codec) {
            BatchCodecOption.HEVC -> MimeTypes.VIDEO_H265
            BatchCodecOption.H264 -> MimeTypes.VIDEO_H264
            BatchCodecOption.AV1 -> MimeTypes.VIDEO_AV1
            BatchCodecOption.AUTO -> chooseAutoCodec(item, quality, supported)
        }
        if (!supported.contains(resolved)) {
            if (quality == BatchQualityPreset.ORIGINAL) {
                throw IllegalStateException(
                    "Perceptually Lossless is blocked because ${codec.label} cannot preserve this source on this device. ${perceptualLosslessRecoveryHint(supported)}"
                )
            }
            return when {
                supported.contains(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H265
                supported.contains(MimeTypes.VIDEO_H264) -> MimeTypes.VIDEO_H264
                else -> resolved
            }
        }
        if (quality == BatchQualityPreset.ORIGINAL && sourceInfo.isHdr && resolved == MimeTypes.VIDEO_H264) {
            val hdrSafeCodecs = supported.filter { it != MimeTypes.VIDEO_H264 }
            throw IllegalStateException(
                "Perceptually Lossless is blocked because H.264 cannot safely preserve HDR output. ${perceptualLosslessRecoveryHint(hdrSafeCodecs)}"
            )
        }
        return resolved
    }

    private fun perceptualLosslessRecoveryHint(supported: List<String>): String {
        val codecOptions = supported
            .distinct()
            .mapNotNull {
                when (it) {
                    MimeTypes.VIDEO_H265 -> "HEVC"
                    MimeTypes.VIDEO_AV1 -> "AV1"
                    MimeTypes.VIDEO_H264 -> "H.264"
                    else -> null
                }
            }
            .filter { it != "H.264" }
        val codecHint = when {
            codecOptions.isEmpty() -> "Use Remux Only or choose a lossy mode."
            codecOptions.size == 1 -> "Select ${codecOptions.first()}, use Remux Only, or choose a lossy mode."
            else -> "Select ${codecOptions.joinToString(" or ")}, use Remux Only, or choose a lossy mode."
        }
        return codecHint
    }

    private fun chooseAutoCodec(item: BatchVideoItem, quality: BatchQualityPreset, supported: List<String>): String {
        val profile = DeviceCapabilityProfiles.current()
        val sourceInfo = item.toSourceInfo()
        if (quality == BatchQualityPreset.ORIGINAL || sourceInfo.isHdr || item.originalHeight >= 2160 || item.originalFps >= 50f) {
            if (supported.contains(MimeTypes.VIDEO_H265)) return MimeTypes.VIDEO_H265
        }
        val profileChoice = profile.chooseDefaultVideoCodec(supported, sourceInfo)
        return when {
            profile.avoidAv1EncodingByDefault && profileChoice == MimeTypes.VIDEO_AV1 && supported.contains(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H265
            supported.contains(profileChoice) -> profileChoice
            supported.contains(MimeTypes.VIDEO_H264) -> MimeTypes.VIDEO_H264
            else -> supported.firstOrNull() ?: MimeTypes.VIDEO_H264
        }
    }

    private fun hasEncoder(mimeType: String, sourceInfo: VideoSourceInfo? = null): Boolean {
        return try {
            DeviceCodecCatalog.codecInfos.any { info ->
                info.isEncoder &&
                    (!Build.VERSION.SDK_INT.let { it >= Build.VERSION_CODES.Q } || !info.isSoftwareOnly) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                    encoderSupportsSource(info, mimeType, sourceInfo)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun encoderSupportsSource(
        info: android.media.MediaCodecInfo,
        mimeType: String,
        sourceInfo: VideoSourceInfo?
    ): Boolean {
        if (sourceInfo == null || sourceInfo.width <= 0 || sourceInfo.height <= 0) return true
        return runCatching {
            val caps = info.getCapabilitiesForType(mimeType)
            val videoCaps = caps.videoCapabilities ?: return@runCatching false
            val requiredFps = sourceInfo.frameRate.coerceAtLeast(1f).toDouble()
            val supported = videoCaps.areSizeAndRateSupported(
                sourceInfo.width,
                sourceInfo.height,
                requiredFps
            ) || videoCaps.areSizeAndRateSupported(
                sourceInfo.height,
                sourceInfo.width,
                requiredFps
            )
            supported && !(sourceInfo.isHdr && mimeType == MimeTypes.VIDEO_H264)
        }.getOrDefault(false)
    }

    private fun buildPerceptualLosslessPlan(
        item: BatchVideoItem,
        outputMime: String,
        exhaustive: Boolean
    ): PerceptualLosslessPlan {
        val source = item.toSourceInfo()
        val profileKey = SmartPerceptualProfileEngine.profileKeyFor(
            source = source,
            encoderMime = outputMime,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT
        )
        val floorRatio = BatchQualityBitratePolicy.perceptualLosslessRatioFloor(source)
        val defaultRatio = BatchQualityBitratePolicy.perceptualLosslessDefaultTargetRatio(source, outputMime)
        val targetRatio = learningEngine.recommendedTargetRatio(profileKey, defaultRatio, floorRatio)
        val profilePrefersRemux = learningEngine.shouldPreferRemux(profileKey)
        // Tier-1 experiment (debug builds): request CBR so the QTI encoder cannot apply its VBR
        // quality-boost overshoot. Gated on real device support, never assumed.
        val useCbrCeiling = ExperimentalEncoderControls.isEnabled(getApplication()) &&
            ExperimentalEncoderControls.isCbrSupportedByHardwareEncoder(outputMime)
        // CBR is expected to hold the requested average; VBR predictions use the measured
        // per-profile overshoot so the near-optimal gate reflects what the encoder actually does.
        val learnedOvershoot = learningEngine.expectedOvershootFactor(profileKey)
        val expectedOvershootFactor = if (useCbrCeiling) 1.0 else learnedOvershoot
        val preserveSourceCodec = BatchQualityBitratePolicy.shouldPreserveSourceCodecForPerceptualLossless(
            source.videoMime,
            outputMime
        )
        // Evidence-based gates from the 2026-07-14 VMAF suite: HDR has zero pixel-validated pairs
        // (stream copy is the only proven HDR/color-preserving output), and sources below the
        // transparency bit-density gate lose visibly in a second encode generation.
        val hdrPixelTransparencyUnvalidated = !preserveSourceCodec && source.isHdr
        val insufficientSourceBitDensity = !preserveSourceCodec && !hdrPixelTransparencyUnvalidated &&
            !BatchQualityBitratePolicy.sourceSupportsTransparentPerceptualLossless(source)
        val nearOptimal = !preserveSourceCodec && BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
            source = source,
            outputMimeType = outputMime,
            sourceSizeBytes = item.originalSize,
            learnedTargetRatio = targetRatio,
            expectedOvershootFactor = expectedOvershootFactor
        )
        val remuxReason = when {
            profilePrefersRemux ->
                "This device profile repeatedly failed perceptually lossless verification, so the exact stream copy was kept."
            preserveSourceCodec ->
                "Source codec is already efficient for this content; kept exact stream copy."
            hdrPixelTransparencyUnvalidated ->
                "HDR re-encoding is not pixel-validated as lossless; kept exact stream copy to preserve HDR/color exactly."
            insufficientSourceBitDensity ->
                "Source is already heavily compressed; a re-encode would visibly lose quality, so the exact stream copy was kept."
            nearOptimal ->
                "Source is already near optimal; kept exact stream copy."
            else -> null
        }
        // On-device pixel probes may refine or overturn the inference-based decision for any
        // SDR source whose output codec is not a downgrade (see refinePlanWithPixelProbes).
        // Same-codec sources and remux-latched profiles deliberately REMAIN probe-eligible:
        // inference picks the default outcome, but only measured pixels may make it final —
        // a prior failure must never permanently deny a clip its trial encodes.
        val codecDowngrade = BatchQualityBitratePolicy.isCodecDowngradeForPerceptualLossless(
            source.videoMime,
            outputMime
        )
        // Pixel evidence has TWO separate gates, because scoring an output once and searching for a
        // lower ratio cost very different amounts of work:
        //
        //  - pixelCertifiable: the output can be scored against the source at all (SDR, non-downgrade
        //    output codec, VMAF present, geometry within VmafPairScorer.MAX_COMPARE_PIXELS). This is
        //    what unlocks the pixel-proven "Perceptually Lossless Verified" label. It now reaches
        //    4K-class sources, which previously could never be pixel-proven at any ratio.
        //  - probeEligible: additionally worth spending the ladder's trial encodes on
        //    (QualityProbePolicy.PROBE_LADDER_MAX_PIXELS). Above that bar the full ladder cannot
        //    finish inside the prober's budget. Outside exhaustive mode such a plan keeps its
        //    default/learned ratio and relies on certification alone. In exhaustive mode it runs
        //    the SHORT ladder instead, up to the 4K scoring cap
        //    (ExhaustivePerceptualLosslessPolicy.probeLadderAllowed).
        val pixelCertifiable = !codecDowngrade && !source.isHdr && VmafNative.isAvailable &&
            QualityProbePolicy.isPixelScoreableGeometry(source.width, source.height)
        val pixelCertifiableBlockReason = CertificationStatus.blockReasonFor(
            isHdr = source.isHdr,
            codecDowngrade = codecDowngrade,
            vmafAvailable = VmafNative.isAvailable,
            geometryScoreable = QualityProbePolicy.isPixelScoreableGeometry(source.width, source.height)
        )
        val probeEligible = pixelCertifiable &&
            ExhaustivePerceptualLosslessPolicy.probeLadderAllowed(source.width, source.height, exhaustive)
        val shortProbeLadder = probeEligible &&
            ExhaustivePerceptualLosslessPolicy.needsShortLadder(source.width, source.height)
        DiagLog.i(
            "CompressorLearning",
            "plan; profileKey=${profileKey.asKey()}; defaultRatio=$defaultRatio; learnedRatio=$targetRatio; " +
                "floorRatio=$floorRatio; learnedOvershoot=$learnedOvershoot; expectedOvershoot=$expectedOvershootFactor; " +
                "experimentalCbrCeiling=$useCbrCeiling; preferRemux=${remuxReason != null}; reason=${remuxReason ?: "none"}"
        )
        return PerceptualLosslessPlan(
            profileKey = profileKey,
            targetRatio = targetRatio,
            floorRatio = floorRatio,
            preferRemux = remuxReason != null,
            remuxReason = remuxReason,
            remuxWasSourceEfficient = (preserveSourceCodec || nearOptimal) && !profilePrefersRemux,
            remuxWasEvidencePreferred = profilePrefersRemux,
            useCbrCeiling = useCbrCeiling,
            expectedOvershootFactor = expectedOvershootFactor,
            probeEligible = probeEligible,
            shortProbeLadder = shortProbeLadder,
            pixelCertifiable = pixelCertifiable,
            pixelCertifiableBlockReason = pixelCertifiableBlockReason,
            defaultRatio = defaultRatio
        )
    }

    /**
     * Refines an inference-based plan with on-device pixel evidence (VMAF probe windows):
     *  - a clip the gates allow at the default ratio may earn a LOWER pixel-proven ratio
     *    (more savings at proven quality);
     *  - a clip the bit-density/near-optimal gates sent to remux may be unlocked when its
     *    probe windows prove the default-ratio encode transparent for THIS clip.
     * Any probe failure leaves the conservative plan untouched. Probe-proven encodes must
     * additionally pass sampled pixel certification after the full encode (fail-closed).
     */
    private suspend fun refinePlanWithPixelProbes(
        item: BatchVideoItem,
        outputMime: String,
        plan: PerceptualLosslessPlan,
        exhaustive: Boolean,
        iFrameIntervalSeconds: Float,
        // What Media3 reads to cut the probe clips: the source, or its normalised copy.
        transformerInputUri: Uri = item.sourceUri
    ): PerceptualLosslessPlan {
        if (!plan.probeEligible) return plan
        // HDR and codec-downgrade plans never probe; those gates are not inference.
        val source = item.toSourceInfo()
        // Bpp-classed ladder: healthy sources get the full downward ladder plus one safer
        // retreat rung; starved-but-probeable sources get safest-biased rungs only (their
        // first-ever measured trials); far-below-gate sources get none — for them even a
        // safest-rung pass would save less than measurement noise, so inference stands.
        val bpp = BatchQualityBitratePolicy.sourceBitsPerPixelPerFrame(source)
        val candidates = ExhaustivePerceptualLosslessPolicy.candidateRatios(
            plan.defaultRatio, bpp, exhaustive, shortLadder = plan.shortProbeLadder
        )
        if (candidates.isEmpty()) return plan
        if (plan.shortProbeLadder) {
            DiagLog.i(
                "CompressorProbe",
                "short ladder; job=${diagnosticJobId(item)}; ${item.originalWidth}x${item.originalHeight} is above " +
                    "the full-ladder geometry; exhaustive mode probes ${candidates.joinToString { "%.2f".format(java.util.Locale.US, it) }} " +
                    "with no bisection"
            )
        }
        // Decaying probe-skip latch: after repeated MEASURED safest-rung rejections for this
        // profile class, a few ladders are skipped to save probe encodes/battery — then the
        // next encounter re-probes so fresh pixels (never stale class history) keep the final
        // say. The skip is recorded in the structured trace so captures can tell "skipped by
        // recent measured evidence" apart from "never tried".
        if (ExhaustivePerceptualLosslessPolicy.honourProbeSkip(exhaustive) &&
            learningEngine.shouldSkipProbes(plan.profileKey)
        ) {
            val latched = learningEngine.noteProbeSkipped(plan.profileKey)
            DiagLog.i(
                "CompressorProbe",
                "probe skip; job=${diagnosticJobId(item)}; profile measured-rejected " +
                    "${latched.consecutiveMeasuredProbeRejections}x consecutively; " +
                    "skip ${latched.probeSkipsSinceLastProbe}/${SmartPerceptualProfileEngine.PROBE_SKIPS_BETWEEN_REPROBES} before forced re-probe"
            )
            return plan.copy(
                probeDetail = "probes skipped: this profile class measured visible loss at every " +
                    "candidate in ${latched.consecutiveMeasuredProbeRejections} consecutive recent ladders " +
                    "(skip ${latched.probeSkipsSinceLastProbe}/${SmartPerceptualProfileEngine.PROBE_SKIPS_BETWEEN_REPROBES}, then re-probes)"
            )
        }
        // Same request shape as the real encode (compressOne), so a probe pass is evidence about it.
        val shape = ProbeEncodeShape(
            bitrateMode = if (plan.useCbrCeiling) {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            } else {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            },
            iFrameIntervalSeconds = iFrameIntervalSeconds,
            maxBFrames = EncoderExperiments.maxBFrames(getApplication())
        )
        val decision = qualityProber.runLadder(
            sourceUri = item.sourceUri,
            durationMs = item.durationMs,
            outputMime = outputMime,
            candidateRatios = candidates,
            targetBitrateForRatio = { ratio ->
                BatchQualityBitratePolicy.calculateVideoBitrate(
                    source = source,
                    mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
                    outputMimeType = outputMime,
                    learnedTargetRatio = ratio,
                    pixelProvenRatioFloor = ratio
                )
            },
            audioBitrate = calculateAudioBitrate(item, BatchQualityPreset.ORIGINAL),
            allowDownwardRefinement = !plan.shortProbeLadder,
            shape = shape,
            budgetMs = ExhaustivePerceptualLosslessPolicy.probeBudgetMs(plan.shortProbeLadder),
            sourceFps = item.originalFps.toDouble(),
            transformerInputUri = transformerInputUri
        )
        DiagLog.i(
            "CompressorProbe",
            "probe result; job=${diagnosticJobId(item)}; probed=${decision.probedRatios}; " +
                "proven=${decision.provenRatio ?: "none"}; marginal=${decision.marginal}; shape=${shape.compact()}; " +
                "input=${if (transformerInputUri == item.sourceUri) "source" else "platform-normalised"}; ${deviceLoadNote()}; " +
                "detail=${decision.detail}"
        )
        val probeTrace = plan.copy(
            probedRatios = decision.probedRatios,
            probeDetail = decision.detail,
            probeWindowScores = compactWindowScores(decision.windowScores),
            probePairDiag = compactPairingDiag(decision.windowScores),
            probeV1Scores = compactV1Scores(decision.windowScores),
            probeRateDiag = decision.rateDiag
        )
        // Nothing was measured and nothing is learned from a file Media3 cannot read; planItem
        // decides whether a normalised copy gets a second attempt.
        decision.sourceParseFailure?.let { return probeTrace.copy(sourceParseFailure = it) }
        val proven = decision.provenRatio ?: run {
            // Measured rejection at the SAFEST candidate ratio is positive pixel evidence
            // that no allowed target can encode this clip transparently: skip the item
            // entirely (original untouched, no stream copy). Unmeasurable probes change
            // nothing — the conservative inference decision stands, honestly labeled.
            return if (decision.highestCandidateMeasuredRejected) {
                learningEngine.recordMeasuredProbeRejection(plan.profileKey)
                probeTrace.copy(
                    preferRemux = true,
                    skipReason = "On-device VMAF measured visible quality loss at every " +
                        "candidate ratio (${decision.probedRatios.joinToString { "%.2f".format(it) }}); " +
                        "original left untouched."
                )
            } else {
                probeTrace
            }
        }
        // A pixel-proven rung clears the probe-skip latch: fresh evidence supersedes history.
        learningEngine.recordProbePass(plan.profileKey)
        // A proven ratio must still clear file-size measurement noise before overturning a
        // remux decision — a NOISE threshold, not a worthiness bar: verified 1-2% savings count.
        // The overshoot is the one this file's probe clips measured, bounded low (MeasuredOvershoot);
        // the class's learned value only stands in when the probes gave too few windows. b167
        // job_732f7ecfb699 (learned 1.003, measured 1.26) was encoded and discarded for size; b168
        // job_458aa0663c3e (measured 1.062, learned 1.119 from 732f) was kept although b167 had
        // encoded it at 1.003 and saved 9.5 %.
        val overshoot = MeasuredOvershoot.forPrediction(plan.expectedOvershootFactor, decision.provenRateFactors)
        val predicted = BatchQualityBitratePolicy.predictedPerceptualLosslessBytes(
            source = source,
            outputMimeType = outputMime,
            learnedTargetRatio = proven,
            expectedOvershootFactor = overshoot,
            pixelProvenRatioFloor = proven
        )
        val worth = ExhaustivePerceptualLosslessPolicy.worthEncoding(
            sourceBytes = item.originalSize,
            predictedBytes = predicted,
            exhaustive = exhaustive,
            meetsNoiseThreshold = BatchQualityBitratePolicy.meetsMinimumUsefulSavings(item.originalSize, predicted)
        )
        DiagLog.i(
            "CompressorProbe",
            "size gate; job=${diagnosticJobId(item)}; proven=${"%.2f".format(java.util.Locale.US, proven)}; " +
                "predictedBytes=$predicted; sourceBytes=${item.originalSize}; " +
                "${MeasuredOvershoot.describe(plan.expectedOvershootFactor, decision.provenRateFactors)}; " +
                "verdict=${if (worth) "encode" else "keep original"}"
        )
        if (!worth) {
            // The ladder returns the lowest rung it may use and every rung above it is larger, so
            // no usable ratio is both proven and smaller: keep the original. Falling back to the
            // inference plan instead would encode at its target ratio, which may be a rung the
            // probes just measured as failing.
            val basis = KeepOriginalMessages.sizeGateBasis(
                provenRatio = proven,
                predictedBytes = predicted,
                sourceBytes = item.originalSize,
                overshoot = overshoot,
                overshootMeasured = MeasuredOvershoot.fromThisFile(decision.provenRateFactors)
            )
            return probeTrace.copy(
                preferRemux = true,
                remuxReason = KeepOriginalMessages.SIZE_GATE_REASON,
                remuxWasSourceEfficient = true,
                remuxWasEvidencePreferred = false,
                sizeGateBasis = basis
            )
        }
        // Adopt the proven ratio in BOTH directions: below the learned target it buys more
        // savings at proven quality; above it (a safer retreat rung) it converts a would-be
        // remux/skip into a small verified reduction. Certification re-checks the full output.
        return probeTrace.copy(
            targetRatio = proven,
            preferRemux = false,
            remuxReason = null,
            remuxWasSourceEfficient = false,
            remuxWasEvidencePreferred = false,
            pixelProvenRatio = proven,
            requiresMeasuredCertification = exhaustive && plan.preferRemux,
            sizeGateOvershoot = overshoot,
            saferPassingRatio = decision.saferPassingRatio,
            saferPassingRateFactors = decision.saferPassingRateFactors
        )
    }

    /**
     * Power-save and thermal state, for the probe line. b168 scored the same ladders 3x slower
     * than b167 on identical scoring code (2.8 s per window for one fifth of the batch, 10-20 s for
     * the rest), which points at the device, and nothing in the capture could say which.
     */
    private fun deviceLoadNote(): String = runCatching {
        val pm = getApplication<Application>().getSystemService(android.os.PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus.toString() else "n/a"
        "powerSave=${pm.isPowerSaveMode}; thermalStatus=$thermal; thermalHeadroom=${thermalHeadroom(pm)}"
    }.getOrDefault("powerSave=unknown")

    // PowerManager.getThermalHeadroom returns NaN when called more often than about once per
    // second and is documented for at most one call per 10 s; the last reading is reused in between.
    @Volatile private var headroomReadAt = 0L
    @Volatile private var headroom = Float.NaN

    private fun thermalHeadroom(pm: android.os.PowerManager): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "n/a"
        val now = android.os.SystemClock.elapsedRealtime()
        if (headroomReadAt == 0L || now - headroomReadAt >= 10_000L) {
            headroom = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
            headroomReadAt = now
        }
        return if (headroom.isNaN()) "unavailable" else String.format(java.util.Locale.US, "%.2f", headroom)
    }

    /**
     * An async trace span around one stage of one attempt, for Perfetto (`atrace` category app).
     * Async because a stage suspends and resumes on other threads; a synchronous section would be
     * left unmatched. Cookie = the attempt number, so a retry's spans are separate.
     */
    private suspend fun <T> traced(stage: String, token: AttemptToken, block: suspend () -> T): T {
        val name = "PL.$stage"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.os.Trace.beginAsyncSection(name, token.attempt)
        try {
            return block()
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.os.Trace.endAsyncSection(name, token.attempt)
        }
    }

    private fun logEncoderPlan(
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        requestedCodec: BatchCodecOption,
        plan: ResolvedEncodePlan?,
        plannedFps: Int?
    ) {
        val source = item.toSourceInfo()
        // The requested bitrates here are the ones the encoder is given (ResolvedEncodePlan). b169
        // logged 2,490,432 bps / audio 256 kbps here for an encode that requested 2,197,440 bps
        // and copied 128 kbps audio, because this line recomputed them without the proven floor.
        DiagLog.i(
            "CompressorEncoderPlan",
            "mode=${quality.label}; requestedCodec=${requestedCodec.label}; resolvedOutputMime=${plan?.outputMime ?: "stream-copy"}; " +
                "source=${item.originalWidth}x${item.originalHeight}@${item.originalFps}fps; bitrate=${item.originalBitrate}; audioBitrate=${item.originalAudioBitrate}; " +
                "sourceCodec=${item.sourceVideoMime ?: "unknown"}; hdr=${if (source.isHdr) "yes" else "no/unknown"}; colorTransfer=${item.sourceColorTransfer ?: "unknown"}; " +
                "target=${targetHeightFor(item, quality)}p@${plannedFps ?: item.originalFps.toInt()}fps; " +
                (plan?.let { "targetVideoBitrate=${it.requestedVideoBitrate}; ${it.describe()}; " } ?: "targetVideoBitrate=${item.originalBitrate}; ") +
                "privacy=${_uiState.value.metadataPrivacyMode}"
        )
    }

    private fun logVerificationResult(
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        verification: OutputVerificationReport,
        outputSize: Long
    ) {
        DiagLog.i(
            "CompressorVerification",
            "mode=${quality.label}; job=${diagnosticJobId(item)}; verification=${verification.verdict}; playable=${verification.playability}; " +
                "replaceAllowed=${verification.replacementSafe}; blockReason=${verification.replacementBlockReason ?: "none"}; outputSize=${outputSize}"
        )
        // On a FAILURE, name the specific checks that failed. The summary above reports only the
        // verdict, which makes a rejection undiagnosable from a capture: a 2026-08-13 S23 Ultra
        // batch produced two outputs that were pixel-proven at ratio 0.65 (VMAF 98.9/97.0/91.0)
        // and were then discarded with nothing in the log to say which parity check rejected them.
        // Emitted only when the verdict is negative, so healthy batches gain no extra noise.
        if (!verification.verified) {
            val failing = verification.failingChecks()
            DiagLog.w(
                "CompressorVerification",
                "verification detail; job=${diagnosticJobId(item)}; verdict=${verification.verdict}; " +
                    "criticalFieldsComplete=${verification.criticalFieldsComplete}; " +
                    "failedOnlyOnVideoBitrateFloor=${verification.failedOnlyOnVideoBitrateFloor}; " +
                    "failing=${if (failing.isEmpty()) "none-identified" else failing.joinToString(",")}"
            )
        }
    }

    private suspend fun compressOne(
        context: Context,
        item: BatchVideoItem,
        index: Int,
        quality: BatchQualityPreset,
        frameRate: BatchFrameRateOption,
        videoMimeType: String,
        // The resolved encode (ResolvedEncodePlan): the bitrate requested here, the audio
        // copy/re-encode decision and the row's estimate all come from it.
        plan: ResolvedEncodePlan,
        // Phase reporting bound to this attempt: a callback from a superseded attempt is ignored.
        phases: PhaseReporter,
        useCbrCeiling: Boolean = false,
        iFrameIntervalSeconds: Float = KeyframeIntervalPolicy.WHEN_UNKNOWN_SECONDS,
        // What Media3 reads: the source, or its platform-normalised copy (Media3InputNormalizer).
        transformerInputUri: Uri = item.sourceUri
    ): EncodeAttemptResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val outputFile = item.cacheOutputFile(context, quality)
            if (outputFile.exists()) outputFile.delete()

            val targetBitrate = plan.requestedVideoBitrate
            val audioBitrate = (plan.audio as? ResolvedEncodePlan.AudioPlan.Reencode)?.requestedBitrate
                ?: calculateAudioBitrate(item, quality)
            val estimatedOutputSize = plan.estimatedBytes
            // Mode-aware: always null for Remux Only and Perceptually Lossless, so no
            // FrameDropEffect can ever be attached in those modes.
            val plannedFps = outputFpsFor(item, frameRate, quality)

            // Experimental Tier-1 ceiling: CBR holds the requested average, so the QTI VBR
            // quality-boost overshoot (measured ~1.25x on this device class) cannot apply.
            // A request is still not a guarantee — OutputVerifier judges the real output.
            val bitrateMode = if (useCbrCeiling) {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            } else {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            }
            val bitrateModeLabel = if (useCbrCeiling) "CBR" else "VBR"

            val decoderFactory = DefaultDecoderFactory.Builder(context)
                .setEnableDecoderFallback(true)
                .build()

            // Under the experiment, disable Media3's silent "closest supported format" fallback:
            // a Perceptually Lossless attempt must either encode exactly what was requested or
            // fail fast into the honest remux fallback, never silently change truth-critical
            // properties. Outside the experiment, production behavior is unchanged.
            // Requesting audio encoder settings forces Media3 to decode, mix and re-encode the
            // audio track. For an AAC source that would only add a lossy generation — see
            // BatchQualityBitratePolicy.shouldPassThroughAudio — so the audio request is left at
            // its default and Media3 copies the track bit-exactly.
            // The same rule, applied once in ResolvedEncodePlan.audioPlan.
            val passThroughAudio = plan.audio is ResolvedEncodePlan.AudioPlan.Copy
            val maxBFrames = EncoderExperiments.maxBFrames(context)
            val videoSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .setBitrateMode(bitrateMode)
                // Keyframes as far apart as the source's (KeyframeIntervalPolicy), not Media3's
                // 1 s default, so intra frames do not take the bits the P-frames are judged on.
                .setiFrameIntervalSeconds(iFrameIntervalSeconds)
            if (maxBFrames > 0) videoSettings.setMaxBFrames(maxBFrames)
            val encoderFactoryBuilder = DefaultEncoderFactory.Builder(context)
                .setEnableFallback(!useCbrCeiling)
                .setRequestedVideoEncoderSettings(videoSettings.build())
            if (!passThroughAudio) {
                encoderFactoryBuilder.setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder()
                        .setBitrate(audioBitrate)
                        .build()
                )
            }
            val encoderFactory = encoderFactoryBuilder.build()
            DiagLog.i(
                "CompressorEncoderPlan",
                "audio; mode=${quality.label}; sourceMime=${item.sourceAudioMime}; " +
                    "sourceBitrate=${item.originalAudioBitrate}; " +
                    if (passThroughAudio) "action=passthrough" else "action=reencode; targetBitrate=$audioBitrate"
            )

            // Counts what the asset loader reads from the source, so an export error can say
            // whether the INPUT side was still delivering (ExportInputProbeReport).
            val inputProbe = ExportInputProbe(DefaultDataSource.Factory(context))
            var progressJob: Job? = null
            val transformer = Transformer.Builder(context)
                // See ExportWatchdogPolicy: the limit is a hang bound — not sized from total
                // item wall time, which the watchdog never observes. The 2026-09-01 abort at
                // 120 s was a wedged export, not a slow healthy encode.
                .setMaxDelayBetweenMuxerSamplesMs(ExportWatchdogPolicy.MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS)
                .setVideoMimeType(videoMimeType)
                .setAssetLoaderFactory(
                    ExoPlayerAssetLoader.Factory(
                        context, decoderFactory, androidx.media3.common.util.Clock.DEFAULT, inputProbe.mediaSourceFactory()
                    )
                )
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressJob?.cancel()
                        val finalSize = outputFile.length()
                        val configDelta = EncoderConfigDelta(
                            requestedMime = videoMimeType,
                            actualMime = exportResult.videoMimeType,
                            requestedWidth = item.originalWidth,
                            requestedHeight = item.originalHeight,
                            actualWidth = exportResult.width,
                            actualHeight = exportResult.height,
                            requestedVideoBitrate = targetBitrate,
                            actualAverageVideoBitrate = exportResult.averageVideoBitrate,
                            requestedBitrateMode = bitrateModeLabel,
                            encoderName = exportResult.videoEncoderName
                        )
                        DiagLog.i(
                            "CompressorEncoderPlan",
                            "encodeResult; mode=${quality.label}; requestedVideoBitrate=$targetBitrate; requestedBitrateMode=$bitrateModeLabel; " +
                                "encoderName=${exportResult.videoEncoderName ?: "unknown"}; reportedAverageVideoBitrate=${exportResult.averageVideoBitrate}; " +
                                "overshootFactor=${if (targetBitrate > 0 && exportResult.averageVideoBitrate > 0) "%.3f".format(exportResult.averageVideoBitrate.toDouble() / targetBitrate) else "unknown"}; " +
                                "outputBytes=$finalSize; config[${configDelta.compact()};gop=${KeyframeIntervalPolicy.describe(iFrameIntervalSeconds)}${EncoderExperiments.describe(maxBFrames)}]"
                        )
                        if (configDelta.formatFellBack) {
                            // Loud on purpose: a silent substitution is exactly the kind of thing
                            // that makes a later verification rejection look inexplicable.
                            DiagLog.w(
                                "CompressorEncoderPlan",
                                "encoder format fallback; job=${diagnosticJobId(item)}; ${configDelta.compact()}"
                            )
                        }
                        // The encode is done; the item is not. The metadata remux, verification and
                        // certification follow, and any of them may still discard this output.
                        phases.encodeFinished()
                        phases.message("Encoded; finalizing the output (metadata, container)…")
                        if (continuation.isActive) {
                            continuation.resume(
                                EncodeAttemptResult(
                                    file = outputFile,
                                    requestedVideoBitrate = targetBitrate,
                                    requestedBitrateModeLabel = bitrateModeLabel,
                                    videoEncoderName = exportResult.videoEncoderName,
                                    reportedAverageVideoBitrate = exportResult.averageVideoBitrate,
                                    configDelta = configDelta
                                )
                            )
                        }
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        progressJob?.cancel()
                        DiagLog.w(
                            "CompressorBatch",
                            "export failed; job=${diagnosticJobId(item)}; code=${exportException.errorCodeName}; ${inputProbe.snapshot()}"
                        )
                        runCatching { outputFile.delete() }
                        // A parse error Media3 did report (during preparation) takes the same path
                        // as one it swallows: the item may retry from a normalised copy.
                        val parse = inputProbe.parseFailure?.takeIf { SourceParseFailure.hasParserCause(exportException) }
                        if (continuation.isActive) {
                            continuation.resumeWithException(parse?.let { SourceParseException(it) } ?: exportException)
                        }
                    }
                })
                .build()

            activeTransformer = transformer
            continuation.invokeOnCancellation {
                progressJob?.cancel()
                transformer.cancel()
                runCatching { outputFile.delete() }
            }
            // Media3 stops loading on a parse error it will never retry and never reports during an
            // export; without this the encode idles until the 120 s muxer watchdog
            // (SourceParseFailure). A full-file export cannot complete once its loader has stopped,
            // so once it stops moving it is ended and the item's fallback decides.
            val parseWatchHandler = android.os.Handler(android.os.Looper.getMainLooper())
            inputProbe.onFatalParse = { failure ->
                val meter = ExportStallMeter()
                parseWatchHandler.post(object : Runnable {
                    override fun run() {
                        if (!continuation.isActive) return
                        val holder = ProgressHolder()
                        val progress = if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            holder.progress.toLong()
                        } else {
                            0L
                        }
                        if (!meter.stalled(outputFile.length() + progress, android.os.SystemClock.elapsedRealtime())) {
                            parseWatchHandler.postDelayed(this, SourceParseFailure.STALL_POLL_MS)
                            return
                        }
                        DiagLog.w(
                            "CompressorBatch",
                            "export stopped; job=${diagnosticJobId(item)}; ${failure.describe()}; no progress for " +
                                "${SourceParseFailure.EXPORT_GRACE_MS}ms; ${inputProbe.snapshot()}"
                        )
                        progressJob?.cancel()
                        transformer.cancel()
                        runCatching { outputFile.delete() }
                        continuation.resumeWithException(SourceParseException(failure))
                    }
                })
            }

            val effectsList = mutableListOf<Effect>()
            val targetHeight = targetHeightFor(item, quality)
            if (targetHeight in 2 until item.originalHeight) {
                val aspectRatio = if (item.originalHeight > 0) item.originalWidth.toFloat() / item.originalHeight else 16f / 9f
                var outputWidth = (targetHeight * aspectRatio).toInt().coerceAtLeast(2)
                var outputHeight = targetHeight.coerceAtLeast(2)
                if (outputWidth % 2 != 0) outputWidth--
                if (outputHeight % 2 != 0) outputHeight--
                effectsList.add(Presentation.createForWidthAndHeight(outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT))
            }

            if (plannedFps != null && item.originalFps > plannedFps + 0.5f) {
                effectsList.add(FrameDropEffect.createSimpleFrameDropEffect(item.originalFps, plannedFps.toFloat()))
            }

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(transformerInputUri))
                .setEffects(Effects(emptyList(), effectsList))
                .build()

            val composition = Composition.Builder(listOf(EditedMediaItemSequence.Builder(editedMediaItem).build()))
                .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
                .build()

            transformer.start(composition, outputFile.absolutePath)

            progressJob = viewModelScope.launch(Dispatchers.Main) {
                while (continuation.isActive) {
                    val progressHolder = ProgressHolder()
                    val progressState = transformer.getProgress(progressHolder)
                    val currentSize = if (outputFile.exists()) outputFile.length() else 0L
                    // Media3's own fraction, or none (WAITING_FOR_AVAILABILITY / UNAVAILABLE): the
                    // bar then goes indeterminate instead of showing a number nobody measured.
                    val progress = if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        progressHolder.progress / 100f
                    } else {
                        null
                    }
                    phases.encodeProgress(progress, currentSize)
                    // The live file length is a temporary footprint: the muxer may reserve and later
                    // truncate space, and the metadata remux rewrites the file. It is not the output size.
                    phases.message(
                        "Writing ${formatFileSize(currentSize)} (temporary, not the final size) • " +
                            "est ${formatFileSize(estimatedOutputSize)}${plannedFps?.let { fps -> " • ${fps}fps" } ?: ""}"
                    )
                    delay(200)
                }
            }
        }
    }

    private suspend fun remuxOnlyOne(
        context: Context,
        item: BatchVideoItem,
        phases: PhaseReporter,
        privacyMode: MetadataPrivacyMode
    ): Mp4MetadataRemuxResult = withContext(Dispatchers.IO) {
        val remuxContext = currentCoroutineContext()
        phases.enter(ItemPhase.REMUXING)
        val outputFile = item.cacheOutputFile(context, BatchQualityPreset.REMUX_ONLY)
        val estimatedOutputSize = estimateOutputSize(
            item = item,
            quality = BatchQualityPreset.REMUX_ONLY,
            codec = codecFromLabel(_uiState.value.codecOption),
            frameRate = frameRateFromLabel(_uiState.value.frameRateOption)
        )
        Mp4MetadataRemuxer.remuxSourceWithoutReencode(
            context = context,
            sourceUri = item.sourceUri,
            outputFile = outputFile,
            snapshot = item.metadataSnapshot.filteredForPrivacy(privacyMode),
            onProgress = { copiedBytes, outputBytes ->
                remuxContext.ensureActive()
                // Source bytes copied over source size: a real fraction of the work, unlike output bytes.
                val progress = if (item.originalSize > 0L) {
                    copiedBytes.toFloat() / item.originalSize.toFloat()
                } else {
                    null
                }
                phases.encodeProgress(progress, outputBytes)
                phases.message("Remuxing: ${formatFileSize(outputBytes)} written (temporary) • est ${formatFileSize(estimatedOutputSize)} • no re-encode")
            }
        )
    }

    private fun qualityFromLabel(label: String): BatchQualityPreset {
        return when (label) {
            "Remux only" -> BatchQualityPreset.REMUX_ONLY
            "Original" -> BatchQualityPreset.ORIGINAL
            "High" -> BatchQualityPreset.HIGH
            "Medium" -> BatchQualityPreset.MEDIUM
            else -> BatchQualityPreset.entries.firstOrNull { it.label == label } ?: BatchQualityPreset.ORIGINAL
        }
    }

    private fun frameRateFromLabel(label: String): BatchFrameRateOption {
        return BatchFrameRateOption.entries.firstOrNull { it.label == label } ?: BatchFrameRateOption.ORIGINAL
    }

    private fun codecFromLabel(label: String): BatchCodecOption {
        return BatchCodecOption.entries.firstOrNull { it.label == label } ?: BatchCodecOption.AUTO
    }

    private fun codecFromMime(mimeType: String): BatchCodecOption {
        return when (mimeType) {
            MimeTypes.VIDEO_H265 -> BatchCodecOption.HEVC
            MimeTypes.VIDEO_AV1 -> BatchCodecOption.AV1
            else -> BatchCodecOption.H264
        }
    }

    private fun BatchQualityPreset.toMode(): BatchQualityMode {
        return when (this) {
            BatchQualityPreset.REMUX_ONLY -> BatchQualityMode.REMUX_ONLY
            BatchQualityPreset.ORIGINAL -> BatchQualityMode.PERCEPTUAL_LOSSLESS
            BatchQualityPreset.HIGH -> BatchQualityMode.HIGH_QUALITY
            BatchQualityPreset.MEDIUM,
            BatchQualityPreset.LOW -> BatchQualityMode.STORAGE_SAVER
        }
    }

    private fun BatchFrameRateOption.toChoice(): BatchFrameRateChoice {
        return BatchFrameRateChoice.fromLabel(label)
    }

    private fun BatchVideoItem.toSourceInfo(trackProbe: OutputVerifier.TrackProbe? = null): VideoSourceInfo {
        return VideoSourceInfo(
            width = originalWidth,
            height = originalHeight,
            frameRate = originalFps,
            durationMs = durationMs,
            totalBitrate = originalBitrate,
            audioBitrate = originalAudioBitrate,
            videoMime = trackProbe?.videoCodec ?: sourceVideoMime,
            audioMime = trackProbe?.audioCodec ?: sourceAudioMime,
            colorTransfer = trackProbe?.colorTransfer ?: sourceColorTransfer,
            colorStandard = trackProbe?.colorStandard ?: sourceColorStandard,
            colorRange = trackProbe?.colorRange ?: sourceColorRange,
            rotationDegrees = metadataSnapshot.rotationDegrees,
            audioChannelCount = trackProbe?.audioChannelCount ?: sourceAudioChannels,
            audioSampleRate = trackProbe?.audioSampleRate ?: sourceAudioSampleRate,
            audioPresent = (trackProbe?.audioCodec ?: sourceAudioMime) != null,
            locationPresent = metadataSnapshot.hasLocation,
            mediaStoreDatePresent = metadataSnapshot.dateSource?.startsWith("MediaStore") == true,
            mp4DatePresent = metadataSnapshot.rawDateTag != null
        )
    }

    private fun outputFpsFor(item: BatchVideoItem, option: BatchFrameRateOption, quality: BatchQualityPreset): Int? {
        return BatchQualityBitratePolicy.plannedOutputFps(item.originalFps, quality.toMode(), option.toChoice())
    }

    private fun estimateOutputSize(item: BatchVideoItem, quality: BatchQualityPreset): Long {
        val codec = codecFromLabel(_uiState.value.codecOption)
        val frameRate = frameRateFromLabel(_uiState.value.frameRateOption)
        return estimateOutputSize(item, quality, codec, frameRate)
    }

    private fun estimateOutputSize(
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        codec: BatchCodecOption,
        frameRate: BatchFrameRateOption
    ): Long {
        if (item.originalSize <= 0L) return 0L
        val selectedMime = when (codec) {
            BatchCodecOption.HEVC -> MimeTypes.VIDEO_H265
            BatchCodecOption.H264 -> MimeTypes.VIDEO_H264
            BatchCodecOption.AV1 -> MimeTypes.VIDEO_AV1
            BatchCodecOption.AUTO -> chooseAutoCodec(
                item,
                quality,
                buildList {
                    add(MimeTypes.VIDEO_H264)
                    if (hasEncoder(MimeTypes.VIDEO_H265, item.toSourceInfo())) add(MimeTypes.VIDEO_H265)
                    if (hasEncoder(MimeTypes.VIDEO_AV1, item.toSourceInfo())) add(MimeTypes.VIDEO_AV1)
                }
            )
        }
        return BatchQualityBitratePolicy.estimateOutputSize(
            source = item.toSourceInfo(),
            mode = quality.toMode(),
            outputMimeType = selectedMime,
            frameRateChoice = frameRate.toChoice()
        )
    }

    private fun calculateAudioBitrate(item: BatchVideoItem, quality: BatchQualityPreset): Int {
        return BatchQualityBitratePolicy.calculateAudioBitrate(item.toSourceInfo(), quality.toMode())
    }

    private fun calculateVideoBitrate(
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        videoMimeType: String,
        learnedTargetRatio: Double? = null,
        pixelProvenRatioFloor: Double? = null
    ): Int {
        return BatchQualityBitratePolicy.calculateVideoBitrate(
            source = item.toSourceInfo(),
            mode = quality.toMode(),
            outputMimeType = videoMimeType,
            outputFps = outputFpsFor(item, frameRateFromLabel(_uiState.value.frameRateOption), quality),
            outputHeight = targetHeightFor(item, quality),
            learnedTargetRatio = learnedTargetRatio,
            pixelProvenRatioFloor = pixelProvenRatioFloor
        )
    }

    private fun perceptualLosslessFloor(item: BatchVideoItem): Int {
        return BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(item.toSourceInfo())
    }

    private fun fallbackOriginalBitrate(item: BatchVideoItem): Int {
        return BatchQualityBitratePolicy.fallbackOriginalBitrate(item.toSourceInfo())
    }

    private fun targetHeightFor(item: BatchVideoItem, quality: BatchQualityPreset): Int {
        val target = BatchQualityBitratePolicy.targetHeightFor(item.originalHeight, quality.toMode())
        return if (quality == BatchQualityPreset.LOW) minOf(target, 720).coerceAtLeast(2) else target
    }

    private fun recordDiagnosticJob(
        diagnostics: DiagnosticsRecorder,
        item: BatchVideoItem,
        requestedQuality: BatchQualityPreset,
        effectiveQuality: BatchQualityPreset,
        resolvedMime: String?,
        plannedTargetRatio: Double?,
        plannedTargetVideoBitrate: Int?,
        plannedDecisionReason: String? = null,
        wasStreamCopy: Boolean,
        verification: OutputVerificationReport?,
        outputSize: Long,
        terminal: BatchTerminalResult,
        elapsedMs: Long,
        fallbackReason: String? = null,
        discardedVideoBitrate: Int? = null,
        // Probe trace: every ratio the ladder attempted, the pixel-proven winner (if any),
        // and the prober's decision detail — so a capture can prove whether a trial encode
        // happened and exactly what it measured, file by file.
        probedRatios: List<Double> = emptyList(),
        pixelProvenRatio: Double? = null,
        probeDetail: String? = null,
        // Raw VMAF window scores ("mean/p5/min;…") from the probe ladder and the final-output
        // certification, plus the thermal state bracket of the whole job — the fields needed to
        // calibrate window thresholds and correlate throughput vs throttling from captures alone.
        probeWindowScores: String? = null,
        // Per-window frame-pairing diagnostics ("ref=N,dist=N,extra=a/b,skewMs=f/max/mean;…")
        // of the same probe rung — separates measured quality from misaligned comparisons.
        probePairDiag: String? = null,
        certWindowScores: String? = null,
        certBandingDiag: String? = null,
        certV1Scores: String? = null,
        probeV1Scores: String? = null,
        probeRateDiag: String? = null,
        certificationStatus: String? = null,
        // For a kept original: the basis sentence the user saw (KeepOriginalMessages.basis).
        decisionBasis: String? = null,
        // What Media3 read when it could not parse the source (Media3InputNormalizer), else null.
        media3Input: String? = null,
        encoderConfig: String? = null,
        thermalStart: String? = null,
        thermalEnd: String? = null,
        // Inter-item handoff telemetry: the thermal cooldown (ms) that was applied AFTER the
        // previous item and BEFORE this one started. 0 when the previous item ran no full encode
        // (the throughput optimization) or was skipped. Timing-only; no decision depends on it.
        precedingCooldownMs: Long? = null,
        // Materialization telemetry (keep-original fast path): REUSED_SOURCE when the original
        // was surfaced with no copy written, GENERATED_FILE when a distinct output was produced.
        // originalReuseBlockReason names the guard that forced a full remux; copyAvoidedBytes is
        // the source size whose stream-copy was skipped.
        materializationMode: String? = null,
        originalReuseBlockReason: String? = null,
        copyAvoidedBytes: Long? = null,
        // Typed retained-source validation (REUSED_SOURCE materialization only). Mutually
        // exclusive with [verification]: a retained source has no output verification, and a
        // generated output has no retention record. Verdict/verified derive from whichever exists.
        retainedValidation: RetainedSourceValidation? = null,
        // Size of the candidate the encode/remux produced, whether kept or discarded. Defaults to
        // [outputSize] (the kept output) when the caller has nothing else to report.
        candidateBytes: Long? = null,
        certificationDecision: CertificationDecision? = null,
        encodePlan: ResolvedEncodePlan? = null,
        attempts: List<String> = emptyList()
    ) {
        require(verification == null || retainedValidation == null) {
            "A job cannot carry both output verification and retained-source validation"
        }
        // The final word, kept apart from the structural verifier's (FinalAcceptance). A discarded
        // candidate never carries a success verdict or replacement permission, and its size moves
        // to candidateBytes instead of counting as an output.
        val acceptance = FinalAcceptance.of(
            terminal = terminal,
            structural = verification,
            keptOutputBytes = outputSize,
            candidateBytes = candidateBytes,
            retainedVerdict = retainedValidation?.verdict,
            retainedReadable = retainedValidation?.readableAtDecisionTime
        )
        val recordedOutputSize = if (retainedValidation != null) outputSize else acceptance.acceptedOutputBytes
        diagnostics.job(
            // The recorder hashes both values before emission; raw URI/name never leave this call.
            sourceKey = item.sourceUri.toString(),
            displayNameForHashOnly = item.originalName,
            sourceMime = item.sourceVideoMime,
            width = item.originalWidth,
            height = item.originalHeight,
            fps = item.originalFps,
            durationMs = item.durationMs,
            sourceSize = item.originalSize,
            sourceTotalBitrate = item.originalBitrate,
            bitrateWasMeasured = item.originalBitrateWasMeasured,
            hdr = item.toSourceInfo().isHdr,
            audioMime = item.sourceAudioMime,
            audioBitrate = item.originalAudioBitrate,
            requestedMode = requestedQuality.label,
            effectiveMode = effectiveQuality.label,
            plannedOutputMime = resolvedMime,
            plannedTargetRatio = plannedTargetRatio,
            plannedTargetVideoBitrate = plannedTargetVideoBitrate,
            plannedDecisionReason = plannedDecisionReason,
            wasStreamCopy = wasStreamCopy,
            verdict = acceptance.verdict,
            // For a retained source, "verified" records ONLY that the source was readable at
            // decision time — the verdict string makes the distinction explicit and machine-
            // readable via materializationMode=REUSED_SOURCE.
            verified = acceptance.verified,
            // Only a real output verification can name failing predicates. A retained source was
            // never verified as an output, so it records null here rather than an empty list that
            // would read as "verified and nothing failed".
            failedChecks = verification?.failingChecks(),
            // Retained sources never encode, so they can never be pixel-certified.
            pixelCertified = verification?.pixelCertified == true,
            replacementSafe = acceptance.replacementSafe,
            blockReason = when {
                retainedValidation != null -> "original retained — replacement is a no-op by design"
                verification != null && !acceptance.accepted && acceptance.structuralVerified == true ->
                    verification.replacementBlockReason ?: fallbackReason ?: acceptance.verdict
                else -> verification?.replacementBlockReason
            },
            outputSize = recordedOutputSize,
            terminal = terminal,
            elapsedMs = elapsedMs,
            fallbackReason = fallbackReason,
            discardedVideoBitrate = discardedVideoBitrate,
            probedRatios = probedRatios.takeIf { it.isNotEmpty() }
                ?.joinToString(",") { "%.2f".format(it) },
            pixelProvenRatio = pixelProvenRatio,
            probeDetail = probeDetail,
            probeWindowScores = probeWindowScores,
            probePairDiag = probePairDiag,
            certWindowScores = certWindowScores,
            certBandingDiag = certBandingDiag,
            certV1Scores = certV1Scores,
            probeV1Scores = probeV1Scores,
            probeRateDiag = probeRateDiag,
            decisionBasis = decisionBasis,
            media3Input = media3Input,
            certificationStatus = certificationStatus,
            audioPreservation = verification?.audioBasis,
            encoderConfig = encoderConfig,
            thermalStart = thermalStart,
            thermalEnd = thermalEnd,
            precedingCooldownMs = precedingCooldownMs,
            materializationMode = materializationMode,
            originalReuseBlockReason = originalReuseBlockReason,
            copyAvoidedBytes = copyAvoidedBytes,
            finalAccepted = acceptance.accepted,
            candidateBytes = acceptance.candidateBytes,
            structuralVerdict = acceptance.structuralVerdict,
            structuralVerified = acceptance.structuralVerified,
            structuralReplacementSafe = acceptance.structuralReplacementSafe,
            certificationDecision = certificationDecision?.wire,
            encodePlan = encodePlan?.describe(),
            attempts = attempts.takeIf { it.isNotEmpty() }?.joinToString(";")
        )
    }

    private fun diagnosticJobId(item: BatchVideoItem): String =
        DiagnosticsRecorder.redactedJobId(item.sourceUri.toString())

    // Compact "mean/p5/min" per window, ";"-joined — the capture-friendly form of VMAF window
    // scores (e.g. "96.2/92.0/85.1;97.0/93.4/88.8"). Null when nothing was measured.
    // Locale-pinned so comma-decimal device locales cannot corrupt the capture format.
    // Three decimals, NOT one: production compares the UNROUNDED WindowScore doubles, so a floor
    // logged as "95.5" could be anything in [95.45, 95.55) and a study fit to it inherits a
    // rounding artifact near every threshold boundary. The v1 calibration study's entire reported
    // "improvement" turned out to be one such artifact (research/perceptual_calibration/
    // REVIEW_FINDINGS.md), and NEXT_ROUND_INSTRUMENTATION.md item 1 asks for exactly this widening.
    // Logging precision only: every decision still reads the unrounded doubles.
    // Compact VMAF v1 shadow scores, ";"-joined. Null when v1 was unavailable. Telemetry only.
    private fun compactV1Scores(scores: List<WindowScore>?): String? =
        scores?.mapNotNull { it.v1?.compact() }?.takeIf { it.isNotEmpty() }?.joinToString(";")

    private fun compactWindowScores(scores: List<WindowScore>?): String? =
        scores?.takeIf { it.isNotEmpty() }
            ?.joinToString(";") { "%.3f/%.3f/%.3f".format(java.util.Locale.US, it.mean, it.p5, it.min) }

    // Compact per-window pairing diagnostics, ";"-joined (see WindowPairingDiag.compact()).
    // Null when nothing was measured or the scores carry no pairing data.
    private fun compactPairingDiag(scores: List<WindowScore>?): String? =
        scores?.mapNotNull { it.pairing?.compact() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(";")

    // Compact per-window banding diagnostics, ";"-joined (see WindowBandingDiag.compact()).
    // Null when banding was not collected or the native feature was unavailable. Telemetry only.
    private fun compactBandingDiag(scores: List<WindowScore>?): String? =
        scores?.mapNotNull { it.banding?.compact() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(";")

    private fun completionMessage(
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        outputSize: Long,
        plannedFps: Int?,
        codecLabel: String,
        muxerMessage: String,
        verification: OutputVerificationReport,
        privacyMode: MetadataPrivacyMode
    ): String {
        val sizeSimilar = item.originalSize > 0L &&
            kotlin.math.abs(outputSize - item.originalSize).toDouble() / item.originalSize.toDouble() < 0.03
        val sizeSummary = if (quality == BatchQualityPreset.REMUX_ONLY && sizeSimilar) {
            "size similar (${formatFileSize(item.originalSize)} -> ${formatFileSize(outputSize)})"
        } else {
            "${formatFileSize(item.originalSize)} -> ${formatFileSize(outputSize)}"
        }
        val modeSummary = if (quality == BatchQualityPreset.REMUX_ONLY) {
            "Remux Only: no re-encode, video/audio copied unchanged"
        } else {
            "${quality.label}: ${item.originalWidth}x${item.originalHeight} • ${plannedFps ?: item.originalFps.toInt()}fps • $codecLabel"
        }
        val audio = verification.audioBasis?.let { " • audio: $it" } ?: ""
        return "$modeSummary • $sizeSummary • ${verification.verdict}$audio • ${privacyMode.summary} • $muxerMessage"
    }

    private fun recommendFor(item: BatchVideoItem): CompressionRecommendation {
        val highFps = item.originalFps >= 50f
        val highBitrate = item.originalBitrate >= 25_000_000
        val hugeFile = item.originalSize >= 900L * 1024L * 1024L
        val fourK = item.originalHeight >= 2160 || item.originalWidth >= 3840

        return when {
            item.originalSize in 1L until 80L * 1024L * 1024L && !highBitrate -> CompressionRecommendation(
                title = "Remux Only",
                expectedSavings = "0-5%",
                qualityRisk = "Very low",
                reason = "The file is already modest in size, so copying tracks avoids quality loss and mainly refreshes the container/metadata.",
                qualityPreset = BatchQualityPreset.REMUX_ONLY.label,
                codecOption = BatchCodecOption.AUTO.label,
                frameRateOption = BatchFrameRateOption.ORIGINAL.label
            )
            fourK && highBitrate -> CompressionRecommendation(
                title = "Perceptually Lossless + HEVC",
                expectedSavings = "20-35%",
                qualityRisk = "Very low",
                reason = "High-bitrate 4K Samsung-style video should benefit from HEVC while preserving resolution, FPS, HDR, and audio quality.",
                qualityPreset = BatchQualityPreset.ORIGINAL.label,
                codecOption = BatchCodecOption.HEVC.label,
                frameRateOption = BatchFrameRateOption.ORIGINAL.label
            )
            hugeFile && highFps -> CompressionRecommendation(
                title = "High Quality + HEVC",
                expectedSavings = "30-50%",
                qualityRisk = "Low",
                reason = "Large high-frame-rate clips usually save more with HEVC and a 30fps cap if storage is the priority.",
                qualityPreset = BatchQualityPreset.HIGH.label,
                codecOption = BatchCodecOption.HEVC.label,
                frameRateOption = BatchFrameRateOption.FPS30.label
            )
            item.originalHeight <= 1080 && item.originalFps <= 30f -> CompressionRecommendation(
                title = "H.264 compatibility mode",
                expectedSavings = "10-25%",
                qualityRisk = "Low",
                reason = "This clip is already easy to share, so H.264 keeps compatibility high.",
                qualityPreset = BatchQualityPreset.HIGH.label,
                codecOption = BatchCodecOption.H264.label,
                frameRateOption = BatchFrameRateOption.ORIGINAL.label
            )
            item.originalSize > 300L * 1024L * 1024L -> CompressionRecommendation(
                title = "Storage Saver",
                expectedSavings = "45-60%",
                qualityRisk = "Medium",
                reason = "The source is large enough that a stronger storage-saver setting may be worth the quality tradeoff.",
                qualityPreset = BatchQualityPreset.MEDIUM.label,
                codecOption = BatchCodecOption.HEVC.label,
                frameRateOption = if (highFps) BatchFrameRateOption.FPS30.label else BatchFrameRateOption.ORIGINAL.label
            )
            else -> CompressionRecommendation(
                title = "High Quality + HEVC",
                expectedSavings = "20-40%",
                qualityRisk = "Low",
                reason = "High quality with HEVC is a balanced default for this video.",
                qualityPreset = BatchQualityPreset.HIGH.label,
                codecOption = BatchCodecOption.HEVC.label,
                frameRateOption = BatchFrameRateOption.ORIGINAL.label
            )
        }
    }

    private data class ReplacementResult(val success: Boolean, val message: String)

    private suspend fun replaceOriginalSafely(
        context: Context,
        item: BatchVideoItem,
        outputFile: File,
        useShizukuFallback: Boolean,
        quality: BatchQualityPreset,
        verification: OutputVerificationReport,
        backupBeforeReplace: Boolean,
        privacyMode: MetadataPrivacyMode
    ): ReplacementResult = withContext(Dispatchers.IO) {
        if (!outputFile.exists() || outputFile.length() <= 0) {
            return@withContext ReplacementResult(false, "Replacement skipped: output file was missing or empty.")
        }

        if (!verification.replacementSafe) {
            val savedUri = saveFileToGallery(context, outputFile, item.outputName(quality), item.metadataSnapshot, privacyMode)
            return@withContext ReplacementResult(
                false,
                if (savedUri != null) {
                    "Replace only after verification blocked replacement: ${verification.replacementBlockReason ?: "output verification failed"}. A safe copy was saved instead."
                } else {
                    "Replace only after verification blocked replacement: ${verification.replacementBlockReason ?: "output verification failed"}, and saving a safe copy failed."
                }
            )
        }

        if (shouldBlockOriginalOverwrite(item, outputFile, quality)) {
            val savedUri = saveFileToGallery(context, outputFile, item.outputName(quality), item.metadataSnapshot, privacyMode)
            val ratioPercent = ((outputFile.length().toDouble() / item.originalSize.toDouble()) * 100.0).toInt()
            return@withContext ReplacementResult(
                false,
                if (savedUri != null) {
                    "Perceptually-lossless overwrite blocked to protect quality: output was only $ratioPercent% of the source. A safe copy was saved instead."
                } else {
                    "Perceptually-lossless overwrite blocked to protect quality: output was only $ratioPercent% of the source, and saving a safe copy failed."
                }
            )
        }

        val backupMessage = if (backupBeforeReplace) {
            val backedUp = saveSourceBackupToGallery(context, item)
            if (!backedUp) {
                val savedUri = saveFileToGallery(context, outputFile, item.outputName(quality), item.metadataSnapshot, privacyMode)
                return@withContext ReplacementResult(
                    false,
                    if (savedUri != null) {
                        "Backup before replace failed, so the original was not touched. A safe output copy was saved instead."
                    } else {
                        "Backup before replace failed, so the original was not touched, and saving the output copy also failed."
                    }
                )
            }
            "Backup copy saved. "
        } else {
            ""
        }

        // Stage a private rollback copy of the ORIGINAL bytes BEFORE the destructive open.
        // openOutputStream(uri,"rwt") truncates the source to zero on open, so a failure during
        // copyTo (ENOSPC, provider error, process death) would otherwise leave the user's only
        // copy truncated with no way back. This is independent of backupBeforeReplace: that saves
        // a gallery copy the user opted into and may have turned off; this recovery copy always
        // exists for the duration of the write and is deleted the instant the write is confirmed.
        // In filesDir, NOT cacheDir. When the replacement fails, this copy can be the user's last
        // intact original, and the likeliest cause of that failure is a full disk. A full disk is
        // exactly when Android deletes other apps' cache directories to free space. The cache was
        // the one place this file was not safe.
        val recoveryDir = File(context.filesDir, BatchCacheRetention.RECOVERY_DIRECTORY).apply { mkdirs() }
        val recoveryCopy = File(recoveryDir, "${BatchCacheRetention.RECOVERY_FILE_PREFIX}${diagnosticJobId(item)}.mp4")

        // Free-space precheck. The recovery copy is a FULL copy of the original, so if the cache
        // volume cannot hold it we must not begin the destructive truncate-then-write at all — an
        // ENOSPC while staging is exactly how the user's only copy would be put at risk. Blocks only
        // on positive evidence of insufficient space; an unmeasurable filesystem proceeds (the staged
        // recovery + rollback remain the real backstop).
        val cacheAvailable = StorageSpacePolicy.availableBytesFor(recoveryDir)
        if (StorageSpacePolicy.blocks(item.originalSize, cacheAvailable)) {
            val shortfall = StorageSpacePolicy.shortfallMessage(item.originalSize, cacheAvailable)
            DiagLog.w("CompressorBatch", "replace precheck; job=${diagnosticJobId(item)}; blocked: $shortfall")
            val savedUri = saveFileToGallery(context, outputFile, item.outputName(quality), item.metadataSnapshot, privacyMode)
            return@withContext ReplacementResult(
                false,
                if (savedUri != null) {
                    "Not enough free space to protect the original ($shortfall), so it was left untouched. A safe compressed copy was saved instead."
                } else {
                    "Not enough free space to protect the original ($shortfall); it was left untouched, and saving a safe copy also failed."
                }
            )
        }
        // The stage -> write -> verify -> rollback decision lives in OriginalReplacementCoordinator so
        // it is exercised by OriginalReplacementCoordinatorTest, including the cases that cannot be
        // injected on a device (ENOSPC mid-write, a provider that will not report a size, a rollback
        // that itself fails). This object supplies only the raw IO.
        val replacementIo = object : OriginalReplacementIo {
            override fun stageRecoveryCopy(): Long {
                if (recoveryCopy.exists()) recoveryCopy.delete()
                val opened = context.contentResolver.openInputStream(item.sourceUri)?.use { input ->
                    recoveryCopy.outputStream().use { output -> input.copyTo(output) }
                }
                return if (opened == null) -1L else recoveryCopy.length()
            }

            override fun writeOutputOverOriginal() {
                context.contentResolver.openOutputStream(item.sourceUri, "rwt")?.use { out ->
                    outputFile.inputStream().use { input -> input.copyTo(out) }
                    // Best-effort durability so the size read-back reflects committed bytes.
                    runCatching { (out as? java.io.FileOutputStream)?.fd?.sync() }
                } ?: error("Could not open original for writing")
            }

            override fun readBackOriginalSize(): Long =
                context.contentResolver.openFileDescriptor(item.sourceUri, "r")?.use { it.statSize } ?: -1L

            override fun restoreOriginalFromRecovery() {
                context.contentResolver.openOutputStream(item.sourceUri, "rwt")?.use { out ->
                    recoveryCopy.inputStream().use { input -> input.copyTo(out) }
                    runCatching { (out as? java.io.FileOutputStream)?.fd?.sync() }
                } ?: error("Could not reopen original to restore it")
            }

            override fun recoveryCopyLength(): Long = recoveryCopy.length()

            override fun discardRecoveryCopy() { runCatching { recoveryCopy.delete() } }
        }

        val expectedLength = outputFile.length()
        val attempt = OriginalReplacementCoordinator.attempt(replacementIo, expectedLength)

        if (attempt is ReplacementAttempt.OriginalIntact &&
            attempt.reason == ReplacementAttempt.Reason.RECOVERY_STAGING_FAILED
        ) {
            val savedUri = saveFileToGallery(context, outputFile, item.outputName(quality), item.metadataSnapshot, privacyMode)
            return@withContext ReplacementResult(
                false,
                if (savedUri != null) {
                    "Could not stage a rollback copy of the original, so it was left untouched. A safe compressed copy was saved instead."
                } else {
                    "Could not stage a rollback copy of the original; it was left untouched, and saving a safe copy also failed."
                }
            )
        }
        if (attempt is ReplacementAttempt.Replaced) {
            val replacedPath = resolveFilesystemPath(context, item.sourceUri)
            val metadataReport = VideoMetadataPreserver.restoreAfterReplacement(
                context = context,
                sourceUri = item.sourceUri,
                snapshot = item.metadataSnapshot.filteredForPrivacy(privacyMode),
                replacedFilePath = replacedPath
            )
            return@withContext ReplacementResult(
                true,
                "${backupMessage}Original replaced through Android writable document access. Replace only after verification passed. Name/folder preserved. ${metadataReport.summary()}"
            )
        }

        // The coordinator already attempted the rollback when the write failed or could not be
        // confirmed. OriginalIntact means the source is whole (never truncated, or restored);
        // OriginalAtRisk means it may be incomplete AND the recovery copy still holds the only
        // intact original bytes, which the fallback below preserves before reporting.
        val sourceRestored = attempt is ReplacementAttempt.OriginalIntact

        // Shizuku writes the FULL verified output to the path, which also repairs a source that the
        // direct write may have truncated — so success here is a clean replacement. All Shizuku
        // sub-failures fall through to the single honest fallback below (which knows sourceRestored),
        // so a failed direct write can never be reported without accounting for the original's state.
        var shizukuWriteFailed = false
        if (useShizukuFallback && ShizukuSupport.hasPermission()) {
            val targetPath = resolveFilesystemPath(context, item.sourceUri)
            if (targetPath != null) {
                val copied = ShizukuSupport.copyFileWithShizuku(outputFile.absolutePath, targetPath)
                if (!copied) shizukuWriteFailed = true
                if (copied) {
                    runCatching { recoveryCopy.delete() }
                    val metadataReport = VideoMetadataPreserver.restoreAfterReplacement(
                        context = context,
                        sourceUri = item.sourceUri,
                        snapshot = item.metadataSnapshot.filteredForPrivacy(privacyMode),
                        replacedFilePath = targetPath
                    )
                    return@withContext ReplacementResult(
                        true,
                        "${backupMessage}Original replaced with Shizuku path fallback. Replace only after verification passed. Original folder/path preserved. ${metadataReport.summary()}"
                    )
                }
            }
        }

        // A FAILED Shizuku attempt may itself have truncated the target: it writes with `cat >`,
        // which truncates on open. So an earlier successful rollback can no longer be assumed to
        // still hold — re-verify against the recovery bytes before reassuring the user.
        val originalStillWhole = if (shizukuWriteFailed && sourceRestored) {
            runCatching {
                ReplacementSizeCheck.verified(replacementIo.readBackOriginalSize(), recoveryCopy.length())
            }.getOrDefault(false)
        } else {
            sourceRestored
        }

        // Every replacement attempt failed. Report the TRUTH about the original's state and never
        // claim it was "protected" when the destructive open may have truncated it.
        val savedUri = saveFileToGallery(context, outputFile, item.outputName(quality), item.metadataSnapshot, privacyMode)
        val result = if (originalStillWhole) {
            runCatching { recoveryCopy.delete() }
            ReplacementResult(
                false,
                if (savedUri != null) {
                    "In-place replacement failed, so the original was restored intact and left in place. A safe compressed copy was saved instead."
                } else {
                    "In-place replacement failed; the original was restored intact, but saving a safe copy also failed."
                }
            )
        } else {
            // Worst case: the source may be incomplete AND we could not restore it in place.
            // Preserve the untouched original bytes we still hold in the recovery copy to the
            // gallery so nothing is lost, then report honestly.
            val originalPreserved = saveFileToGallery(
                context, recoveryCopy, "ORIGINAL_${item.outputName(quality)}", item.metadataSnapshot, privacyMode
            ) != null
            // Only discard once the bytes exist somewhere else. If preserving them failed, this file
            // IS the user's last intact original — deleting it here would destroy it and make the
            // "don't clear the cache" advice below a lie. Keep it and say where it is.
            if (originalPreserved) {
                runCatching { recoveryCopy.delete() }
            } else {
                DiagLog.e(
                    "CompressorBatch",
                    "job=${diagnosticJobId(item)}; original may be incomplete and could not be preserved; " +
                        "retaining the only intact copy at ${recoveryCopy.absolutePath}"
                )
            }
            ReplacementResult(
                false,
                when {
                    originalPreserved && savedUri != null ->
                        "In-place replacement failed and the original on disk may be incomplete. A copy of your original AND the compressed version were both saved to your gallery — please verify the file in its original folder."
                    originalPreserved ->
                        "In-place replacement failed and the original on disk may be incomplete. A copy of your untouched original was saved to your gallery — please recover it from there."
                    else ->
                        "In-place replacement failed and the original on disk may be incomplete, and saving a recovered copy to your gallery failed. An intact copy of the original is still held inside the app — do not clear the app's storage, and free up space then try again."
                }
            )
        }
        result
    }

    private fun shouldBlockOriginalOverwrite(
        item: BatchVideoItem,
        compressedFile: File,
        quality: BatchQualityPreset
    ): Boolean {
        if (quality != BatchQualityPreset.ORIGINAL) return false
        if (item.originalSize < 200L * 1024L * 1024L) return false
        if (item.originalSize <= 0L || compressedFile.length() <= 0L) return false
        val outputRatio = compressedFile.length().toDouble() / item.originalSize.toDouble()
        return outputRatio <= 0.20
    }

    @Suppress("DEPRECATION")
    private fun resolveFilesystemPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveFileToGallery(
        context: Context,
        file: File,
        targetName: String,
        metadata: VideoMetadataSnapshot = VideoMetadataSnapshot(),
        privacyMode: MetadataPrivacyMode = MetadataPrivacyMode.PRESERVE_ALL
    ): Uri? {
        var inserted: Uri? = null
        return try {
            val filteredMetadata = metadata.filteredForPrivacy(privacyMode)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, targetName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (filteredMetadata.hasDate) {
                    VideoMetadataPreserver.applyToNewGalleryValues(this, filteredMetadata)
                } else if (!privacyMode.removeDate) {
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Compressor")
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val uri = context.contentResolver.insert(collection, values) ?: return null
            inserted = uri
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: error("could not open the new gallery entry for writing")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            uri
        } catch (_: Exception) {
            // Remove the half-written entry. Left behind, it is a truncated video in the gallery,
            // or on Q+ a pending row that never becomes visible but still holds its bytes.
            inserted?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            null
        }
    }

    private fun saveSourceBackupToGallery(context: Context, item: BatchVideoItem): Boolean {
        val backupDir = File(getApplication<Application>().cacheDir, "batch_compressed_videos").apply { mkdirs() }
        val base = item.originalName.substringBeforeLast(".").ifBlank { "Video_${System.currentTimeMillis()}" }
        val backupFile = File(backupDir, "${base}_Backup.mp4")
        return runCatching {
            if (backupFile.exists()) backupFile.delete()
            context.contentResolver.openInputStream(item.sourceUri)?.use { input ->
                backupFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            saveFileToGallery(
                context = context,
                file = backupFile,
                targetName = "${base}_Backup.mp4",
                metadata = item.metadataSnapshot,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            ) != null
        }.getOrDefault(false).also {
            runCatching { backupFile.delete() }
        }
    }

    private fun isLikelyCompressorOutput(name: String): Boolean {
        // Only skip files that match THIS app's own output naming (see [outputName]:
        // "<base>_Compressed.mp4" / "<base>_Remuxed.mp4"). The previous heuristic also matched
        // generic web/tool conventions ("compressed_video.mp4", "video_compressed_final.mp4"),
        // which silently skipped legitimate downloaded videos. Downloaded media must be inspected,
        // not skipped on a name guess, so only the exact app suffix qualifies.
        val base = name.substringBeforeLast('.').lowercase()
        return base.endsWith("_compressed") ||
            base.endsWith("-compressed") ||
            base.endsWith("_remuxed") ||
            base.endsWith("-remuxed")
    }

    private fun BatchVideoItem.outputName(quality: BatchQualityPreset): String {
        val base = originalName.substringBeforeLast(".").ifBlank { "Video_${System.currentTimeMillis()}" }
        val suffix = if (quality == BatchQualityPreset.REMUX_ONLY) "Remuxed" else "Compressed"
        return "${base}_$suffix.mp4"
    }

    private fun BatchVideoItem.cacheOutputFile(
        context: Context,
        quality: BatchQualityPreset
    ): File {
        val outputDir = File(context.cacheDir, "batch_compressed_videos").apply { mkdirs() }
        val base = originalName.substringBeforeLast(".").ifBlank { "Video" }
        val suffix = if (quality == BatchQualityPreset.REMUX_ONLY) "Remuxed" else "Compressed"
        val sourceToken = DiagnosticsRecorder.redactedJobId(sourceUri.toString()).removePrefix("job_")
        return File(outputDir, "${base}_${sourceToken}_$suffix.mp4")
    }

    /**
     * Deletes cached batch outputs, optionally preserving files that the CURRENT ui state still
     * points at. Starting a run used to wipe the whole directory, which could delete outputs the
     * still-displayed previous results referenced — leaving share/save pointing at files that no
     * longer exist. Passing the live outputPaths keeps those intact; genuine orphans (nothing in the
     * UI can reach them) are still reclaimed. An explicit user "clear" passes nothing and wipes all.
     */
    private fun clearBatchCache(preservePaths: Set<String> = emptySet()) {
        runCatching {
            val dir = File(getApplication<Application>().cacheDir, "batch_compressed_videos")
            if (!dir.exists()) return@runCatching
            val files = dir.listFiles() ?: return@runCatching
            files.forEach { file ->
                if (BatchCacheRetention.isDeletable(file.absolutePath, preservePaths)) file.delete()
            }
        }
    }
}
