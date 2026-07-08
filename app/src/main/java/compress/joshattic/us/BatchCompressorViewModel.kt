package compress.joshattic.us

import android.app.Application
import android.content.ContentValues
import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    Skipped
}

data class BatchVideoItem(
    val sourceUri: Uri,
    val originalName: String,
    val originalSize: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalBitrate: Int,
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
    val message: String? = null
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
    val qualityPreset: String = BatchQualityPreset.ORIGINAL.label,
    val frameRateOption: String = BatchFrameRateOption.ORIGINAL.label,
    val codecOption: String = BatchCodecOption.AUTO.label,
    val selectedPreset: String? = null,
    val metadataPrivacyMode: String = MetadataPrivacyMode.PRESERVE_ALL.label,
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
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val doneCount: Int get() = items.count { it.status == BatchItemStatus.Done || it.status == BatchItemStatus.Replaced || it.status == BatchItemStatus.SavedCopy }
    val failedCount: Int get() = items.count { it.status == BatchItemStatus.Failed }
    val skippedCount: Int get() = items.count { it.status == BatchItemStatus.Skipped || it.isAlreadyCompressed }
    val compressibleCount: Int get() = items.count { !it.isAlreadyCompressed && it.status != BatchItemStatus.Skipped }
    val activeIndex: Int get() = items.indexOfFirst { it.status == BatchItemStatus.Compressing }
    val activeItem: BatchVideoItem? get() = items.getOrNull(activeIndex)
    val hasOutputs: Boolean get() = items.any { it.outputPath != null }
    val totalOriginalBytes: Long get() = items.sumOf { it.originalSize }
    val totalOutputBytes: Long get() = items.sumOf { it.outputSize }
    val totalCurrentOutputBytes: Long get() = items.sumOf { if (it.outputSize > 0L) it.outputSize else it.currentOutputSize }
    val totalTargetOutputBytes: Long get() = items.sumOf { it.targetOutputSize }
    val totalSavedBytes: Long get() = (totalOriginalBytes - totalOutputBytes).coerceAtLeast(0L)
    val currentBatchProgress: Float get() = if (items.isEmpty()) 0f else items.sumOf { it.progress.toDouble() }.toFloat() / items.size
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
    private var activeTransformer: Transformer? = null

    // Local-only encode calibration; recommends Perceptually Lossless targets but never bypasses
    // verification. Stored in app SharedPreferences, nothing leaves the device.
    private val learningEngine by lazy {
        SmartPerceptualProfileEngine(
            SmartPerceptualProfileEngine.SharedPreferencesProfileStore(getApplication())
        )
    }

    private data class PerceptualLosslessPlan(
        val profileKey: SmartPerceptualProfileEngine.EncodeProfileKey,
        val targetRatio: Double,
        val floorRatio: Double,
        val preferRemux: Boolean,
        val remuxReason: String?
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

    fun setMetadataPrivacyMode(label: String) {
        _uiState.update { it.copy(metadataPrivacyMode = MetadataPrivacyMode.fromLabel(label).label) }
    }

    fun clear() {
        cancelCompression()
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
                            message = "Already compressed by Compressor — skipped."
                        )
                    } else {
                        item.copy(
                            targetOutputSize = estimateOutputSize(item, quality, codec, frameRate),
                            recommendation = recommendFor(item)
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }
            val skipped = items.count { it.isAlreadyCompressed }
            _uiState.update {
                it.copy(
                    items = items,
                    isLoading = false,
                    deviceProfile = DeviceCapabilityProfiles.current().name,
                    hasHardwareAv1Encoder = hasEncoder(MimeTypes.VIDEO_AV1),
                    statusMessage = when {
                        items.isEmpty() -> "No readable videos found."
                        skipped > 0 -> "Ready: ${items.size} selected. $skipped already compressed item${if (skipped == 1) "" else "s"} will be skipped."
                        else -> "Ready: ${items.size} video${if (items.size == 1) "" else "s"} selected."
                    },
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

    fun startCompression(context: Context) {
        val current = _uiState.value
        if (current.items.isEmpty() || current.isCompressing) return
        if (current.compressibleCount == 0) {
            _uiState.update { it.copy(statusMessage = "All selected videos already look compressed by Compressor, so nothing was recompressed.") }
            return
        }

        compressionJob = viewModelScope.launch(Dispatchers.Main) {
            val batchStartedAt = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isCompressing = true,
                    errorMessage = null,
                    batchMetrics = null,
                    statusMessage = "Compressing with thermal-safe batch pacing."
                )
            }

            val quality = qualityFromLabel(_uiState.value.qualityPreset)
            val frameRate = frameRateFromLabel(_uiState.value.frameRateOption)
            val codec = codecFromLabel(_uiState.value.codecOption)
            val privacyMode = MetadataPrivacyMode.fromLabel(_uiState.value.metadataPrivacyMode)
            clearBatchCache()

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
                                message = "Already compressed by Compressor — skipped."
                            )
                        } else {
                            item.copy(
                                status = BatchItemStatus.Pending,
                                progress = 0f,
                                currentOutputSize = 0L,
                                targetOutputSize = estimateOutputSize(item, quality, codec, frameRate),
                                outputUri = null,
                                outputPath = null,
                                outputSize = 0L,
                                outputMode = null,
                                verificationReport = null,
                                metrics = null,
                                message = null
                            )
                        }
                    }
                )
            }

            _uiState.value.items.forEachIndexed { index, item ->
                if (item.isAlreadyCompressed) return@forEachIndexed

                val thermalWindow = waitForThermalWindow(context, item.originalName)
                val itemStartedAt = System.currentTimeMillis()
                val plannedFps = outputFpsFor(item, frameRate, quality)
                updateItem(index) {
                    it.copy(
                        status = BatchItemStatus.Compressing,
                        progress = 0f,
                        currentOutputSize = 0L,
                        targetOutputSize = estimateOutputSize(it, quality, codec, frameRate),
                        message = if (quality == BatchQualityPreset.REMUX_ONLY) {
                            "Remuxing: video/audio copied unchanged • no re-encode • ${thermalWindow.thermalLabel}"
                        } else {
                            "Compressing: 0 MB / est ${formatFileSize(estimateOutputSize(it, quality, codec, frameRate))} • ${codec.label}${plannedFps?.let { fps -> " • ${fps}fps" } ?: " • source FPS"} • ${thermalWindow.thermalLabel}"
                        }
                    )
                }
                try {
                    val resolvedMime = if (quality == BatchQualityPreset.REMUX_ONLY) {
                        null
                    } else {
                        chooseOutputMime(codec, item, quality)
                    }
                    val codecLabel = when (resolvedMime) {
                        MimeTypes.VIDEO_H265 -> "HEVC"
                        MimeTypes.VIDEO_AV1 -> "AV1"
                        else -> "H.264"
                    }
                    var effectiveQuality = quality
                    var preEncodeRemuxNote: String? = null
                    val perceptualPlan = if (quality == BatchQualityPreset.ORIGINAL && resolvedMime != null) {
                        buildPerceptualLosslessPlan(item, resolvedMime)
                    } else {
                        null
                    }
                    if (perceptualPlan?.preferRemux == true) {
                        effectiveQuality = BatchQualityPreset.REMUX_ONLY
                        preEncodeRemuxNote = perceptualPlan.remuxReason
                    }
                    logEncoderPlan(
                        item,
                        effectiveQuality,
                        codec,
                        if (effectiveQuality == BatchQualityPreset.REMUX_ONLY) null else resolvedMime,
                        plannedFps,
                        perceptualPlan?.takeIf { !it.preferRemux }?.targetRatio
                    )
                    var remuxResult = if (effectiveQuality == BatchQualityPreset.REMUX_ONLY) {
                        remuxOnlyOne(context, item, index, privacyMode)
                    } else {
                        val safeResolvedMime = resolvedMime
                            ?: throw IllegalStateException("Encoder selection failed before export planning. Use Remux Only or choose a different codec.")
                        val encodedFile = compressOne(
                            context,
                            item,
                            index,
                            quality,
                            frameRate,
                            safeResolvedMime,
                            perceptualPlan?.targetRatio
                        )
                        withContext(Dispatchers.IO) {
                            Mp4MetadataRemuxer.remuxWithSourceMetadata(
                                context,
                                encodedFile,
                                item.metadataSnapshot.filteredForPrivacy(privacyMode)
                            )
                        }
                    }
                    var outputFile = remuxResult.outputFile
                    var outputUri = Uri.fromFile(outputFile)
                    var outputSize = outputFile.length()
                    var verification = withContext(Dispatchers.IO) {
                        OutputVerifier.verify(context, item, outputFile, effectiveQuality.label, privacyMode)
                    }
                    if (effectiveQuality == BatchQualityPreset.ORIGINAL &&
                        PerceptualLosslessVerifier.shouldFallbackToRemux(verification, item.originalSize, outputSize)
                    ) {
                        val failureReason = verification.replacementBlockReason ?: verification.verdict
                        Log.w(
                            "CompressorBatch",
                            "Perceptually lossless fallback to remux for ${item.originalName}: $failureReason"
                        )
                        // Log the full field-by-field report of the discarded attempt so device
                        // logs show exactly which check failed or which field was not exposed.
                        verification.summaryLines.forEach { line ->
                            Log.w("CompressorVerification", "discarded PL attempt; $line")
                        }
                        perceptualPlan?.let { plan ->
                            val learned = learningEngine.recordFailure(
                                plan.profileKey,
                                plan.targetRatio,
                                failureReason,
                                plan.floorRatio
                            )
                            Log.i(
                                "CompressorLearning",
                                "result=failure; profileKey=${plan.profileKey.asKey()}; usedRatio=${plan.targetRatio}; " +
                                    "reason=$failureReason; nextRatio=${learned.nextTargetRatio}; preferRemux=${learned.preferRemux}"
                            )
                        }
                        runCatching { outputFile.delete() }
                        remuxResult = remuxOnlyOne(context, item, index, privacyMode)
                        outputFile = remuxResult.outputFile
                        outputUri = Uri.fromFile(outputFile)
                        outputSize = outputFile.length()
                        effectiveQuality = BatchQualityPreset.REMUX_ONLY
                        preEncodeRemuxNote = "Remux Fallback Kept: perceptually lossless could not be verified ($failureReason)"
                        verification = withContext(Dispatchers.IO) {
                            OutputVerifier.verify(context, item, outputFile, effectiveQuality.label, privacyMode)
                        }
                    } else if (effectiveQuality == BatchQualityPreset.ORIGINAL && verification.verified) {
                        perceptualPlan?.let { plan ->
                            val sizeRatio = if (item.originalSize > 0L) {
                                outputSize.toDouble() / item.originalSize.toDouble()
                            } else {
                                1.0
                            }
                            val learned = learningEngine.recordVerifiedSuccess(
                                plan.profileKey,
                                plan.targetRatio,
                                sizeRatio,
                                plan.floorRatio
                            )
                            Log.i(
                                "CompressorLearning",
                                "result=verified; profileKey=${plan.profileKey.asKey()}; usedRatio=${plan.targetRatio}; " +
                                    "sizeRatio=$sizeRatio; nextRatio=${learned.nextTargetRatio}"
                            )
                        }
                    }
                    logVerificationResult(item, effectiveQuality, verification, outputSize)
                    val thermalEnd = ThermalBatchGovernor.snapshot(context, _uiState.value.thermalMode, _uiState.value.cooldownSeconds)
                    val metrics = BatchItemMetrics(
                        operationLabel = if (effectiveQuality == BatchQualityPreset.REMUX_ONLY) "Remux" else "Encode",
                        elapsedMs = System.currentTimeMillis() - itemStartedAt,
                        outputBytes = outputSize,
                        savedBytes = (item.originalSize - outputSize).coerceAtLeast(0L),
                        thermalStart = thermalWindow.thermalLabel,
                        thermalEnd = thermalEnd.thermalLabel,
                        batteryStart = thermalWindow.batteryPercent,
                        batteryEnd = thermalEnd.batteryPercent,
                        cooldownMs = 0L
                    )

                    updateItem(index) {
                        it.copy(
                            status = BatchItemStatus.Done,
                            progress = 1f,
                            currentOutputSize = outputSize,
                            outputUri = outputUri,
                            outputPath = outputFile.absolutePath,
                            outputSize = outputSize,
                            outputMode = effectiveQuality.label,
                            verificationReport = verification,
                            metrics = metrics,
                            message = completionMessage(
                                it,
                                effectiveQuality,
                                outputSize,
                                plannedFps,
                                codecLabel,
                                preEncodeRemuxNote?.let { note -> "${remuxResult.message} • $note" } ?: remuxResult.message,
                                verification,
                                privacyMode
                            )
                        )
                    }

                    if (_uiState.value.replaceOriginals) {
                        val replacement = replaceOriginalSafely(
                            context = context,
                            item = item,
                            outputFile = outputFile,
                            useShizukuFallback = _uiState.value.useShizukuFallback,
                            quality = effectiveQuality,
                            verification = verification,
                            backupBeforeReplace = _uiState.value.backupBeforeReplace,
                            privacyMode = privacyMode
                        )
                        updateItem(index) {
                            it.copy(
                                status = if (replacement.success) BatchItemStatus.Replaced else BatchItemStatus.SavedCopy,
                                message = replacement.message
                            )
                        }
                    }

                    if (index < _uiState.value.items.lastIndex) {
                        val cooldown = ThermalBatchGovernor.snapshot(context, _uiState.value.thermalMode, _uiState.value.cooldownSeconds)
                        _uiState.update {
                            it.copy(
                                thermalStatus = cooldown.summary,
                                statusMessage = "Cooling down ${cooldown.postItemDelayMs / 1000}s before next video."
                            )
                        }
                        updateItem(index) {
                            it.copy(metrics = it.metrics?.copy(cooldownMs = cooldown.postItemDelayMs))
                        }
                        if (cooldown.postItemDelayMs > 0L) delay(cooldown.postItemDelayMs)
                    }
                } catch (e: Exception) {
                    updateItem(index) {
                        it.copy(
                            status = BatchItemStatus.Failed,
                            progress = 0f,
                            message = e.message ?: "Compression failed"
                        )
                    }
                }
            }

            _uiState.update {
                val metrics = BatchMetricsSummary(
                    totalElapsedMs = System.currentTimeMillis() - batchStartedAt,
                    totalCooldownMs = it.items.sumOf { item -> item.metrics?.cooldownMs ?: 0L },
                    doneCount = it.doneCount,
                    failedCount = it.failedCount,
                    skippedCount = it.skippedCount,
                    totalSavedBytes = it.totalSavedBytes
                )
                it.copy(
                    isCompressing = false,
                    batchMetrics = metrics,
                    statusMessage = "Finished ${it.doneCount}/${it.items.size}. Skipped ${it.skippedCount}. Saved ${it.formattedTotalSaved} total.",
                    errorMessage = if (it.failedCount > 0) "${it.failedCount} item${if (it.failedCount == 1) "" else "s"} failed. Tap each item for details." else null
                )
            }
        }
    }

    fun cancelCompression() {
        activeTransformer?.cancel()
        compressionJob?.cancel()
        _uiState.update { it.copy(isCompressing = false, statusMessage = "Compression canceled.") }
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
            val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            list.codecInfos.any { info ->
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
        outputMime: String
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
        val nearOptimal = BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
            source = source,
            outputMimeType = outputMime,
            sourceSizeBytes = item.originalSize,
            learnedTargetRatio = targetRatio
        )
        val remuxReason = when {
            profilePrefersRemux ->
                "This device profile repeatedly failed perceptually lossless verification, so the exact stream copy was kept."
            nearOptimal ->
                "Source is already near optimal; kept exact stream copy."
            else -> null
        }
        Log.i(
            "CompressorLearning",
            "plan; profileKey=${profileKey.asKey()}; defaultRatio=$defaultRatio; learnedRatio=$targetRatio; " +
                "floorRatio=$floorRatio; preferRemux=${remuxReason != null}; reason=${remuxReason ?: "none"}"
        )
        return PerceptualLosslessPlan(
            profileKey = profileKey,
            targetRatio = targetRatio,
            floorRatio = floorRatio,
            preferRemux = remuxReason != null,
            remuxReason = remuxReason
        )
    }

    private fun logEncoderPlan(
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        requestedCodec: BatchCodecOption,
        resolvedMime: String?,
        plannedFps: Int?,
        learnedTargetRatio: Double? = null
    ) {
        val source = item.toSourceInfo()
        Log.i(
            "CompressorEncoderPlan",
            "mode=${quality.label}; requestedCodec=${requestedCodec.label}; resolvedOutputMime=${resolvedMime ?: "stream-copy"}; " +
                "source=${item.originalWidth}x${item.originalHeight}@${item.originalFps}fps; bitrate=${item.originalBitrate}; audioBitrate=${item.originalAudioBitrate}; " +
                "sourceCodec=${item.sourceVideoMime ?: "unknown"}; hdr=${if (source.isHdr) "yes" else "no/unknown"}; colorTransfer=${item.sourceColorTransfer ?: "unknown"}; " +
                "target=${targetHeightFor(item, quality)}p@${plannedFps ?: item.originalFps.toInt()}fps; targetVideoBitrate=${resolvedMime?.let { calculateVideoBitrate(item, quality, it, learnedTargetRatio) } ?: item.originalBitrate}; " +
                "learnedTargetRatio=${learnedTargetRatio ?: "default"}; " +
                "targetAudioBitrate=${calculateAudioBitrate(item, quality)}; privacy=${_uiState.value.metadataPrivacyMode}"
        )
    }

    private fun logVerificationResult(
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        verification: OutputVerificationReport,
        outputSize: Long
    ) {
        Log.i(
            "CompressorVerification",
            "mode=${quality.label}; file=${item.originalName}; verification=${verification.verdict}; playable=${verification.playability}; " +
                "replaceAllowed=${verification.replacementSafe}; blockReason=${verification.replacementBlockReason ?: "none"}; outputSize=${outputSize}"
        )
    }

    private suspend fun compressOne(
        context: Context,
        item: BatchVideoItem,
        index: Int,
        quality: BatchQualityPreset,
        frameRate: BatchFrameRateOption,
        videoMimeType: String,
        learnedTargetRatio: Double? = null
    ): File = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val outputDir = File(context.cacheDir, "batch_compressed_videos").apply { mkdirs() }
            val outputFile = File(outputDir, item.outputName(quality))
            if (outputFile.exists()) outputFile.delete()

            val targetBitrate = calculateVideoBitrate(item, quality, videoMimeType, learnedTargetRatio)
            val audioBitrate = calculateAudioBitrate(item, quality)
            val estimatedOutputSize = estimateOutputSize(item, quality, codecFromMime(videoMimeType), frameRate)
            // Mode-aware: always null for Remux Only and Perceptually Lossless, so no
            // FrameDropEffect can ever be attached in those modes.
            val plannedFps = outputFpsFor(item, frameRate, quality)

            val decoderFactory = DefaultDecoderFactory.Builder(context)
                .setEnableDecoderFallback(true)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(targetBitrate)
                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        .build()
                )
                .setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder()
                        .setBitrate(audioBitrate)
                        .build()
                )
                .build()

            var progressJob: Job? = null
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(videoMimeType)
                .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoderFactory, androidx.media3.common.util.Clock.DEFAULT))
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressJob?.cancel()
                        val finalSize = outputFile.length()
                        updateItem(index) {
                            it.copy(
                                progress = 1f,
                                currentOutputSize = finalSize,
                                targetOutputSize = estimatedOutputSize,
                                message = "Compressing: ${formatFileSize(finalSize)} / est ${formatFileSize(estimatedOutputSize)} • 100%"
                            )
                        }
                        if (continuation.isActive) continuation.resume(outputFile)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        progressJob?.cancel()
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()

            activeTransformer = transformer
            continuation.invokeOnCancellation {
                progressJob?.cancel()
                transformer.cancel()
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

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(item.sourceUri))
                .setEffects(Effects(emptyList(), effectsList))
                .build()

            val composition = Composition.Builder(listOf(EditedMediaItemSequence(editedMediaItem)))
                .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
                .build()

            transformer.start(composition, outputFile.absolutePath)

            progressJob = viewModelScope.launch(Dispatchers.Main) {
                while (continuation.isActive) {
                    val progressHolder = ProgressHolder()
                    val progressState = transformer.getProgress(progressHolder)
                    val currentSize = if (outputFile.exists()) outputFile.length() else 0L
                    val progress = if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        (progressHolder.progress / 100f).coerceIn(0f, 0.99f)
                    } else {
                        0f
                    }
                    updateItem(index) {
                        it.copy(
                            progress = progress,
                            currentOutputSize = currentSize,
                            targetOutputSize = estimatedOutputSize,
                            message = "Compressing: ${formatFileSize(currentSize)} / est ${formatFileSize(estimatedOutputSize)} • ${(progress * 100f).toInt()}%${plannedFps?.let { fps -> " • ${fps}fps" } ?: ""}"
                        )
                    }
                    delay(200)
                }
            }
        }
    }

    private suspend fun remuxOnlyOne(
        context: Context,
        item: BatchVideoItem,
        index: Int,
        privacyMode: MetadataPrivacyMode
    ): Mp4MetadataRemuxResult = withContext(Dispatchers.IO) {
        val outputDir = File(context.cacheDir, "batch_compressed_videos").apply { mkdirs() }
        val outputFile = File(outputDir, item.outputName(BatchQualityPreset.REMUX_ONLY))
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
            snapshot = item.metadataSnapshot.filteredForPrivacy(privacyMode)
        ) { copiedBytes, outputBytes ->
            val progress = if (item.originalSize > 0L) {
                (copiedBytes.toFloat() / item.originalSize.toFloat()).coerceIn(0f, 0.99f)
            } else {
                0f
            }
            updateItem(index) {
                it.copy(
                    progress = progress,
                    currentOutputSize = outputBytes,
                    targetOutputSize = estimatedOutputSize,
                    message = "Remuxing: ${formatFileSize(outputBytes)} written • ${(progress * 100f).toInt()}% • no re-encode"
                )
            }
        }
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
        learnedTargetRatio: Double? = null
    ): Int {
        return BatchQualityBitratePolicy.calculateVideoBitrate(
            source = item.toSourceInfo(),
            mode = quality.toMode(),
            outputMimeType = videoMimeType,
            outputFps = outputFpsFor(item, frameRateFromLabel(_uiState.value.frameRateOption), quality),
            outputHeight = targetHeightFor(item, quality),
            learnedTargetRatio = learnedTargetRatio
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
        return "$modeSummary • $sizeSummary • ${verification.verdict} • ${privacyMode.summary} • $muxerMessage"
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

        val directResult = runCatching {
            context.contentResolver.openOutputStream(item.sourceUri, "rwt")?.use { out ->
                outputFile.inputStream().use { input -> input.copyTo(out) }
            } ?: error("Could not open original for writing")
            val verifiedSize = context.contentResolver.openFileDescriptor(item.sourceUri, "r")?.use { it.statSize } ?: -1L
            verifiedSize <= 0L || verifiedSize == outputFile.length()
        }

        if (directResult.getOrDefault(false)) {
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

        if (useShizukuFallback) {
            if (!ShizukuSupport.hasPermission()) {
                val savedUri = saveFileToGallery(context, outputFile, item.outputName(quality), item.metadataSnapshot, privacyMode)
                return@withContext ReplacementResult(false, if (savedUri != null) "Android write failed and Shizuku is not authorized, so a safe copy was saved." else "Android write failed and Shizuku is not authorized; saving copy also failed.")
            }
            val targetPath = resolveFilesystemPath(context, item.sourceUri)
            if (targetPath == null) {
                val savedUri = saveFileToGallery(context, outputFile, item.outputName(quality), item.metadataSnapshot, privacyMode)
                return@withContext ReplacementResult(false, if (savedUri != null) "Shizuku is authorized, but Android did not expose a filesystem path for this video; safe copy saved." else "Shizuku is authorized, but no filesystem path was available and saving copy failed.")
            }
            val copied = ShizukuSupport.copyFileWithShizuku(outputFile.absolutePath, targetPath)
            if (copied) {
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

        val savedUri = saveFileToGallery(context, outputFile, item.outputName(quality), item.metadataSnapshot, privacyMode)
        ReplacementResult(
            false,
            if (savedUri != null) {
                "Original was protected by Android storage, so a safe compressed copy was saved instead. Use the in-app file picker for writable replacement."
            } else {
                "Original was protected by Android storage and saving a fallback copy failed."
            }
        )
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
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            uri
        } catch (_: Exception) {
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
        val base = name.substringBeforeLast('.').lowercase()
        return base.endsWith("_compressed") ||
            base.endsWith("-compressed") ||
            base.endsWith("_remuxed") ||
            base.endsWith("-remuxed") ||
            base.contains("_compressed_") ||
            base.contains("_remuxed_") ||
            base.startsWith("compressed_") ||
            base.startsWith("remuxed_")
    }

    private fun BatchVideoItem.outputName(quality: BatchQualityPreset): String {
        val base = originalName.substringBeforeLast(".").ifBlank { "Video_${System.currentTimeMillis()}" }
        val suffix = if (quality == BatchQualityPreset.REMUX_ONLY) "Remuxed" else "Compressed"
        return "${base}_$suffix.mp4"
    }

    private fun clearBatchCache() {
        runCatching {
            val dir = File(getApplication<Application>().cacheDir, "batch_compressed_videos")
            if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
        }
    }
}
