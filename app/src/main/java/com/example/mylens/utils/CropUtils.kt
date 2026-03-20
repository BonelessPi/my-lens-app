package com.example.mylens.utils

import android.graphics.Bitmap
import com.example.mylens.data.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap
import kotlin.math.sqrt

object CropUtils {

    /**
     * Apply a perspective warp to the decoded bitmap using the four corner points
     * from a [CropRect]. The corners are in normalized 0f..1f coordinates.
     *
     * Call this on Dispatchers.IO — OpenCV operations are CPU-intensive.
     *
     * @param sourceBitmap  Already-decoded, EXIF-corrected bitmap (from ImageUtils.decodeUri)
     * @param crop          The four corners of the desired quad in normalized coordinates
     * @param outputMaxDim  Longest side of the output bitmap (default 2048 for preview,
     *                      use 4096 for PDF export)
     * @return              The warped, cropped bitmap
     */
    suspend fun warpPerspective(
        sourceBitmap: Bitmap,
        crop: CropRect,
        outputMaxDim: Int = 2048
    ): Bitmap = withContext(Dispatchers.Default) {
        val w = sourceBitmap.width.toFloat()
        val h = sourceBitmap.height.toFloat()

        // Convert normalized corners to pixel coordinates
        val srcPoints = MatOfPoint2f(
            Point((crop.topLeft.first     * w).toDouble(), (crop.topLeft.second     * h).toDouble()),
            Point((crop.topRight.first    * w).toDouble(), (crop.topRight.second    * h).toDouble()),
            Point((crop.bottomRight.first * w).toDouble(), (crop.bottomRight.second * h).toDouble()),
            Point((crop.bottomLeft.first  * w).toDouble(), (crop.bottomLeft.second  * h).toDouble())
        )

        // Calculate output dimensions preserving aspect ratio of the warped region
        val outW: Double
        val outH: Double
        val topWidth    = distance(crop.topLeft,    crop.topRight,    w, h)
        val bottomWidth = distance(crop.bottomLeft, crop.bottomRight, w, h)
        val leftHeight  = distance(crop.topLeft,    crop.bottomLeft,  w, h)
        val rightHeight = distance(crop.topRight,   crop.bottomRight, w, h)
        val maxWidth    = maxOf(topWidth, bottomWidth)
        val maxHeight   = maxOf(leftHeight, rightHeight)

        // Scale so longest side = outputMaxDim
        val scale = outputMaxDim / maxOf(maxWidth, maxHeight)
        outW = maxWidth * scale
        outH = maxHeight * scale

        val dstPoints = MatOfPoint2f(
            Point(0.0,      0.0),
            Point(outW - 1, 0.0),
            Point(outW - 1, outH - 1),
            Point(0.0,      outH - 1)
        )

        // Convert Bitmap → OpenCV Mat
        val srcMat = Mat()
        Utils.bitmapToMat(sourceBitmap, srcMat)

        // Compute and apply perspective transform
        val transformMat = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val dstMat = Mat()
        Imgproc.warpPerspective(
            srcMat, dstMat, transformMat,
            Size(outW, outH),
            Imgproc.INTER_LINEAR
        )

        // Convert back to Bitmap
        val resultBitmap = createBitmap(dstMat.cols(), dstMat.rows())
        Utils.matToBitmap(dstMat, resultBitmap)

        // Clean up
        srcMat.release()
        dstMat.release()
        transformMat.release()

        resultBitmap
    }

    /**
     * Attempt to auto-detect document corners in a bitmap using OpenCV contour detection.
     * Returns a [CropRect] with the detected corners, or a full-image default if detection fails.
     */
    suspend fun detectDocumentCorners(bitmap: Bitmap): CropRect = withContext(Dispatchers.Default) {
        try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // Convert to grayscale, blur, edge detect
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            val edges = Mat()
            Imgproc.Canny(gray, edges, 75.0, 200.0)

            // Dilate edges to connect gaps
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, edges, kernel)

            // Find contours
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                edges, contours, Mat(),
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            // Find largest quadrilateral contour
            val w = mat.cols().toDouble()
            val h = mat.rows().toDouble()
            val minArea = w * h * 0.2  // Must cover at least 20% of image

            var bestQuad: MatOfPoint2f? = null
            var bestArea = 0.0

            for (contour in contours) {
                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)

                if (approx.rows() == 4) {
                    val area = Imgproc.contourArea(approx)
                    if (area > minArea && area > bestArea) {
                        bestArea = area
                        bestQuad = approx
                    }
                }
            }

            // Clean up
            mat.release(); gray.release(); edges.release(); kernel.release()

            if (bestQuad != null) {
                val pts = orderPoints(bestQuad.toArray())
                CropRect(
                    topLeft     = Pair((pts[0].x / w).toFloat(), (pts[0].y / h).toFloat()),
                    topRight    = Pair((pts[1].x / w).toFloat(), (pts[1].y / h).toFloat()),
                    bottomRight = Pair((pts[2].x / w).toFloat(), (pts[2].y / h).toFloat()),
                    bottomLeft  = Pair((pts[3].x / w).toFloat(), (pts[3].y / h).toFloat())
                )
            } else {
                CropRect() // default: full image
            }
        } catch (e: Exception) {
            e.printStackTrace()
            CropRect()
        }
    }

    /**
     * Order four points as: top-left, top-right, bottom-right, bottom-left.
     * This matches the expected winding for getPerspectiveTransform.
     */
    private fun orderPoints(pts: Array<Point>): Array<Point> {
        // Sort by sum (x+y): smallest = top-left, largest = bottom-right
        val sortedBySum = pts.sortedBy { it.x + it.y }
        val topLeft     = sortedBySum[0]
        val bottomRight = sortedBySum[3]

        // Sort remaining by difference (x-y): smallest = bottom-left, largest = top-right
        val remaining   = listOf(sortedBySum[1], sortedBySum[2]).sortedBy { it.x - it.y }
        val topRight    = remaining[1]
        val bottomLeft  = remaining[0]

        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(
        a: Pair<Float, Float>, b: Pair<Float, Float>,
        imgW: Float, imgH: Float
    ): Double {
        val dx = (b.first - a.first) * imgW
        val dy = (b.second - a.second) * imgH
        return sqrt((dx * dx + dy * dy).toDouble())
    }
}
