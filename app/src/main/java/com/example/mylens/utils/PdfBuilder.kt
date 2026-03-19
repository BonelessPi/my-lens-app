package com.example.mylens.utils

import android.content.Context
import android.graphics.Bitmap
import com.example.docscanner.data.ScanPage
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

object PdfBuilder {

    /**
     * Build a PDF from the list of ScanPages.
     * Each page is sized to fit the image exactly (no margins).
     * Runs on Dispatchers.IO.
     */
    suspend fun build(
        context: Context,
        pages: List<ScanPage>,
        outputDir: File,
        fileName: String
    ): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val outputFile = File(outputDir, fileName)

        val writer = PdfWriter(outputFile)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc)
        document.setMargins(0f, 0f, 0f, 0f)

        pages.forEach { page ->
            // Decode at full quality for export
            val bitmap = ImageUtils.decodeUri(context, page.uri, maxDimension = 4096)
                ?: return@forEach

            // Apply manual rotation on top of EXIF-corrected bitmap
            val rotated = if (page.rotation != 0) {
                ImageUtils.rotateBitmap(bitmap, page.rotation).also { bitmap.recycle() }
            } else {
                bitmap
            }

            // Convert bitmap to JPEG bytes
            val bytes = bitmapToJpegBytes(rotated, quality = 90)
            rotated.recycle()

            // Create a PDF page sized exactly to the image (points = pixels here; iText uses 72dpi)
            // For a scanner app we want the PDF page to match the image aspect, so we use
            // a reasonable A4-like size scaled to fit.
            val (pdfW, pdfH) = fitToA4Points(rotated.width.toFloat(), rotated.height.toFloat())
            val pageSize = PageSize(pdfW, pdfH)
            pdfDoc.addNewPage(pageSize)

            val imageData = ImageDataFactory.create(bytes)
            val pdfImage = Image(imageData).apply {
                setFixedPosition(pdfDoc.numberOfPages, 0f, 0f)
                setWidth(pdfW)
                setHeight(pdfH)
            }
            document.add(pdfImage)
        }

        document.close()
        outputFile
    }

    /**
     * Scale image dimensions so the longest side fits within standard A4 at 150dpi equivalent.
     * Returns (width, height) in PDF points (1 point = 1/72 inch).
     */
    private fun fitToA4Points(imgW: Float, imgH: Float): Pair<Float, Float> {
        // A4 in points: 595 x 842
        val maxW = 595f
        val maxH = 842f
        val scale = minOf(maxW / imgW, maxH / imgH)
        return Pair(imgW * scale, imgH * scale)
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
