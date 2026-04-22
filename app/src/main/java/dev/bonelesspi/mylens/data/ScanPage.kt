package dev.bonelesspi.mylens.data

import android.graphics.Bitmap
import android.net.Uri
import java.util.UUID

/**
 * Represents a single page in the scan document.
 *
 * @param id                  Unique ID for Compose keying and ViewModel lookups.
 * @param uri                 Original source image URI — never modified. Always the reset target.
 * @param actions             Ordered list of edits applied to this page. This is the source of
 *                            truth for the page's edit state. Applied in order to produce both
 *                            the preview bitmap (at preview resolution) and the export bitmap
 *                            (at full resolution decoded fresh from [uri]).
 * @param exportResolution    Per-page export resolution override (longest side in pixels).
 *                            Null means inherit the global default from settings.
 * @param jpegQuality         Per-page JPEG quality override (50–100).
 *                            Null means inherit the global default from settings.
 * @param baseBitmap          The source URI decoded at preview resolution (~720p) with NO actions
 *                            applied. Cached to avoid re-decoding from storage on every undo.
 *                            Null until the page is first opened in EditScreen.
 *                            Lives as long as the page exists — recycled on page removal or clearAll.
 * @param previewBitmap       [baseBitmap] with all [actions] applied. Shown in EditScreen.
 *                            Null until first EditScreen open. Replaced whenever actions change.
 * @param thumbnailBitmap     Low-res (~144px) copy of [previewBitmap] for SelectScreen list.
 *                            Regenerated whenever [previewBitmap] changes.
 */
data class ScanPage(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val actions: List<EditAction> = emptyList(),
    val exportResolution: Int? = null,
    val jpegQuality: Int? = null,
    val baseBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val thumbnailBitmap: Bitmap? = null
)
