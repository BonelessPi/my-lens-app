package dev.bonelesspi.mylens.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bonelesspi.mylens.data.CropRect
import dev.bonelesspi.mylens.data.ScanPage
import dev.bonelesspi.mylens.ui.screens.PageSize
import dev.bonelesspi.mylens.utils.CropUtils
import dev.bonelesspi.mylens.utils.ImageUtils
import dev.bonelesspi.mylens.utils.PdfBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File

sealed class ExportState {
    object Idle : ExportState()
    object Building : ExportState()
    data class Done(val file: File) : ExportState()
    data class Error(val message: String) : ExportState()
}

/**
 * Edit resolution for working bitmaps kept in memory during editing.
 * Good balance between quality and memory — a 2048px bitmap is ~16MB RGBA.
 * Full quality re-decode happens at export time.
 */
private const val EDIT_RESOLUTION = 2048

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    val pages = mutableStateListOf<ScanPage>()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    init {
        OpenCVLoader.initLocal()
    }

    // ── Page list management ─────────────────────────────────────────────────

    fun addPages(uris: List<Uri>) {
        uris.forEach { pages.add(ScanPage(uri = it)) }
    }

    fun addPage(uri: Uri) {
        pages.add(ScanPage(uri = uri))
    }

    fun removePage(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        pages[index].workingBitmap?.recycle()
        pages.removeAt(index)
    }

    fun movePage(from: Int, to: Int) {
        if (from == to) return
        pages.add(to, pages.removeAt(from))
    }

    fun clearAll() {
        pages.forEach { it.workingBitmap?.recycle() }
        pages.clear()
        _exportState.value = ExportState.Idle
    }

    // ── Working bitmap lifecycle ─────────────────────────────────────────────

    /**
     * Decode the source URI into a working bitmap at edit resolution.
     * Called when EditScreen opens a page for the first time (workingBitmap == null).
     * No-op if the page already has a working bitmap.
     */
    fun ensureWorkingBitmap(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1 || pages[index].workingBitmap != null) return

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageUtils.decodeUri(getApplication(), pages[index].uri, EDIT_RESOLUTION)
            } ?: return@launch

            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) pages[i] = pages[i].copy(workingBitmap = bitmap)
        }
    }

    /**
     * Reset a page's working bitmap back to a fresh decode of the source URI.
     * Recycles the old working bitmap.
     */
    fun resetPage(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageUtils.decodeUri(getApplication(), pages[index].uri, EDIT_RESOLUTION)
            } ?: return@launch

            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) {
                pages[i].workingBitmap?.recycle()
                pages[i] = pages[i].copy(workingBitmap = bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    // ── Edit operations ──────────────────────────────────────────────────────

    /**
     * Rotate the working bitmap 90° clockwise in-place.
     * The result replaces the current working bitmap; the old one is recycled.
     */
    fun applyRotate(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val current = pages[index].workingBitmap ?: return

        viewModelScope.launch {
            val rotated = withContext(Dispatchers.Default) {
                ImageUtils.rotateBitmap(current, 90)
            }
            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) {
                if (rotated !== current) current.recycle()
                pages[i] = pages[i].copy(workingBitmap = rotated)
            } else {
                rotated.recycle()
            }
        }
    }

    /**
     * Apply a perspective warp to the working bitmap using [crop].
     * The warped result replaces the current working bitmap; the old one is recycled.
     * Since this is baked into the bitmap, the operation is visible immediately
     * in both EditScreen and the SelectScreen thumbnail.
     */
    fun applyWarp(id: String, crop: CropRect) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val current = pages[index].workingBitmap ?: return

        viewModelScope.launch {
            val warped = CropUtils.warpPerspective(current, crop, outputMaxDim = EDIT_RESOLUTION)
            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) {
                current.recycle()
                pages[i] = pages[i].copy(workingBitmap = warped)
            } else {
                warped.recycle()
            }
        }
    }

    // ── PDF export ────────────────────────────────────────────────────────────

    fun exportPdf(
        outputDir: File,
        fileName: String = "scan.pdf",
        pageSize: PageSize = PageSize.A4,
        quality: Int = 90
    ) {
        viewModelScope.launch {
            _exportState.value = ExportState.Building
            try {
                val file = PdfBuilder.build(
                    context = getApplication(),
                    pages = pages.toList(),
                    outputDir = outputDir,
                    fileName = fileName,
                    pageSize = pageSize,
                    quality = quality
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
