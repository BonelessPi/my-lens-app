package dev.bonelesspi.mylens.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bonelesspi.mylens.viewmodel.SettingsViewModel

// ── Named resolution presets ──────────────────────────────────────────────────

data class ResolutionOption(val label: String, val pixels: Int)

val EXPORT_RESOLUTION_OPTIONS = listOf(
    ResolutionOption("Low (1024 px)",     1024),
    ResolutionOption("Medium (2048 px)",  2048),
    ResolutionOption("High (3072 px)",    3072),
    ResolutionOption("Maximum (4096 px)", 4096)
)

private val PREVIEW_RESOLUTION_OPTIONS = listOf(
    ResolutionOption("Low (720 px)",     720),
    ResolutionOption("Medium (1280 px)", 1280),
    ResolutionOption("High (1920 px)",   1920)
)

private val THUMBNAIL_RESOLUTION_OPTIONS = listOf(
    ResolutionOption("Small (96 px)",    96),
    ResolutionOption("Medium (144 px)",  144),
    ResolutionOption("Large (240 px)",   240),
    ResolutionOption("Maximum (360 px)", 360)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val exportResolution    by viewModel.exportResolution.collectAsStateWithLifecycle()
    val previewResolution   by viewModel.previewResolution.collectAsStateWithLifecycle()
    val thumbnailResolution by viewModel.thumbnailResolution.collectAsStateWithLifecycle()
    val jpegQuality         by viewModel.jpegQuality.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            DropdownSetting(
                label = "Export resolution",
                options = EXPORT_RESOLUTION_OPTIONS,
                selectedPixels = exportResolution,
                onSelect = { viewModel.setExportResolution(it) }
            )

            DropdownSetting(
                label = "Preview resolution",
                options = PREVIEW_RESOLUTION_OPTIONS,
                selectedPixels = previewResolution,
                onSelect = { viewModel.setPreviewResolution(it) }
            )

            DropdownSetting(
                label = "Thumbnail resolution",
                options = THUMBNAIL_RESOLUTION_OPTIONS,
                selectedPixels = thumbnailResolution,
                onSelect = { viewModel.setThumbnailResolution(it) }
            )

            // ── JPEG quality ──────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Default JPEG quality", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "$jpegQuality%",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = jpegQuality.toFloat(),
                    onValueChange = { viewModel.setJpegQuality(it.toInt()) },
                    valueRange = 50f..100f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Smaller file", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Best quality", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

// ── Reusable dropdown for resolution options ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    label: String,
    options: List<ResolutionOption>,
    selectedPixels: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.pixels == selectedPixels }?.label
        ?: "$selectedPixels px"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, true)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option.pixels); expanded = false }
                )
            }
        }
    }
}
