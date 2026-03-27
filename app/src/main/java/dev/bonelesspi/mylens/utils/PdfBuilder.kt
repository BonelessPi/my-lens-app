package dev.bonelesspi.mylens.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.io.FileOutputStream

object PdfBuilder {

    suspend fun build(
        context: Context,
        pages: List<ScanPage>,
        outputDir: File,
        fileName: String,
        pageSize: PageSize = PageSize.A4,
        quality: Int = 90
    ): File = withContext(Dispatchers.IO) {

        // Write PDF to app-private cache first, then copy to Documents.
        // This avoids permission issues and ensures iText7 can always write
        // regardless of Android version.
        val cacheFile = File(context.cacheDir, fileName)

        val writer = PdfWriter(cacheFile)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc)
        document.setMargins(0f, 0f, 0f, 0f)

        pages.forEach { page ->
            val ownsBitmap: Boolean
            val bitmap: Bitmap

            if (page.workingBitmap != null) {
                bitmap = page.workingBitmap
                ownsBitmap = false
            } else {
                val decoded = ImageUtils.decodeUri(context, page.uri, maxDimension = 4096)
                    ?: return@forEach
                bitmap = decoded
                ownsBitmap = true
            }

            val bytes = bitmapToJpegBytes(bitmap, quality)
            val (pdfW, pdfH) = resolvePageSize(pageSize, bitmap.width.toFloat(), bitmap.height.toFloat())
            if (ownsBitmap) bitmap.recycle()

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

        // Copy from cache to public Documents folder
        val outputFile = copyToDocuments(context, cacheFile, fileName)
        cacheFile.delete()
        outputFile
    }

    /**
     * Copy the PDF from app cache to the public Documents folder.
     * Uses MediaStore on API 29+ for proper indexing; direct file copy on older versions.
     */
    private fun copyToDocuments(context: Context, src: File, fileName: String): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: use MediaStore so the file appears in Files app immediately
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
            }

            // Delete existing file with same name if present
            val existing = resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(fileName, "${Environment.DIRECTORY_DOCUMENTS}/"),
                null
            )
            existing?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    resolver.delete(
                        MediaStore.Files.getContentUri("external", id), null, null
                    )
                }
            }

            val uri = resolver.insert(
                MediaStore.Files.getContentUri("external"),
                contentValues
            ) ?: throw IllegalStateException("MediaStore insert failed")

            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not open MediaStore output stream")

            // Return a File object pointing to the expected location for the Done message
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                fileName
            )
        } else {
            // Below API 29: direct file write
            val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            destDir.mkdirs()
            val dest = File(destDir, fileName)
            src.inputStream().use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest
        }
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
