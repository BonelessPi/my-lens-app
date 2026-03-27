package dev.bonelesspi.mylens.viewmodel

import android.app.Application
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
 * Resolution used for all working bitmaps — both during editing and at export time.
 *
 * The working bitmap IS the export source: PdfBuilder encodes it directly with no
 * further decode or transform pass. This means this constant directly controls the
 * maximum output quality of exported PDFs.
 *
 * At 4096px the longest side: ~64MB per page in memory (ARGB_8888).
 * For a personal scanner app scanning a handful of pages at a time this is fine.
 * Lower to 2048 if memory pressure becomes an issue (produces ~16MB per page).
 */
private const val WORKING_RESOLUTION = 4096

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
     * Decode the source URI into a working bitmap at [WORKING_RESOLUTION].
     * Called when EditScreen opens a page for the first time.
     * No-op if the page already has a working bitmap.
     */
    fun ensureWorkingBitmap(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1 || pages[index].workingBitmap != null) return

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageUtils.decodeUri(getApplication(), pages[index].uri, WORKING_RESOLUTION)
            } ?: return@launch

            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) pages[i] = pages[i].copy(workingBitmap = bitmap)
            else bitmap.recycle()
        }
    }

    /**
     * Reset a page to a fresh decode of its source URI, discarding all edits.
     * Recycles the old working bitmap.
     */
    fun resetPage(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageUtils.decodeUri(getApplication(), pages[index].uri, WORKING_RESOLUTION)
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
     * Rotate the working bitmap 90° clockwise.
     * Result replaces the current working bitmap; old one is recycled.
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
     * Apply a perspective warp to the working bitmap.
     * Result replaces the current working bitmap; old one is recycled.
     * Output is capped at [WORKING_RESOLUTION] on the longest side.
     */
    fun applyWarp(id: String, crop: CropRect) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val current = pages[index].workingBitmap ?: return

        viewModelScope.launch {
            val warped = CropUtils.warpPerspective(current, crop, outputMaxDim = WORKING_RESOLUTION)
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

    /**
     * Export all pages to a PDF file.
     *
     * Pages that have a working bitmap are encoded directly from it — the working
     * bitmap is the single source of truth for both editing and export.
     * Pages that were never opened in EditScreen (no working bitmap) are decoded
     * fresh from their URI at [WORKING_RESOLUTION] by PdfBuilder as a fallback.
     */
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
                    context    = getApplication(),
                    pages      = pages.toList(),
                    outputDir  = outputDir,
                    fileName   = fileName,
                    pageSize   = pageSize,
                    quality    = quality,
                    fallbackResolution = WORKING_RESOLUTION
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
