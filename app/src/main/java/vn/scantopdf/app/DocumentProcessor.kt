package vn.scantopdf.app

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object DocumentProcessor {
    @Volatile
    private var ready = false

    fun init(): Boolean {
        if (!ready) ready = OpenCVLoader.initLocal()
        return ready
    }

    fun detectNormalizedCorners(bitmap: Bitmap): List<PointF>? {
        if (!init()) return null

        val maxSide = max(bitmap.width, bitmap.height)
        val scale = if (maxSide > 1400) 1400.0 / maxSide else 1.0
        val workBitmap = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap

        val src = Mat()
        val gray = Mat()
        val edges = Mat()
        Utils.bitmapToMat(workBitmap, src)
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(gray, edges, 60.0, 180.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.dilate(edges, edges, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val imageArea = workBitmap.width.toDouble() * workBitmap.height
        var best: Array<Point>? = null
        var bestArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < imageArea * 0.18 || area <= bestArea) continue

            val curve = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            val perimeter = Imgproc.arcLength(curve, true)
            Imgproc.approxPolyDP(curve, approx, 0.02 * perimeter, true)
            val points = approx.toArray()
            if (points.size == 4) {
                val poly = MatOfPoint(*points)
                if (Imgproc.isContourConvex(poly)) {
                    best = points
                    bestArea = area
                }
                poly.release()
            }
            curve.release()
            approx.release()
        }

        contours.forEach { it.release() }
        src.release()
        gray.release()
        edges.release()
        kernel.release()
        if (workBitmap !== bitmap) workBitmap.recycle()

        val ordered = best?.let { orderCorners(it.toList()) } ?: return null
        val w = (bitmap.width * scale).toFloat()
        val h = (bitmap.height * scale).toFloat()
        return ordered.map {
            PointF(
                (it.x.toFloat() / w).coerceIn(0f, 1f),
                (it.y.toFloat() / h).coerceIn(0f, 1f)
            )
        }
    }

    fun autoCorrect(bitmap: Bitmap): Bitmap {
        val corners = detectNormalizedCorners(bitmap) ?: return bitmap
        return warpNormalized(bitmap, corners)
    }

    fun warpNormalized(bitmap: Bitmap, normalized: List<PointF>): Bitmap {
        if (!init() || normalized.size != 4) return bitmap
        val pts = normalized.map {
            Point(
                (it.x.coerceIn(0f, 1f) * bitmap.width).toDouble(),
                (it.y.coerceIn(0f, 1f) * bitmap.height).toDouble()
            )
        }
        val ordered = orderCorners(pts)

        val widthTop = distance(ordered[0], ordered[1])
        val widthBottom = distance(ordered[3], ordered[2])
        val heightLeft = distance(ordered[0], ordered[3])
        val heightRight = distance(ordered[1], ordered[2])

        val outW = max(widthTop, widthBottom).toInt().coerceAtLeast(64)
        val outH = max(heightLeft, heightRight).toInt().coerceAtLeast(64)

        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val srcPts = MatOfPoint2f(
            ordered[0], ordered[1], ordered[2], ordered[3]
        )
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((outW - 1).toDouble(), 0.0),
            Point((outW - 1).toDouble(), (outH - 1).toDouble()),
            Point(0.0, (outH - 1).toDouble())
        )
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val dst = Mat(outH, outW, CvType.CV_8UC4)
        Imgproc.warpPerspective(
            src, dst, transform, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
        )

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, result)

        src.release()
        srcPts.release()
        dstPts.release()
        transform.release()
        dst.release()
        return result
    }

    private fun orderCorners(input: List<Point>): List<Point> {
        val tl = input.minBy { it.x + it.y }
        val br = input.maxBy { it.x + it.y }
        val tr = input.minBy { it.y - it.x }
        val bl = input.maxBy { it.y - it.x }
        return listOf(tl, tr, br, bl)
    }

    private fun distance(a: Point, b: Point): Double =
        hypot(a.x - b.x, a.y - b.y)
}
