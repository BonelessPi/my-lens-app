package dev.bonelesspi.mylens.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Flash mode cycling ────────────────────────────────────────────────────────

private enum class FlashMode { OFF, AUTO, ON }

private fun FlashMode.next() = when (this) {
    FlashMode.OFF  -> FlashMode.AUTO
    FlashMode.AUTO -> FlashMode.ON
    FlashMode.ON   -> FlashMode.OFF
}

private fun FlashMode.toImageCaptureFlashMode() = when (this) {
    FlashMode.OFF  -> ImageCapture.FLASH_MODE_OFF
    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    FlashMode.ON   -> ImageCapture.FLASH_MODE_ON
}

private fun FlashMode.icon() = when (this) {
    FlashMode.OFF  -> Icons.Default.FlashOff
    FlashMode.AUTO -> Icons.Default.FlashAuto
    FlashMode.ON   -> Icons.Default.FlashOn
}

private fun FlashMode.label() = when (this) {
    FlashMode.OFF  -> "Flash off"
    FlashMode.AUTO -> "Flash auto"
    FlashMode.ON   -> "Flash on"
}

// ── Focus ring state ──────────────────────────────────────────────────────────

private data class FocusRing(val position: Offset, val progress: Float)  // progress 0→1 = ring fading

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    viewModel: dev.bonelesspi.mylens.viewmodel.ScannerViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    // ── Camera state ─────────────────────────────────────────────────────────
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var isCaptureInProgress by remember { mutableStateOf(false) }

    // ── Controls state ───────────────────────────────────────────────────────
    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    // Exposure index: typically -2..+2 EV steps, camera reports actual range
    var exposureIndex by remember { mutableIntStateOf(0) }
    var exposureRange by remember { mutableStateOf(0..0) }
    var showExposureSlider by remember { mutableStateOf(false) }

    // ── Focus ring ───────────────────────────────────────────────────────────
    var focusRing by remember { mutableStateOf<FocusRing?>(null) }
    val focusAlpha by animateFloatAsState(
        targetValue = focusRing?.let { 1f - it.progress } ?: 0f,
        animationSpec = tween(200),
        label = "focus_alpha"
    )

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    // Sync flash mode to ImageCapture whenever it changes
    LaunchedEffect(flashMode, imageCapture) {
        imageCapture?.flashMode = flashMode.toImageCaptureFlashMode()
    }

    // Sync zoom ratio to camera whenever it changes
    LaunchedEffect(zoomRatio, camera) {
        camera?.cameraControl?.setZoomRatio(zoomRatio)
    }

    // Sync exposure index to camera whenever it changes
    LaunchedEffect(exposureIndex, camera) {
        camera?.cameraControl?.setExposureCompensationIndex(exposureIndex)
    }

    // Auto-hide exposure slider after 3 s of inactivity
    LaunchedEffect(showExposureSlider, exposureIndex) {
        if (showExposureSlider) {
            delay(3000)
            showExposureSlider = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Flash toggle
                    IconButton(onClick = {
                        flashMode = flashMode.next()
                        imageCapture?.flashMode = flashMode.toImageCaptureFlashMode()
                    }) {
                        Icon(flashMode.icon(), flashMode.label())
                    }
                }
            )
        }
    ) { padding ->
        if (cameraPermission.status.isGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {

                // ── Viewfinder ────────────────────────────────────────────────
                AndroidView(
                    factory = { ctx ->
                        val pv = PreviewView(ctx).also { previewView = it }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(pv.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setFlashMode(flashMode.toImageCaptureFlashMode())
                                .build()
                            imageCapture = capture

                            try {
                                cameraProvider.unbindAll()
                                val cam = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture
                                )
                                camera = cam

                                // Grab zoom and exposure ranges from camera info
                                val zoomState = cam.cameraInfo.zoomState.value
                                minZoom = zoomState?.minZoomRatio ?: 1f
                                maxZoom = zoomState?.maxZoomRatio ?: 8f
                                zoomRatio = zoomState?.zoomRatio ?: 1f

                                val exposureState = cam.cameraInfo.exposureState
                                if (exposureState.isExposureCompensationSupported) {
                                    val range = exposureState.exposureCompensationRange
                                    exposureRange = range.lower..range.upper
                                    exposureIndex = exposureState.exposureCompensationIndex
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        pv
                    },
                    // Observe live zoom changes from the camera (e.g. after pinch)
                    update = { _ ->
                        camera?.cameraInfo?.zoomState?.value?.let { state ->
                            // Clamp to avoid feedback loop with LaunchedEffect
                            if (kotlin.math.abs(state.zoomRatio - zoomRatio) > 0.01f) {
                                zoomRatio = state.zoomRatio.coerceIn(minZoom, maxZoom)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // ── Gesture overlay: pinch-to-zoom + tap-to-focus ────────────
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(camera, minZoom, maxZoom) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                val newZoom = (zoomRatio * zoomChange).coerceIn(minZoom, maxZoom)
                                zoomRatio = newZoom
                                camera?.cameraControl?.setZoomRatio(newZoom)
                            }
                        }
                        .pointerInput(camera, previewView) {
                            detectTapGestures { tapOffset ->
                                // Use PreviewView.meteringPointFactory — it correctly maps
                                // screen coordinates to sensor coordinates accounting for
                                // display rotation, preview scaling and letterboxing.
                                val pv = previewView ?: return@detectTapGestures
                                val point = pv.meteringPointFactory.createPoint(tapOffset.x, tapOffset.y)
                                val action = FocusMeteringAction.Builder(point)
                                    .addPoint(point, FocusMeteringAction.FLAG_AE)
                                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                camera?.cameraControl?.startFocusAndMetering(action)

                                // Show focus ring animation
                                scope.launch {
                                    focusRing = FocusRing(tapOffset, 0f)
                                    delay(800)
                                    focusRing = FocusRing(tapOffset, 1f)
                                    delay(200)
                                    focusRing = null
                                }
                            }
                        }
                )

                // ── Focus ring canvas ────────────────────────────────────────
                focusRing?.let { ring ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val ringSize = 72.dp.toPx()
                        drawRect(
                            color = Color.White.copy(alpha = focusAlpha),
                            topLeft = Offset(ring.position.x - ringSize / 2, ring.position.y - ringSize / 2),
                            size = Size(ringSize, ringSize),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                // ── Zoom ratio badge (appears during pinch) ──────────────────
                if (zoomRatio > minZoom + 0.05f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "%.1fx".format(zoomRatio),
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }

                // ── Right-side controls: exposure ────────────────────────────
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Exposure button — toggles slider
                    if (exposureRange.last > exposureRange.first) {
                        IconButton(
                            onClick = { showExposureSlider = !showExposureSlider },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                                .size(44.dp)
                        ) {
                            // Show live EV value, coloured yellow when slider is open
                            val evLabel = when {
                                exposureIndex > 0 -> "+$exposureIndex"
                                else -> "$exposureIndex"
                            }
                            Text(
                                text = evLabel,
                                color = if (showExposureSlider) Color.Yellow else Color.White,
                                fontSize = 13.sp
                            )
                        }

                        // Vertical exposure slider — shown/hidden
                        AnimatedVisibility(
                            visible = showExposureSlider,
                            enter = fadeIn() + slideInHorizontally { it },
                            exit = fadeOut() + slideOutHorizontally { it }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (exposureIndex >= 0) "+$exposureIndex" else "$exposureIndex",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                VerticalSlider(
                                    value = exposureIndex.toFloat(),
                                    onValueChange = { v ->
                                        exposureIndex = v.toInt()
                                        showExposureSlider = true   // reset auto-hide timer
                                    },
                                    valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                                    modifier = Modifier.height(160.dp)
                                )
                            }
                        }
                    }
                }

                // ── Zoom slider at bottom ────────────────────────────────────
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 108.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (maxZoom > minZoom + 0.5f) {
                        Slider(
                            value = zoomRatio,
                            onValueChange = { v ->
                                zoomRatio = v
                                camera?.cameraControl?.setZoomRatio(v)
                            },
                            valueRange = minZoom..maxZoom,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.4f)
                            )
                        )
                    }
                }

                // ── Shutter button ───────────────────────────────────────────
                FloatingActionButton(
                    onClick = {
                        if (!isCaptureInProgress) {
                            isCaptureInProgress = true
                            capturePhoto(context, imageCapture) { uri ->
                                uri?.let { viewModel.addPage(it) }
                                isCaptureInProgress = false
                                onBack()
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .size(72.dp)
                ) {
                    Icon(
                        Icons.Default.Camera,
                        contentDescription = "Capture",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Camera permission is required")
                    Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                        Text("Grant Permission")
                    }
                }
            }
        }
    }
}

// ── Vertical slider (wraps horizontal Slider with rotation) ──────────────────

@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    // Compose doesn't have a built-in vertical slider; rotate a horizontal one
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .width(160.dp)     // becomes the height after rotation
                .rotate(-90f),
            colors = SliderDefaults.colors(
                thumbColor = Color.Yellow,
                activeTrackColor = Color.Yellow,
                inactiveTrackColor = Color.White.copy(alpha = 0.4f)
            )
        )
    }
}

// ── Photo capture (unchanged) ────────────────────────────────────────────────

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    onResult: (Uri?) -> Unit
) {
    imageCapture ?: run { onResult(null); return }

    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "scan_${System.currentTimeMillis()}")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyLens")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onResult(output.savedUri)
            }
            override fun onError(exception: ImageCaptureException) {
                exception.printStackTrace()
                onResult(null)
            }
        }
    )
}
