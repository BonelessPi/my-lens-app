package dev.bonelesspi.mylens.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bonelesspi.mylens.ui.components.PageThumbnail
import dev.bonelesspi.mylens.viewmodel.ScannerViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToCamera: () -> Unit,
    onNavigateToExport: () -> Unit,
    onNavigateToCrop: (pageId: String) -> Unit,
    viewModel: ScannerViewModel = viewModel()
) {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addPages(uris)
    }

    // FIX: create LazyListState separately and pass it to both LazyColumn and rememberReorderableLazyListState
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.movePage(from.index, to.index)
    }

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
                // Camera button
                SmallFloatingActionButton(onClick = onNavigateToCamera) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Take photo")
                }
                // Gallery picker button
                SmallFloatingActionButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add from gallery")
                }
                // Export button — only shown when there are pages
                if (viewModel.pages.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = onNavigateToExport,
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.Default.Description, contentDescription = "Export PDF")
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
                        Icons.Default.Description,
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
            // FIX: pass lazyListState directly to LazyColumn instead of reorderableState.listState
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = viewModel.pages, key = { it.id }) { page ->
                    ReorderableItem(reorderableState, key = page.id) { isDragging ->
                        PageThumbnail(
                            page = page,
                            pageNumber = viewModel.pages.indexOf(page) + 1,
                            onRotate = { viewModel.rotatePage(page.id) },
                            onDelete = { viewModel.removePage(page.id) },
                            onEdit   = { onNavigateToCrop(page.id) },
                            isDragging = isDragging,
                            // FIX: longPressDraggableHandle is experimental — opt in explicitly
                            modifier = Modifier
                                .fillMaxWidth()
                                .longPressDraggableHandle()
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}
