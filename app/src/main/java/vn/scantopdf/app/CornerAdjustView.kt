package vn.scantopdf.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class CornerAdjustView(
    context: Context,
    private val bitmap: Bitmap,
    initial: List<PointF>
) : View(context) {

    private val corners = initial.map { PointF(it.x, it.y) }.toMutableList()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 180, 255)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 110, 220)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private var active = -1
    private var drawLeft = 0f
    private var drawTop = 0f
    private var drawWidth = 1f
    private var drawHeight = 1f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        drawWidth = bitmap.width * scale
        drawHeight = bitmap.height * scale
        drawLeft = (width - drawWidth) / 2f
        drawTop = (height - drawHeight) / 2f

        canvas.drawColor(Color.rgb(25, 29, 35))
        canvas.drawBitmap(
            bitmap,
            null,
            android.graphics.RectF(drawLeft, drawTop, drawLeft + drawWidth, drawTop + drawHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        val screen = corners.map { toScreen(it) }
        for (i in 0..3) {
            val a = screen[i]
            val b = screen[(i + 1) % 4]
            canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
        }
        screen.forEach {
            canvas.drawCircle(it.x, it.y, 19f, handlePaint)
            canvas.drawCircle(it.x, it.y, 19f, handleBorder)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = nearestCorner(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active >= 0) {
                    val nx = ((event.x - drawLeft) / drawWidth).coerceIn(0f, 1f)
                    val ny = ((event.y - drawTop) / drawHeight).coerceIn(0f, 1f)
                    corners[active].set(nx, ny)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun normalizedCorners(): List<PointF> = corners.map { PointF(it.x, it.y) }

    private fun toScreen(p: PointF): PointF =
        PointF(drawLeft + p.x * drawWidth, drawTop + p.y * drawHeight)

    private fun nearestCorner(x: Float, y: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        corners.forEachIndexed { index, p ->
            val s = toScreen(p)
            val dx = s.x - x
            val dy = s.y - y
            val d = dx * dx + dy * dy
            if (d < bestDistance) {
                bestDistance = d
                best = index
            }
        }
        return best
    }
}
