package dev.bonelesspi.mylens.ui.screens

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bonelesspi.mylens.ui.components.PageCard
import dev.bonelesspi.mylens.viewmodel.ExportState
import dev.bonelesspi.mylens.viewmodel.ScannerViewModel
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SelectScreen(
    onNavigateToCamera: () -> Unit,
    onNavigateToEdit: (pageId: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ScannerViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()

    // ── Photo picker ──────────────────────────────────────────────────────────
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) viewModel.addPages(uris) }

    // ── Folder picker (SAF) ───────────────────────────────────────────────────
    var outputFolderUri by remember { mutableStateOf<Uri?>(null) }
    val outputFolderLabel by remember(outputFolderUri) {
        derivedStateOf {
            outputFolderUri?.lastPathSegment?.substringAfterLast(':') ?: "Documents"
        }
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            outputFolderUri = uri
        }
    }

    // ── Reorderable list ──────────────────────────────────────────────────────
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.movePage(from.index, to.index)
    }

    // ── Bottom sheet state ────────────────────────────────────────────────────
    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true
        )
    )

    // ── Export settings state ─────────────────────────────────────────────────
    var fileName by remember { mutableStateOf("scan") }
    var selectedPageSize by remember { mutableStateOf(PageSize.A4) }
    var jpegQuality by remember { mutableFloatStateOf(90f) }

    // ── Clear all dialog ──────────────────────────────────────────────────────
    var showClearConfirm by remember { mutableStateOf(false) }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all pages?") },
            text = { Text("All ${viewModel.pages.size} pages will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearConfirm = false
                }) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // ── Long-press delete dialog ──────────────────────────────────────────────
    var pageIdPendingDelete by remember { mutableStateOf<String?>(null) }
    if (pageIdPendingDelete != null) {
        val pageNumber = viewModel.pages.indexOfFirst { it.id == pageIdPendingDelete } + 1
        AlertDialog(
            onDismissRequest = { pageIdPendingDelete = null },
            title = { Text("Remove page $pageNumber?") },
            text = { Text("This page will be removed from the document.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removePage(pageIdPendingDelete!!)
                    pageIdPendingDelete = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pageIdPendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = 64.dp,
        sheetDragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Box(Modifier.size(width = 32.dp, height = 4.dp))
            }
        },
        sheetContent = {
            ExportSheetContent(
                exportState       = exportState,
                fileName          = fileName,
                onFileNameChange  = { fileName = it },
                selectedPageSize  = selectedPageSize,
                onPageSizeChange  = { selectedPageSize = it },
                jpegQuality       = jpegQuality,
                onQualityChange   = { jpegQuality = it },
                outputFolderLabel = outputFolderLabel,
                onChooseFolder    = {
                    folderPicker.launch(
                        "content://com.android.externalstorage.documents/tree/primary:Documents".toUri()
                    )
                },
                pageCount         = viewModel.pages.size,
                onExport          = {
                    val safeName = fileName.ifBlank { "scan" }.trim() + ".pdf"
                    val outputDir = outputFolderUri
                        ?.let { safUriToFile(it) }
                        ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    viewModel.exportPdf(
                        outputDir = outputDir,
                        fileName  = safeName,
                        pageSize  = selectedPageSize,
                        quality   = jpegQuality.toInt()
                    )
                }
            )
        },
        topBar = {
            TopAppBar(
                title = { Text("MyLens") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    if (viewModel.pages.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Clear all pages",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(
                        onClick = { scope.launch { sheetState.bottomSheetState.expand() } },
                        enabled = viewModel.pages.isNotEmpty()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Export",
                            tint = if (viewModel.pages.isNotEmpty())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            )
        },
    ) { padding ->
        val fabBottomPadding = 16.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (viewModel.pages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            "No pages yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            "Use the + button to add photos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = fabBottomPadding + 80.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = viewModel.pages, key = { it.id }) { page ->
                        ReorderableItem(reorderableState, key = page.id) { isDragging ->
                            PageCard(
                                page        = page,
                                pageNumber  = viewModel.pages.indexOf(page) + 1,
                                onTap       = { onNavigateToEdit(page.id) },
                                onLongPress = { pageIdPendingDelete = page.id },
                                isDragging  = isDragging,
                                modifier    = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = 16.dp, end = 16.dp, bottom = fabBottomPadding),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToCamera,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Camera")
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Photos")
                }
            }
        }
    }
}

// ── Export sheet content ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheetContent(
    exportState: ExportState,
    fileName: String,
    onFileNameChange: (String) -> Unit,
    selectedPageSize: PageSize,
    onPageSizeChange: (PageSize) -> Unit,
    jpegQuality: Float,
    onQualityChange: (Float) -> Unit,
    outputFolderLabel: String,
    onChooseFolder: () -> Unit,
    pageCount: Int,
    onExport: () -> Unit
) {
    var pageSizeMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            "$pageCount page${if (pageCount != 1) "s" else ""} ready to export",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = fileName,
            onValueChange = onFileNameChange,
            label = { Text("File name") },
            suffix = { Text(".pdf") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = outputFolderLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Save to folder") },
            trailingIcon = {
                IconButton(onClick = onChooseFolder) {
                    Icon(Icons.Default.Folder, "Choose folder")
                }
            },
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
                    .menuAnchor(MenuAnchorType.PrimaryEditable, true)
            )
            ExposedDropdownMenu(
                expanded = pageSizeMenuExpanded,
                onDismissRequest = { pageSizeMenuExpanded = false }
            ) {
                PageSize.entries.forEach { size ->
                    DropdownMenuItem(
                        text = { Text(size.label) },
                        onClick = { onPageSizeChange(size); pageSizeMenuExpanded = false }
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
                onValueChange = onQualityChange,
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

        when (exportState) {
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
                    "Export failed: ${exportState.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                SaveButton(pageCount = pageCount, onClick = onExport)
            }
            is ExportState.Done -> {
                Text(
                    "✓ Saved: ${exportState.file.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                SaveButton(pageCount = pageCount, onClick = onExport)
            }
            else -> {
                SaveButton(pageCount = pageCount, onClick = onExport)
            }
        }
    }
}

@Composable
private fun SaveButton(pageCount: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = pageCount > 0
    ) {
        Text("Save PDF", style = MaterialTheme.typography.titleMedium)
    }
}

private fun safUriToFile(uri: Uri): File? {
    val path = uri.lastPathSegment ?: return null
    val relativePath = path.substringAfter(':', "")
    if (relativePath.isEmpty()) return null
    return File(Environment.getExternalStorageDirectory(), relativePath)
}
