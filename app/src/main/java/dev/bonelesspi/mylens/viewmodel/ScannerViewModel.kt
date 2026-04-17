package dev.bonelesspi.mylens.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bonelesspi.mylens.data.CropRect
import dev.bonelesspi.mylens.data.EditAction
import dev.bonelesspi.mylens.data.ScanPage
import dev.bonelesspi.mylens.data.SettingsRepository
import dev.bonelesspi.mylens.ui.screens.PageSize
import dev.bonelesspi.mylens.utils.CropUtils
import dev.bonelesspi.mylens.utils.ImageUtils
import dev.bonelesspi.mylens.utils.PdfBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import androidx.core.graphics.scale

sealed class ExportState {
    object Idle : ExportState()
    object Building : ExportState()
    data class Done(val file: File) : ExportState()
    data class Error(val message: String) : ExportState()
}

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    // ── Settings ──────────────────────────────────────────────────────────────

    private val settings = SettingsRepository(application)

    // Expose as StateFlows so resolution values are always current.
    // SharingStarted.Eagerly means the value is ready before any coroutine reads it.
    val exportResolution: StateFlow<Int> = settings.exportResolution.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsRepository.DEFAULT_EXPORT_RESOLUTION
    )

    val previewResolution: StateFlow<Int> = settings.previewResolution.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsRepository.DEFAULT_PREVIEW_RESOLUTION
    )

    val thumbnailResolution: StateFlow<Int> = settings.thumbnailResolution.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsRepository.DEFAULT_THUMBNAIL_RESOLUTION
    )

    // ── Page list ─────────────────────────────────────────────────────────────

    val pages = mutableStateListOf<ScanPage>()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    init {
        OpenCVLoader.initLocal()
    }

    // ── Page list management ─────────────────────────────────────────────────

    fun addPages(uris: List<Uri>) {
        uris.forEach { uri ->
            val page = ScanPage(uri = uri)
            pages.add(page)
            loadImageDimensions(page.id, uri)
        }
    }

    fun addPage(uri: Uri) {
        val page = ScanPage(uri = uri)
        pages.add(page)
        loadImageDimensions(page.id, uri)
    }

    /**
     * Read the source image dimensions without decoding pixel data.
     * Uses inJustDecodeBounds — fast and uses negligible memory.
     */
    private fun loadImageDimensions(id: String, uri: Uri) {
        viewModelScope.launch {
            val (w, h) = withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                Pair(opts.outWidth.coerceAtLeast(0), opts.outHeight.coerceAtLeast(0))
            }
            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) {
                pages[i] = pages[i].copy(originalWidth = w, originalHeight = h)
            }
        }
    }

    fun removePage(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        recyclePage(pages[index])
        pages.removeAt(index)
    }

    fun movePage(from: Int, to: Int) {
        if (from == to) return
        pages.add(to, pages.removeAt(from))
    }

    fun clearAll() {
        pages.forEach { recyclePage(it) }
        pages.clear()
        _exportState.value = ExportState.Idle
    }

    private fun recyclePage(page: ScanPage) {
        page.baseBitmap?.recycle()
        page.previewBitmap?.recycle()
        page.thumbnailBitmap?.recycle()
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    private fun makeThumbnail(source: Bitmap): Bitmap {
        val maxDim = thumbnailResolution.value
        val scale = maxDim.toFloat() / maxOf(source.width, source.height)
        val w = (source.width  * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return source.scale(w, h)
    }

    private suspend fun applyActions(base: Bitmap, actions: List<EditAction>): Bitmap =
        withContext(Dispatchers.Default) {
            if (actions.isEmpty()) return@withContext base.copy(base.config ?: Bitmap.Config.ARGB_8888, true)
            var current = base
            for (action in actions) {
                val next = when (action) {
                    is EditAction.Rotate -> ImageUtils.rotateBitmap(current, 90)
                    is EditAction.Warp   -> CropUtils.warpPerspective(
                        current, action.crop, outputMaxDim = previewResolution.value
                    )
                }
                if (current !== base) current.recycle()
                current = next
            }
            current
        }

    // ── EditScreen lifecycle ──────────────────────────────────────────────────

    fun ensurePreview(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val page = pages[index]

        if (page.baseBitmap != null && page.previewBitmap != null) return

        viewModelScope.launch {
            val base = page.baseBitmap
                ?: (withContext(Dispatchers.IO) {
                    ImageUtils.decodeUri(getApplication(), page.uri, previewResolution.value)
                } ?: return@launch)

            val preview = applyActions(base, page.actions)
            val thumbnail = withContext(Dispatchers.Default) { makeThumbnail(preview) }

            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) {
                if (pages[i].baseBitmap != null) {
                    pages[i].previewBitmap?.recycle()
                }
                pages[i].thumbnailBitmap?.recycle()
                pages[i] = pages[i].copy(
                    baseBitmap      = base,
                    previewBitmap   = preview,
                    thumbnailBitmap = thumbnail
                )
            } else {
                if (page.baseBitmap == null) base.recycle()
                if (preview !== base) preview.recycle()
                thumbnail.recycle()
            }
        }
    }

    // ── Edit operations ──────────────────────────────────────────────────────

    fun applyRotate(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val current = pages[index].previewBitmap ?: return

        viewModelScope.launch {
            val rotated = withContext(Dispatchers.Default) {
                ImageUtils.rotateBitmap(current, 90)
            }
            val thumbnail = withContext(Dispatchers.Default) { makeThumbnail(rotated) }

            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) {
                if (rotated !== current) current.recycle()
                pages[i].thumbnailBitmap?.recycle()
                pages[i] = pages[i].copy(
                    actions         = pages[i].actions + EditAction.Rotate,
                    previewBitmap   = rotated,
                    thumbnailBitmap = thumbnail
                )
            } else {
                rotated.recycle()
                thumbnail.recycle()
            }
        }
    }

    fun applyWarp(id: String, crop: CropRect) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val current = pages[index].previewBitmap ?: return

        viewModelScope.launch {
            val warped = CropUtils.warpPerspective(
                current, crop, outputMaxDim = previewResolution.value
            )
            val thumbnail = withContext(Dispatchers.Default) { makeThumbnail(warped) }

            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) {
                current.recycle()
                pages[i].thumbnailBitmap?.recycle()
                pages[i] = pages[i].copy(
                    actions         = pages[i].actions + EditAction.Warp(crop),
                    previewBitmap   = warped,
                    thumbnailBitmap = thumbnail
                )
            } else {
                warped.recycle()
                thumbnail.recycle()
            }
        }
    }

    fun undoLastAction(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val page = pages[index]
        if (page.actions.isEmpty()) return
        val base = page.baseBitmap ?: return

        val newActions = page.actions.dropLast(1)

        viewModelScope.launch {
            val newPreview = applyActions(base, newActions)
            val thumbnail = withContext(Dispatchers.Default) { makeThumbnail(newPreview) }

            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) {
                pages[i].previewBitmap?.recycle()
                pages[i].thumbnailBitmap?.recycle()
                pages[i] = pages[i].copy(
                    actions         = newActions,
                    previewBitmap   = newPreview,
                    thumbnailBitmap = thumbnail
                )
            } else {
                newPreview.recycle()
                thumbnail.recycle()
            }
        }
    }

    fun resetPage(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return

        viewModelScope.launch {
            val newBase = withContext(Dispatchers.IO) {
                ImageUtils.decodeUri(getApplication(), pages[index].uri, previewResolution.value)
            } ?: return@launch

            val newPreview = withContext(Dispatchers.Default) {
                newBase.copy(newBase.config ?: Bitmap.Config.ARGB_8888, true)
            }
            val thumbnail = withContext(Dispatchers.Default) { makeThumbnail(newPreview) }

            val i = pages.indexOfFirst { it.id == id }
            if (i != -1) {
                recyclePage(pages[i])
                pages[i] = pages[i].copy(
                    actions         = emptyList(),
                    baseBitmap      = newBase,
                    previewBitmap   = newPreview,
                    thumbnailBitmap = thumbnail
                )
            } else {
                newBase.recycle()
                newPreview.recycle()
                thumbnail.recycle()
            }
        }
    }

    // ── PDF export ────────────────────────────────────────────────────────────

    fun exportPdf(
        outputDir: File,
        fileName: String = "scan.pdf",
        pageSize: PageSize = PageSize.A4,
        quality: Int = SettingsRepository.DEFAULT_JPEG_QUALITY
    ) {
        viewModelScope.launch {
            _exportState.value = ExportState.Building
            try {
                val file = PdfBuilder.build(
                    context                 = getApplication(),
                    pages                   = pages.toList(),
                    outputDir               = outputDir,
                    fileName                = fileName,
                    pageSize                = pageSize,
                    globalQuality           = quality,
                    globalExportResolution  = exportResolution.value
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

    // ── Per-page overrides ────────────────────────────────────────────────────

    /**
     * Set or clear the per-page export resolution override.
     * Pass null to revert to the global setting default.
     */
    fun setPageExportResolution(id: String, resolution: Int?) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        pages[index] = pages[index].copy(exportResolution = resolution)
    }

    /**
     * Set or clear the per-page JPEG quality override.
     * Pass null to revert to the global setting default.
     */
    fun setPageJpegQuality(id: String, quality: Int?) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        pages[index] = pages[index].copy(jpegQuality = quality)
    }
}
