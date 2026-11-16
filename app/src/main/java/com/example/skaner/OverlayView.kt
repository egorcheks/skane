package com.example.skaner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Item(val box: Rect, val text: String)

    @Volatile
    private var items: List<Item> = emptyList()
    private var imgW = 1
    private var imgH = 1

    @Volatile
    var fitMode = false

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 20, 20, 20)
        style = Paint.Style.FILL
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        style = Paint.Style.STROKE
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    fun setItems(list: List<Item>, w: Int, h: Int) {
        items = list
        imgW = max(1, w)
        imgH = max(1, h)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawInto(canvas, width.toFloat(), height.toFloat(), fitMode,
            resources.displayMetrics.density)
    }

    fun drawInto(canvas: Canvas, areaW: Float, areaH: Float, fit: Boolean, unit: Float) {
        val list = items
        if (list.isEmpty() || areaW <= 0f || areaH <= 0f) return

        val sx = areaW / imgW
        val sy = areaH / imgH
        val scale = if (fit) min(sx, sy) else max(sx, sy)
        val dx = (areaW - imgW * scale) / 2f
        val dy = (areaH - imgH * scale) / 2f

        val pad = 4f * unit
        val radius = 6f * unit
        border.strokeWidth = 1.5f * unit

        for (item in list) {
            val boxW = item.box.width() * scale
            val boxH = item.box.height() * scale

            val layoutW = max(boxW, 120f * unit)
                .coerceAtMost(areaW - 2 * pad).toInt().coerceAtLeast(1)
            val layout = fitLayout(item.text, layoutW, boxH, unit)

            var left = item.box.left * scale + dx
            var top = item.box.top * scale + dy
            if (left + layoutW + pad > areaW) left = areaW - layoutW - pad
            if (left < pad) left = pad
            if (top + layout.height + pad > areaH) top = areaH - layout.height - pad
            if (top < pad) top = pad

            canvas.save()
            canvas.translate(left, top)
            rect.set(-pad, -pad, layoutW + pad, layout.height + pad)
            canvas.drawRoundRect(rect, radius, radius, bg)
            canvas.drawRoundRect(rect, radius, radius, border)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun fitLayout(text: String, width: Int, boxH: Float, unit: Float): StaticLayout {
        val maxSize = min(max(boxH, 14f * unit), 34f * unit)
        val minSize = 11f * unit
        var size = maxSize
        while (true) {
            textPaint.textSize = size
            val l = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, width)
                .build()
            if (l.height <= boxH + 4f * unit || size <= minSize) return l
            size = max(minSize, size * 0.92f)
        }
    }
}