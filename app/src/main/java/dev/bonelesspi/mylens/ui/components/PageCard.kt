package dev.bonelesspi.mylens.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.bonelesspi.mylens.data.ScanPage
import sh.calvin.reorderable.ReorderableCollectionItemScope

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ReorderableCollectionItemScope.PageCard(
    page: ScanPage,
    pageNumber: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Prefer the edited preview bitmap dimensions (reflects crops/rotations), then to a portrait default.
    val (displayWidth, displayHeight) = when {
        page.previewBitmap != null ->
            Pair(page.previewBitmap.width, page.previewBitmap.height)
        else -> Pair(0, 0)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 10.dp else 2.dp
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Page number + drag handle ─────────────────────────────────────
            // Number on top, handle below — both centered in a fixed-width column.
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .draggableHandle(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "$pageNumber",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Thumbnail ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = page.thumbnailBitmap
                if (bitmap != null) {
                    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Page $pageNumber thumbnail",
                        contentScale = ContentScale.Fit,  // Fit, not Crop — preserves full image
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = page.uri,
                        contentDescription = "Page $pageNumber thumbnail",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ── Metadata ──────────────────────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Dimensions — show post-edit size when available (from previewBitmap),
                // Hidden until at least one dimension is known.
                if (displayWidth > 0 && displayHeight > 0) {
                    val dimLabel = if (page.previewBitmap != null && page.actions.isNotEmpty())
                        "$displayWidth × $displayHeight (edited)"
                    else
                        "$displayWidth × $displayHeight"
                    Text(
                        text = dimLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Chip row — only chips that are relevant are shown
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Number of edits applied
                    if (page.actions.isNotEmpty()) {
                        val editLabel = if (page.actions.size == 1) "1 edit" else "${page.actions.size} edits"
                        PageChip(
                            text = editLabel,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    // Per-page export resolution override
                    if (page.exportResolution != null) {
                        PageChip(
                            text = "${page.exportResolution}px",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    // Per-page JPEG quality override
                    if (page.jpegQuality != null) {
                        PageChip(
                            text = "Q${page.jpegQuality}%",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageChip(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        modifier = Modifier.wrapContentSize()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
