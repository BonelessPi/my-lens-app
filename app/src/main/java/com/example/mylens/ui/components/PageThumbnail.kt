package com.example.mylens.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.mylens.data.ScanPage

@Composable
fun PageThumbnail(
    page: ScanPage,
    pageNumber: Int,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 12.dp else 2.dp
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Page number badge
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$pageNumber",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.width(8.dp))

            // Thumbnail — tap to open crop editor
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
            ) {
                AsyncImage(
                    model = page.uri,
                    contentDescription = "Page $pageNumber",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(page.rotation.toFloat())
                )
                // Tap overlay to open crop screen
                Surface(
                    onClick = onEdit,
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {}
            }

            Spacer(Modifier.width(8.dp))

            // Action buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(onClick = onRotate) {
                    Icon(
                        imageVector = Icons.Default.Rotate90DegreesCw,
                        contentDescription = "Rotate 90°",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Crop,
                        contentDescription = "Crop / warp",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
