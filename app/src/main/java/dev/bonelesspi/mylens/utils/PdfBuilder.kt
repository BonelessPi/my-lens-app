package dev.bonelesspi.mylens.utils

import android.content.Context
import android.graphics.Bitmap
import dev.bonelesspi.mylens.data.ScanPage
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
     * Applies perspective warp (if a CropRect is set) and rotation per page.
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
            var bitmap = ImageUtils.decodeUri(context, page.uri, maxDimension = 4096)
                ?: return@forEach

            // Apply perspective warp if a crop quad is set
            if (page.cropRect != null) {
                val warped = CropUtils.warpPerspective(bitmap, page.cropRect, outputMaxDim = 4096)
                bitmap.recycle()
                bitmap = warped
            }

            // Apply manual rotation
            if (page.rotation != 0) {
                val rotated = ImageUtils.rotateBitmap(bitmap, page.rotation)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }

            // Convert bitmap to JPEG bytes for iText7
            val bytes = bitmapToJpegBytes(bitmap, quality = 90)
            val (pdfW, pdfH) = fitToA4Points(bitmap.width.toFloat(), bitmap.height.toFloat())
            bitmap.recycle()

            // Add page sized exactly to fit the image
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

    private fun fitToA4Points(imgW: Float, imgH: Float): Pair<Float, Float> {
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
