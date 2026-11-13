package compress.joshattic.us

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import compress.joshattic.us.ui.theme.CompressorTheme
import java.io.File
import java.util.ArrayList

class BatchMainActivity : ComponentActivity() {
    private val viewModel by viewModels<BatchCompressorViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startupIntent = intent
        setContent {
            CompressorTheme {
                BatchCompressorScreen(
                    viewModel = viewModel,
                    onShareOutputs = { shareCompressedOutputs(this, viewModel.uiState.value) }
                )
            }
        }
        window.decorView.post { consumeIncomingVideos(startupIntent) }
        // Diagnostics-only: logs real encoder capabilities once per process in debug builds.
        EncoderCapabilityDiagnostics.dumpOnceInBackground(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingVideos(intent)
    }

    private fun consumeIncomingVideos(intent: Intent?) {
        val uris = extractIncomingVideoUris(intent)
        if (uris.isNotEmpty()) viewModel.loadUris(this, uris)
    }

    @Suppress("DEPRECATION")
    private fun extractIncomingVideoUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
            }
            else -> emptyList()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchCompressorScreen(
    viewModel: BatchCompressorViewModel,
    onShareOutputs: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pickAfterMetadataGrant by remember { mutableStateOf(false) }
    val pickVideos = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                persistVideoPermissions(context, uris)
                viewModel.loadUris(context, uris)
            }
        }
    )
    val metadataAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted && state.items.isNotEmpty()) {
                viewModel.loadUris(context, state.items.map { it.sourceUri })
            }
            if (granted && pickAfterMetadataGrant) {
                pickVideos.launch(arrayOf("video/*"))
            }
            pickAfterMetadataGrant = false
        }
    )
    val requestOriginalMediaAccess = {
        if (needsOriginalMediaLocationPermission(context)) {
            metadataAccessLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else if (state.items.isNotEmpty()) {
            viewModel.loadUris(context, state.items.map { it.sourceUri })
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        viewModel.refreshShizukuStatus(context)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Compressor Batch", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.deviceProfile, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Default is Perceptually Lossless: keep 4K as 4K, keep source FPS/audio, and fall back to Remux Only if safety cannot be verified.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                if (needsOriginalMediaLocationPermission(context)) {
                                    pickAfterMetadataGrant = true
                                    metadataAccessLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                                } else {
                                    pickVideos.launch(arrayOf("video/*"))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isCompressing && !state.isLoading
                        ) {
                            Text(if (state.items.isEmpty()) "Select videos with write access" else "Select different videos")
                        }
                    }
                }
            }

            item { BatchSettingsCard(state, viewModel, context, requestOriginalMediaAccess) }
            item {
                DiagnosticsExportCard(
                    context,
                    state.isCompressing,
                    state.items.any { !it.isAlreadyCompressed },
                    state.isSelfChecking,
                    viewModel::runScorerSelfCheck,
                    viewModel::resetLearnedProfiles
                )
            }
            if (state.items.isNotEmpty()) {
                item { BatchSummaryCard(state) }
                item { PreservationReportCard(state) }
                state.batchMetrics?.let { metrics ->
                    item { BatchMetricsCard(metrics) }
                }
                if (!state.isCompressing && state.highQualityRetryCount > 0) {
                    item {
                        HighQualityRetryCard(
                            count = state.highQualityRetryCount,
                            onRetry = { viewModel.retryUnshrunkAsHighQuality(context) }
                        )
                    }
                }
            }

            state.statusMessage?.let { message ->
                item { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
            state.errorMessage?.let { message ->
                item { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }

            if (state.items.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.startCompression(context) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isCompressing && !state.isLoading
                        ) { Text("Compress ${state.items.size}") }
                        OutlinedButton(
                            onClick = { viewModel.cancelCompression() },
                            modifier = Modifier.weight(1f),
                            enabled = state.isCompressing
                        ) { Text("Cancel") }
                    }
                }

                if (state.hasOutputs) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.saveAllCopiesToGallery(context) },
                                modifier = Modifier.weight(1f),
                                enabled = !state.isCompressing
                            ) { Text("Save copies") }
                            OutlinedButton(
                                onClick = onShareOutputs,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isCompressing
                            ) { Text("Share") }
                        }
                    }
                }

                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = { viewModel.clear() },
                            enabled = !state.isCompressing
                        ) { Text("Clear batch") }
                    }
                }

                itemsIndexed(
                    items = state.items,
                    key = { _, item -> item.sourceUri.toString() }
                ) { index, item ->
                    BatchItemCard(
                        index = index + 1,
                        item = item,
                        batchRunning = state.isCompressing,
                        replaceOriginals = state.replaceOriginals,
                        settings = BatchSettingsView(state.qualityPreset, state.codecOption, state.frameRateOption),
                        queuedCount = state.items.count { it.status == BatchItemStatus.Pending && !it.isAlreadyCompressed },
                        onApplyRecommendation = { viewModel.applyRecommendation(item.sourceUri) }
                    )
                }
            } else if (!state.isLoading) {
                item {
                    Text(
                        "For true replace-original mode, pick videos inside Compressor with the file picker. Videos shared from Gallery may be read-only and may need a safe copy fallback.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Post-batch offer shown after a Perceptually Lossless run that honestly kept some videos at
 * original size. High Quality is the honest lossy lever that can actually shrink those; tapping
 * re-runs only the offered videos in High Quality (see [BatchCompressorViewModel.retryUnshrunkAsHighQuality]).
 */
@Composable
private fun HighQualityRetryCard(count: Int, onRetry: () -> Unit) {
    val many = count != 1
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Want the rest smaller?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                "$count ${if (many) "videos were" else "video was"} kept at original quality — " +
                    "Perceptually Lossless couldn't shrink ${if (many) "them" else "it"} without visible change. " +
                    "High Quality can re-encode ${if (many) "them" else "it"} smaller at the same resolution and " +
                    "frame rate, for a small quality trade.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Shrink $count with High Quality")
            }
        }
    }
}

/**
 * Diagnostics export.
 *
 * Getting captures off this device has been the slowest part of every debugging round: the
 * recorder writes to the app's private files directory, which adb cannot read across Android users
 * (from Secure Folder there is no adb route at all), and the logcat workaround is bounded by a
 * 5 MiB buffer this device will not raise — a 219-job capture arrived with its `session_start`
 * record already lost. Both buttons here need no adb, no root and no PC.
 *
 * The Termux row is offered only as a live-capture aid, and it deliberately never claims more than
 * it can verify: Termux additionally requires `allow-external-apps=true`, which no API exposes, so
 * a dispatched command is reported as *sent* and the copy-to-clipboard route stays available in
 * every state.
 */
@Composable
private fun DiagnosticsExportCard(
    context: Context,
    isCompressing: Boolean,
    hasSelection: Boolean,
    isSelfChecking: Boolean,
    onRunScorerSelfCheck: (Context) -> Unit,
    onResetLearnedProfiles: ((Result<String>) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val termuxAvailability = remember { DiagnosticsExporter.termuxAvailability(context) }

    fun readLearnedState() = runCatching {
        SmartPerceptualProfileEngine(
            SmartPerceptualProfileEngine.SharedPreferencesProfileStore(context.applicationContext)
        ).learnedStateIdentity()
    }.getOrNull()

    var learnedState by remember { mutableStateOf<String?>(null) }

    // Re-read learned profiles on first show and whenever a batch finishes, since the engine
    // mutates them during a run. Done in an effect on the IO dispatcher: this used to read
    // SharedPreferences and write state during composition itself, on the main thread.
    LaunchedEffect(isCompressing) {
        if (!isCompressing) learnedState = withContext(Dispatchers.IO) { readLearnedState() }
    }

    // The reset is irreversible, so the button asks first (the AlertDialog below).
    var confirmReset by remember { mutableStateOf(false) }

    fun resetLearnedProfiles() {
        busy = true
        onResetLearnedProfiles { result ->
            busy = false
            result.onSuccess {
                learnedState = it
                isError = false
                message = "Learned profiles and measurement history reset. The next batch starts from the default targets."
            }.onFailure {
                isError = true
                message = "Could not reset learned profiles: ${it.message ?: "unknown error"}"
            }
        }
    }

    fun report(result: DiagnosticsExporter.ExportResult) {
        when (result) {
            is DiagnosticsExporter.ExportResult.Written -> {
                isError = false
                message = "Saved ${result.displayName} " +
                    "(${DiagnosticsExportPlan.describeSize(result.bytes)}) to " +
                    "Downloads/${DiagnosticsExportPlan.EXPORT_SUBDIRECTORY}."
            }
            is DiagnosticsExporter.ExportResult.Empty -> {
                isError = false
                message = result.reason
            }
            is DiagnosticsExporter.ExportResult.Failed -> {
                isError = true
                message = result.reason
            }
        }
    }

    // Export reads files and spawns logcat, so it never runs on the UI thread.
    fun runExport(block: () -> DiagnosticsExporter.ExportResult) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            report(result)
            busy = false
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Save this app's own batch records and log to Downloads. No adb, root or PC needed, " +
                    "and it works inside Secure Folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            var scope by remember { mutableStateOf(DiagnosticsArchivePlan.Scope.CURRENT_RUN) }
            Text("What to include", style = MaterialTheme.typography.labelLarge)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticsArchivePlan.Scope.entries.forEach { option ->
                    FilterChip(
                        selected = scope == option,
                        onClick = { scope = option },
                        label = { Text(option.label) },
                        enabled = !busy
                    )
                }
            }
            Button(
                onClick = { runExport { DiagnosticsExporter.exportArchive(context, scope) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) { Text("Save diagnostics ZIP to Downloads") }
            OutlinedButton(
                onClick = { runExport { DiagnosticsExporter.exportArchive(context, scope, includeEvidenceFiles = false) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) { Text("Save log-only ZIP to Downloads") }
            Text(
                "Log-only keeps the records, decisions and crash reports for the selected scope. " +
                    "Everything also includes the learned profiles and learning history. " +
                    "Retained videos and per-frame traces stay on the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when (scope) {
                    DiagnosticsArchivePlan.Scope.CURRENT_RUN ->
                        "The newest batch: its records and decision log, any scorer self-check run " +
                            "just before or after it, any crash or process-exit report written since it " +
                            "started, and the device log buffer."
                    DiagnosticsArchivePlan.Scope.PREVIOUS_RUN ->
                        "The batch before the newest one, with its self-checks and the crash reports from its time."
                    DiagnosticsArchivePlan.Scope.ALL_RUNS ->
                        "Every retained run (the newest ${DiagnosticsRetention.MAX_RUNS}), every crash report, and the device log buffer."
                    DiagnosticsArchivePlan.Scope.EVERYTHING ->
                        "All runs, all crash reports, the device log buffer and the learned profiles. " +
                            "The file is named Compressor-v<version>-<time>-Everything.zip."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "One archive with a manifest.json; each run keeps its raw session.jsonl and " +
                    "decisions.log under runs/<batchId>/. Export soon after a batch: the device log " +
                    "buffer is small and overwrites itself, though the app's own records do not.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Text("Scorer self-check", style = MaterialTheme.typography.labelLarge)
            Text(
                "Control tests on the first selected video check that the source and a stream copy " +
                    "decode to identical frames. A 2x-bitrate encode of one window shows what this " +
                    "encoder can achieve on the clip. The result appears " +
                    "in the status line and in the next diagnostics ZIP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { onRunScorerSelfCheck(context) },
                modifier = Modifier.fillMaxWidth(),
                // One self-check at a time; starting a batch stops a running one.
                enabled = !busy && !isCompressing && hasSelection && !isSelfChecking
            ) { Text(if (isSelfChecking) "Scorer self-check running…" else "Run scorer self-check") }

            HorizontalDivider()

            val traceState by ExportDebugTracing.state.collectAsState()
            Text("Media3 export trace", style = MaterialTheme.typography.labelLarge)
            Text(
                when (traceState) {
                    ExportDebugTracing.State.ARMED -> "Trace armed for the next batch. The summary appears in export failure messages."
                    ExportDebugTracing.State.ACTIVE -> "Recording per-stage pipeline events for this batch."
                    ExportDebugTracing.State.IDLE -> "Arm only for a targeted diagnostic batch. Long or high-frame-rate exports can accumulate substantial trace data."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (traceState == ExportDebugTracing.State.IDLE) {
                OutlinedButton(
                    onClick = ExportDebugTracing::armNextBatch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCompressing
                ) { Text("Arm trace for next batch") }
            }

            HorizontalDivider()

            val operationRunning by ExportDebugTracing.isOperationRunning.collectAsState()
            val canChangeExperiments = !isCompressing && !operationRunning
            var bFrames by remember { mutableStateOf(EncoderExperiments.isBFramesEnabled(context)) }
            Text("Encoder experiment: B-frames", style = MaterialTheme.typography.labelLarge)
            Text(
                "Off by default. When on, probes and encodes ask the HEVC encoder for " +
                    "${EncoderExperiments.B_FRAMES_WHEN_ENABLED} B-frames (max-bframes). The same gates judge " +
                    "the result, and encodeResult lines carry bframes= so runs can be compared. Whether " +
                    "this device's encoder honours the request is not yet known.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = bFrames,
                    onCheckedChange = { on ->
                        if (EncoderExperiments.setBFramesEnabled(context, on)) bFrames = on
                    },
                    enabled = canChangeExperiments
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (bFrames) "B-frames requested for the next batch" else "B-frames off",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider()

            var saferRetry by remember { mutableStateOf(EncoderExperiments.isSaferRungRetryEnabled(context)) }
            Text("Experiment: safer-rung retry", style = MaterialTheme.typography.labelLarge)
            Text(
                "Off by default. When a full encode fails pixel certification on measured windows, and the " +
                    "probes had also passed a higher (safer) ratio for the same file, encode once more at that " +
                    "ratio. The retry must pass every check again, including the unchanged pixel bar; if it " +
                    "fails, the original is kept. At most one retry per file. Whether it recovers anything is " +
                    "not yet measured on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = saferRetry,
                    onCheckedChange = { on ->
                        if (EncoderExperiments.setSaferRungRetryEnabled(context, on)) saferRetry = on
                    },
                    enabled = canChangeExperiments
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (saferRetry) "Safer-rung retry on for the next batch" else "Safer-rung retry off",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider()

            var longGop by remember { mutableStateOf(EncoderExperiments.isLongGopEnabled(context)) }
            Text("Experiment: longer keyframe interval", style = MaterialTheme.typography.labelLarge)
            Text(
                "Off by default. Probes and encodes request twice the source's keyframe interval (at most " +
                    "${EncoderExperiments.LONG_GOP_MAX_SECONDS.toInt()} s). Every quality check is unchanged, and this " +
                    "setting learns separately, so it never uses or changes the ratios learned without it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = longGop,
                    onCheckedChange = { on ->
                        if (EncoderExperiments.setLongGopEnabled(context, on)) longGop = on
                    },
                    enabled = canChangeExperiments
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (longGop) "Longer keyframe interval on for the next batch" else "Longer keyframe interval off",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider()

            var shadowCalibration by remember { mutableStateOf(EncoderExperiments.isShadowCalibrationEnabled(context)) }
            Text("Experiment: VMAF v1 shadow calibration", style = MaterialTheme.typography.labelLarge)
            Text(
                "Off by default. Also scores certification windows with the newer VMAF v1 model, for " +
                    "calibration only: no decision reads it, and pass/fail stays on the v0.6.1 bar. It is slow " +
                    "(up to minutes per 4K window), so it runs on at most ${compress.joshattic.us.quality.ShadowCalibration.MAX_WINDOWS_PER_BATCH} " +
                    "windows per batch and never above 1080p.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = shadowCalibration,
                    onCheckedChange = { on ->
                        if (EncoderExperiments.setShadowCalibrationEnabled(context, on)) shadowCalibration = on
                    },
                    enabled = canChangeExperiments
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (shadowCalibration) "Shadow calibration on for the next batch" else "Shadow calibration off",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider()

            var frameTrace by remember { mutableStateOf(EncoderExperiments.isFrameTraceEnabled(context)) }
            Text("Research: per-frame evidence", style = MaterialTheme.typography.labelLarge)
            Text("Records frame hashes, timestamps and scores for every probe, certification and self-check window. " +
                "Adds CPU and storage work. No decoded images are saved. Export the diagnostics ZIP after a focused test.",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = frameTrace, enabled = canChangeExperiments, onCheckedChange = { on ->
                    if (EncoderExperiments.setFrameTraceEnabled(context, on)) frameTrace = on
                })
                Spacer(Modifier.width(8.dp))
                Text(if (frameTrace) "Per-frame evidence on" else "Per-frame evidence off", style = MaterialTheme.typography.bodySmall)
            }
            var retainCandidates by remember { mutableStateOf(EncoderExperiments.isRetainCandidatesEnabled(context)) }
            Text("Research: include encoded probe clips", style = MaterialTheme.typography.labelLarge)
            Text("Includes the actual short videos in the exported ZIP for offline scoring. The archive will contain video content " +
                "and can be large (512 MiB of clips per run). Use with per-frame evidence on a small selected test.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = retainCandidates, enabled = canChangeExperiments, onCheckedChange = { on ->
                    if (EncoderExperiments.setRetainCandidatesEnabled(context, on)) retainCandidates = on
                })
                Spacer(Modifier.width(8.dp))
                Text(if (retainCandidates) "Include encoded clips" else "Exclude encoded clips", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()

            var fullSourceHash by remember { mutableStateOf(EncoderExperiments.isFullSourceHashEnabled(context)) }
            Text("Pilot: full source hash", style = MaterialTheme.typography.labelLarge)
            Text(
                "Off by default. Reads every selected original in full before and after its job and " +
                    "records its SHA-256 in the diagnostics, to prove the pilot arms used identical files " +
                    "and that no original changed. It never modifies a file and records no names or paths, " +
                    "but it reads each original twice, so leave it off for normal batches.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = fullSourceHash,
                    onCheckedChange = { on ->
                        if (EncoderExperiments.setFullSourceHashEnabled(context, on)) fullSourceHash = on
                    },
                    enabled = canChangeExperiments
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (fullSourceHash) "Full source hash on for the next batch" else "Full source hash off",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider()
            // Which build is actually running. Checking this used to mean exporting a capture and
            // diffing a log string against the source; on 2026-09-01 a whole calibration round was
            // spent on an APK that predated the instrumentation it was meant to exercise. The
            // build number is monotonic, so "is this newer than the one I ran?" is answerable here.
            Text(
                "Build ${BuildConfig.BUILD_TAG} (${BuildConfig.GIT_COMMIT}) • v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                learnedState?.let {
                    if (it == "empty") "No learned profiles — the next batch starts from the defaults."
                    else "Learned state: $it. Learned targets, and in Fast search the probe skips, " +
                        "carry over between runs, so a comparison against another run is not controlled."
                } ?: "Learned state unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (confirmReset) {
                AlertDialog(
                    onDismissRequest = { confirmReset = false },
                    title = { Text("Reset learned profiles?") },
                    text = {
                        Text(
                            "This erases every learned target and probe skip on this device and resets " +
                                "which measurements may train again. It cannot be undone; the next batch " +
                                "starts from the default targets."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { confirmReset = false; resetLearnedProfiles() }) { Text("Reset") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                    }
                )
            }
            OutlinedButton(
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !isCompressing
            ) { Text("Reset learned profiles") }
            Text(
                "Do this before an A/B run. The adb broadcast cannot reach a Secure Folder install " +
                    "(it is a separate Android user), so in there this button is the only way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Text("Live capture with Termux", style = MaterialTheme.typography.labelLarge)
            Text(
                when (termuxAvailability) {
                    DiagnosticsExportPlan.TermuxAvailability.CAN_RUN_COMMAND ->
                        "Termux can be started directly. It must also have allow-external-apps=true " +
                            "set, which cannot be checked from here — confirm the command actually ran."
                    DiagnosticsExportPlan.TermuxAvailability.INSTALLED_WITHOUT_PERMISSION ->
                        "Termux is installed but has not granted RUN_COMMAND, so it cannot be " +
                            "started from here. Copy the command and paste it into Termux."
                    DiagnosticsExportPlan.TermuxAvailability.NOT_INSTALLED ->
                        "Termux is not installed. The command is still available to copy."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { runExport { DiagnosticsExporter.startTermuxCapture(context) } },
                    modifier = Modifier.weight(1f),
                    enabled = !busy &&
                        termuxAvailability == DiagnosticsExportPlan.TermuxAvailability.CAN_RUN_COMMAND
                ) { Text("Start in Termux") }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(DiagnosticsExporter.termuxScript(context)))
                        isError = false
                        message = "Command copied. Paste it into Termux before starting the batch."
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                ) { Text("Copy command") }
            }
            if (termuxAvailability != DiagnosticsExportPlan.TermuxAvailability.NOT_INSTALLED) {
                TextButton(
                    onClick = {
                        DiagnosticsExporter.termuxLaunchIntent(context)?.let { context.startActivity(it) }
                    },
                    enabled = !busy
                ) { Text("Open Termux") }
            }

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchSettingsCard(
    state: BatchCompressorUiState,
    viewModel: BatchCompressorViewModel,
    context: Context,
    onRequestOriginalMediaAccess: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val remuxOnly = state.qualityPreset == "Remux Only"
            Text("Batch settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            val deviceProfile = remember { DeviceCapabilityProfiles.current() }
            Text("${deviceProfile.presetFamily} presets", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BatchPresetOption.forDevice(deviceProfile).forEach { preset ->
                    val label = preset.label
                    FilterChip(
                        selected = state.selectedPreset == label,
                        onClick = { viewModel.applyPreset(label) },
                        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isCompressing
                    )
                }
            }
            state.selectedPreset?.let {
                Text("Preset: $it. Manual changes are still allowed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Text("Video mode", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Remux Only", "Perceptually Lossless").forEach { label ->
                        FilterChip(
                            selected = state.qualityPreset == label,
                            onClick = { viewModel.setQuality(label) },
                            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            enabled = !state.isCompressing
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("High Quality", "Storage Saver").forEach { label ->
                        FilterChip(
                            selected = state.qualityPreset == label,
                            onClick = { viewModel.setQuality(label) },
                            label = { Text(label, maxLines = 1) },
                            enabled = !state.isCompressing
                        )
                    }
                }
            }
            Text(
                if (remuxOnly) {
                    "No re-encode: video/audio copied unchanged. This keeps quality exact but may not shrink much."
                } else {
                    "Perceptually Lossless only shrinks a file when it can with no visible change — otherwise it keeps the original unchanged (many phone videos are already efficient, so this is common and honest). High Quality re-encodes at a lower bitrate (same resolution and FPS) for a small quality trade — the usual way to shrink files PL leaves unchanged (already low-bitrate videos may not shrink). Storage Saver goes smaller still and may show visible loss."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Codec", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buildList {
                    add("Auto")
                    add("HEVC")
                    add("H.264")
                    if (state.hasHardwareAv1Encoder) add("AV1")
                }.forEach { label ->
                    FilterChip(
                        selected = state.codecOption == label,
                        onClick = { viewModel.setCodec(label) },
                        label = { Text(label, maxLines = 1) },
                        enabled = !state.isCompressing && !remuxOnly
                    )
                }
            }
            Text(
                if (remuxOnly) {
                    "Disabled in Remux Only because the source codec is copied unchanged."
                } else {
                    "Auto stays HEVC-first for camera/archive/HDR clips. AV1 is explicit opt-in only when real hardware support exists."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val perceptuallyLossless = state.qualityPreset == "Perceptually Lossless"
            val fpsLocked = remuxOnly || perceptuallyLossless
            Text("Frame rate", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Original", "120 fps", "60 fps").forEach { label ->
                        FilterChip(
                            selected = state.frameRateOption == label,
                            onClick = { viewModel.setFrameRate(label) },
                            label = { Text(label, maxLines = 1) },
                            enabled = !state.isCompressing && !fpsLocked
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("30 fps", "24 fps").forEach { label ->
                        FilterChip(
                            selected = state.frameRateOption == label,
                            onClick = { viewModel.setFrameRate(label) },
                            label = { Text(label, maxLines = 1) },
                            enabled = !state.isCompressing && !fpsLocked
                        )
                    }
                }
            }
            Text(
                when {
                    remuxOnly -> "Disabled in Remux Only because source timestamps and FPS are copied unchanged."
                    perceptuallyLossless -> "Disabled in Perceptually Lossless because source FPS/motion (including 120fps) is always preserved. Choose High Quality or Storage Saver to cap FPS."
                    else -> "Original/source FPS is preserved by default, including 120fps. 120/60/30/24 caps are applied only when you explicitly choose them."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Perceptually Lossless search", style = MaterialTheme.typography.labelLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = state.exhaustivePerceptualLossless,
                    onCheckedChange = { viewModel.setExhaustivePerceptualLossless(it) },
                    enabled = !state.isCompressing
                )
                Text(
                    if (state.exhaustivePerceptualLossless) "Exhaustive: measure every file" else "Fast: skip likely failures",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                if (state.exhaustivePerceptualLossless)
                    "SDR videos up to 4K get a real VMAF test before being kept as-is (a shorter test " +
                        "above 1080p), and any verified saving is kept, however small. Same quality bar: " +
                        "a file is only replaced when the test proves no visible loss. Slower and uses " +
                        "more battery, most of all on 4K. Never tested: HDR (no validated HDR quality " +
                        "model), 8K, clips under 2 seconds, and files already in a more efficient codec " +
                        "than the phone can encode (such as AV1)."
                else
                    "Skips the VMAF test for files that look already compressed, or whose type " +
                        "failed recently. Faster, but some compressible files are never tried.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Metadata privacy", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Preserve all metadata",
                    "Remove location only",
                    "Remove date only",
                    "Remove location + date",
                    "Keep camera/video technical metadata",
                    "Privacy mode for sharing"
                ).forEach { label ->
                    FilterChip(
                        selected = state.metadataPrivacyMode == label,
                        onClick = { viewModel.setMetadataPrivacyMode(label) },
                        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isCompressing
                    )
                }
            }
            Text(
                MetadataPrivacyMode.fromLabel(state.metadataPrivacyMode).description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Thermal safety", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Conservative", "Balanced", "Fast").forEach { label ->
                    FilterChip(
                        selected = state.thermalMode == label,
                        onClick = { viewModel.setThermalMode(label) },
                        label = { Text(label, maxLines = 1) },
                        enabled = !state.isCompressing
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { viewModel.setCooldownSeconds(state.cooldownSeconds - 5) },
                    enabled = !state.isCompressing && state.cooldownSeconds > 0
                ) { Text("-5s") }

                Text(
                    "Cooldown: ${state.cooldownSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                OutlinedButton(
                    onClick = { viewModel.setCooldownSeconds(state.cooldownSeconds + 5) },
                    enabled = !state.isCompressing && state.cooldownSeconds < 60
                ) { Text("+5s") }
            }
            Text(
                state.thermalStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { viewModel.refreshThermalStatus(context) },
                enabled = !state.isCompressing
            ) {
                Text("Check heat/battery")
            }

            HorizontalDivider()
            SettingSwitchRow("Replace originals after compression", "Off by default. Best results require selecting videos inside Compressor with write access.", state.replaceOriginals, !state.isCompressing) { viewModel.toggleReplaceOriginals() }
            SettingSwitchRow("Backup before replace", "When enabled, a backup copy is saved before any original is overwritten.", state.backupBeforeReplace, state.replaceOriginals && !state.isCompressing) { viewModel.toggleBackupBeforeReplace() }
            Text("Replace only after verification: On", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            SettingSwitchRow("Use Shizuku fallback", "Uses Shizuku only after normal Android replacement fails.", state.useShizukuFallback, state.replaceOriginals && !state.isCompressing) { viewModel.toggleShizukuFallback() }
            Text("Shizuku: ${state.shizukuStatus}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Location metadata: grant original media access, then reselect or reload videos so Android allows unredacted GPS reads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Three action buttons with long labels do not fit one phone-width row (the last,
            // "Test access", was squeezed/clipped). FlowRow wraps them cleanly at any width and font
            // scale; heightIn keeps each a >=48dp touch target with a single un-clipped label line.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.requestShizukuPermission(context) },
                    enabled = !state.isCompressing,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Authorize", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onRequestOriginalMediaAccess,
                    enabled = !state.isCompressing,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Metadata access", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { viewModel.testReplacementAccess(context) },
                    enabled = !state.isCompressing,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Test access", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = { onCheckedChange() }, enabled = enabled)
    }
}

@Composable
private fun BatchSummaryCard(state: BatchCompressorUiState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Batch summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Videos: ${state.items.size} • Outputs ready: ${state.doneCount}")
            Text("Real compressions: ${state.realCompressionCount} • Remuxed/kept/no size win: ${state.nonCompressionCount}")
            Text("Failed: ${state.failedCount} • Skipped: ${state.skippedCount} • Cancelled: ${state.cancelledCount}")
            Text("Original: ${state.formattedTotalOriginal}")
            Text("Mode: ${state.qualityPreset} • Codec: ${state.codecOption} • FPS: ${state.frameRateOption}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Metadata: ${MetadataPrivacyMode.fromLabel(state.metadataPrivacyMode).summary}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Thermal: ${state.thermalMode} • Cooldown: ${state.cooldownSeconds}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.isCompressing) {
                state.activeItem?.let { active ->
                    Text(
                        "Active: ${ItemProgressModel.describe(active)} • ${active.activeSizeLine()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Finished: ${state.items.count { ItemProgressModel.isTerminal(it) }} of ${state.items.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.totalOutputBytes > 0) {
                Text("Outputs: ${state.formattedTotalOutput} • ${AcceptedReduction.LABEL}: ${state.formattedTotalSaved}")
                AcceptedReduction.storageNote(state.realCompressionCount, state.originalsReplacedCount, state.backupBeforeReplace)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BatchMetricsCard(metrics: BatchMetricsSummary) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Batch metrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            metrics.summaryLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PreservationReportCard(state: BatchCompressorUiState) {
    val dateCount = state.items.count { it.metadataSnapshot.hasDate }
    val mp4DateCount = state.items.count { !it.metadataSnapshot.rawDateTag.isNullOrBlank() }
    val locationCount = state.items.count { it.metadataSnapshot.hasLocation }
    val outputCount = state.items.count { it.outputSize > 0L }
    val skippedCount = state.items.count { it.status == BatchItemStatus.Skipped || it.isAlreadyCompressed }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preservation report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Source metadata found: date $dateCount/${state.items.size} • location $locationCount/${state.items.size}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Outputs ready: $outputCount/${state.items.size} • skipped: $skippedCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (mp4DateCount > 0) {
                Text(
                    "Date diagnostics: $mp4DateCount source date tag${if (mp4DateCount == 1) "" else "s"} found through MP4/retriever metadata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (state.hasOutputs) {
                Text(
                    "Saved outputs follow the selected metadata privacy mode when Android exposes those fields.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    "Compress a batch to see before/after savings and final metadata status here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The batch settings a recommendation is compared with. */
internal data class BatchSettingsView(val quality: String, val codec: String, val frameRate: String)

/**
 * One row of the batch. Identity first (a stable name area, the status below it when the window or
 * the font is narrow), then what is happening to the original and the copy, then the active phase
 * or the result. Durable warnings stay visible; the full verification evidence folds away.
 */
@Composable
internal fun BatchItemCard(
    index: Int,
    item: BatchVideoItem,
    batchRunning: Boolean,
    replaceOriginals: Boolean,
    settings: BatchSettingsView,
    queuedCount: Int,
    onApplyRecommendation: () -> Unit
) {
    val skipped = item.status == BatchItemStatus.Skipped || item.isAlreadyCompressed
    val cardColors = CardDefaults.outlinedCardColors(
        containerColor = if (skipped) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    )

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = cardColors
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemHeader(index, item)
            Text(
                "Original: ${item.originalWidth}x${item.originalHeight} • ${item.originalFps.toInt()}fps • ${item.displaySize}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val original = OriginalStatus.describe(item, replaceOriginals)
            Text(
                original.text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (original.tone == OriginalStatus.Tone.NEUTRAL) FontWeight.Normal else FontWeight.SemiBold,
                color = when (original.tone) {
                    OriginalStatus.Tone.WARNING -> MaterialTheme.colorScheme.error
                    OriginalStatus.Tone.DONE -> MaterialTheme.colorScheme.primary
                    OriginalStatus.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag(ItemCardTags.ORIGINAL_STATUS)
            )
            Text(
                item.metadataSummary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (item.status == BatchItemStatus.Compressing) {
                ActiveItemBlock(item)
            } else {
                ResultBlock(item)
            }

            item.recommendation?.takeIf { item.status == BatchItemStatus.Pending && !batchRunning }?.let { recommendation ->
                RecommendationSection(item, recommendation, settings, queuedCount, onApplyRecommendation)
            }
        }
    }
}

/** Test tags for the parts of a row whose presence or absence a test checks. */
internal object ItemCardTags {
    const val STATUS = "item-status"
    const val ORIGINAL_STATUS = "item-original-status"
    const val PHASE = "item-phase"
    const val ATTEMPT = "item-attempt"
    const val SIZE = "item-size"
    const val DETAIL = "item-phase-detail"
    const val OUTCOME = "item-outcome"
    const val DETAILS_TOGGLE = "item-details-toggle"
    const val SUGGESTION_TOGGLE = "item-suggestion-toggle"
    const val APPLY_TO_BATCH = "item-apply-to-batch"
}

@Composable
private fun ItemHeader(index: Int, item: BatchVideoItem) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // A long name and a long status cannot share a narrow row: the status goes below it, so the
        // name keeps a stable area. Large text narrows the row as much as a small window does.
        val narrow = maxWidth < 360.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
        val name = @Composable { modifier: Modifier ->
            Text(
                "$index. ${item.originalName}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier
            )
        }
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                name(Modifier)
                StatusBadge(item)
            }
        } else {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                name(Modifier.weight(1f))
                Spacer(modifier = Modifier.width(10.dp))
                StatusBadge(item)
            }
        }
    }
}

/** The row's status as readable, non-interactive text (a disabled chip greys its label out). */
@Composable
private fun StatusBadge(item: BatchVideoItem) {
    val label = StatusLabel.of(item)
    val failed = StatusLabel.isAlarming(item)
    val (container, content) = when {
        failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        item.status == BatchItemStatus.Compressing -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        item.status == BatchItemStatus.Pending -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .testTag(ItemCardTags.STATUS)
            .semantics(mergeDescendants = true) { contentDescription = "Status: $label" }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ActiveItemBlock(item: BatchVideoItem) {
    // The phase and its own measured fraction (ItemProgressModel), never an encoder's 100 %
    // presented as the item's: finalizing, verification and certification follow.
    Text(
        ItemProgressModel.describe(item),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.testTag(ItemCardTags.PHASE)
    )
    // Why the work restarted and why the estimate grew: kept for the whole retry (R4).
    item.attemptContext?.let { attempt ->
        Text(
            "${attempt.label}: ${attempt.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(ItemCardTags.ATTEMPT)
        )
    }
    Text(
        item.activeSizeLine(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(ItemCardTags.SIZE)
    )
    val fraction = item.phaseFraction
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    // Only the current phase's own detail: text a finished phase wrote is obsolete (R3).
    item.detail?.takeIf { it.phase == item.phase }?.let { detail ->
        Text(
            detail.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ItemCardTags.DETAIL)
        )
    }
    // Durable text written while the row is active (a warning) stays visible beside the phase.
    item.message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultBlock(item: BatchVideoItem) {
    // The encode's result, qualified when a replacement was asked for and did not happen (OutcomeLine).
    OutcomeLine.of(item)?.let { outcome ->
        Text(
            outcome.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (outcome.tone == OriginalStatus.Tone.WARNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(ItemCardTags.OUTCOME)
        )
    }
    if (item.outputSize > 0L) {
        Text(
            item.beforeAfterSummary(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    item.attemptContext?.let { attempt ->
        Text(
            "${attempt.label}: ${attempt.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.testTag(ItemCardTags.ATTEMPT)
        )
    }
    // Warnings are never folded away.
    item.verificationReport?.replacementBlockReason?.let { reason ->
        Text(
            "Replacement blocked: $reason",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    // An outcome, a failure or a replacement's account is shown; the long technical completion
    // line of a verified output goes with the verification details.
    val messageUpFront = item.status == BatchItemStatus.Failed || item.replacementOutcome != null || item.verificationReport == null
    if (messageUpFront) {
        item.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == BatchItemStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    val report = item.verificationReport
    if (report != null || item.metrics != null || (!messageUpFront && item.message != null)) {
        var expanded by rememberSaveable(item.sourceUri.toString()) { mutableStateOf(false) }
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag(ItemCardTags.DETAILS_TOGGLE)
        ) {
            Text(if (expanded) "Hide verification details" else "Show verification details")
        }
        if (expanded) {
            HorizontalDivider()
            report?.summaryLines?.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.metrics?.let { metrics ->
                Text(metrics.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!messageUpFront) {
                item.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/**
 * A recommendation is a rule of thumb about the kind of clip, and applying it changes the WHOLE
 * batch's settings, not this file's. It is folded by default, says what it would change and
 * whether that leaves Perceptually Lossless, and asks before applying (R5). Hidden while a batch runs.
 */
@Composable
private fun RecommendationSection(
    item: BatchVideoItem,
    recommendation: CompressionRecommendation,
    settings: BatchSettingsView,
    queuedCount: Int,
    onApply: () -> Unit
) {
    val preview = RecommendationPreview.of(recommendation, settings.quality, settings.codec, settings.frameRate)
    var open by rememberSaveable("suggestion-" + item.sourceUri.toString()) { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    HorizontalDivider()
    Text(
        "Suggestion: ${recommendation.title}" +
            when {
                !preview.changesAnything -> " (the batch already uses it)"
                preview.leavesPerceptuallyLossless -> " (would leave Perceptually Lossless)"
                else -> ""
            },
        style = MaterialTheme.typography.bodySmall,
        color = if (preview.leavesPerceptuallyLossless) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
    TextButton(onClick = { open = !open }, modifier = Modifier.testTag(ItemCardTags.SUGGESTION_TOGGLE)) {
        Text(if (open) "Hide suggestion" else "Show suggestion")
    }
    if (open) {
        Text(RecommendationPreview.estimateLine(recommendation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(recommendation.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (preview.changesAnything) {
            Text("Applies to the whole batch, not only this file:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            preview.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (preview.leavesPerceptuallyLossless) {
                Text(
                    "Every output would be lossy, and the measured Perceptually Lossless checks would not apply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            OutlinedButton(onClick = { confirming = true }, modifier = Modifier.testTag(ItemCardTags.APPLY_TO_BATCH)) {
                Text("Apply to entire batch…")
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Change the whole batch?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("These settings apply to all $queuedCount queued file${if (queuedCount == 1) "" else "s"}:")
                    preview.changes.forEach { Text("• $it") }
                    if (preview.leavesPerceptuallyLossless) {
                        Text("Outputs would be lossy: Perceptually Lossless would be off.", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onApply() }) { Text("Apply to batch") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Sizes for an active row, each named for what it is: the live file while the muxer writes (a
 * temporary footprint that can shrink when the file is closed), the finalized candidate (not yet
 * accepted), and the estimate, marked provisional until the probes have chosen a ratio.
 */
private fun BatchVideoItem.activeSizeLine(): String {
    val estimate = if (targetOutputSize > 0L) {
        "est ${formatCardBytes(targetOutputSize)}${if (estimateIsProvisional) " (provisional)" else ""}"
    } else {
        "no estimate"
    }
    return when {
        candidateOutputSize > 0L -> "Candidate ${formatCardBytes(candidateOutputSize)} (not yet accepted) • $estimate"
        currentOutputSize > 0L -> "Writing ${formatCardBytes(currentOutputSize)} (temporary) • $estimate"
        else -> estimate
    }
}

/** What the source carries; what happens to the original and the copy is OriginalStatus's line. */
private fun BatchVideoItem.metadataSummary(): String {
    val date = if (metadataSnapshot.hasDate) "${metadataSnapshot.dateDiagnosticLabel} ✓" else "date —"
    val location = if (metadataSnapshot.hasLocation) "location ✓" else "location —"
    return "Metadata: $date • $location"
}

private fun BatchVideoItem.beforeAfterSummary(): String {
    if (outputSize <= 0L) return "Output pending"
    val savedBytes = (originalSize - outputSize).coerceAtLeast(0L)
    val savedPercent = if (originalSize > 0L) ((savedBytes * 100.0) / originalSize).toInt() else 0
    val similarSize = originalSize > 0L && kotlin.math.abs(outputSize - originalSize).toDouble() / originalSize.toDouble() < 0.03
    if (terminalResult?.countsAsRealCompression == true) {
        // A size reduction, not freed space: the original may still sit beside this output.
        return "Before/after: ${formatCardBytes(originalSize)} → ${formatCardBytes(outputSize)} • ${formatCardBytes(savedBytes)} smaller ($savedPercent%)"
    }
    val rawDelta = outputSize - originalSize
    val deltaLabel = when {
        similarSize -> "size similar"
        rawDelta < 0L -> "container/file delta ${formatCardBytes(-rawDelta)} smaller (not compression savings)"
        rawDelta > 0L -> "${formatCardBytes(rawDelta)} larger"
        else -> "same size"
    }
    val outcome = terminalResult?.label ?: "not classified as a real compression"
    return "Before/after: ${formatCardBytes(originalSize)} → ${formatCardBytes(outputSize)} • $deltaLabel • $outcome"
}

private fun formatCardBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L).toDouble()
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        safe >= gb -> String.format(java.util.Locale.US, "%.2f GiB", safe / gb)
        safe >= mb -> String.format(java.util.Locale.US, "%.1f MiB", safe / mb)
        safe >= kb -> String.format(java.util.Locale.US, "%.1f KiB", safe / kb)
        else -> "${bytes.coerceAtLeast(0L)} B"
    }
}

private fun persistVideoPermissions(context: Context, uris: List<Uri>) {
    val resolver = context.contentResolver
    val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    uris.forEach { uri ->
        runCatching { resolver.takePersistableUriPermission(uri, readWriteFlags) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

private fun needsOriginalMediaLocationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
}

private fun shareCompressedOutputs(context: Context, state: BatchCompressorUiState) {
    val outputUris = state.items.mapNotNull { item ->
        val path = item.outputPath ?: return@mapNotNull null
        val file = File(path)
        if (!file.exists()) return@mapNotNull null
        FileProvider.getUriForFile(context, context.packageName + ".provider", file)
    }
    if (outputUris.isEmpty()) return

    val intent = if (outputUris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, outputUris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/mp4"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(outputUris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(intent, "Share video output${if (outputUris.size == 1) "" else "s"}"))
}
