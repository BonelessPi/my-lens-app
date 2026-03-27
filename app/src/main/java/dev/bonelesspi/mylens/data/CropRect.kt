package dev.bonelesspi.mylens.data

/**
- * Normalized crop/warp quad. Each value is in 0f..1f relative to image dimensions.
- * topLeft, topRight, bottomRight, bottomLeft represent the four corners of
- * the desired crop region â€” use for perspective warp later.
- */
data class CropRect(
    val topLeft: Pair<Float, Float>     = Pair(0f, 0f),
    val topRight: Pair<Float, Float>    = Pair(1f, 0f),
    val bottomRight: Pair<Float, Float> = Pair(1f, 1f),
    val bottomLeft: Pair<Float, Float>  = Pair(0f, 1f)
)
