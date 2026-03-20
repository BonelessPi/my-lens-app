package dev.bonelesspi.mylens.ui.screens

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.bonelesspi.mylens.data.CropRect
import dev.bonelesspi.mylens.utils.CropUtils
import dev.bonelesspi.mylens.utils.ImageUtils
import kotlinx.coroutines.launch
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    pageId: String,
    onBack: () -> Unit,
    viewModel: dev.bonelesspi.mylens.viewmodel.ScannerViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = viewModel.pages.firstOrNull { it.id == pageId } ?: run { onBack(); return }

    var crop by remember { mutableStateOf(page.cropRect ?: CropRect()) }
    var isAutoDetecting by remember { mutableStateOf(false) }

    // Full canvas size (the Box containing image + overlay)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Intrinsic image size in pixels, populated once Coil loads the image
    var imageIntrinsicSize by remember { mutableStateOf<IntSize?>(null) }

    var draggingCorner by remember { mutableStateOf<Int?>(null) }

    val density = LocalDensity.current
    // Hit target radius (large, for easy touch) — kept as-is per request
    val hitRadiusPx = with(density) { 44.dp.toPx() }
    // Visual radius (small dot drawn on screen)
    val drawRadiusPx = with(density) { 8.dp.toPx() }
    // L-shaped arm length for the corner indicator
    val armLengthPx = with(density) { 16.dp.toPx() }
    val armWidthPx = with(density) { 3.dp.toPx() }

    // Compute the rect within the canvas that the image actually occupies (ContentScale.Fit)
    // Returns Offset(left, top) and Size(width, height) of the image area in canvas pixels.
    fun imageRect(): Pair<Offset, Size> {
        val intrinsic = imageIntrinsicSize ?: return Pair(Offset.Zero, Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()))
        val cw = canvasSize.width.toFloat()
        val ch = canvasSize.height.toFloat()
        val iw = intrinsic.width.toFloat()
        val ih = intrinsic.height.toFloat()
        val scale = minOf(cw / iw, ch / ih)
        val displayW = iw * scale
        val displayH = ih * scale
        val left = (cw - displayW) / 2f
        val top = (ch - displayH) / 2f
        return Pair(Offset(left, top), Size(displayW, displayH))
    }

    // Convert normalized crop coord (0..1 relative to image) → canvas pixel offset
    fun normToCanvas(nx: Float, ny: Float): Offset {
        val (origin, size) = imageRect()
        return Offset(origin.x + nx * size.width, origin.y + ny * size.height)
    }

    // Convert canvas pixel offset → normalized crop coord (0..1 relative to image)
    fun canvasToNorm(x: Float, y: Float): Pair<Float, Float> {
        val (origin, size) = imageRect()
        val nx = ((x - origin.x) / size.width).coerceIn(0f, 1f)
        val ny = ((y - origin.y) / size.height).coerceIn(0f, 1f)
        return Pair(nx, ny)
    }

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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
            ) {
                // Load image and capture its intrinsic size
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(page.uri)
                        .allowHardware(false)
                        .listener { _, result ->
                            val d = result.drawable
                            imageIntrinsicSize = IntSize(d.intrinsicWidth, d.intrinsicHeight)
                        }
                        .build(),
                    contentDescription = "Page to crop",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay canvas — handles touch and draws the quad + corners
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(canvasSize, imageIntrinsicSize) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    draggingCorner =
                                        closestCorner(
                                            touch = offset,
                                            crop = crop,
                                            toCanvas = ::normToCanvas,
                                            thresholdPx = hitRadiusPx
                                        )
                                },
                                onDrag = { change, _ ->
                                    val corner = draggingCorner ?: return@detectDragGestures
                                    val (nx, ny) = canvasToNorm(change.position.x, change.position.y)
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
                    val tl = normToCanvas(crop.topLeft.first,     crop.topLeft.second)
                    val tr = normToCanvas(crop.topRight.first,    crop.topRight.second)
                    val br = normToCanvas(crop.bottomRight.first, crop.bottomRight.second)
                    val bl = normToCanvas(crop.bottomLeft.first,  crop.bottomLeft.second)

                    // Semi-transparent quad fill
                    val quadPath = Path().apply {
                        moveTo(tl.x, tl.y); lineTo(tr.x, tr.y)
                        lineTo(br.x, br.y); lineTo(bl.x, bl.y)
                        close()
                    }
                    drawPath(quadPath, Color(0x2200AAFF))
                    drawPath(quadPath, Color(0xFF0088FF), style = Stroke(width = 2.dp.toPx()))

                    // Corner handles — small L-shaped brackets instead of large circles
                    listOf(tl, tr, br, bl).forEachIndexed { i, pt ->
                        val isActive = draggingCorner == i
                        val color = if (isActive) Color.White else Color(0xFF00AAFF)

                        // Determine arm directions based on which corner this is
                        val xDir = if (i == 0 || i == 3) 1f else -1f  // tl,bl go right; tr,br go left
                        val yDir = if (i == 0 || i == 1) 1f else -1f  // tl,tr go down; br,bl go up

                        // Horizontal arm
                        drawLine(
                            color = color,
                            start = pt,
                            end = Offset(pt.x + xDir * armLengthPx, pt.y),
                            strokeWidth = armWidthPx
                        )
                        // Vertical arm
                        drawLine(
                            color = color,
                            start = pt,
                            end = Offset(pt.x, pt.y + yDir * armLengthPx),
                            strokeWidth = armWidthPx
                        )
                        // Small dot at the corner point
                        drawCircle(color = color, radius = drawRadiusPx, center = pt)
                    }
                }
            }
        }
    }
}

private fun closestCorner(
    touch: Offset,
    crop: CropRect,
    toCanvas: (Float, Float) -> Offset,
    thresholdPx: Float
): Int? {
    val corners = listOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft)
    var bestIdx: Int? = null
    var bestDist = thresholdPx

    corners.forEachIndexed { i, (nx, ny) ->
        val pt = toCanvas(nx, ny)
        val dx = touch.x - pt.x
        val dy = touch.y - pt.y
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist < bestDist) {
            bestDist = dist
            bestIdx = i
        }
    }
    return bestIdx
}
