package dev.bonelesspi.mylens.utils

import android.graphics.Bitmap
import dev.bonelesspi.mylens.data.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

object CropUtils {

    /**
     * Apply a perspective warp to a bitmap using the four corner points from a [CropRect].
     * Corners are normalized 0f..1f relative to bitmap dimensions.
     */
    suspend fun warpPerspective(
        sourceBitmap: Bitmap,
        crop: CropRect,
        outputMaxDim: Int = 2048
    ): Bitmap = withContext(Dispatchers.Default) {
        val w = sourceBitmap.width.toFloat()
        val h = sourceBitmap.height.toFloat()

        val srcPoints = MatOfPoint2f(
            Point((crop.topLeft.first     * w).toDouble(), (crop.topLeft.second     * h).toDouble()),
            Point((crop.topRight.first    * w).toDouble(), (crop.topRight.second    * h).toDouble()),
            Point((crop.bottomRight.first * w).toDouble(), (crop.bottomRight.second * h).toDouble()),
            Point((crop.bottomLeft.first  * w).toDouble(), (crop.bottomLeft.second  * h).toDouble())
        )

        val topWidth    = distance(crop.topLeft,    crop.topRight,    w, h)
        val bottomWidth = distance(crop.bottomLeft, crop.bottomRight, w, h)
        val leftHeight  = distance(crop.topLeft,    crop.bottomLeft,  w, h)
        val rightHeight = distance(crop.topRight,   crop.bottomRight, w, h)
        val maxWidth    = maxOf(topWidth, bottomWidth)
        val maxHeight   = maxOf(leftHeight, rightHeight)

        val scale = outputMaxDim / maxOf(maxWidth, maxHeight)
        val outW = maxWidth * scale
        val outH = maxHeight * scale

        val dstPoints = MatOfPoint2f(
            Point(0.0,      0.0),
            Point(outW - 1, 0.0),
            Point(outW - 1, outH - 1),
            Point(0.0,      outH - 1)
        )

        val srcMat = Mat()
        Utils.bitmapToMat(sourceBitmap, srcMat)

        val transformMat = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val dstMat = Mat()
        Imgproc.warpPerspective(srcMat, dstMat, transformMat, Size(outW, outH), Imgproc.INTER_LINEAR)

        val resultBitmap = Bitmap.createBitmap(dstMat.cols(), dstMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMat, resultBitmap)

        srcMat.release(); dstMat.release(); transformMat.release()
        resultBitmap
    }

    /**
     * Auto-detect document corners in a bitmap.
     *
     * Strategy (in order of preference):
     * 1. Find the largest 4-sided contour using Canny edge detection at multiple thresholds
     * 2. If no quad found, find the largest contour of any shape and fit a bounding quad
     * 3. Fall back to full-image rectangle
     *
     * Returns a [CropRect] in normalized 0f..1f coordinates.
     */
    suspend fun detectDocumentCorners(bitmap: Bitmap): CropRect = withContext(Dispatchers.Default) {
        try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            val w = mat.cols().toDouble()
            val h = mat.rows().toDouble()

            // Preprocess: grayscale + blur
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            // Try multiple Canny thresholds from tight to loose
            val cannyThresholds = listOf(
                Pair(50.0, 150.0),
                Pair(30.0, 100.0),
                Pair(10.0,  50.0)
            )

            var bestQuad: MatOfPoint2f? = null
            var bestArea = 0.0
            // Minimum area: 10% of image (loosened from 20%)
            val minArea = w * h * 0.10

            for ((low, high) in cannyThresholds) {
                val edges = Mat()
                Imgproc.Canny(gray, edges, low, high)

                // Dilate to connect gaps
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.dilate(edges, edges, kernel)
                kernel.release()

                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(
                    edges, contours, Mat(),
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
                )
                edges.release()

                // Try to find a quad at this threshold
                for (contour in contours) {
                    val contour2f = MatOfPoint2f(*contour.toArray())
                    val peri = Imgproc.arcLength(contour2f, true)

                    // Try a range of approximation epsilons to find a 4-sided shape
                    for (epsilonFactor in listOf(0.02, 0.04, 0.06)) {
                        val approx = MatOfPoint2f()
                        Imgproc.approxPolyDP(contour2f, approx, epsilonFactor * peri, true)
                        if (approx.rows() == 4) {
                            val area = Imgproc.contourArea(approx)
                            if (area > minArea && area > bestArea) {
                                bestArea = area
                                bestQuad = approx
                            }
                        }
                    }
                }

                if (bestQuad != null) break  // Found a good quad, no need to try looser thresholds
            }

            // Fallback: if no quad found, use the bounding rect of the largest contour
            if (bestQuad == null) {
                val edges = Mat()
                Imgproc.Canny(gray, edges, 10.0, 50.0)
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
                Imgproc.dilate(edges, edges, kernel)
                kernel.release()

                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(
                    edges, contours, Mat(),
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
                )
                edges.release()

                val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
                if (largest != null && Imgproc.contourArea(largest) > minArea) {
                    val rect = Imgproc.boundingRect(largest)
                    // Convert bounding rect to a CropRect quad
                    val l = rect.x.toDouble()
                    val t = rect.y.toDouble()
                    val r = (rect.x + rect.width).toDouble()
                    val b = (rect.y + rect.height).toDouble()
                    bestQuad = MatOfPoint2f(
                        Point(l, t), Point(r, t),
                        Point(r, b), Point(l, b)
                    )
                }
            }

            mat.release(); gray.release()

            if (bestQuad != null) {
                val pts = orderPoints(bestQuad.toArray())
                CropRect(
                    topLeft     = Pair((pts[0].x / w).toFloat(), (pts[0].y / h).toFloat()),
                    topRight    = Pair((pts[1].x / w).toFloat(), (pts[1].y / h).toFloat()),
                    bottomRight = Pair((pts[2].x / w).toFloat(), (pts[2].y / h).toFloat()),
                    bottomLeft  = Pair((pts[3].x / w).toFloat(), (pts[3].y / h).toFloat())
                )
            } else {
                CropRect() // full image
            }
        } catch (e: Exception) {
            e.printStackTrace()
            CropRect()
        }
    }

    private fun orderPoints(pts: Array<Point>): Array<Point> {
        val sortedBySum = pts.sortedBy { it.x + it.y }
        val topLeft     = sortedBySum[0]
        val bottomRight = sortedBySum[3]
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
