package dev.bonelesspi.mylens.ui.screens

enum class PageSize(val label: String, val widthPt: Float, val heightPt: Float) {
    A4("A4 (210 × 297 mm)", 595f, 842f),
    LETTER("US Letter (8.5 × 11 in)", 612f, 792f),
    A3("A3 (297 × 420 mm)", 842f, 1191f),
    FIT_IMAGE("Fit to image", 0f, 0f)
}
