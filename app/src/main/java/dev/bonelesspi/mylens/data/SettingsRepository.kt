package dev.bonelesspi.mylens.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single DataStore instance per process, tied to the application context.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Persistent settings backed by Jetpack DataStore.
 *
 * All flows emit the current value immediately on collection and again
 * whenever the value changes. Writes are safe to call from any coroutine.
 *
 * Defaults match the previous hardcoded constants so existing behaviour
 * is preserved for users who have never opened the settings screen.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        // Pixel counts for the longest side of decoded bitmaps
        val KEY_EXPORT_RESOLUTION     = intPreferencesKey("export_resolution")
        val KEY_PREVIEW_RESOLUTION    = intPreferencesKey("preview_resolution")
        val KEY_THUMBNAIL_RESOLUTION  = intPreferencesKey("thumbnail_resolution")

        // JPEG quality 50–100
        val KEY_JPEG_QUALITY          = intPreferencesKey("jpeg_quality")

        // PageSize enum name, e.g. "A4", "LETTER"
        val KEY_DEFAULT_PAGE_SIZE     = stringPreferencesKey("default_page_size")

        // SAF URI string for the default output folder, empty = Documents
        val KEY_OUTPUT_FOLDER_URI     = stringPreferencesKey("output_folder_uri")

        // ── Defaults ──────────────────────────────────────────────────────────
        const val DEFAULT_EXPORT_RESOLUTION    = 4096
        const val DEFAULT_PREVIEW_RESOLUTION   = 1280
        const val DEFAULT_THUMBNAIL_RESOLUTION = 144
        const val DEFAULT_JPEG_QUALITY         = 90
        const val DEFAULT_PAGE_SIZE            = "A4"
        const val DEFAULT_OUTPUT_FOLDER_URI    = ""
    }

    // ── Flows ─────────────────────────────────────────────────────────────────

    val exportResolution: Flow<Int> = context.dataStore.data.map {
        it[KEY_EXPORT_RESOLUTION] ?: DEFAULT_EXPORT_RESOLUTION
    }

    val previewResolution: Flow<Int> = context.dataStore.data.map {
        it[KEY_PREVIEW_RESOLUTION] ?: DEFAULT_PREVIEW_RESOLUTION
    }

    val thumbnailResolution: Flow<Int> = context.dataStore.data.map {
        it[KEY_THUMBNAIL_RESOLUTION] ?: DEFAULT_THUMBNAIL_RESOLUTION
    }

    val jpegQuality: Flow<Int> = context.dataStore.data.map {
        it[KEY_JPEG_QUALITY] ?: DEFAULT_JPEG_QUALITY
    }

    val defaultPageSize: Flow<String> = context.dataStore.data.map {
        it[KEY_DEFAULT_PAGE_SIZE] ?: DEFAULT_PAGE_SIZE
    }

    val outputFolderUri: Flow<String> = context.dataStore.data.map {
        it[KEY_OUTPUT_FOLDER_URI] ?: DEFAULT_OUTPUT_FOLDER_URI
    }

    // ── Writers ───────────────────────────────────────────────────────────────

    suspend fun setExportResolution(value: Int) {
        context.dataStore.edit { it[KEY_EXPORT_RESOLUTION] = value }
    }

    suspend fun setPreviewResolution(value: Int) {
        context.dataStore.edit { it[KEY_PREVIEW_RESOLUTION] = value }
    }

    suspend fun setThumbnailResolution(value: Int) {
        context.dataStore.edit { it[KEY_THUMBNAIL_RESOLUTION] = value }
    }

    suspend fun setJpegQuality(value: Int) {
        context.dataStore.edit { it[KEY_JPEG_QUALITY] = value }
    }

    suspend fun setDefaultPageSize(value: String) {
        context.dataStore.edit { it[KEY_DEFAULT_PAGE_SIZE] = value }
    }

    suspend fun setOutputFolderUri(value: String) {
        context.dataStore.edit { it[KEY_OUTPUT_FOLDER_URI] = value }
    }
}
