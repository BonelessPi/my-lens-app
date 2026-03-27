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
import kotlin.math.abs
import kotlin.math.sqrt

// What the user is currently dragging
private sealed class DragTarget {
    data class Corner(val index: Int) : DragTarget()  // 0=TL, 1=TR, 2=BR, 3=BL
    data class Side(val index: Int) : DragTarget()    // 0=top, 1=right, 2=bottom, 3=left
    object Body : DragTarget()                        // dragging the whole quad
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
    // Last touch position in normalized space, used to compute body drag deltas
    var lastDragNorm by remember { mutableStateOf(Pair(0f, 0f)) }

    val density = LocalDensity.current
    val cornerHitPx    = with(density) { 44.dp.toPx() }
    val sideHitPx      = with(density) { 28.dp.toPx() }
    val drawRadiusPx   = with(density) { 7.dp.toPx() }
    val armLengthPx    = with(density) { 18.dp.toPx() }
    val armWidthPx     = with(density) { 3.dp.toPx() }
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

    fun sideMidpoint(crop: CropRect, sideIndex: Int): Offset {
        val tl = normToCanvas(crop.topLeft.first,     crop.topLeft.second)
        val tr = normToCanvas(crop.topRight.first,    crop.topRight.second)
        val br = normToCanvas(crop.bottomRight.first, crop.bottomRight.second)
        val bl = normToCanvas(crop.bottomLeft.first,  crop.bottomLeft.second)
        return when (sideIndex) {
            0 -> Offset((tl.x + tr.x) / 2f, (tl.y + tr.y) / 2f)
            1 -> Offset((tr.x + br.x) / 2f, (tr.y + br.y) / 2f)
            2 -> Offset((br.x + bl.x) / 2f, (br.y + bl.y) / 2f)
            3 -> Offset((bl.x + tl.x) / 2f, (bl.y + tl.y) / 2f)
            else -> Offset.Zero
        }
    }

    // ── Hit testing ───────────────────────────────────────────────────────────

    fun findDragTarget(touch: Offset, crop: CropRect): DragTarget {
        val corners = listOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft)

        // Corners first (highest priority)
        var bestCorner: Int? = null
        var bestCornerDist = cornerHitPx
        corners.forEachIndexed { i, (nx, ny) ->
            val d = dist(touch, normToCanvas(nx, ny))
            if (d < bestCornerDist) { bestCornerDist = d; bestCorner = i }
        }
        if (bestCorner != null) return DragTarget.Corner(bestCorner!!)

        // Side midpoints
        var bestSide: Int? = null
        var bestSideDist = sideHitPx
        for (i in 0..3) {
            val d = dist(touch, sideMidpoint(crop, i))
            if (d < bestSideDist) { bestSideDist = d; bestSide = i }
        }
        if (bestSide != null) return DragTarget.Side(bestSide!!)

        // Body — check if touch is inside the quad using cross-product winding
        val tl = normToCanvas(crop.topLeft.first,     crop.topLeft.second)
        val tr = normToCanvas(crop.topRight.first,    crop.topRight.second)
        val br = normToCanvas(crop.bottomRight.first, crop.bottomRight.second)
        val bl = normToCanvas(crop.bottomLeft.first,  crop.bottomLeft.second)
        if (pointInQuad(touch, tl, tr, br, bl)) return DragTarget.Body

        // Nothing hit — still return Body so a missed touch near the quad pans it
        // rather than doing nothing
        return DragTarget.Body
    }

    // ── Drag application ─────────────────────────────────────────────────────

    fun applyCornerDrag(index: Int, canvasX: Float, canvasY: Float, crop: CropRect): CropRect {
        val (nx, ny) = canvasToNorm(canvasX, canvasY)
        return when (index) {
            0 -> crop.copy(topLeft     = Pair(nx, ny))
            1 -> crop.copy(topRight    = Pair(nx, ny))
            2 -> crop.copy(bottomRight = Pair(nx, ny))
            3 -> crop.copy(bottomLeft  = Pair(nx, ny))
            else -> crop
        }
    }

    /**
     * Rail-constrained side drag.
     *
     * For a given side, each of its two corners must stay on its adjacent rail edge.
     * The rail for a corner is the line defined by that corner and its non-shared neighbour.
     *
     * Example: top side (TL and TR).
     *   - TL's rail is the left edge, direction = BL - TL
     *   - TR's rail is the right edge, direction = BR - TR
     *
     * We compute how far to push each corner along its rail so that the component of
     * motion perpendicular to the dragged side matches the user's drag.
     *
     * Bounds handling: compute the unclamped t for each corner. Find the maximum t
     * both corners can move while staying in [0,1]². Apply the smaller t to both,
     * preserving the angle relationship.
     */
    fun applySideDrag(
        sideIndex: Int,
        dragDeltaNx: Float,
        dragDeltaNy: Float,
        crop: CropRect
    ): CropRect {
        // Identify the two corners and their rails for this side
        // Each entry: (corner normalized pos, rail direction normalized)
        val (c0, c1, rail0, rail1) = when (sideIndex) {
            0 -> SideData(  // top: TL and TR, rails = left edge and right edge
                crop.topLeft,    crop.topRight,
                vecSub(crop.bottomLeft,  crop.topLeft),
                vecSub(crop.bottomRight, crop.topRight)
            )
            1 -> SideData(  // right: TR and BR, rails = top edge and bottom edge
                crop.topRight,    crop.bottomRight,
                vecSub(crop.topLeft,    crop.topRight),
                vecSub(crop.bottomLeft, crop.bottomRight)
            )
            2 -> SideData(  // bottom: BR and BL, rails = right edge and left edge
                crop.bottomRight, crop.bottomLeft,
                vecSub(crop.topRight, crop.bottomRight),
                vecSub(crop.topLeft,  crop.bottomLeft)
            )
            3 -> SideData(  // left: BL and TL, rails = bottom edge and top edge
                crop.bottomLeft, crop.topLeft,
                vecSub(crop.bottomRight, crop.bottomLeft),
                vecSub(crop.topRight,    crop.topLeft)
            )
            else -> return crop
        }

        val drag = Pair(dragDeltaNx, dragDeltaNy)

        // Project drag onto each corner's rail to find parameter t
        // t is the scalar along the rail direction that achieves the drag displacement
        val t0 = projectDragOntoRail(drag, rail0)
        val t1 = projectDragOntoRail(drag, rail1)

        // If both projections are degenerate (rail perpendicular to drag), nothing moves
        if (t0 == null && t1 == null) return crop

        val rawT0 = t0 ?: 0f
        val rawT1 = t1 ?: 0f

        // Compute unclamped new positions
        val new0 = Pair(c0.first + rawT0 * rail0.first, c0.second + rawT0 * rail0.second)
        val new1 = Pair(c1.first + rawT1 * rail1.first, c1.second + rawT1 * rail1.second)

        // Check bounds — find the largest fraction of t we can apply while staying in [0,1]²
        val maxFraction = minOf(
            maxTFraction(c0, rail0, rawT0),
            maxTFraction(c1, rail1, rawT1)
        )

        if (maxFraction <= 0f) return crop  // Already at boundary in this direction

        val clampedT0 = rawT0 * maxFraction
        val clampedT1 = rawT1 * maxFraction

        val final0 = Pair(
            (c0.first  + clampedT0 * rail0.first).coerceIn(0f, 1f),
            (c0.second + clampedT0 * rail0.second).coerceIn(0f, 1f)
        )
        val final1 = Pair(
            (c1.first  + clampedT1 * rail1.first).coerceIn(0f, 1f),
            (c1.second + clampedT1 * rail1.second).coerceIn(0f, 1f)
        )

        return when (sideIndex) {
            0 -> crop.copy(topLeft = final0,    topRight    = final1)
            1 -> crop.copy(topRight = final0,   bottomRight = final1)
            2 -> crop.copy(bottomRight = final0, bottomLeft = final1)
            3 -> crop.copy(bottomLeft = final0,  topLeft    = final1)
            else -> crop
        }
    }

    fun applyBodyDrag(dragDeltaNx: Float, dragDeltaNy: Float, crop: CropRect): CropRect {
        // Compute the maximum delta that keeps all corners in [0,1]²
        val corners = listOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft)

        // Find the actual allowed delta — limited by whichever corner hits a wall first
        val allowedDx = if (dragDeltaNx > 0f)
            minOf(dragDeltaNx, corners.minOf { 1f - it.first })
        else
            maxOf(dragDeltaNx, -corners.minOf { it.first })

        val allowedDy = if (dragDeltaNy > 0f)
            minOf(dragDeltaNy, corners.minOf { 1f - it.second })
        else
            maxOf(dragDeltaNy, -corners.minOf { it.second })

        return CropRect(
            topLeft     = Pair(crop.topLeft.first     + allowedDx, crop.topLeft.second     + allowedDy),
            topRight    = Pair(crop.topRight.first    + allowedDx, crop.topRight.second    + allowedDy),
            bottomRight = Pair(crop.bottomRight.first + allowedDx, crop.bottomRight.second + allowedDy),
            bottomLeft  = Pair(crop.bottomLeft.first  + allowedDx, crop.bottomLeft.second  + allowedDy)
        )
    }

    // ── Reset dialog ──────────────────────────────────────────────────────────

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
                                    val target = findDragTarget(offset, workingCrop)
                                    dragTarget = target
                                    // Record starting position in norm space for body drag
                                    lastDragNorm = canvasToNorm(offset.x, offset.y)
                                },
                                onDrag = { change, _ ->
                                    val target = dragTarget ?: return@detectDragGestures
                                    val cx = change.position.x
                                    val cy = change.position.y
                                    val (curNx, curNy) = canvasToNorm(cx, cy)

                                    workingCrop = when (target) {
                                        is DragTarget.Corner ->
                                            applyCornerDrag(target.index, cx, cy, workingCrop)

                                        is DragTarget.Side -> {
                                            val (lastNx, lastNy) = lastDragNorm
                                            val dNx = curNx - lastNx
                                            val dNy = curNy - lastNy
                                            applySideDrag(target.index, dNx, dNy, workingCrop)
                                        }

                                        is DragTarget.Body -> {
                                            val (lastNx, lastNy) = lastDragNorm
                                            val dNx = curNx - lastNx
                                            val dNy = curNy - lastNy
                                            applyBodyDrag(dNx, dNy, workingCrop)
                                        }
                                    }

                                    lastDragNorm = Pair(curNx, curNy)
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
                        val active = dragTarget is DragTarget.Corner &&
                                (dragTarget as DragTarget.Corner).index == i
                        val color = if (active) Color.White else Color(0xFF00AAFF)
                        val xDir = if (i == 0 || i == 3) 1f else -1f
                        val yDir = if (i == 0 || i == 1) 1f else -1f
                        drawLine(color, pt, Offset(pt.x + xDir * armLengthPx, pt.y), armWidthPx)
                        drawLine(color, pt, Offset(pt.x, pt.y + yDir * armLengthPx), armWidthPx)
                        drawCircle(color, drawRadiusPx, pt)
                    }

                    // Side midpoint handles
                    for (i in 0..3) {
                        val mid = sideMidpoint(workingCrop, i)
                        val active = dragTarget is DragTarget.Side &&
                                (dragTarget as DragTarget.Side).index == i
                        val color = if (active) Color.White else Color(0xBB00AAFF)
                        drawCircle(color, sideDotRadiusPx, mid)
                        drawCircle(Color(0xFF00AAFF), sideDotRadiusPx, mid,
                            style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
        }
    }
}

// ── Geometry helpers ──────────────────────────────────────────────────────────

private data class SideData(
    val c0: Pair<Float, Float>,
    val c1: Pair<Float, Float>,
    val rail0: Pair<Float, Float>,
    val rail1: Pair<Float, Float>
)

private fun vecSub(a: Pair<Float, Float>, b: Pair<Float, Float>) =
    Pair(a.first - b.first, a.second - b.second)

private fun dot(a: Pair<Float, Float>, b: Pair<Float, Float>) =
    a.first * b.first + a.second * b.second

/**
 * Given a drag vector and a rail direction, find the scalar t such that
 * moving along the rail by t achieves the same component of displacement
 * as the drag vector in the direction perpendicular to the rail.
 *
 * Equivalently: t = dot(drag, railPerp) / dot(rail, railPerp)
 *             = dot(drag, railPerp) / |rail|²
 * where railPerp is the rail rotated 90°.
 *
 * Returns null if the rail is near-zero length (degenerate).
 */
private fun projectDragOntoRail(
    drag: Pair<Float, Float>,
    rail: Pair<Float, Float>
): Float? {
    val railLenSq = dot(rail, rail)
    if (railLenSq < 1e-8f) return null
    // Project drag onto rail direction: t = dot(drag, rail) / |rail|²
    return dot(drag, rail) / railLenSq
}

/**
 * Find the maximum fraction of t that can be applied to a corner moving along
 * its rail while keeping it within [0,1]². Returns a value in [0,1].
 *
 * If t is 0 or the corner is already at a boundary in the direction of motion,
 * returns 0.0 to block movement.
 */
private fun maxTFraction(
    corner: Pair<Float, Float>,
    rail: Pair<Float, Float>,
    t: Float
): Float {
    if (abs(t) < 1e-8f) return 1f  // No movement, fraction is irrelevant

    var maxFrac = 1f

    // For each axis (x=0, y=1), find the fraction of t before hitting 0 or 1
    for (axis in 0..1) {
        val pos = if (axis == 0) corner.first else corner.second
        val vel = if (axis == 0) rail.first * t else rail.second * t

        if (vel > 0f) {
            // Moving toward 1.0 boundary
            val room = 1f - pos
            if (room < vel) maxFrac = minOf(maxFrac, room / vel)
        } else if (vel < 0f) {
            // Moving toward 0.0 boundary
            val room = pos  // distance to 0
            if (room < -vel) maxFrac = minOf(maxFrac, room / (-vel))
        }
    }

    return maxFrac.coerceIn(0f, 1f)
}

/**
 * Test if a point is inside a convex quad using cross products.
 * Assumes corners are ordered: TL, TR, BR, BL (clockwise).
 */
private fun pointInQuad(p: Offset, tl: Offset, tr: Offset, br: Offset, bl: Offset): Boolean {
    fun cross(o: Offset, a: Offset, b: Offset) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val d0 = cross(tl, tr, p)
    val d1 = cross(tr, br, p)
    val d2 = cross(br, bl, p)
    val d3 = cross(bl, tl, p)
    val hasNeg = d0 < 0 || d1 < 0 || d2 < 0 || d3 < 0
    val hasPos = d0 > 0 || d1 > 0 || d2 > 0 || d3 > 0
    return !(hasNeg && hasPos)
}

private fun dist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
