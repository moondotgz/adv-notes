package com.advnotes.app.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class ShapeKind(val label: String) {
    LINE("Line"), ARROW("Arrow"), RECT("Rectangle"), ELLIPSE("Ellipse"), TRIANGLE("Triangle"),
    RIGHT_TRIANGLE("Right triangle"), DIAMOND("Diamond"), AXES("Axes"), POLYGON("Polygon"),
}

/** Builds point lists (x, y pairs) for geometric shapes. Each inner array is one stroke. */
object ShapeFactory {
    private const val STEP = 4f

    fun make(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float, constrain: Boolean = false): List<FloatArray> {
        var ex = x1; var ey = y1
        if (constrain && kind != ShapeKind.LINE && kind != ShapeKind.ARROW) {
            val d = max(abs(x1 - x0), abs(y1 - y0))
            ex = x0 + d * sign(x1 - x0); ey = y0 + d * sign(y1 - y0)
        }
        val l = min(x0, ex); val r = max(x0, ex); val t = min(y0, ey); val b = max(y0, ey)
        return when (kind) {
            ShapeKind.LINE -> listOf(poly(floatArrayOf(x0, y0, ex, ey)))
            ShapeKind.ARROW -> arrow(x0, y0, ex, ey)
            ShapeKind.RECT -> listOf(poly(floatArrayOf(l, t, r, t, r, b, l, b, l, t)))
            ShapeKind.ELLIPSE -> listOf(ellipse((l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2))
            ShapeKind.TRIANGLE -> listOf(poly(floatArrayOf((l + r) / 2, t, r, b, l, b, (l + r) / 2, t)))
            ShapeKind.RIGHT_TRIANGLE -> listOf(poly(floatArrayOf(l, t, l, b, r, b, l, t)))
            ShapeKind.DIAMOND -> listOf(poly(floatArrayOf((l + r) / 2, t, r, (t + b) / 2, (l + r) / 2, b, l, (t + b) / 2, (l + r) / 2, t)))
            ShapeKind.AXES -> {
                val cx = (l + r) / 2; val cy = (t + b) / 2
                arrow(l, cy, r, cy) + arrow(cx, b, cx, t)
            }
            ShapeKind.POLYGON -> listOf(poly(floatArrayOf(x0, y0, ex, ey)))
        }
    }

    private fun sign(v: Float) = if (v < 0) -1f else 1f

    fun arrow(x0: Float, y0: Float, x1: Float, y1: Float): List<FloatArray> {
        val ang = atan2(y1 - y0, x1 - x0)
        val len = hypot(x1 - x0, y1 - y0)
        val head = (len * 0.18f).coerceIn(10f, 36f)
        val a1 = ang + PI.toFloat() * 0.82f
        val a2 = ang - PI.toFloat() * 0.82f
        return listOf(
            poly(floatArrayOf(x0, y0, x1, y1)),
            poly(floatArrayOf(x1 + cos(a1) * head, y1 + sin(a1) * head, x1, y1, x1 + cos(a2) * head, y1 + sin(a2) * head)),
        )
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rotation: Float = 0f): FloatArray {
        val perim = (2 * PI * sqrt((rx * rx + ry * ry) / 2.0)).toFloat()
        val n = (perim / STEP).toInt().coerceIn(24, 400)
        val out = FloatArray((n + 1) * 2)
        val cr = cos(rotation); val sr = sin(rotation)
        for (i in 0..n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat()
            val px = cos(a) * rx; val py = sin(a) * ry
            out[i * 2] = cx + px * cr - py * sr
            out[i * 2 + 1] = cy + px * sr + py * cr
        }
        return out
    }

    /** Densifies a polyline so the eraser and lasso work on shapes just like on handwriting. */
    fun poly(v: FloatArray): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until v.size / 2 - 1) {
            val ax = v[i * 2]; val ay = v[i * 2 + 1]; val bx = v[i * 2 + 2]; val by = v[i * 2 + 3]
            val n = max(1, (hypot(bx - ax, by - ay) / STEP).toInt())
            for (k in 0 until n) {
                val tt = k.toFloat() / n
                out += ax + (bx - ax) * tt; out += ay + (by - ay) * tt
            }
        }
        out += v[v.size - 2]; out += v[v.size - 1]
        return out.toFloatArray()
    }
}

/**
 * Recognises a hand-drawn stroke as a line, ellipse, triangle, rectangle or polygon.
 * Used by "hold to shape": draw, keep the pen still for a moment, and the stroke snaps.
 */
object ShapeRecognizer {
    data class Result(val kind: ShapeKind, val strokes: List<FloatArray>)

    fun recognize(xy: FloatArray): Result? {
        val n = xy.size / 2
        if (n < 4) return null
        var length = 0f
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = xy[i * 2]; val y = xy[i * 2 + 1]
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            if (i > 0) length += hypot(x - xy[i * 2 - 2], y - xy[i * 2 - 1])
        }
        if (length < 12f) return null
        val sx = xy[0]; val sy = xy[1]; val ex = xy[n * 2 - 2]; val ey = xy[n * 2 - 1]
        val chord = hypot(ex - sx, ey - sy)

        // Straight line: the stroke barely deviates from its chord.
        var maxDev = 0f
        for (i in 0 until n) maxDev = max(maxDev, Geometry.distToSegment(xy[i * 2], xy[i * 2 + 1], sx, sy, ex, ey))
        if (chord > 0.85f * length && maxDev < max(6f, 0.07f * chord)) {
            var x1 = ex; var y1 = ey
            // snap to horizontal / vertical / 45° when close
            val ang = atan2(ey - sy, ex - sx)
            val snap = (Math.round(ang / (PI / 4)) * (PI / 4)).toFloat()
            if (abs(ang - snap) < 0.07f) { x1 = sx + cos(snap) * chord; y1 = sy + sin(snap) * chord }
            return Result(ShapeKind.LINE, listOf(ShapeFactory.poly(floatArrayOf(sx, sy, x1, y1))))
        }

        val size = max(r - l, b - t)
        val closed = chord < max(0.22f * length, 0.25f * size)
        if (!closed || size < 15f) return null

        // Ellipse: radial distance from the centre relative to the fitted ellipse is ~constant.
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val rx = (r - l) / 2f; val ry = (b - t) / 2f
        var err = 0f
        for (i in 0 until n) {
            val dx = (xy[i * 2] - cx) / max(rx, 1f); val dy = (xy[i * 2 + 1] - cy) / max(ry, 1f)
            err += abs(sqrt(dx * dx + dy * dy) - 1f)
        }
        err /= n

        val corners = simplifyClosed(xy, 0.045f * length)
        val k = corners.size / 2
        if (err < 0.09f || (k > 5 && err < 0.16f)) {
            var erx = rx; var ery = ry
            if (abs(rx - ry) < 0.12f * max(rx, ry)) { val m = (rx + ry) / 2; erx = m; ery = m }
            return Result(ShapeKind.ELLIPSE, listOf(ShapeFactory.ellipse(cx, cy, erx, ery)))
        }
        return when (k) {
            3 -> Result(ShapeKind.TRIANGLE, listOf(ShapeFactory.poly(corners + corners.copyOfRange(0, 2))))
            4 -> {
                if (isAxisAligned(corners)) Result(ShapeKind.RECT, ShapeFactory.make(ShapeKind.RECT, l, t, r, b))
                else Result(ShapeKind.POLYGON, listOf(ShapeFactory.poly(corners + corners.copyOfRange(0, 2))))
            }
            in 5..8 -> Result(ShapeKind.POLYGON, listOf(ShapeFactory.poly(corners + corners.copyOfRange(0, 2))))
            else -> null
        }
    }

    private fun isAxisAligned(c: FloatArray): Boolean {
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val ang = abs(atan2(c[j * 2 + 1] - c[i * 2 + 1], c[j * 2] - c[i * 2]))
            val m = ang % (PI.toFloat() / 2)
            if (min(m, PI.toFloat() / 2 - m) > 0.3f) return false
        }
        return true
    }

    /** Douglas–Peucker on a closed curve; returns the corner points (x, y pairs). */
    private fun simplifyClosed(xy: FloatArray, eps: Float): FloatArray {
        val n = xy.size / 2
        // start at the point farthest from the first point to get a stable split
        var far = 0; var best = 0f
        for (i in 0 until n) {
            val d = hypot(xy[i * 2] - xy[0], xy[i * 2 + 1] - xy[1])
            if (d > best) { best = d; far = i }
        }
        val keep = BooleanArray(n)
        keep[0] = true; keep[far] = true
        dp(xy, 0, far, eps, keep)
        dp(xy, far, n - 1, eps, keep)
        val out = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) { out += xy[i * 2]; out += xy[i * 2 + 1] }
        // merge the last point into the first when they are (nearly) the same corner
        var arr = out.toFloatArray()
        val m = arr.size / 2
        if (m > 2 && hypot(arr[0] - arr[m * 2 - 2], arr[1] - arr[m * 2 - 1]) < eps * 2.5f) arr = arr.copyOf(arr.size - 2)
        // drop nearly collinear corners
        return removeCollinear(arr)
    }

    private fun removeCollinear(c: FloatArray): FloatArray {
        var pts = c
        var changed = true
        while (changed && pts.size / 2 > 3) {
            changed = false
            val m = pts.size / 2
            for (i in 0 until m) {
                val p = (i - 1 + m) % m; val nx = (i + 1) % m
                val a1 = atan2(pts[i * 2 + 1] - pts[p * 2 + 1], pts[i * 2] - pts[p * 2])
                val a2 = atan2(pts[nx * 2 + 1] - pts[i * 2 + 1], pts[nx * 2] - pts[i * 2])
                var d = abs(a1 - a2); if (d > PI) d = (2 * PI - d).toFloat()
                if (d < 0.35f) {
                    pts = pts.copyOfRange(0, i * 2) + pts.copyOfRange(i * 2 + 2, pts.size)
                    changed = true
                    break
                }
            }
        }
        return pts
    }

    private fun dp(xy: FloatArray, a: Int, b: Int, eps: Float, keep: BooleanArray) {
        if (b <= a + 1) return
        var idx = -1; var maxD = 0f
        for (i in a + 1 until b) {
            val d = Geometry.distToSegment(xy[i * 2], xy[i * 2 + 1], xy[a * 2], xy[a * 2 + 1], xy[b * 2], xy[b * 2 + 1])
            if (d > maxD) { maxD = d; idx = i }
        }
        if (maxD > eps && idx >= 0) {
            keep[idx] = true
            dp(xy, a, idx, eps, keep)
            dp(xy, idx, b, eps, keep)
        }
    }
}
