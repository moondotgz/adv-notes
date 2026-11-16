package com.advnotes.app.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.advnotes.app.model.InkKind
import com.advnotes.app.model.Page
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle
import com.advnotes.app.model.Stroke
import com.advnotes.app.model.TextBox
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Turns strokes into filled outlines whose width follows pen pressure. Paths are cached per stroke.
 */
class StrokeRenderer(var pressureCurve: Float = 1f) {
    private val cache = object : LruCache<Stroke, Path>(4000) {}
    private val textCache = object : LruCache<TextBox, StaticLayout>(300) {}

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun clearCache() { cache.evictAll() }

    fun widthAt(s: Stroke, p: Float): Float =
        if (!s.pressure) s.width * 0.7f
        else s.width * (0.18f + 0.82f * p.coerceIn(0f, 1f).pow(pressureCurve))

    fun draw(canvas: Canvas, s: Stroke, live: Boolean = false) {
        if (s.count == 0) return
        if (s.kind == InkKind.HIGHLIGHTER) {
            hlPaint.color = s.color
            hlPaint.strokeWidth = s.width
            val path = if (live) centerline(s) else cache.get(s) ?: centerline(s).also { cache.put(s, it) }
            if (s.count == 1) {
                penPaint.color = s.color
                canvas.drawCircle(s.x(0), s.y(0), s.width / 2f, penPaint)
            } else canvas.drawPath(path, hlPaint)
        } else {
            penPaint.color = s.color
            val path = if (live) outline(s) else cache.get(s) ?: outline(s).also { cache.put(s, it) }
            canvas.drawPath(path, penPaint)
        }
    }

    fun drawText(canvas: Canvas, t: TextBox) {
        val layout = layout(t)
        canvas.save()
        canvas.translate(t.x, t.y)
        layout.draw(canvas)
        canvas.restore()
    }

    fun layout(t: TextBox): StaticLayout = textCache.get(t) ?: run {
        textPaint.textSize = t.size
        textPaint.color = t.color
        StaticLayout.Builder.obtain(t.text, 0, t.text.length, TextPaint(textPaint), t.width.toInt().coerceAtLeast(20))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build().also { textCache.put(t, it) }
    }

    fun textHeight(t: TextBox): Float = layout(t).height.toFloat()

    /** Draws a whole page (paper + highlighters underneath + ink + text). Canvas in page units. */
    fun drawPage(
        canvas: Canvas,
        page: Page,
        paper: PaperStyle,
        color: PaperColor,
        exclude: Set<String> = emptySet(),
        drawPaper: Boolean = true,
    ) {
        if (drawPaper) PaperRenderer.draw(canvas, page.paper ?: paper, color)
        for (s in page.strokes) if (s.kind == InkKind.HIGHLIGHTER && s.id !in exclude) draw(canvas, s)
        for (s in page.strokes) if (s.kind == InkKind.PEN && s.id !in exclude) draw(canvas, s)
        for (t in page.texts) if (t.id !in exclude) drawText(canvas, t)
    }

    private fun centerline(s: Stroke): Path {
        val path = Path()
        path.moveTo(s.x(0), s.y(0))
        if (s.count == 2) { path.lineTo(s.x(1), s.y(1)); return path }
        for (i in 1 until s.count - 1) {
            val mx = (s.x(i) + s.x(i + 1)) / 2f
            val my = (s.y(i) + s.y(i + 1)) / 2f
            path.quadTo(s.x(i), s.y(i), mx, my)
        }
        path.lineTo(s.x(s.count - 1), s.y(s.count - 1))
        return path
    }

    /** Variable-width outline built from left/right offset curves plus round caps. */
    private fun outline(s: Stroke): Path {
        val n = s.count
        val path = Path().apply { fillType = Path.FillType.WINDING }
        val hw = FloatArray(n) { widthAt(s, s.p(it)) / 2f }
        // smooth widths a little to avoid ripples from noisy pressure
        if (n > 2 && s.pressure) {
            val tmp = hw.copyOf()
            for (i in 1 until n - 1) hw[i] = (tmp[i - 1] + 2 * tmp[i] + tmp[i + 1]) / 4f
        }
        if (n == 1) {
            path.addCircle(s.x(0), s.y(0), hw[0], Path.Direction.CW)
            return path
        }
        val lx = FloatArray(n); val ly = FloatArray(n); val rx = FloatArray(n); val ry = FloatArray(n)
        for (i in 0 until n) {
            val a = if (i == 0) 0 else i - 1
            val b = if (i == n - 1) n - 1 else i + 1
            var tx = s.x(b) - s.x(a); var ty = s.y(b) - s.y(a)
            val len = hypot(tx, ty)
            if (len < 1e-4f) { tx = 1f; ty = 0f } else { tx /= len; ty /= len }
            val nx = -ty; val ny = tx
            lx[i] = s.x(i) + nx * hw[i]; ly[i] = s.y(i) + ny * hw[i]
            rx[i] = s.x(i) - nx * hw[i]; ry[i] = s.y(i) - ny * hw[i]
        }
        path.moveTo(lx[0], ly[0])
        for (i in 1 until n - 1) path.quadTo(lx[i], ly[i], (lx[i] + lx[i + 1]) / 2f, (ly[i] + ly[i + 1]) / 2f)
        path.lineTo(lx[n - 1], ly[n - 1])
        path.lineTo(rx[n - 1], ry[n - 1])
        for (i in n - 2 downTo 1) path.quadTo(rx[i], ry[i], (rx[i] + rx[i - 1]) / 2f, (ry[i] + ry[i - 1]) / 2f)
        path.lineTo(rx[0], ry[0])
        path.close()
        // round caps
        path.addCircle(s.x(0), s.y(0), hw[0], Path.Direction.CW)
        path.addCircle(s.x(n - 1), s.y(n - 1), hw[n - 1], Path.Direction.CW)
        // round joins at sharp corners, where the offset curves would fold over
        for (i in 1 until n - 1) {
            val ax = s.x(i) - s.x(i - 1); val ay = s.y(i) - s.y(i - 1)
            val bx = s.x(i + 1) - s.x(i); val by = s.y(i + 1) - s.y(i)
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < 1e-3f || lb < 1e-3f) continue
            val cos = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
            if (abs(acos(cos)) > 0.9f) path.addCircle(s.x(i), s.y(i), hw[i], Path.Direction.CW)
        }
        return path
    }
}
