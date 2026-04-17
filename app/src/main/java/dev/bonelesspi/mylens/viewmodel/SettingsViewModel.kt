package dev.bonelesspi.mylens.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bonelesspi.mylens.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    val repository = SettingsRepository(application)

    // ── State flows ───────────────────────────────────────────────────────────
    // Each flow is converted to a StateFlow so the settings screen can read the
    // current value immediately without waiting for the first emission.

    val exportResolution = repository.exportResolution.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsRepository.DEFAULT_EXPORT_RESOLUTION
    )

    val previewResolution = repository.previewResolution.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsRepository.DEFAULT_PREVIEW_RESOLUTION
    )

    val thumbnailResolution = repository.thumbnailResolution.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsRepository.DEFAULT_THUMBNAIL_RESOLUTION
    )

    val jpegQuality = repository.jpegQuality.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsRepository.DEFAULT_JPEG_QUALITY
    )

    val defaultPageSize = repository.defaultPageSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsRepository.DEFAULT_PAGE_SIZE
    )

    val outputFolderUri = repository.outputFolderUri.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsRepository.DEFAULT_OUTPUT_FOLDER_URI
    )

    // ── Write actions ─────────────────────────────────────────────────────────

    fun setExportResolution(value: Int) {
        viewModelScope.launch { repository.setExportResolution(value) }
    }

    fun setPreviewResolution(value: Int) {
        viewModelScope.launch { repository.setPreviewResolution(value) }
    }

    fun setThumbnailResolution(value: Int) {
        viewModelScope.launch { repository.setThumbnailResolution(value) }
    }

    fun setJpegQuality(value: Int) {
        viewModelScope.launch { repository.setJpegQuality(value) }
    }

    fun setDefaultPageSize(value: String) {
        viewModelScope.launch { repository.setDefaultPageSize(value) }
    }

    fun setOutputFolderUri(value: String) {
        viewModelScope.launch { repository.setOutputFolderUri(value) }
    }
}
