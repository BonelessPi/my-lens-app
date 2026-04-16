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

// Thumbnail aspect ratio is clamped to this range to prevent extremely
// tall or wide cards when the source image has an unusual aspect ratio.
private const val MIN_ASPECT = 3f / 4f  // tallest allowed (portrait)
private const val MAX_ASPECT = 4f / 3f  // widest allowed (landscape)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReorderableCollectionItemScope.PageCard(
    page: ScanPage,
    pageNumber: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Determine thumbnail aspect ratio from the source image dimensions,
    // falling back to 3:4 (portrait document) if not yet loaded.
    val thumbnailAspect = if (page.originalWidth > 0 && page.originalHeight > 0) {
        (page.originalWidth.toFloat() / page.originalHeight.toFloat())
            .coerceIn(MIN_ASPECT, MAX_ASPECT)
    } else {
        MIN_ASPECT  // default portrait until dimensions are available
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
            // ── Drag handle + page number ─────────────────────────────────────
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .draggableHandle(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$pageNumber",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ── Thumbnail ─────────────────────────────────────────────────────
            // Height is fixed; width derives from the aspect ratio so the
            // thumbnail always shows the full image without cropping.
            val thumbHeight = 100.dp
            val thumbWidth = thumbHeight * thumbnailAspect

            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .height(thumbHeight)
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
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = page.uri,
                        contentDescription = "Page $pageNumber thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ── Metadata ──────────────────────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Original dimensions — shown once loaded
                if (page.originalWidth > 0 && page.originalHeight > 0) {
                    Text(
                        text = "${page.originalWidth} × ${page.originalHeight}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Edited chip — only shown when at least one action has been applied
                if (page.actions.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.wrapContentSize()
                    ) {
                        Text(
                            text = "Edited",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}
