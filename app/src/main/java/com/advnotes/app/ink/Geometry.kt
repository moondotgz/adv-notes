package com.advnotes.app.ink

import android.graphics.RectF
import com.advnotes.app.model.Stroke
import com.advnotes.app.model.TextBox
import kotlin.math.hypot
import kotlin.math.max

object Geometry {
    fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax; val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * vx), py - (ay + t * vy))
    }

    fun bounds(s: Stroke, out: RectF = RectF()): RectF {
        if (s.count == 0) { out.setEmpty(); return out }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until s.count) {
            val x = s.x(i); val y = s.y(i)
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        val pad = s.width / 2f + 1f
        out.set(l - pad, t - pad, r + pad, b + pad)
        return out
    }

    fun strokeHitsCircle(s: Stroke, x: Float, y: Float, r: Float): Boolean {
        val rr = r + s.width * 0.5f
        if (s.count == 1) return hypot(s.x(0) - x, s.y(0) - y) <= rr
        for (i in 1 until s.count) {
            if (distToSegment(x, y, s.x(i - 1), s.y(i - 1), s.x(i), s.y(i)) <= rr) return true
        }
        return false
    }

    /**
     * Partial eraser: removes the parts of [s] within radius [r] of (x, y). Returns null if the
     * stroke is untouched, otherwise the remaining pieces (possibly empty).
     */
    fun eraseFromStroke(s: Stroke, x: Float, y: Float, r: Float): List<Stroke>? {
        val dense = densify(s, max(1.5f, r / 3f))
        val rr = r + dense.width * 0.3f
        var touched = false
        val pieces = ArrayList<Stroke>()
        val cur = FloatArrayBuilder()
        for (i in 0 until dense.count) {
            val inside = hypot(dense.x(i) - x, dense.y(i) - y) <= rr
            if (inside) {
                touched = true
                if (cur.size >= 6) pieces += dense.copy(id = com.advnotes.app.model.newId(), pts = cur.toArray())
                cur.clear()
            } else {
                cur.add(dense.x(i), dense.y(i), dense.p(i))
            }
        }
        if (!touched) return null
        if (cur.size >= 6) pieces += dense.copy(id = com.advnotes.app.model.newId(), pts = cur.toArray())
        return pieces
    }

    /** Inserts intermediate points so no two consecutive points are further apart than [step]. */
    fun densify(s: Stroke, step: Float): Stroke {
        if (s.count < 2) return s
        val b = FloatArrayBuilder()
        b.add(s.x(0), s.y(0), s.p(0))
        for (i in 1 until s.count) {
            val ax = s.x(i - 1); val ay = s.y(i - 1); val ap = s.p(i - 1)
            val bx = s.x(i); val by = s.y(i); val bp = s.p(i)
            val d = hypot(bx - ax, by - ay)
            val n = (d / step).toInt()
            for (k in 1..n) {
                val t = k / (n + 1f)
                b.add(ax + (bx - ax) * t, ay + (by - ay) * t, ap + (bp - ap) * t)
            }
            b.add(bx, by, bp)
        }
        return if (b.size == s.pts.size) s else s.copy(pts = b.toArray())
    }

    fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** A stroke counts as lassoed when most of its points are inside the polygon. */
    fun strokeInPolygon(s: Stroke, poly: FloatArray): Boolean {
        if (s.count == 0) return false
        var inside = 0
        for (i in 0 until s.count) if (pointInPolygon(s.x(i), s.y(i), poly)) inside++
        return inside >= max(1, (s.count * 0.6f).toInt())
    }

    fun textBounds(t: TextBox, height: Float): RectF = RectF(t.x, t.y, t.x + t.width, t.y + height)
}

/** Growable float array without boxing. */
class FloatArrayBuilder(cap: Int = 192) {
    private var a = FloatArray(cap)
    var size = 0
        private set

    fun add(x: Float, y: Float, p: Float) {
        if (size + 3 > a.size) a = a.copyOf(a.size * 2)
        a[size++] = x; a[size++] = y; a[size++] = p
    }

    fun clear() { size = 0 }
    fun toArray(): FloatArray = a.copyOf(size)
    operator fun get(i: Int) = a[i]
    fun set(i: Int, v: Float) { a[i] = v }
    val points get() = size / 3
}
