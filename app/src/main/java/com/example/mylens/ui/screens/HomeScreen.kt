package com.example.mylens.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mylens.ui.components.PageThumbnail
import com.example.mylens.viewmodel.ScannerViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToCamera: () -> Unit,
    onNavigateToExport: () -> Unit,
    viewModel: ScannerViewModel = viewModel()
) {
    // Multi-image picker (Photo Picker API — no permissions needed on API 33+)
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addPages(uris)
    }

    // Reorderable list state
    val reorderableState = rememberReorderableLazyListState(
        onMove = { from, to -> viewModel.movePage(from.index, to.index) }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MyLens") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Camera FAB
                SmallFloatingActionButton(onClick = onNavigateToCamera) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Take photo")
                }
                // Gallery picker FAB
                SmallFloatingActionButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add from gallery")
                }
                // Export FAB (only shown when there are pages)
                if (viewModel.pages.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = onNavigateToExport,
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Export PDF")
                    }
                }
            }
        }
    ) { padding ->
        if (viewModel.pages.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Text(
                        "No pages yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        "Tap + to add images or use the camera",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = reorderableState.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = viewModel.pages,
                    key = { it.id }
                ) { page ->
                    ReorderableItem(reorderableState, key = page.id) { isDragging ->
                        PageThumbnail(
                            page = page,
                            pageNumber = viewModel.pages.indexOf(page) + 1,
                            onRotate = { viewModel.rotatePage(page.id) },
                            onDelete = { viewModel.removePage(page.id) },
                            isDragging = isDragging,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Long-press drag handle on the whole card
                                .longPressDraggableHandle()
                        )
                    }
                }
                // Bottom padding for FAB
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}
