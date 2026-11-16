package com.advnotes.app.ink

import android.graphics.Canvas
import android.graphics.Paint
import com.advnotes.app.model.MM
import com.advnotes.app.model.PAGE_HEIGHT
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle
import kotlin.math.ceil
import kotlin.math.sqrt

/** Draws notebook paper backgrounds. The canvas must already be in page units. */
object PaperRenderer {
    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun draw(canvas: Canvas, style: PaperStyle, color: PaperColor) {
        fill.color = color.argb
        canvas.drawRect(0f, 0f, PAGE_WIDTH, PAGE_HEIGHT, fill)

        val dark = color.dark
        val faint = if (dark) 0xFF3A414D.toInt() else 0xFFCAD5EA.toInt()
        val strong = if (dark) 0xFF4D5666.toInt() else 0xFF9FB2D9.toInt()
        val margin = if (dark) 0xFF8A4A52.toInt() else 0xFFEBA3A6.toInt()

        when (style) {
            PaperStyle.PLAIN -> Unit
            PaperStyle.NARROW_GRID -> grid(canvas, 5f * MM, faint, 0.7f, majorEvery = 0)
            PaperStyle.GRID -> grid(canvas, 7f * MM, faint, 0.8f, majorEvery = 0)
            PaperStyle.DOTTED -> dots(canvas, 5f * MM, if (dark) 0xFF5A6373.toInt() else 0xFFA9B6CF.toInt())
            PaperStyle.OXFORD -> {
                // Oxford: 7 mm ruling below a header band, red left margin.
                val top = 28f * MM
                ruled(canvas, top + 7f * MM, 7f * MM, faint, 0.8f)
                hLine(canvas, top, strong, 1.4f)
                vLine(canvas, 24f * MM, margin, 1.1f, 0f, PAGE_HEIGHT)
                // small date box in the header
                line.color = strong; line.strokeWidth = 0.9f
                canvas.drawRect(PAGE_WIDTH - 62f * MM, 10f * MM, PAGE_WIDTH - 12f * MM, 20f * MM, line)
            }
            PaperStyle.COLLEGE_RULED -> {
                ruled(canvas, 32f * MM, 7.1f * MM, faint, 0.8f)
                vLine(canvas, 32f * MM, margin, 1.1f, 0f, PAGE_HEIGHT)
            }
            PaperStyle.WIDE_RULED -> {
                ruled(canvas, 32f * MM, 8.7f * MM, faint, 0.8f)
                vLine(canvas, 32f * MM, margin, 1.1f, 0f, PAGE_HEIGHT)
            }
            PaperStyle.CORNELL -> {
                val top = 25f * MM
                val bottom = PAGE_HEIGHT - 55f * MM
                ruled(canvas, top + 7f * MM, 7f * MM, faint, 0.8f, until = bottom)
                hLine(canvas, top, strong, 1.4f)
                hLine(canvas, bottom, strong, 1.4f)
                vLine(canvas, 63f * MM, strong, 1.2f, top, bottom)
            }
            PaperStyle.ISOMETRIC -> isometric(canvas, 6f * MM, faint)
        }
    }

    private fun grid(c: Canvas, s: Float, color: Int, w: Float, majorEvery: Int) {
        line.color = color; line.strokeWidth = w
        val cols = ceil(PAGE_WIDTH / s).toInt()
        val rows = ceil(PAGE_HEIGHT / s).toInt()
        // centre the grid on the page
        val ox = (PAGE_WIDTH - (cols - 1) * s) / 2f % s
        val oy = (PAGE_HEIGHT - (rows - 1) * s) / 2f % s
        var x = ox
        while (x <= PAGE_WIDTH) { c.drawLine(x, 0f, x, PAGE_HEIGHT, line); x += s }
        var y = oy
        while (y <= PAGE_HEIGHT) { c.drawLine(0f, y, PAGE_WIDTH, y, line); y += s }
    }

    private fun dots(c: Canvas, s: Float, color: Int) {
        dot.color = color
        var y = s
        while (y < PAGE_HEIGHT) {
            var x = s
            while (x < PAGE_WIDTH) { c.drawCircle(x, y, 1.3f, dot); x += s }
            y += s
        }
    }

    private fun ruled(c: Canvas, start: Float, s: Float, color: Int, w: Float, until: Float = PAGE_HEIGHT - 8f * MM) {
        line.color = color; line.strokeWidth = w
        var y = start
        while (y <= until) { c.drawLine(0f, y, PAGE_WIDTH, y, line); y += s }
    }

    private fun hLine(c: Canvas, y: Float, color: Int, w: Float) {
        line.color = color; line.strokeWidth = w
        c.drawLine(0f, y, PAGE_WIDTH, y, line)
    }

    private fun vLine(c: Canvas, x: Float, color: Int, w: Float, y0: Float, y1: Float) {
        line.color = color; line.strokeWidth = w
        c.drawLine(x, y0, x, y1, line)
    }

    private fun isometric(c: Canvas, s: Float, color: Int) {
        line.color = color; line.strokeWidth = 0.7f
        // vertical lines
        val dx = s * sqrt(3f) / 2f
        var x = 0f
        while (x <= PAGE_WIDTH) { c.drawLine(x, 0f, x, PAGE_HEIGHT, line); x += dx }
        // ±30° lines: slope tan(30°)
        val k = 1f / sqrt(3f)
        var b = -PAGE_WIDTH * k
        while (b <= PAGE_HEIGHT + PAGE_WIDTH * k) {
            c.drawLine(0f, b, PAGE_WIDTH, b + PAGE_WIDTH * k, line)
            c.drawLine(0f, b, PAGE_WIDTH, b - PAGE_WIDTH * k, line)
            b += s
        }
    }
}
