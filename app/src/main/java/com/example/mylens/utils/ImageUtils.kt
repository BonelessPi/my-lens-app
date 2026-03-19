package com.example.mylens.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

object ImageUtils {

    /**
     * Decode a URI to a Bitmap, automatically correcting EXIF orientation.
     * Works for JPEG, PNG, WebP, and HEIC (API 26+ native decoding).
     *
     * @param maxDimension  Downsamples large images so the longest side <= this value.
     *                      Use 2048 for preview quality, 4096 for export quality.
     */
    fun decodeUri(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? {
        return try {
            // First pass: get dimensions only
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }

            // Calculate sample size
            opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, maxDimension)
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888

            // Second pass: decode with subsampling
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // Correct EXIF orientation
            val exifRotation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            applyExifRotation(bitmap, exifRotation)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Apply an additional manual rotation (from user interaction) on top of an already
     * EXIF-corrected bitmap. degrees should be 0, 90, 180, or 270.
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Calculate BitmapFactory inSampleSize to keep decoded image within maxDimension.
     */
    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val longestSide = maxOf(width, height)
        while (longestSide / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifRotation(bitmap: Bitmap, exifOrientation: Int): Bitmap {
        val degrees = when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else                                  -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it != bitmap) bitmap.recycle() }
    }
}
