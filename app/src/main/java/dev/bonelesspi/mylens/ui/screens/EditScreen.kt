package dev.bonelesspi.mylens.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
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
import dev.bonelesspi.mylens.viewmodel.ScannerViewModel
import kotlinx.coroutines.launch
import kotlin.math.sqrt

// Represents what the user grabbed — a corner or a side midpoint
private sealed class DragTarget {
    data class Corner(val index: Int) : DragTarget()   // 0=TL, 1=TR, 2=BR, 3=BL
    data class Side(val index: Int) : DragTarget()     // 0=top, 1=right, 2=bottom, 3=left
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    pageId: String,
    onBack: () -> Unit,
    viewModel: ScannerViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = viewModel.pages.firstOrNull { it.id == pageId } ?: run { onBack(); return }

    var cropMode by remember { mutableStateOf(false) }
    var workingCrop by remember { mutableStateOf(CropRect()) }
    var isAutoDetecting by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var imageIntrinsicSize by remember { mutableStateOf<IntSize?>(null) }
    var dragTarget by remember { mutableStateOf<DragTarget?>(null) }

    val density = LocalDensity.current
    val cornerHitPx = with(density) { 44.dp.toPx() }
    val sideHitPx   = with(density) { 32.dp.toPx() }
    val drawRadiusPx = with(density) { 7.dp.toPx() }
    val armLengthPx  = with(density) { 18.dp.toPx() }
    val armWidthPx   = with(density) { 3.dp.toPx() }
    val sideDotRadiusPx = with(density) { 5.dp.toPx() }

    LaunchedEffect(pageId) { viewModel.ensureWorkingBitmap(pageId) }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    fun imageRect(): Pair<Offset, Size> {
        val intrinsic = imageIntrinsicSize
            ?: return Pair(Offset.Zero, Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()))
        val cw = canvasSize.width.toFloat()
        val ch = canvasSize.height.toFloat()
        val scale = minOf(cw / intrinsic.width, ch / intrinsic.height)
        val dw = intrinsic.width * scale
        val dh = intrinsic.height * scale
        return Pair(Offset((cw - dw) / 2f, (ch - dh) / 2f), Size(dw, dh))
    }

    fun normToCanvas(nx: Float, ny: Float): Offset {
        val (origin, size) = imageRect()
        return Offset(origin.x + nx * size.width, origin.y + ny * size.height)
    }

    fun canvasToNorm(x: Float, y: Float): Pair<Float, Float> {
        val (origin, size) = imageRect()
        return Pair(
            ((x - origin.x) / size.width).coerceIn(0f, 1f),
            ((y - origin.y) / size.height).coerceIn(0f, 1f)
        )
    }

    // Midpoint of a side in canvas space
    fun sideMidpoint(crop: CropRect, sideIndex: Int): Offset {
        val tl = normToCanvas(crop.topLeft.first,     crop.topLeft.second)
        val tr = normToCanvas(crop.topRight.first,    crop.topRight.second)
        val br = normToCanvas(crop.bottomRight.first, crop.bottomRight.second)
        val bl = normToCanvas(crop.bottomLeft.first,  crop.bottomLeft.second)
        return when (sideIndex) {
            0 -> Offset((tl.x + tr.x) / 2f, (tl.y + tr.y) / 2f)    // top
            1 -> Offset((tr.x + br.x) / 2f, (tr.y + br.y) / 2f)    // right
            2 -> Offset((br.x + bl.x) / 2f, (br.y + bl.y) / 2f)    // bottom
            3 -> Offset((bl.x + tl.x) / 2f, (bl.y + tl.y) / 2f)    // left
            else -> Offset.Zero
        }
    }

    // Find the closest draggable target (corner takes priority over side)
    fun findDragTarget(touch: Offset, crop: CropRect): DragTarget? {
        val corners = listOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft)

        // Check corners first
        var bestCorner: Int? = null
        var bestCornerDist = cornerHitPx
        corners.forEachIndexed { i, (nx, ny) ->
            val pt = normToCanvas(nx, ny)
            val d = dist(touch, pt)
            if (d < bestCornerDist) { bestCornerDist = d; bestCorner = i }
        }
        if (bestCorner != null) return DragTarget.Corner(bestCorner!!)

        // Then check side midpoints
        var bestSide: Int? = null
        var bestSideDist = sideHitPx
        for (i in 0..3) {
            val mid = sideMidpoint(crop, i)
            val d = dist(touch, mid)
            if (d < bestSideDist) { bestSideDist = d; bestSide = i }
        }
        if (bestSide != null) return DragTarget.Side(bestSide!!)

        return null
    }

    // Apply a drag delta to the working crop based on what's being dragged
    fun applyDrag(target: DragTarget, canvasX: Float, canvasY: Float, crop: CropRect): CropRect {
        val (nx, ny) = canvasToNorm(canvasX, canvasY)
        return when (target) {
            is DragTarget.Corner -> when (target.index) {
                0 -> crop.copy(topLeft     = Pair(nx, ny))
                1 -> crop.copy(topRight    = Pair(nx, ny))
                2 -> crop.copy(bottomRight = Pair(nx, ny))
                3 -> crop.copy(bottomLeft  = Pair(nx, ny))
                else -> crop
            }
            is DragTarget.Side -> {
                // For a side drag, compute how far the user moved in normalized space
                // relative to the current midpoint, then shift both attached corners by that delta.
                val mid = sideMidpoint(crop, target.index)
                val (midNx, midNy) = canvasToNorm(mid.x, mid.y)
                val dNx = nx - midNx
                val dNy = ny - midNy
                when (target.index) {
                    0 -> crop.copy(  // top: move TL and TR
                        topLeft  = Pair((crop.topLeft.first  + dNx).coerceIn(0f, 1f),
                                        (crop.topLeft.second + dNy).coerceIn(0f, 1f)),
                        topRight = Pair((crop.topRight.first  + dNx).coerceIn(0f, 1f),
                                        (crop.topRight.second + dNy).coerceIn(0f, 1f))
                    )
                    1 -> crop.copy(  // right: move TR and BR
                        topRight    = Pair((crop.topRight.first    + dNx).coerceIn(0f, 1f),
                                           (crop.topRight.second   + dNy).coerceIn(0f, 1f)),
                        bottomRight = Pair((crop.bottomRight.first  + dNx).coerceIn(0f, 1f),
                                           (crop.bottomRight.second + dNy).coerceIn(0f, 1f))
                    )
                    2 -> crop.copy(  // bottom: move BR and BL
                        bottomRight = Pair((crop.bottomRight.first  + dNx).coerceIn(0f, 1f),
                                           (crop.bottomRight.second + dNy).coerceIn(0f, 1f)),
                        bottomLeft  = Pair((crop.bottomLeft.first   + dNx).coerceIn(0f, 1f),
                                           (crop.bottomLeft.second  + dNy).coerceIn(0f, 1f))
                    )
                    3 -> crop.copy(  // left: move BL and TL
                        bottomLeft = Pair((crop.bottomLeft.first  + dNx).coerceIn(0f, 1f),
                                          (crop.bottomLeft.second + dNy).coerceIn(0f, 1f)),
                        topLeft    = Pair((crop.topLeft.first  + dNx).coerceIn(0f, 1f),
                                          (crop.topLeft.second + dNy).coerceIn(0f, 1f))
                    )
                    else -> crop
                }
            }
        }
    }

    // ── Reset confirmation dialog ─────────────────────────────────────────────

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset to original?") },
            text = { Text("All rotations and crops applied to this page will be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetPage(pageId)
                    showResetConfirm = false
                    cropMode = false
                    workingCrop = CropRect()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (cropMode) "Crop & Warp" else "Edit Page") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (cropMode) { workingCrop = CropRect(); cropMode = false }
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            if (cropMode) "Cancel crop" else "Back")
                    }
                },
                actions = {
                    if (!cropMode) {
                        IconButton(onClick = { showResetConfirm = true }) {
                            Icon(Icons.Default.Refresh, "Reset to original")
                        }
                        TextButton(onClick = onBack) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Done")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = cropMode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "bottom_bar"
                ) { inCropMode ->
                    if (inCropMode) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isAutoDetecting = true
                                        val bitmap = page.workingBitmap
                                        if (bitmap != null) {
                                            workingCrop = CropUtils.detectDocumentCorners(bitmap)
                                        }
                                        isAutoDetecting = false
                                    }
                                },
                                enabled = !isAutoDetecting && page.workingBitmap != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Auto-detect edges")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { workingCrop = CropRect(); cropMode = false },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Cancel") }
                                Button(
                                    onClick = {
                                        viewModel.applyWarp(pageId, workingCrop)
                                        workingCrop = CropRect()
                                        cropMode = false
                                    },
                                    enabled = page.workingBitmap != null,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Apply") }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.applyRotate(pageId) },
                                enabled = page.workingBitmap != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Rotate90DegreesCw, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Rotate 90°")
                            }
                            OutlinedButton(
                                onClick = { workingCrop = CropRect(); cropMode = true },
                                enabled = page.workingBitmap != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Crop, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Crop & Warp")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { canvasSize = it },
            contentAlignment = Alignment.Center
        ) {
            if (isAutoDetecting) CircularProgressIndicator()

            val bitmap = page.workingBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged {
                            imageIntrinsicSize = IntSize(bitmap.width, bitmap.height)
                        }
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(page.uri)
                        .allowHardware(false)
                        .listener { _, result ->
                            imageIntrinsicSize = IntSize(
                                result.drawable.intrinsicWidth,
                                result.drawable.intrinsicHeight
                            )
                        }
                        .build(),
                    contentDescription = "Page preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            if (cropMode) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(canvasSize, imageIntrinsicSize) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    dragTarget = findDragTarget(offset, workingCrop)
                                },
                                onDrag = { change, _ ->
                                    val target = dragTarget ?: return@detectDragGestures
                                    workingCrop = applyDrag(target, change.position.x, change.position.y, workingCrop)
                                },
                                onDragEnd = { dragTarget = null }
                            )
                        }
                ) {
                    val tl = normToCanvas(workingCrop.topLeft.first,     workingCrop.topLeft.second)
                    val tr = normToCanvas(workingCrop.topRight.first,    workingCrop.topRight.second)
                    val br = normToCanvas(workingCrop.bottomRight.first, workingCrop.bottomRight.second)
                    val bl = normToCanvas(workingCrop.bottomLeft.first,  workingCrop.bottomLeft.second)

                    val quadPath = Path().apply {
                        moveTo(tl.x, tl.y); lineTo(tr.x, tr.y)
                        lineTo(br.x, br.y); lineTo(bl.x, bl.y)
                        close()
                    }
                    drawPath(quadPath, Color(0x2200AAFF))
                    drawPath(quadPath, Color(0xFF0088FF), style = Stroke(width = 2.dp.toPx()))

                    // Corner handles
                    listOf(tl, tr, br, bl).forEachIndexed { i, pt ->
                        val isActive = dragTarget is DragTarget.Corner && (dragTarget as DragTarget.Corner).index == i
                        val color = if (isActive) Color.White else Color(0xFF00AAFF)
                        val xDir = if (i == 0 || i == 3) 1f else -1f
                        val yDir = if (i == 0 || i == 1) 1f else -1f
                        drawLine(color, pt, Offset(pt.x + xDir * armLengthPx, pt.y), armWidthPx)
                        drawLine(color, pt, Offset(pt.x, pt.y + yDir * armLengthPx), armWidthPx)
                        drawCircle(color, drawRadiusPx, pt)
                    }

                    // Side midpoint handles — small filled circles
                    for (i in 0..3) {
                        val mid = sideMidpoint(workingCrop, i)
                        val isActive = dragTarget is DragTarget.Side && (dragTarget as DragTarget.Side).index == i
                        val color = if (isActive) Color.White else Color(0xBB00AAFF)
                        drawCircle(color, sideDotRadiusPx, mid)
                        drawCircle(Color(0xFF00AAFF), sideDotRadiusPx, mid, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
        }
    }
}

private fun dist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
