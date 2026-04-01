package dev.bonelesspi.mylens.data

/**
 * Represents a single edit operation applied to a page.
 * The action list is the source of truth for a page's edit state.
 * Actions are re-applied in order to produce the preview and export bitmaps.
 */
sealed class EditAction {
    /** Rotate 90° clockwise. */
    object Rotate : EditAction()

    /** Perspective warp using the given quad. */
    data class Warp(val crop: CropRect) : EditAction()
}
