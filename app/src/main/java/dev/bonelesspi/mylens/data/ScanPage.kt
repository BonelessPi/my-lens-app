package dev.bonelesspi.mylens.data

import android.net.Uri
import java.util.UUID

/**
 * Represents a single page in the scan document.
 *
 * @param id          Unique ID used as the key for Compose recomposition and reordering.
 * @param uri         The source URI of the image (from gallery or camera).
 * @param rotation    Clockwise rotation in degrees: 0, 90, 180, or 270.
 * @param cropRect    Optional normalized crop rect (0f..1f) as (left, top, right, bottom).
 *                    Null means no crop applied. Will be used when OpenCV warp is added.
 */
data class ScanPage(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val rotation: Int = 0,
    val cropRect: CropRect? = null
)

/**
 * Normalized crop/warp quad. Each value is in 0f..1f relative to image dimensions.
 * topLeft, topRight, bottomRight, bottomLeft represent the four corners of
 * the desired crop region — use for perspective warp later.
 */
data class CropRect(
    val topLeft: Pair<Float, Float>     = Pair(0f, 0f),
    val topRight: Pair<Float, Float>    = Pair(1f, 0f),
    val bottomRight: Pair<Float, Float> = Pair(1f, 1f),
    val bottomLeft: Pair<Float, Float>  = Pair(0f, 1f)
)
