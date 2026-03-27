package dev.bonelesspi.mylens.data

import android.graphics.Bitmap
import android.net.Uri
import java.util.UUID

/**
 * Represents a single page in the scan document.
 *
 * @param id             Unique ID for Compose keying and ViewModel lookups.
 * @param uri            Original source image URI — never modified. Used for reset.
 * @param workingBitmap  The current edit state of the image. Starts as a decoded
 *                       copy of [uri] at edit resolution, then has rotations and
 *                       perspective warps baked in destructively as the user edits.
 *                       Null until the page is first opened in EditScreen.
 *                       At export time, this bitmap is JPEG-encoded directly —
 *                       no further transforms are applied.
 */
data class ScanPage(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val workingBitmap: Bitmap? = null
)
