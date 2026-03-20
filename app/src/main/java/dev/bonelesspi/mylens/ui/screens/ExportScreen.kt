package dev.bonelesspi.mylens.ui.screens

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bonelesspi.mylens.viewmodel.ExportState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    viewModel: dev.bonelesspi.mylens.viewmodel.ScannerViewModel = viewModel()
) {
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    var fileName by remember { mutableStateOf("scan") }

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
                        "PDF saved to Documents folder!",
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
                            // Save to public Documents folder, visible in the Files app
                            val outputDir = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOCUMENTS
                            )
                            viewModel.exportPdf(
                                outputDir = outputDir,
                                fileName = safeName
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = viewModel.pages.isNotEmpty()
                    ) {
                        Text("Save PDF")
                    }
                }
            }
        }
    }
}
