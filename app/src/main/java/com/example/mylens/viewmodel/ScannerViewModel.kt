package com.example.mylens.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mylens.data.CropRect
import com.example.mylens.data.ScanPage
import com.example.mylens.utils.PdfBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import java.io.File

sealed class ExportState {
    object Idle : ExportState()
    object Building : ExportState()
    data class Done(val file: File) : ExportState()
    data class Error(val message: String) : ExportState()
}

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    val pages = mutableStateListOf<ScanPage>()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    init {
        // Initialize OpenCV. With the Maven artifact this is synchronous and always succeeds.
        OpenCVLoader.initLocal()
    }

    // ── Page management ──────────────────────────────────────────────────────

    fun addPages(uris: List<Uri>) {
        uris.forEach { pages.add(ScanPage(uri = it)) }
    }

    fun addPage(uri: Uri) {
        pages.add(ScanPage(uri = uri))
    }

    fun removePage(id: String) {
        pages.removeAll { it.id == id }
    }

    fun movePage(from: Int, to: Int) {
        if (from == to) return
        val item = pages.removeAt(from)
        pages.add(to, item)
    }

    fun rotatePage(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val page = pages[index]
        pages[index] = page.copy(rotation = (page.rotation + 90) % 360)
    }

    /**
     * Save the crop quad for a page. Pass null to clear the crop (revert to full image).
     */
    fun setCrop(id: String, crop: CropRect?) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        pages[index] = pages[index].copy(cropRect = crop)
    }

    fun clearAll() {
        pages.clear()
        _exportState.value = ExportState.Idle
    }

    // ── PDF export ────────────────────────────────────────────────────────────

    fun exportPdf(outputDir: File, fileName: String = "scan.pdf") {
        viewModelScope.launch {
            _exportState.value = ExportState.Building
            try {
                val file = PdfBuilder.build(
                    context = getApplication(),
                    pages = pages.toList(),
                    outputDir = outputDir,
                    fileName = fileName
                )
                _exportState.value = ExportState.Done(file)
            } catch (e: Exception) {
                _exportState.value = ExportState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }
}
