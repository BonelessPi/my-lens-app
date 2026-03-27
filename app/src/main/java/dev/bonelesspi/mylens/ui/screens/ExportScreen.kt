package dev.bonelesspi.mylens.ui.screens

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bonelesspi.mylens.viewmodel.ExportState
import dev.bonelesspi.mylens.viewmodel.ScannerViewModel
import java.io.File
import androidx.core.net.toUri

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
    viewModel: ScannerViewModel = viewModel()
) {
    val context = LocalContext.current
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()

    var fileName by remember { mutableStateOf("scan") }
    var selectedPageSize by remember { mutableStateOf(PageSize.A4) }
    var jpegQuality by remember { mutableFloatStateOf(90f) }
    var pageSizeMenuExpanded by remember { mutableStateOf(false) }

    // Output folder — defaults to Documents, user can pick via SAF folder picker
    var outputFolderUri by remember { mutableStateOf<Uri?>(null) }
    val outputFolderLabel by remember(outputFolderUri) {
        derivedStateOf {
            outputFolderUri?.lastPathSegment
                ?.substringAfterLast(':')
                ?: "Documents"
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist read/write permission across reboots
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            outputFolderUri = uri
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export PDF") },
                navigationIcon = {
                    IconButton(onClick = {
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

            // ── File name ────────────────────────────────────────────────────
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("File name") },
                suffix = { Text(".pdf") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ── Output folder ────────────────────────────────────────────────
            OutlinedTextField(
                value = outputFolderLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Save to folder") },
                trailingIcon = {
                    IconButton(onClick = {
                        folderPicker.launch(
                            // Start picker at Documents by default
                            "content://com.android.externalstorage.documents/tree/primary:Documents".toUri()
                        )
                    }) {
                        Icon(Icons.Default.Folder, "Choose folder")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // ── Page size ────────────────────────────────────────────────────
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

            // ── Image quality ────────────────────────────────────────────────
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

            // ── Export state ─────────────────────────────────────────────────
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
                    // Fall through to show Save button again below
                    exportButton(
                        fileName, selectedPageSize, jpegQuality, outputFolderUri, context, viewModel
                    )
                }

                is ExportState.Done -> {
                    // Show success message, then the Save button again (no special buttons —
                    // user just uses the back button to return to pages, or saves again)
                    Text(
                        "✓ Saved: ${state.file.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    exportButton(
                        fileName, selectedPageSize, jpegQuality, outputFolderUri, context, viewModel
                    )
                }

                else -> {
                    exportButton(
                        fileName, selectedPageSize, jpegQuality, outputFolderUri, context, viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun exportButton(
    fileName: String,
    selectedPageSize: PageSize,
    jpegQuality: Float,
    outputFolderUri: Uri?,
    context: android.content.Context,
    viewModel: ScannerViewModel
) {
    Button(
        onClick = {
            val safeName = fileName.ifBlank { "scan" }.trim() + ".pdf"

            // Resolve output directory:
            // If user picked a folder via SAF, convert its URI to a File path.
            // Otherwise fall back to public Documents folder.
            val outputDir = if (outputFolderUri != null) {
                safUriToFile(outputFolderUri, context)
                    ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            }

            viewModel.exportPdf(
                outputDir  = outputDir,
                fileName   = safeName,
                pageSize   = selectedPageSize,
                quality    = jpegQuality.toInt()
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

/**
 * Convert a SAF tree URI (from OpenDocumentTree) to a java.io.File.
 * Works for primary storage paths (internal storage).
 * Returns null for non-primary storage (SD card etc.) — PdfBuilder handles that gracefully.
 */
private fun safUriToFile(uri: Uri, context: android.content.Context): File? {
    val path = uri.lastPathSegment ?: return null
    val relativePath = path.substringAfter(':', "")
    if (relativePath.isEmpty()) return null
    return File(Environment.getExternalStorageDirectory(), relativePath)
}
