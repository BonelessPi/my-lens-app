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

    /**
     * Build a PDF from the given pages and write it to [outputDir]/[fileName].
     *
     * For each page:
     * - If [ScanPage.workingBitmap] is present, it is encoded directly. The working
     *   bitmap is the authoritative edit state — no further decode or transform is applied.
     * - If no working bitmap exists (page was never opened in EditScreen), the source
     *   URI is decoded at [fallbackResolution] as a fallback. This ensures pages added
     *   to a scan but never edited still appear in the PDF at a consistent quality.
     *
     * [fallbackResolution] should match WORKING_RESOLUTION in ScannerViewModel so that
     * edited and unedited pages are encoded at the same quality ceiling.
     */
    suspend fun build(
        context: Context,
        pages: List<ScanPage>,
        outputDir: File,
        fileName: String,
        pageSize: PageSize = PageSize.A4,
        quality: Int = 90,
        fallbackResolution: Int = 4096
    ): File = withContext(Dispatchers.IO) {

        // Write to app-private cache first — iText7 can always write here regardless
        // of Android version or storage permissions. We copy to the target location after.
        val cacheFile = File(context.cacheDir, fileName)

        val writer = PdfWriter(cacheFile)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc)
        document.setMargins(0f, 0f, 0f, 0f)

        pages.forEach { page ->
            // Determine the bitmap source and whether we own it (must recycle after use)
            val ownsBitmap: Boolean
            val bitmap: Bitmap

            if (page.workingBitmap != null) {
                // Primary path: encode working bitmap directly, no transforms needed
                bitmap = page.workingBitmap
                ownsBitmap = false  // still owned by ViewModel, do not recycle
            } else {
                // Fallback: page was never edited — decode from source URI
                val decoded = ImageUtils.decodeUri(
                    context, page.uri, maxDimension = fallbackResolution
                ) ?: return@forEach
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

        // Verify the output is a valid PDF before copying it anywhere
        val header = cacheFile.inputStream().use { it.readNBytes(4) }
        if (header.toString(Charsets.US_ASCII) != "%PDF") {
            cacheFile.delete()
            throw IllegalStateException("iText7 did not produce a valid PDF file")
        }

        val outputFile = copyToDestination(context, cacheFile, fileName, outputDir)
        cacheFile.delete()
        outputFile
    }

    /**
     * Copy the finished PDF to its final destination.
     *
     * On API 29+ we use MediaStore so the file is immediately visible in the Files
     * app with the correct MIME type. On older versions we write directly.
     *
     * The [outputDir] parameter is used for direct file writes (API < 29) and as a
     * hint for the MediaStore RELATIVE_PATH on API 29+.
     */
    private fun copyToDestination(
        context: Context,
        src: File,
        fileName: String,
        outputDir: File
    ): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver

            // Derive a relative path for MediaStore from the output directory
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

            // Remove any existing file with the same name in the same folder
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

            File(outputDir, fileName)
        } else {
            outputDir.mkdirs()
            val dest = File(outputDir, fileName)
            src.inputStream().use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
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
