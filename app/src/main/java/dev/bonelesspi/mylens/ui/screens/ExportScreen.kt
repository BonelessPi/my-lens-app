package dev.bonelesspi.mylens.ui.screens

import android.os.Environment
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
import dev.bonelesspi.mylens.viewmodel.ExportState
import dev.bonelesspi.mylens.viewmodel.ScannerViewModel

enum class PageSize(val label: String, val widthPt: Float, val heightPt: Float) {
    A4("A4 (210 × 297 mm)", 595f, 842f),
    LETTER("US Letter (8.5 × 11 in)", 612f, 792f),
    A3("A3 (297 × 420 mm)", 842f, 1191f),
    FIT_IMAGE("Fit to image", 0f, 0f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    viewModel: ScannerViewModel = viewModel()
) {
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()

    var fileName by remember { mutableStateOf("scan") }
    var selectedPageSize by remember { mutableStateOf(PageSize.A4) }
    var jpegQuality by remember { mutableFloatStateOf(90f) }
    var pageSizeMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export PDF") },
                navigationIcon = {
                    IconButton(onClick = {
                        // FIX: reset export state so the Export button reappears in SelectScreen
                        viewModel.resetExportState()
                        onBack()
                    }) {
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                "${viewModel.pages.size} page${if (viewModel.pages.size != 1) "s" else ""} ready to export",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("File name") },
                suffix = { Text(".pdf") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(
                expanded = pageSizeMenuExpanded,
                onExpandedChange = { pageSizeMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedPageSize.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Page size") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(pageSizeMenuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = pageSizeMenuExpanded,
                    onDismissRequest = { pageSizeMenuExpanded = false }
                ) {
                    PageSize.entries.forEach { size ->
                        DropdownMenuItem(
                            text = { Text(size.label) },
                            onClick = { selectedPageSize = size; pageSizeMenuExpanded = false }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Image quality", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${jpegQuality.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = jpegQuality,
                    onValueChange = { jpegQuality = it },
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

            Spacer(Modifier.weight(1f))

            when (val state = exportState) {
                is ExportState.Building -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Building PDF…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                is ExportState.Error -> {
                    Text(
                        "Export failed: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { viewModel.resetExportState() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try Again")
                    }
                }

                is ExportState.Done -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "✓ Saved to Documents/${state.file.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(
                            onClick = {
                                viewModel.clearAll()
                                onFinished()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start New Scan")
                        }
                        OutlinedButton(
                            onClick = {
                                // Keep pages, reset export state, go back to select
                                viewModel.resetExportState()
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Back to Pages")
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = {
                            val safeName = fileName.ifBlank { "scan" }.trim() + ".pdf"
                            viewModel.exportPdf(
                                outputDir = Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOCUMENTS
                                ),
                                fileName = safeName,
                                pageSize = selectedPageSize,
                                quality = jpegQuality.toInt()
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = viewModel.pages.isNotEmpty()
                    ) {
                        Text("Save PDF", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
