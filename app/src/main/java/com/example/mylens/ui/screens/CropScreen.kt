package com.example.mylens.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.mylens.data.CropRect
import com.example.mylens.utils.CropUtils
import com.example.mylens.utils.ImageUtils
import com.example.mylens.viewmodel.ScannerViewModel
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Full-screen crop/warp editor for a single page.
 *
 * Shows the image with four draggable corner handles overlaid.
 * The user can:
 *  - Drag any corner to define the perspective warp quad
 *  - Tap "Auto" to run OpenCV document detection
 *  - Tap ✓ to confirm and save the crop
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    pageId: String,
    onBack: () -> Unit,
    viewModel: ScannerViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = viewModel.pages.firstOrNull { it.id == pageId } ?: run { onBack(); return }

    // Corner positions in normalized 0..1 coordinates (relative to the image display area)
    var crop by remember {
        mutableStateOf(page.cropRect ?: CropRect())
    }
    var isAutoDetecting by remember { mutableStateOf(false) }
    // Size of the image display area in pixels, needed to convert drag offsets to normalized coords
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Which corner handle is being dragged (null = none)
    // 0=topLeft, 1=topRight, 2=bottomRight, 3=bottomLeft
    var draggingCorner by remember { mutableStateOf<Int?>(null) }

    val handleRadius = 22.dp
    val handleRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { handleRadius.toPx() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop & Warp") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Auto-detect button
                    IconButton(
                        onClick = {
                            scope.launch {
                                isAutoDetecting = true
                                val bitmap = ImageUtils.decodeUri(context, page.uri, 1024)
                                if (bitmap != null) {
                                    crop = CropUtils.detectDocumentCorners(bitmap)
                                    bitmap.recycle()
                                }
                                isAutoDetecting = false
                            }
                        },
                        enabled = !isAutoDetecting
                    ) {
                        Icon(Icons.Default.AutoFixHigh, "Auto-detect")
                    }
                    // Confirm button
                    IconButton(onClick = {
                        viewModel.setCrop(pageId, crop)
                        onBack()
                    }) {
                        Icon(Icons.Default.Check, "Confirm crop")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (isAutoDetecting) {
                CircularProgressIndicator()
            }

            // Image + overlay in a Box that fills available space
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
            ) {
                // The base image
                AsyncImage(
                    model = page.uri,
                    contentDescription = "Page to crop",
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay: quad outline + 4 corner handles
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(canvasSize) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    // Find which handle is closest to the touch point
                                    draggingCorner = closestCorner(
                                        offset, crop, canvasSize, handleRadiusPx * 2
                                    )
                                },
                                onDrag = { change, _ ->
                                    val corner = draggingCorner ?: return@detectDragGestures
                                    val nx = (change.position.x / canvasSize.width).coerceIn(0f, 1f)
                                    val ny = (change.position.y / canvasSize.height).coerceIn(0f, 1f)
                                    crop = when (corner) {
                                        0 -> crop.copy(topLeft     = Pair(nx, ny))
                                        1 -> crop.copy(topRight    = Pair(nx, ny))
                                        2 -> crop.copy(bottomRight = Pair(nx, ny))
                                        3 -> crop.copy(bottomLeft  = Pair(nx, ny))
                                        else -> crop
                                    }
                                },
                                onDragEnd = { draggingCorner = null }
                            )
                        }
                ) {
                    val w = size.width
                    val h = size.height

                    fun norm(p: Pair<Float, Float>) = Offset(p.first * w, p.second * h)

                    val tl = norm(crop.topLeft)
                    val tr = norm(crop.topRight)
                    val br = norm(crop.bottomRight)
                    val bl = norm(crop.bottomLeft)

                    // Semi-transparent quad fill
                    val quadPath = Path().apply {
                        moveTo(tl.x, tl.y)
                        lineTo(tr.x, tr.y)
                        lineTo(br.x, br.y)
                        lineTo(bl.x, bl.y)
                        close()
                    }
                    drawPath(quadPath, Color(0x3300AAFF))

                    // Quad outline
                    drawPath(quadPath, Color(0xFF0088FF), style = Stroke(width = 3.dp.toPx()))

                    // Corner handles
                    listOf(tl, tr, br, bl).forEachIndexed { i, pt ->
                        val isActive = draggingCorner == i
                        drawCircle(
                            color = if (isActive) Color(0xFFFFFFFF) else Color(0xFF0088FF),
                            radius = handleRadiusPx,
                            center = pt
                        )
                        drawCircle(
                            color = Color(0xFF0088FF),
                            radius = handleRadiusPx,
                            center = pt,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

/** Returns the index (0–3) of the corner closest to [touch], or null if none within threshold. */
private fun closestCorner(
    touch: Offset,
    crop: CropRect,
    size: IntSize,
    thresholdPx: Float
): Int? {
    val corners = listOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft)
    var bestIdx: Int? = null
    var bestDist = thresholdPx

    corners.forEachIndexed { i, (nx, ny) ->
        val dx = touch.x - nx * size.width
        val dy = touch.y - ny * size.height
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist < bestDist) {
            bestDist = dist
            bestIdx = i
        }
    }
    return bestIdx
}
