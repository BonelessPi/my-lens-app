package com.example.mylens.ui.screens

import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mylens.viewmodel.ExportState
import com.example.mylens.viewmodel.ScannerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    viewModel: ScannerViewModel = viewModel()
) {
    val context = LocalContext.current
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    var fileName by remember { mutableStateOf("scan") }

    // Auto-share when export completes
    LaunchedEffect(exportState) {
        if (exportState is ExportState.Done) {
            val file = (exportState as ExportState.Done).file
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Save or share PDF"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export PDF") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "${viewModel.pages.size} page${if (viewModel.pages.size != 1) "s" else ""}",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("File name") },
                suffix = { Text(".pdf") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.weight(1f))

            when (exportState) {
                is ExportState.Building -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Building PDF…")
                        }
                    }
                }

                is ExportState.Error -> {
                    Text(
                        "Error: ${(exportState as ExportState.Error).message}",
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = { viewModel.resetExportState() }) {
                        Text("Try again")
                    }
                }

                is ExportState.Done -> {
                    Text(
                        "PDF saved!",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = {
                            viewModel.resetExportState()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Done")
                    }
                }

                else -> {
                    Button(
                        onClick = {
                            val safeName = fileName.ifBlank { "scan" }.trim() + ".pdf"
                            val outputDir = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOCUMENTS)

                            viewModel.exportPdf(
                                outputDir = outputDir,
                                fileName = safeName
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = viewModel.pages.isNotEmpty()
                    ) {
                        Text("Export & Share")
                    }
                }
            }
        }
    }
}
