package compress.joshattic.us

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        if (uris.isNotEmpty()) {
            viewModel.loadUris(this, uris)
        }
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

    val pickVideos = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(50),
        onResult = { uris ->
            if (uris.isNotEmpty()) viewModel.loadUris(context, uris)
        }
    )

    LaunchedEffect(Unit) {
        viewModel.refreshShizukuStatus(context)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Compressor Batch", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
                        "Device profile: ${state.deviceProfile}. Batch jobs run one at a time to protect the hardware encoder from heat and codec init failures.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isCompressing && !state.isLoading
                    ) {
                        Text(if (state.items.isEmpty()) "Select videos" else "Select different videos")
                    }
                }
            }

            BatchSettingsCard(state, viewModel, context)

            if (state.items.isNotEmpty()) {
                BatchSummaryCard(state)
            }

            state.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            state.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            if (state.items.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.startCompression(context) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isCompressing && !state.isLoading
                    ) {
                        Text("Compress ${state.items.size}")
                    }
                    OutlinedButton(
                        onClick = { viewModel.cancelCompression() },
                        modifier = Modifier.weight(1f),
                        enabled = state.isCompressing
                    ) {
                        Text("Cancel")
                    }
                }

                if (state.hasOutputs) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.saveAllCopiesToGallery(context) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isCompressing
                        ) {
                            Text("Save copies")
                        }
                        OutlinedButton(
                            onClick = onShareOutputs,
                            modifier = Modifier.weight(1f),
                            enabled = !state.isCompressing
                        ) {
                            Text("Share")
                        }
                    }
                }

                TextButton(
                    onClick = { viewModel.clear() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enabled = !state.isCompressing
                ) {
                    Text("Clear batch")
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.items.forEachIndexed { index, item ->
                        BatchItemCard(index + 1, item)
                    }
                }
            } else if (!state.isLoading) {
                Text(
                    "Pick several videos here, or share multiple videos from Gallery into Compressor.",
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
    context: Context
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Batch settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            Text("Quality preset", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("High", "Medium", "Low").forEach { label ->
                    FilterChip(
                        selected = state.qualityPreset == label,
                        onClick = { viewModel.setQuality(label) },
                        label = { Text(label) },
                        enabled = !state.isCompressing
                    )
                }
            }

            SettingSwitchRow(
                title = "Prefer HEVC/H.265",
                subtitle = "Best default for S23 Ultra storage savings when the hardware encoder supports it.",
                checked = state.preferHevc,
                enabled = !state.isCompressing,
                onCheckedChange = { viewModel.togglePreferHevc() }
            )

            HorizontalDivider()

            SettingSwitchRow(
                title = "Replace originals after compression",
                subtitle = "Off by default. Compressor writes only after the compressed output exists and verifies. Protected originals fall back to a safe copy.",
                checked = state.replaceOriginals,
                enabled = !state.isCompressing,
                onCheckedChange = { viewModel.toggleReplaceOriginals() }
            )

            SettingSwitchRow(
                title = "Use Shizuku fallback",
                subtitle = "Optional advanced path for file-path originals when Android blocks normal writes. Status: ${state.shizukuStatus}",
                checked = state.useShizukuFallback,
                enabled = state.replaceOriginals && !state.isCompressing,
                onCheckedChange = { viewModel.toggleShizukuFallback() }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.refreshShizukuStatus(context) }, enabled = !state.isCompressing) {
                    Text("Refresh Shizuku")
                }
                OutlinedButton(onClick = { viewModel.requestShizukuPermission(context) }, enabled = !state.isCompressing) {
                    Text("Authorize")
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
            if (state.totalOutputBytes > 0) {
                Text("Compressed: ${state.formattedTotalOutput} • Saved: ${state.formattedTotalSaved}")
            }
        }
    }
}

@Composable
private fun BatchItemCard(index: Int, item: BatchVideoItem) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "$index. ${item.originalName}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${item.originalWidth}x${item.originalHeight} • ${item.originalFps.toInt()}fps • ${item.displaySize}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Status: ${item.status.name}${item.message?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            if (item.status == BatchItemStatus.Compressing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (item.outputSize > 0L) {
                Text("Output: ${item.outputDisplaySize}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
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
