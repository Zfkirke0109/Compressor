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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        consumeIncomingVideos(intent)
        setContent {
            CompressorTheme {
                BatchCompressorScreen(
                    viewModel = viewModel,
                    onShareOutputs = { shareCompressedOutputs(this, viewModel.uiState.value) }
                )
            }
        }
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
    val scrollState = rememberScrollState()
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

    LaunchedEffect(Unit) { viewModel.refreshShizukuStatus(context) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Compressor Batch", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Samsung Galaxy S23 Ultra optimized", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Default is Original: keep 4K as 4K, keep source FPS/audio, and use perceptually lossless compression for storage savings.",
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

            BatchSettingsCard(state, viewModel, context, requestOriginalMediaAccess)
            if (state.items.isNotEmpty()) {
                BatchSummaryCard(state)
                PreservationReportCard(state)
            }

            state.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            state.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

            if (state.items.isNotEmpty()) {
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

                if (state.hasOutputs) {
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

                TextButton(
                    onClick = { viewModel.clear() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enabled = !state.isCompressing
                ) { Text("Clear batch") }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.items.forEachIndexed { index, item -> BatchItemCard(index + 1, item) }
                }
            } else if (!state.isLoading) {
                Text(
                    "For true replace-original mode, pick videos inside Compressor with the file picker. Videos shared from Gallery may be read-only and may need a safe copy fallback.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BatchSettingsCard(
    state: BatchCompressorUiState,
    viewModel: BatchCompressorViewModel,
    context: Context,
    onRequestOriginalMediaAccess: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Batch settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Video quality", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Original", "High").forEach { label ->
                        FilterChip(
                            selected = state.qualityPreset == label,
                            onClick = { viewModel.setQuality(label) },
                            label = { Text(label, maxLines = 1) },
                            enabled = !state.isCompressing
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Medium", "Low").forEach { label ->
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
                "Original is the default perceptually lossless mode: keeps source resolution, source FPS, HDR mode, and audio bitrate. Medium/Low are explicit downgrade choices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Codec", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Auto", "HEVC", "H.264").forEach { label ->
                    FilterChip(
                        selected = state.codecOption == label,
                        onClick = { viewModel.setCodec(label) },
                        label = { Text(label, maxLines = 1) },
                        enabled = !state.isCompressing
                    )
                }
            }
            Text(
                "Auto chooses the best S23 Ultra hardware encoder. HEVC usually saves more storage; H.264 is best for compatibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Frame rate", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Original", "60 fps", "30 fps").forEach { label ->
                        FilterChip(
                            selected = state.frameRateOption == label,
                            onClick = { viewModel.setFrameRate(label) },
                            label = { Text(label, maxLines = 1) },
                            enabled = !state.isCompressing
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.frameRateOption == "24 fps",
                        onClick = { viewModel.setFrameRate("24 fps") },
                        label = { Text("24 fps", maxLines = 1) },
                        enabled = !state.isCompressing
                    )
                }
            }
            Text(
                "Original keeps source FPS. 60 caps high-frame-rate clips to 60. 30/24 lower higher-frame-rate clips only when selected.",
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
            SettingSwitchRow("Use Shizuku fallback", "Uses Shizuku only after normal Android replacement fails.", state.useShizukuFallback, state.replaceOriginals && !state.isCompressing) { viewModel.toggleShizukuFallback() }
            Text("Shizuku: ${state.shizukuStatus}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Location metadata: grant original media access, then reselect or reload videos so Android allows unredacted GPS reads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.requestShizukuPermission(context) }, enabled = !state.isCompressing) {
                    Text("Authorize")
                }
                OutlinedButton(onClick = onRequestOriginalMediaAccess, enabled = !state.isCompressing) {
                    Text("Metadata access")
                }
                OutlinedButton(onClick = { viewModel.testReplacementAccess(context) }, enabled = !state.isCompressing) {
                    Text("Test access")
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
            Text("Videos: ${state.items.size} • Done: ${state.doneCount} • Failed: ${state.failedCount}")
            Text("Original: ${state.formattedTotalOriginal}")
            Text("Mode: ${state.qualityPreset} • Codec: ${state.codecOption} • FPS: ${state.frameRateOption}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Thermal: ${state.thermalMode} • Cooldown: ${state.cooldownSeconds}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.isCompressing) {
                state.activeItem?.let { active ->
                    Text(
                        "Active: ${active.currentOutputDisplaySize} / est ${active.targetOutputDisplaySize} • ${active.progressPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (state.totalTargetOutputBytes > 0L) {
                    Text(
                        "Batch written: ${state.formattedTotalCurrentOutput} / est ${state.formattedTotalTargetOutput}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.totalOutputBytes > 0) Text("Compressed: ${state.formattedTotalOutput} • Saved: ${state.formattedTotalSaved}")
        }
    }
}

@Composable
private fun PreservationReportCard(state: BatchCompressorUiState) {
    val dateCount = state.items.count { it.metadataSnapshot.hasDate }
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

            if (state.hasOutputs) {
                Text(
                    "Saved outputs carry best-effort source date/location metadata when Android exposes it.",
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

@Composable
private fun BatchItemCard(index: Int, item: BatchVideoItem) {
    val skipped = item.status == BatchItemStatus.Skipped || item.isAlreadyCompressed
    val cardColors = CardDefaults.outlinedCardColors(
        containerColor = if (skipped) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    )

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = cardColors
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "$index. ${item.originalName}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Original: ${item.originalWidth}x${item.originalHeight} • ${item.originalFps.toInt()}fps • ${item.displaySize}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(item.shortStatusLabel()) }
                )
            }

            Text(
                item.preservationSummary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (item.status == BatchItemStatus.Compressing) {
                Text(
                    "Output: ${item.currentOutputDisplaySize} / est ${item.targetOutputDisplaySize} • ${item.progressPercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                LinearProgressIndicator(progress = { item.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }

            if (item.outputSize > 0L) {
                HorizontalDivider()
                Text(
                    item.beforeAfterSummary(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == BatchItemStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun BatchVideoItem.shortStatusLabel(): String {
    return when {
        status == BatchItemStatus.Skipped || isAlreadyCompressed -> "Skipped"
        status == BatchItemStatus.Compressing -> "${progressPercent}%"
        status == BatchItemStatus.Done -> "Done"
        status == BatchItemStatus.Replaced -> "Replaced"
        status == BatchItemStatus.SavedCopy -> "Saved"
        status == BatchItemStatus.Failed -> "Failed"
        else -> "Ready"
    }
}

private fun BatchVideoItem.preservationSummary(): String {
    val date = if (metadataSnapshot.hasDate) "date ✓" else "date —"
    val location = if (metadataSnapshot.hasLocation) "location ✓" else "location —"
    val replacement = when (status) {
        BatchItemStatus.Replaced -> "original replaced"
        BatchItemStatus.SavedCopy -> "safe copy saved"
        BatchItemStatus.Done -> "copy ready"
        BatchItemStatus.Skipped -> "skipped"
        BatchItemStatus.Failed -> "failed"
        BatchItemStatus.Compressing -> "compressing"
        else -> "ready"
    }
    return "Metadata: $date • $location • $replacement"
}

private fun BatchVideoItem.beforeAfterSummary(): String {
    if (outputSize <= 0L) return "Output pending"
    val savedBytes = (originalSize - outputSize).coerceAtLeast(0L)
    val savedPercent = if (originalSize > 0L) ((savedBytes * 100.0) / originalSize).toInt() else 0
    return "Before/after: ${formatCardBytes(originalSize)} → ${formatCardBytes(outputSize)} • saved ${formatCardBytes(savedBytes)} ($savedPercent%)"
}

private fun formatCardBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L).toDouble()
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        safe >= gb -> String.format(java.util.Locale.US, "%.2f GB", safe / gb)
        safe >= mb -> String.format(java.util.Locale.US, "%.1f MB", safe / mb)
        safe >= kb -> String.format(java.util.Locale.US, "%.1f KB", safe / kb)
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
    context.startActivity(Intent.createChooser(intent, "Share compressed video${if (outputUris.size == 1) "" else "s"}"))
}
