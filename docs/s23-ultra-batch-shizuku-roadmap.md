# S23 Ultra, batch compression, Shizuku, and replace-original roadmap

This fork currently uses a native Kotlin + Jetpack Compose UI and AndroidX Media3 Transformer. It is intentionally lightweight and avoids FFmpeg, storage permissions, and invasive APIs.

## Current code shape

- The app is single-video oriented:
  - `CompressorUiState.selectedUri: Uri?`
  - `CompressorUiState.compressedUri: Uri?`
  - `MainActivity` registers `ACTION_SEND`, not only a batch-aware state flow.
- Compression is already hardware-first through `MediaCodecList` and Media3 Transformer.
- Save behavior currently creates a new MediaStore entry or writes to a user-picked URI. It does not replace the original asset.

## Implemented scaffold in this branch

- Adds `DeviceCapabilityProfile.kt` with a Samsung Galaxy S23 Ultra profile:
  - detects `SM-S918*` / `dm3q` devices;
  - prefers HEVC/H.265 defaults when supported;
  - keeps batch compression sequential by default to reduce heat/throttling;
  - avoids AV1 as a default encode target until tested on-device.
- Adds `ShizukuSupport.kt` as an optional guardrail helper:
  - app remains fully usable without Shizuku;
  - checks binder availability and permission;
  - exposes the backend identity label: root/Sui, ADB shell, or unavailable.
- Adds Shizuku API/provider dependencies and manifest provider registration.
- Adds the `SEND_MULTIPLE` intent filter so Android can route multi-video shares to the app once state plumbing is complete.
- Adds Android CI so PRs run `./gradlew :app:assembleDebug --stacktrace`.

## Next implementation steps

### 1. Batch state model

Add a batch item model, for example:

```kotlin
data class VideoQueueItem(
    val sourceUri: Uri,
    val originalName: String?,
    val originalSize: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalBitrate: Int,
    val originalAudioBitrate: Int,
    val originalFps: Float,
    val originalVideoMime: String?,
    val durationMs: Long,
    val status: QueueStatus = QueueStatus.Pending,
    val compressedUri: Uri? = null,
    val compressedSize: Long = 0L,
    val error: String? = null
)

enum class QueueStatus { Pending, Compressing, Done, Failed, Skipped }
```

Then replace or augment:

```kotlin
selectedUri: Uri?
compressedUri: Uri?
```

with:

```kotlin
selectedUris: List<Uri>
queue: List<VideoQueueItem>
activeQueueIndex: Int
```

Keep backwards-compatible convenience values for the current/first item so the existing UI can be migrated incrementally.

### 2. Multi-picker and multi-share

In `MainActivity`:

- use `ActivityResultContracts.PickMultipleVisualMedia()` for selecting multiple videos;
- keep the existing single picker for older flows if desired;
- handle both `Intent.ACTION_SEND` and `Intent.ACTION_SEND_MULTIPLE`.

Implementation sketch:

```kotlin
val pickMultipleMedia = rememberLauncherForActivityResult(
    ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
) { uris ->
    if (uris.isNotEmpty()) viewModel.updateSelectedUris(context, uris)
}
```

For share intents:

```kotlin
Intent.ACTION_SEND_MULTIPLE -> {
    val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    if (uris.isNotEmpty()) viewModel.updateSelectedUris(this, uris)
}
```

### 3. Sequential batch compression

Do not run several Media3 Transformers in parallel by default. On the S23 Ultra, one hardware encoder job at a time is the safer default for heat, battery, and failed codec init errors.

Add:

```kotlin
fun startBatchCompression(context: Context) = viewModelScope.launch(Dispatchers.Main) {
    val profile = DeviceCapabilityProfiles.current()
    val parallelism = profile.recommendedBatchParallelism // currently 1
    // Process queue sequentially first; add bounded parallelism only after device testing.
}
```

Refactor the current `startCompression(context)` into an internal function like:

```kotlin
private suspend fun compressOne(
    context: Context,
    item: VideoQueueItem,
    settings: CompressionSettings
): CompressionResult
```

That makes batch mode reuse the same tested Media3 pipeline instead of duplicating compression logic.

### 4. S23 Ultra tuning

Use `DeviceCapabilityProfiles.current()` after `checkSupportedCodecs()`:

```kotlin
val profile = DeviceCapabilityProfiles.current()
val defaultCodec = profile.chooseDefaultVideoCodec(supportedCodecs)
```

Recommended S23 Ultra defaults:

- HEVC/H.265 default when supported;
- keep AV1 opt-in only;
- keep 4K/60 source FPS unless the selected preset requires downscaling;
- sequential queue processing;
- optionally show a device profile line in the info dialog.

### 5. Replace-original option

Add a disabled-by-default setting:

```kotlin
val replaceOriginalAfterCompression: Boolean = false
```

Use this exact safety flow:

1. Compress to app cache.
2. Confirm compressed file exists and is smaller unless the user chose “save anyway.”
3. Persist a normal backup/new-copy save first when possible.
4. Replace original only when the app can write to the original URI.
5. If direct write fails, fall back to Storage Access Framework or Shizuku.
6. Never delete the original until the replacement copy verifies byte length and can be read back.

For normal Android storage, try:

```kotlin
context.contentResolver.openOutputStream(originalUri, "wt")
```

If that fails and Shizuku is available/authorized, route through a future privileged file operation. Keep Shizuku optional because many users will not have it running.

### 6. Shizuku role

Shizuku should be used only as a fallback for advanced users who explicitly enable it. Good uses:

- replacing a file path that MediaStore/SAF cannot write to;
- preserving timestamps/ownership where Android APIs block it;
- reading file metadata that normal scoped storage hides.

Bad uses:

- bypassing user consent;
- defaulting to privileged writes;
- deleting originals before verifying the compressed replacement.

## Suggested PR split

1. CI + Shizuku/provider scaffold + S23 Ultra profile.  
2. Multi-video state and picker/share intent handling.  
3. Sequential batch compression queue.  
4. Replace-original setting with normal Android writer.  
5. Optional Shizuku replacement fallback.  
6. On-device S23 Ultra benchmarks and tuning.
