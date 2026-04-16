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
import androidx.core.graphics.scale

sealed class ExportState {
    object Idle : ExportState()
    object Building : ExportState()
    data class Done(val file: File) : ExportState()
    data class Error(val message: String) : ExportState()
}

/** Longest side in pixels for the preview bitmap shown in EditScreen. ~720p. */
private const val PREVIEW_RESOLUTION = 1280

/** Longest side in pixels for the thumbnail shown in SelectScreen. ~144p. */
private const val THUMBNAIL_RESOLUTION = 144

/** Longest side in pixels for export. Decoded fresh from URI at export time. */
private const val EXPORT_RESOLUTION = 4096

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

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
     * Updates the page's originalWidth/originalHeight fields in place.
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

    /** Recycle all bitmaps owned by a page. */
    private fun recyclePage(page: ScanPage) {
        page.baseBitmap?.recycle()
        page.previewBitmap?.recycle()
        page.thumbnailBitmap?.recycle()
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    private fun makeThumbnail(source: Bitmap): Bitmap {
        val scale = THUMBNAIL_RESOLUTION.toFloat() / maxOf(source.width, source.height)
        val w = (source.width  * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return source.scale(w, h)
    }

    /**
     * Apply [actions] in order to [base], returning the resulting bitmap.
     * Each intermediate bitmap is recycled as we go to avoid accumulation.
     * Used for undo (re-apply remainder of stack) and export (full-res re-apply).
     * If actions is empty, returns a copy of [base].
     */
    private suspend fun applyActions(base: Bitmap, actions: List<EditAction>): Bitmap =
        withContext(Dispatchers.Default) {
            if (actions.isEmpty()) return@withContext base.copy(base.config ?: Bitmap.Config.ARGB_8888, true)
            var current = base
            for (action in actions) {
                val next = when (action) {
                    is EditAction.Rotate -> ImageUtils.rotateBitmap(current, 90)
                    is EditAction.Warp   -> CropUtils.warpPerspective(
                        current, action.crop, outputMaxDim = PREVIEW_RESOLUTION
                    )
                }
                // Recycle intermediate bitmaps but never recycle the original base
                if (current !== base) current.recycle()
                current = next
            }
            current
        }

    // ── EditScreen lifecycle ──────────────────────────────────────────────────

    /**
     * Ensure this page has a baseBitmap and previewBitmap ready for EditScreen.
     * If baseBitmap is already present (page was opened before), only regenerates
     * the previewBitmap if it is missing. No-op if both are already present.
     */
    fun ensurePreview(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val page = pages[index]

        // Both already ready — nothing to do
        if (page.baseBitmap != null && page.previewBitmap != null) return

        viewModelScope.launch {
            val base =
                page.baseBitmap
                    ?: (withContext(Dispatchers.IO) {
                        ImageUtils.decodeUri(getApplication(), page.uri, PREVIEW_RESOLUTION)
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

    /**
     * Rotate 90° CW: append action, update previewBitmap in-place from current preview.
     * Fast path — no re-apply from base needed.
     */
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

    /**
     * Warp: append action, update previewBitmap in-place from current preview.
     * Fast path — no re-apply from base needed.
     */
    fun applyWarp(id: String, crop: CropRect) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return
        val current = pages[index].previewBitmap ?: return

        viewModelScope.launch {
            val warped = CropUtils.warpPerspective(
                current, crop, outputMaxDim = PREVIEW_RESOLUTION
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

    /**
     * Undo the last action: pop from the action list, re-apply the remainder
     * to baseBitmap to produce the new previewBitmap.
     * No-op if the action list is empty.
     */
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

    /**
     * Reset a page: clear all actions, re-decode base from URI, regenerate preview.
     */
    fun resetPage(id: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index == -1) return

        viewModelScope.launch {
            val newBase = withContext(Dispatchers.IO) {
                ImageUtils.decodeUri(getApplication(), pages[index].uri, PREVIEW_RESOLUTION)
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
        quality: Int = 90
    ) {
        viewModelScope.launch {
            _exportState.value = ExportState.Building
            try {
                val file = PdfBuilder.build(
                    context          = getApplication(),
                    pages            = pages.toList(),
                    outputDir        = outputDir,
                    fileName         = fileName,
                    pageSize         = pageSize,
                    quality          = quality,
                    exportResolution = EXPORT_RESOLUTION
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
