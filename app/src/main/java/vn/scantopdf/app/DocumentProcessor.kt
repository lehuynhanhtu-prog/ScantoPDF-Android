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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
        val scale = if (maxSide > 1500) 1500.0 / maxSide else 1.0
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
        val blur = Mat()
        val edges = Mat()
        val closed = Mat()
        Utils.bitmapToMat(workBitmap, src)
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(blur, edges, 50.0, 150.0)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.dilate(closed, closed, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            closed,
            contours,
            Mat(),
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val imageW = workBitmap.width.toDouble()
        val imageH = workBitmap.height.toDouble()
        val imageArea = imageW * imageH

        var best: List<Point>? = null
        var bestScore = Double.NEGATIVE_INFINITY

        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }.take(12)
        val epsilons = doubleArrayOf(0.012, 0.018, 0.024, 0.032, 0.045)

        for (contour in sorted) {
            val contourArea = Imgproc.contourArea(contour)
            val areaRatio = contourArea / imageArea
            if (areaRatio < 0.28) continue

            val curve = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(curve, true)

            for (epsilon in epsilons) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(curve, approx, epsilon * perimeter, true)
                val points = approx.toArray()
                approx.release()

                if (points.size != 4) continue
                val poly = MatOfPoint(*points)
                val convex = Imgproc.isContourConvex(poly)
                val quadArea = abs(Imgproc.contourArea(poly))
                poly.release()
                if (!convex) continue

                val quadAreaRatio = quadArea / imageArea
                if (quadAreaRatio < 0.30) continue

                val ordered = orderCorners(points.toList())
                val top = distance(ordered[0], ordered[1])
                val bottom = distance(ordered[3], ordered[2])
                val left = distance(ordered[0], ordered[3])
                val right = distance(ordered[1], ordered[2])

                val avgW = (top + bottom) / 2.0
                val avgH = (left + right) / 2.0
                if (avgW < imageW * 0.28 || avgH < imageH * 0.28) continue

                val ratio = avgW / avgH
                if (ratio < 0.32 || ratio > 3.1) continue

                val edgeBalance =
                    min(top, bottom) / max(top, bottom) *
                    min(left, right) / max(left, right)
                if (edgeBalance < 0.38) continue

                val minX = ordered.minOf { it.x }
                val maxX = ordered.maxOf { it.x }
                val minY = ordered.minOf { it.y }
                val maxY = ordered.maxOf { it.y }
                val boxCoverage = ((maxX - minX) * (maxY - minY)) / imageArea

                // Ưu tiên vùng giấy lớn, hình học cân đối, gần vùng bao ngoài.
                val score =
                    quadAreaRatio * 5.0 +
                    edgeBalance * 1.6 +
                    boxCoverage * 1.8

                if (score > bestScore) {
                    bestScore = score
                    best = ordered
                }
            }
            curve.release()
        }

        contours.forEach { it.release() }
        src.release()
        gray.release()
        blur.release()
        edges.release()
        closed.release()
        kernel.release()

        if (workBitmap !== bitmap) workBitmap.recycle()

        val detected = best ?: return null

        // Nới nhẹ ra ngoài để tránh cắt sát mất mép giấy/chữ.
        val cx = detected.map { it.x }.average()
        val cy = detected.map { it.y }.average()
        val padded = detected.map { p ->
            val px = cx + (p.x - cx) * 1.018
            val py = cy + (p.y - cy) * 1.018
            Point(
                px.coerceIn(0.0, imageW - 1.0),
                py.coerceIn(0.0, imageH - 1.0)
            )
        }

        return padded.map {
            PointF(
                (it.x / imageW).toFloat().coerceIn(0f, 1f),
                (it.y / imageH).toFloat().coerceIn(0f, 1f)
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
            src,
            dst,
            transform,
            Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_CUBIC,
            Core.BORDER_REPLICATE
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

    fun enhanceColorBackground(bitmap: Bitmap): Bitmap {
        if (!init()) return bitmap

        val rgba = Mat()
        val rgb = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

        val channels = mutableListOf<Mat>()
        Core.split(rgb, channels)
        val enhancedChannels = mutableListOf<Mat>()

        for (channel in channels) {
            val background = Mat()
            val normalized = Mat()
            val blended = Mat()
            Imgproc.GaussianBlur(channel, background, Size(0.0, 0.0), 25.0)
            Core.divide(channel, background, normalized, 245.0)
            Core.addWeighted(normalized, 0.78, channel, 0.22, 0.0, blended)
            enhancedChannels.add(blended)
            background.release()
            normalized.release()
        }

        val merged = Mat()
        Core.merge(enhancedChannels, merged)
        val outRgba = Mat()
        Imgproc.cvtColor(merged, outRgba, Imgproc.COLOR_RGB2RGBA)
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, result)

        channels.forEach { it.release() }
        enhancedChannels.forEach { it.release() }
        rgba.release()
        rgb.release()
        merged.release()
        outRgba.release()
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
