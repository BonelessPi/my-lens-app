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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Tune
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.bonelesspi.mylens.data.CropRect
import dev.bonelesspi.mylens.viewmodel.ScannerViewModel
import dev.bonelesspi.mylens.viewmodel.SettingsViewModel
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
    viewModel: ScannerViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = viewModel.pages.firstOrNull { it.id == pageId } ?: run { onBack(); return }

    // Global defaults from settings — used as display hints and seed values
    val globalExportResolution by settingsViewModel.exportResolution.collectAsStateWithLifecycle()
    val globalJpegQuality      by settingsViewModel.jpegQuality.collectAsStateWithLifecycle()

    var cropMode by remember { mutableStateOf(false) }
    var workingCrop by remember { mutableStateOf(CropRect()) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var imageIntrinsicSize by remember { mutableStateOf<IntSize?>(null) }
    var dragTarget by remember { mutableStateOf<DragTarget?>(null) }
    var lastDragNorm by remember { mutableStateOf(Pair(0f, 0f)) }

    // ── Page settings sheet state ─────────────────────────────────────────────
    val pageSettingsSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var showPageSettings by remember { mutableStateOf(false) }

    // Override switch states — initialized from the page's current stored overrides
    var resolutionOverrideEnabled by remember(page.exportResolution) {
        mutableStateOf(page.exportResolution != null)
    }
    var qualityOverrideEnabled by remember(page.jpegQuality) {
        mutableStateOf(page.jpegQuality != null)
    }

    // Selector values — seeded from the page override if present, else the global default.
    // These are local working copies; they are only written back to the ViewModel when the
    // corresponding switch is on. This prevents confusing a manual "90" from an inherited "90".
    var resolutionSelectorValue by remember(page.exportResolution, globalExportResolution) {
        mutableIntStateOf(page.exportResolution ?: globalExportResolution)
    }
    var qualitySelectorValue by remember(page.jpegQuality, globalJpegQuality) {
        mutableIntStateOf(page.jpegQuality ?: globalJpegQuality)
    }

    val density = LocalDensity.current
    val cornerHitPx     = with(density) { 44.dp.toPx() }
    val sideHitPx       = with(density) { 28.dp.toPx() }
    val drawRadiusPx    = with(density) { 7.dp.toPx() }
    val armLengthPx     = with(density) { 18.dp.toPx() }
    val armWidthPx      = with(density) { 3.dp.toPx() }
    val sideDotRadiusPx = with(density) { 5.dp.toPx() }

    LaunchedEffect(pageId) { viewModel.ensurePreview(pageId) }

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

        var bestCorner: Int? = null
        var bestCornerDist = cornerHitPx
        corners.forEachIndexed { i, (nx, ny) ->
            val d = dist(touch, normToCanvas(nx, ny))
            if (d < bestCornerDist) { bestCornerDist = d; bestCorner = i }
        }
        if (bestCorner != null) return DragTarget.Corner(bestCorner)

        var bestSide: Int? = null
        var bestSideDist = sideHitPx
        for (i in 0..3) {
            val d = dist(touch, sideMidpoint(crop, i))
            if (d < bestSideDist) { bestSideDist = d; bestSide = i }
        }
        if (bestSide != null) return DragTarget.Side(bestSide)

        val tl = normToCanvas(crop.topLeft.first,     crop.topLeft.second)
        val tr = normToCanvas(crop.topRight.first,    crop.topRight.second)
        val br = normToCanvas(crop.bottomRight.first, crop.bottomRight.second)
        val bl = normToCanvas(crop.bottomLeft.first,  crop.bottomLeft.second)
        if (pointInQuad(touch, tl, tr, br, bl)) return DragTarget.Body

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

    fun applySideDrag(
        sideIndex: Int,
        dragDeltaNx: Float,
        dragDeltaNy: Float,
        crop: CropRect
    ): CropRect {
        val (c0, c1, rail0, rail1) = when (sideIndex) {
            0 -> SideData(
                crop.topLeft,    crop.topRight,
                vecSub(crop.bottomLeft,  crop.topLeft),
                vecSub(crop.bottomRight, crop.topRight)
            )
            1 -> SideData(
                crop.topRight,    crop.bottomRight,
                vecSub(crop.topLeft,    crop.topRight),
                vecSub(crop.bottomLeft, crop.bottomRight)
            )
            2 -> SideData(
                crop.bottomRight, crop.bottomLeft,
                vecSub(crop.topRight, crop.bottomRight),
                vecSub(crop.topLeft,  crop.bottomLeft)
            )
            3 -> SideData(
                crop.bottomLeft, crop.topLeft,
                vecSub(crop.bottomRight, crop.bottomLeft),
                vecSub(crop.topRight,    crop.topLeft)
            )
            else -> return crop
        }

        val drag = Pair(dragDeltaNx, dragDeltaNy)
        val t0 = projectDragOntoRail(drag, rail0)
        val t1 = projectDragOntoRail(drag, rail1)
        if (t0 == null && t1 == null) return crop

        val rawT0 = t0 ?: 0f
        val rawT1 = t1 ?: 0f

        val maxFraction = minOf(
            maxTFraction(c0, rail0, rawT0),
            maxTFraction(c1, rail1, rawT1)
        )
        if (maxFraction <= 0f) return crop

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
            0 -> crop.copy(topLeft = final0,     topRight    = final1)
            1 -> crop.copy(topRight = final0,    bottomRight = final1)
            2 -> crop.copy(bottomRight = final0, bottomLeft  = final1)
            3 -> crop.copy(bottomLeft = final0,  topLeft     = final1)
            else -> crop
        }
    }

    fun applyBodyDrag(dragDeltaNx: Float, dragDeltaNy: Float, crop: CropRect): CropRect {
        val corners = listOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft)
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

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset to original?") },
            text = { Text("All edits applied to this page will be undone.") },
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove this page?") },
            text = { Text("This page will be removed from the document.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.removePage(pageId)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // ── Page settings bottom sheet ────────────────────────────────────────────

    if (showPageSettings) {
        ModalBottomSheet(
            onDismissRequest = { showPageSettings = false },
            sheetState = pageSettingsSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text("Page settings", style = MaterialTheme.typography.titleLarge)

                // ── Export resolution ─────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Export resolution", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = resolutionOverrideEnabled,
                            onCheckedChange = { enabled ->
                                resolutionOverrideEnabled = enabled
                                if (enabled) {
                                    // Seed selector with global default when first enabled
                                    // so the user starts from a sensible value
                                    resolutionSelectorValue = globalExportResolution
                                    viewModel.setPageExportResolution(pageId, resolutionSelectorValue)
                                } else {
                                    viewModel.setPageExportResolution(pageId, null)
                                }
                            }
                        )
                    }
                    ResolutionDropdown(
                        options = EXPORT_RESOLUTION_OPTIONS,
                        selectedPixels = resolutionSelectorValue,
                        globalPixels = globalExportResolution,
                        enabled = resolutionOverrideEnabled,
                        onSelect = { pixels ->
                            resolutionSelectorValue = pixels
                            if (resolutionOverrideEnabled) {
                                viewModel.setPageExportResolution(pageId, pixels)
                            }
                        }
                    )
                }

                HorizontalDivider()

                // ── JPEG quality ──────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("JPEG quality", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = qualityOverrideEnabled,
                            onCheckedChange = { enabled ->
                                qualityOverrideEnabled = enabled
                                if (enabled) {
                                    qualitySelectorValue = globalJpegQuality
                                    viewModel.setPageJpegQuality(pageId, qualitySelectorValue)
                                } else {
                                    viewModel.setPageJpegQuality(pageId, null)
                                }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (qualityOverrideEnabled)
                                "$qualitySelectorValue%"
                            else
                                "Default ($globalJpegQuality%)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (qualityOverrideEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = qualitySelectorValue.toFloat(),
                        onValueChange = { value ->
                            qualitySelectorValue = value.toInt()
                            if (qualityOverrideEnabled) {
                                viewModel.setPageJpegQuality(pageId, qualitySelectorValue)
                            }
                        },
                        valueRange = 50f..100f,
                        steps = 9,
                        enabled = qualityOverrideEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Smaller file", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Best quality", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
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
                        IconButton(
                            onClick = { viewModel.undoLastAction(pageId) },
                            enabled = page.actions.isNotEmpty()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "Undo last action")
                        }
                        IconButton(onClick = { showResetConfirm = true }) {
                            Icon(Icons.Default.Refresh, "Reset to original")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete page",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = {
                            scope.launch { pageSettingsSheetState.show() }
                            showPageSettings = true
                        }) {
                            Icon(Icons.Default.Tune, contentDescription = "Page settings")
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
                                    enabled = page.previewBitmap != null,
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
                                enabled = page.previewBitmap != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Rotate90DegreesCw, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Rotate 90°")
                            }
                            OutlinedButton(
                                onClick = { workingCrop = CropRect(); cropMode = true },
                                enabled = page.previewBitmap != null,
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
                .padding(horizontal = 20.dp)
                .onSizeChanged { canvasSize = it },
            contentAlignment = Alignment.Center
        ) {
            val bitmap = page.previewBitmap
            if (bitmap != null) {
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                LaunchedEffect(bitmap) {
                    imageIntrinsicSize = IntSize(bitmap.width, bitmap.height)
                }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Page preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
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
                                            applySideDrag(target.index, curNx - lastNx, curNy - lastNy, workingCrop)
                                        }
                                        is DragTarget.Body -> {
                                            val (lastNx, lastNy) = lastDragNorm
                                            applyBodyDrag(curNx - lastNx, curNy - lastNy, workingCrop)
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

// ── Resolution dropdown for the page settings sheet ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolutionDropdown(
    options: List<ResolutionOption>,
    selectedPixels: Int,
    globalPixels: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = if (enabled) {
        options.firstOrNull { it.pixels == selectedPixels }?.label ?: "$selectedPixels px"
    } else {
        "Default (${options.firstOrNull { it.pixels == globalPixels }?.label ?: "$globalPixels px"})"
    }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true)
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option.pixels); expanded = false }
                )
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

private fun projectDragOntoRail(
    drag: Pair<Float, Float>,
    rail: Pair<Float, Float>
): Float? {
    val railLenSq = dot(rail, rail)
    if (railLenSq < 1e-8f) return null
    return dot(drag, rail) / railLenSq
}

private fun maxTFraction(
    corner: Pair<Float, Float>,
    rail: Pair<Float, Float>,
    t: Float
): Float {
    if (abs(t) < 1e-8f) return 1f
    var maxFrac = 1f
    for (axis in 0..1) {
        val pos = if (axis == 0) corner.first else corner.second
        val vel = if (axis == 0) rail.first * t else rail.second * t
        if (vel > 0f) {
            val room = 1f - pos
            if (room < vel) maxFrac = minOf(maxFrac, room / vel)
        } else if (vel < 0f) {
            val room = pos
            if (room < -vel) maxFrac = minOf(maxFrac, room / (-vel))
        }
    }
    return maxFrac.coerceIn(0f, 1f)
}

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
