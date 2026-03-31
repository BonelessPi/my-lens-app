package dev.bonelesspi.mylens.utils

import android.graphics.Bitmap
import dev.bonelesspi.mylens.data.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap
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

        val resultBitmap = createBitmap(dstMat.cols(), dstMat.rows())
        Utils.matToBitmap(dstMat, resultBitmap)

        srcMat.release(); dstMat.release(); transformMat.release()
        resultBitmap
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
