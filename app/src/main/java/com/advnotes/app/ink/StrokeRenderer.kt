package com.advnotes.app.ink

import android.graphics.Bitmap
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
    /** Pen strokes: [body] is the variable-width outline, [caps] the round ends/joins. */
    private class Outline(val body: Path, val caps: Path)

    private val cache = object : LruCache<Stroke, Path>(4000) {}
    private val penCache = object : LruCache<Stroke, Outline>(4000) {}
    private val textCache = object : LruCache<TextBox, StaticLayout>(300) {}

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun clearCache() { cache.evictAll(); penCache.evictAll() }

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
            val o = if (live) outline(s) else penCache.get(s) ?: outline(s).also { penCache.put(s, it) }
            // Drawn separately: in one path, caps wound opposite to the outline would cancel
            // out under the non-zero fill rule and leave white holes.
            canvas.drawPath(o.body, penPaint)
            canvas.drawPath(o.caps, penPaint)
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

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Draws a whole page (paper, imported background, highlighters underneath, ink, text).
     * Canvas in page units.
     */
    fun drawPage(
        canvas: Canvas,
        page: Page,
        paper: PaperStyle,
        color: PaperColor,
        exclude: Set<String> = emptySet(),
        drawPaper: Boolean = true,
        background: Bitmap? = null,
        images: (com.advnotes.app.model.ImageBox) -> Bitmap? = { null },
    ) {
        if (drawPaper) PaperRenderer.draw(canvas, page.paper ?: paper, color, page.height)
        if (background != null) {
            canvas.drawBitmap(
                background, null,
                android.graphics.RectF(0f, 0f, com.advnotes.app.model.PAGE_WIDTH, page.height), bmpPaint,
            )
        }
        for (im in page.images) if (im.id !in exclude) drawImage(canvas, im, images(im))
        for (s in page.strokes) if (s.kind == InkKind.HIGHLIGHTER && s.id !in exclude) draw(canvas, s)
        for (s in page.strokes) if (s.kind == InkKind.PEN && s.id !in exclude) draw(canvas, s)
        for (t in page.texts) if (t.id !in exclude) drawText(canvas, t)
    }

    private val imgPlaceholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22808080 }

    /** Draws a placed picture, or a grey box while it is still loading. */
    fun drawImage(canvas: Canvas, im: com.advnotes.app.model.ImageBox, bmp: Bitmap?) {
        val r = android.graphics.RectF(im.x, im.y, im.x + im.w, im.y + im.h)
        if (bmp != null) canvas.drawBitmap(bmp, null, r, bmpPaint) else canvas.drawRect(r, imgPlaceholder)
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
    private fun outline(s: Stroke): Outline {
        val n = s.count
        val path = Path().apply { fillType = Path.FillType.WINDING }
        val caps = Path().apply { fillType = Path.FillType.WINDING }
        val hw = FloatArray(n) { widthAt(s, s.p(it)) / 2f }
        // smooth widths a little to avoid ripples from noisy pressure
        if (n > 2 && s.pressure) {
            val tmp = hw.copyOf()
            for (i in 1 until n - 1) hw[i] = (tmp[i - 1] + 2 * tmp[i] + tmp[i + 1]) / 4f
        }
        if (n == 1) {
            caps.addCircle(s.x(0), s.y(0), hw[0], Path.Direction.CW)
            return Outline(path, caps)
        }
        // Split at sharp corners: each piece gets its own outline (all traced with the same
        // orientation, so they merge under the non-zero rule) and the corner gets a round join.
        val cuts = ArrayList<Int>().apply { add(0) }
        for (i in 1 until n - 1) {
            val ax = s.x(i) - s.x(i - 1); val ay = s.y(i) - s.y(i - 1)
            val bx = s.x(i + 1) - s.x(i); val by = s.y(i + 1) - s.y(i)
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < 1e-3f || lb < 1e-3f) continue
            val cos = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
            if (abs(acos(cos)) > 0.9f) cuts += i
        }
        cuts += n - 1
        for (k in 0 until cuts.size - 1) addPiece(path, s, hw, cuts[k], cuts[k + 1])
        for (i in cuts) caps.addCircle(s.x(i), s.y(i), hw[i], Path.Direction.CW)
        return Outline(path, caps)
    }

    /** Adds the closed outline of points [from]..[to] (inclusive) to [path]. */
    private fun addPiece(path: Path, s: Stroke, hw: FloatArray, from: Int, to: Int) {
        val m = to - from + 1
        if (m < 2) return
        val lx = FloatArray(m); val ly = FloatArray(m); val rx = FloatArray(m); val ry = FloatArray(m)
        for (j in 0 until m) {
            val i = from + j
            val a = if (j == 0) i else i - 1
            val b = if (j == m - 1) i else i + 1
            var tx = s.x(b) - s.x(a); var ty = s.y(b) - s.y(a)
            val len = hypot(tx, ty)
            if (len < 1e-4f) { tx = 1f; ty = 0f } else { tx /= len; ty /= len }
            val nx = -ty; val ny = tx
            lx[j] = s.x(i) + nx * hw[i]; ly[j] = s.y(i) + ny * hw[i]
            rx[j] = s.x(i) - nx * hw[i]; ry[j] = s.y(i) - ny * hw[i]
        }
        path.moveTo(lx[0], ly[0])
        for (j in 1 until m - 1) path.quadTo(lx[j], ly[j], (lx[j] + lx[j + 1]) / 2f, (ly[j] + ly[j + 1]) / 2f)
        path.lineTo(lx[m - 1], ly[m - 1])
        path.lineTo(rx[m - 1], ry[m - 1])
        for (j in m - 2 downTo 1) path.quadTo(rx[j], ry[j], (rx[j] + rx[j - 1]) / 2f, (ry[j] + ry[j - 1]) / 2f)
        path.lineTo(rx[0], ry[0])
        path.close()
    }
}
