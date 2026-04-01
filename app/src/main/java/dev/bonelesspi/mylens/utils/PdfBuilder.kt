package dev.bonelesspi.mylens.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import dev.bonelesspi.mylens.data.EditAction
import dev.bonelesspi.mylens.data.ScanPage
import dev.bonelesspi.mylens.ui.screens.PageSize
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize as ITextPageSize
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
     * Build a PDF from the given pages.
     *
     * For each page, decodes the source URI fresh at [exportResolution] and applies
     * the page's action list at full quality. This ensures exported PDFs are always
     * at maximum quality regardless of the preview resolution used during editing.
     *
     * Pages with no actions are simply decoded and encoded directly.
     */
    suspend fun build(
        context: Context,
        pages: List<ScanPage>,
        outputDir: File,
        fileName: String,
        pageSize: PageSize = PageSize.A4,
        quality: Int = 90,
        exportResolution: Int = 4096
    ): File = withContext(Dispatchers.IO) {

        val cacheFile = File(context.cacheDir, fileName)

        val writer = PdfWriter(cacheFile)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc)
        document.setMargins(0f, 0f, 0f, 0f)

        pages.forEach { page ->
            // Always decode fresh from URI at full export resolution
            var bitmap = ImageUtils.decodeUri(
                context, page.uri, maxDimension = exportResolution
            ) ?: return@forEach

            // Apply the action stack at full resolution
            for (action in page.actions) {
                val next = when (action) {
                    is EditAction.Rotate -> ImageUtils.rotateBitmap(bitmap, 90)
                    is EditAction.Warp   -> CropUtils.warpPerspective(
                        bitmap, action.crop, outputMaxDim = exportResolution
                    )
                }
                bitmap.recycle()
                bitmap = next
            }

            val bytes = bitmapToJpegBytes(bitmap, quality)
            val (pdfW, pdfH) = resolvePageSize(pageSize, bitmap.width.toFloat(), bitmap.height.toFloat())
            bitmap.recycle()

            pdfDoc.addNewPage(ITextPageSize(pdfW, pdfH))
            val imageData = ImageDataFactory.create(bytes)
            val pdfImage = Image(imageData).apply {
                setFixedPosition(pdfDoc.numberOfPages, 0f, 0f)
                setWidth(pdfW)
                setHeight(pdfH)
            }
            document.add(pdfImage)
        }

        document.close()

        val header = cacheFile.inputStream().use { it.readNBytes(4) }
        if (header.toString(Charsets.US_ASCII) != "%PDF") {
            cacheFile.delete()
            throw IllegalStateException("iText7 did not produce a valid PDF file")
        }

        val outputFile = copyToDestination(context, cacheFile, fileName, outputDir)
        cacheFile.delete()
        outputFile
    }

    private fun copyToDestination(
        context: Context,
        src: File,
        fileName: String,
        outputDir: File
    ): File {
        val resolver = context.contentResolver
        val externalRoot = Environment.getExternalStorageDirectory().absolutePath
        val relativePath = if (outputDir.absolutePath.startsWith(externalRoot)) {
            outputDir.absolutePath.removePrefix(externalRoot).trimStart('/')
        } else {
            Environment.DIRECTORY_DOCUMENTS
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }

        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(fileName, "$relativePath/"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                resolver.delete(MediaStore.Files.getContentUri("external", id), null, null)
            }
        }

        val uri = resolver.insert(
            MediaStore.Files.getContentUri("external"),
            contentValues
        ) ?: throw IllegalStateException("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Could not open MediaStore output stream")

        return File(outputDir, fileName)
    }

    private fun resolvePageSize(pageSize: PageSize, imgW: Float, imgH: Float): Pair<Float, Float> {
        return if (pageSize == PageSize.FIT_IMAGE) {
            val maxPt = 2000f
            val scale = if (maxOf(imgW, imgH) > maxPt) maxPt / maxOf(imgW, imgH) else 1f
            Pair(imgW * scale, imgH * scale)
        } else {
            val maxW = pageSize.widthPt
            val maxH = pageSize.heightPt
            val (w, h) = if (imgW > imgH && maxW < maxH) Pair(maxH, maxW) else Pair(maxW, maxH)
            val scale = minOf(w / imgW, h / imgH)
            Pair(imgW * scale, imgH * scale)
        }
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
