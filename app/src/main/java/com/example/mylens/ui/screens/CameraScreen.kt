package com.example.mylens.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mylens.viewmodel.ScannerViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    viewModel: ScannerViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCaptureInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // ArrowBack moved to AutoMirrored in recent versions
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            // FIX: CAPTURE_MODE_STILL was removed; use CAPTURE_MODE_MAXIMIZE_QUALITY
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()
                            imageCapture = capture

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture
                            )
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

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
                        .padding(32.dp)
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
