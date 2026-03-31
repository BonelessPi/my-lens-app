package dev.bonelesspi.mylens.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReorderableCollectionItemScope.PageCard(
    page: ScanPage,
    pageNumber: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 10.dp else 2.dp
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Drag grip zone ────────────────────────────────────────────────
            // draggableHandle() here means drag starts immediately on touch of this
            // zone — no long press required. The grip icon signals the affordance.
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .fillMaxHeight()
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
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                // Use the pre-scaled thumbnail for display — workingBitmap is full
                // resolution and would cause render thread stutter in a scrolling list
                val bitmap = page.thumbnailBitmap
                if (bitmap != null) {
                    // remember keyed on bitmap instance to avoid creating a new GPU texture
                    // upload on every recomposition
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

            Spacer(Modifier.weight(1f))

            // ── Actions ───────────────────────────────────────────────────────
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit page",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove page",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
