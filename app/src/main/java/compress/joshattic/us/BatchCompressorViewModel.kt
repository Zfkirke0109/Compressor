package compress.joshattic.us

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    HIGH("High", 0.70f),
    MEDIUM("Medium", 0.40f),
    LOW("Low", 0.22f)
}

enum class BatchItemStatus {
    Pending,
    Compressing,
    Done,
    Failed,
    Replaced,
    SavedCopy
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
    val status: BatchItemStatus = BatchItemStatus.Pending,
    val progress: Float = 0f,
    val outputUri: Uri? = null,
    val outputPath: String? = null,
    val outputSize: Long = 0L,
    val message: String? = null
) {
    val displaySize: String get() = formatFileSize(originalSize)
    val outputDisplaySize: String get() = formatFileSize(outputSize)
}

data class BatchCompressorUiState(
    val items: List<BatchVideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val isCompressing: Boolean = false,
    val qualityPreset: String = BatchQualityPreset.MEDIUM.label,
    val preferHevc: Boolean = true,
    val replaceOriginals: Boolean = false,
    val useShizukuFallback: Boolean = false,
    val shizukuStatus: String = "Unavailable",
    val deviceProfile: String = DeviceCapabilityProfiles.current().name,
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val doneCount: Int get() = items.count { it.status == BatchItemStatus.Done || it.status == BatchItemStatus.Replaced || it.status == BatchItemStatus.SavedCopy }
    val failedCount: Int get() = items.count { it.status == BatchItemStatus.Failed }
    val hasOutputs: Boolean get() = items.any { it.outputPath != null }
    val totalOriginalBytes: Long get() = items.sumOf { it.originalSize }
    val totalOutputBytes: Long get() = items.sumOf { it.outputSize }
    val totalSavedBytes: Long get() = (totalOriginalBytes - totalOutputBytes).coerceAtLeast(0L)
    val formattedTotalOriginal: String get() = formatFileSize(totalOriginalBytes)
    val formattedTotalOutput: String get() = formatFileSize(totalOutputBytes)
    val formattedTotalSaved: String get() = formatFileSize(totalSavedBytes)
}

@OptIn(UnstableApi::class)
class BatchCompressorViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(BatchCompressorUiState())
    val uiState = _uiState.asStateFlow()

    private var compressionJob: Job? = null
    private var activeTransformer: Transformer? = null

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
        _uiState.update { it.copy(qualityPreset = label) }
    }

    fun togglePreferHevc() {
        _uiState.update { it.copy(preferHevc = !it.preferHevc) }
    }

    fun toggleReplaceOriginals() {
        _uiState.update { it.copy(replaceOriginals = !it.replaceOriginals) }
    }

    fun toggleShizukuFallback() {
        _uiState.update { it.copy(useShizukuFallback = !it.useShizukuFallback) }
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
                isCompressing = false
            )
        }
    }

    fun loadUris(context: Context, uris: List<Uri>) {
        val distinct = uris.distinct()
        if (distinct.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = "Reading ${distinct.size} video${if (distinct.size == 1) "" else "s"}…") }
            val items = distinct.mapNotNull { uri ->
                try {
                    readMetadata(context, uri)
                } catch (e: Exception) {
                    null
                }
            }
            val profile = DeviceCapabilityProfiles.current()
            _uiState.update {
                it.copy(
                    items = items,
                    isLoading = false,
                    preferHevc = profile.preferHevcForDefaultCompression,
                    deviceProfile = profile.name,
                    statusMessage = if (items.isEmpty()) "No readable videos found." else "Ready: ${items.size} video${if (items.size == 1) "" else "s"} selected.",
                    errorMessage = null
                )
            }
            refreshShizukuStatus(context)
        }
    }

    fun startCompression(context: Context) {
        val current = _uiState.value
        if (current.items.isEmpty() || current.isCompressing) return

        compressionJob = viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    isCompressing = true,
                    errorMessage = null,
                    statusMessage = "Compressing batch sequentially to avoid codec failures and heat throttling."
                )
            }

            val quality = BatchQualityPreset.entries.firstOrNull { it.label == _uiState.value.qualityPreset } ?: BatchQualityPreset.MEDIUM
            val preferredMime = chooseOutputMime(_uiState.value.preferHevc)
            clearBatchCache()

            _uiState.value.items.forEachIndexed { index, item ->
                updateItem(index) { it.copy(status = BatchItemStatus.Compressing, progress = 0f, message = "Compressing…") }
                try {
                    val outputFile = compressOne(context, item, quality, preferredMime)
                    val outputUri = Uri.fromFile(outputFile)
                    val outputSize = outputFile.length()

                    updateItem(index) {
                        it.copy(
                            status = BatchItemStatus.Done,
                            progress = 1f,
                            outputUri = outputUri,
                            outputPath = outputFile.absolutePath,
                            outputSize = outputSize,
                            message = "Compressed: ${formatFileSize(it.originalSize)} → ${formatFileSize(outputSize)}"
                        )
                    }

                    if (_uiState.value.replaceOriginals) {
                        val replacement = replaceOriginalSafely(context, item, outputFile, _uiState.value.useShizukuFallback)
                        updateItem(index) {
                            it.copy(
                                status = if (replacement.success) BatchItemStatus.Replaced else BatchItemStatus.SavedCopy,
                                message = replacement.message
                            )
                        }
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
                it.copy(
                    isCompressing = false,
                    statusMessage = "Finished ${it.doneCount}/${it.items.size}. Saved ${it.formattedTotalSaved} total.",
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
            var saved = 0
            outputs.forEach { (item, file) ->
                if (file.exists()) {
                    val savedUri = saveFileToGallery(context, file, item.compressedName())
                    if (savedUri != null) saved++
                }
            }
            _uiState.update { it.copy(statusMessage = "Saved $saved compressed cop${if (saved == 1) "y" else "ies"} to Movies/Compressor.") }
        }
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
        var audioBitrate = 0

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

            audioBitrate = getAudioBitrate(context, uri)
            retriever.setDataSource(context, uri)
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
            fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: getVideoFrameRate(context, uri)
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
            originalAudioBitrate = audioBitrate,
            originalFps = fps,
            durationMs = duration
        )
    }

    private fun getAudioBitrate(context: Context, uri: Uri): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    return format.getInteger(MediaFormat.KEY_BIT_RATE)
                }
            }
            0
        } catch (_: Exception) {
            0
        } finally {
            extractor.release()
        }
    }

    private fun getVideoFrameRate(context: Context, uri: Uri): Float {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    return try {
                        format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                    } catch (_: Exception) {
                        format.getFloat(MediaFormat.KEY_FRAME_RATE)
                    }
                }
            }
            0f
        } catch (_: Exception) {
            0f
        } finally {
            extractor.release()
        }
    }

    private fun chooseOutputMime(preferHevc: Boolean): String {
        val supported = mutableListOf(MimeTypes.VIDEO_H264)
        if (hasEncoder(MimeTypes.VIDEO_H265)) supported.add(MimeTypes.VIDEO_H265)
        val profileChoice = DeviceCapabilityProfiles.current().chooseDefaultVideoCodec(supported)
        return when {
            preferHevc && supported.contains(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H265
            supported.contains(profileChoice) -> profileChoice
            else -> MimeTypes.VIDEO_H264
        }
    }

    private fun hasEncoder(mimeType: String): Boolean {
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder &&
                    (!Build.VERSION.SDK_INT.let { it >= Build.VERSION_CODES.Q } || !info.isSoftwareOnly) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun compressOne(
        context: Context,
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        videoMimeType: String
    ): File = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val outputDir = File(context.cacheDir, "batch_compressed_videos").apply { mkdirs() }
            val outputFile = File(outputDir, item.compressedName())
            if (outputFile.exists()) outputFile.delete()

            val targetBitrate = calculateVideoBitrate(item, quality)
            val audioBitrate = if (item.originalAudioBitrate > 0) item.originalAudioBitrate.coerceAtMost(192_000) else 128_000

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

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(videoMimeType)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoderFactory, androidx.media3.common.util.Clock.DEFAULT))
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(outputFile)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()

            activeTransformer = transformer
            continuation.invokeOnCancellation { transformer.cancel() }

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

            if (quality != BatchQualityPreset.HIGH && item.originalFps > 30f) {
                effectsList.add(FrameDropEffect.createSimpleFrameDropEffect(item.originalFps, 30f))
            }

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(item.sourceUri))
                .setEffects(Effects(listOf(SonicAudioProcessor()), effectsList))
                .build()

            val composition = Composition.Builder(listOf(EditedMediaItemSequence(editedMediaItem)))
                .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
                .build()

            transformer.start(composition, outputFile.absolutePath)
        }
    }

    private fun calculateVideoBitrate(item: BatchVideoItem, quality: BatchQualityPreset): Int {
        val durationSec = (item.durationMs / 1000.0).coerceAtLeast(1.0)
        val targetBits = item.originalSize * 8.0 * quality.targetRatio
        val audioBits = durationSec * 128_000.0
        val overheadBits = targetBits * 0.03
        val videoBits = (targetBits - audioBits - overheadBits).coerceAtLeast(targetBits * 0.20)
        val calculated = (videoBits / durationSec).toInt()
        val floor = when {
            item.originalHeight >= 2160 -> 3_500_000
            item.originalHeight >= 1440 -> 2_500_000
            item.originalHeight >= 1080 -> 1_500_000
            item.originalHeight >= 720 -> 900_000
            else -> 450_000
        }
        val ceiling = if (item.originalBitrate > 0) item.originalBitrate else 60_000_000
        return calculated.coerceIn(floor, ceiling)
    }

    private fun targetHeightFor(item: BatchVideoItem, quality: BatchQualityPreset): Int {
        return when (quality) {
            BatchQualityPreset.HIGH -> item.originalHeight
            BatchQualityPreset.MEDIUM -> minOf(item.originalHeight, 1080)
            BatchQualityPreset.LOW -> minOf(item.originalHeight, 720)
        }.coerceAtLeast(2)
    }

    private data class ReplacementResult(val success: Boolean, val message: String)

    private suspend fun replaceOriginalSafely(
        context: Context,
        item: BatchVideoItem,
        compressedFile: File,
        useShizukuFallback: Boolean
    ): ReplacementResult = withContext(Dispatchers.IO) {
        if (!compressedFile.exists() || compressedFile.length() <= 0) {
            return@withContext ReplacementResult(false, "Replacement skipped: compressed file was missing or empty.")
        }

        val directResult = runCatching {
            context.contentResolver.openOutputStream(item.sourceUri, "rwt")?.use { out ->
                compressedFile.inputStream().use { input -> input.copyTo(out) }
            } ?: error("Could not open original for writing")
            val verifiedSize = context.contentResolver.openFileDescriptor(item.sourceUri, "r")?.use { it.statSize } ?: -1L
            verifiedSize <= 0L || verifiedSize == compressedFile.length()
        }

        if (directResult.getOrDefault(false)) {
            return@withContext ReplacementResult(true, "Original replaced after verifying the compressed file.")
        }

        if (useShizukuFallback && ShizukuSupport.hasPermission()) {
            val targetPath = resolveFilesystemPath(context, item.sourceUri)
            val externalBridge = File(context.externalCacheDir ?: context.cacheDir, compressedFile.name)
            runCatching { compressedFile.copyTo(externalBridge, overwrite = true) }
            if (targetPath != null && externalBridge.exists()) {
                val copied = ShizukuSupport.copyFileWithShizuku(externalBridge.absolutePath, targetPath)
                if (copied) {
                    return@withContext ReplacementResult(true, "Original replaced with Shizuku fallback.")
                }
            }
        }

        val savedUri = saveFileToGallery(context, compressedFile, item.compressedName())
        ReplacementResult(
            false,
            if (savedUri != null) {
                "Original was protected by Android storage, so a safe compressed copy was saved instead."
            } else {
                "Original was protected by Android storage and saving a fallback copy failed."
            }
        )
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

    private fun saveFileToGallery(context: Context, file: File, targetName: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, targetName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
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

    private fun BatchVideoItem.compressedName(): String {
        val base = originalName.substringBeforeLast(".").ifBlank { "Compressed_${System.currentTimeMillis()}" }
        return "${base}_Compressed.mp4"
    }

    private fun clearBatchCache() {
        runCatching {
            val dir = File(getApplication<Application>().cacheDir, "batch_compressed_videos")
            if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
        }
    }
}
