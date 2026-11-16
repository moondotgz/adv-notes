package com.advnotes.app.importer

import com.advnotes.app.model.InkKind
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.PAGE_HEIGHT
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.Page
import com.advnotes.app.model.PaperStyle
import com.advnotes.app.model.Stroke
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Reader for Samsung Notes `.sdocx` handwriting and shapes.
 *
 * The format is undocumented. The stroke decoding follows the reverse-engineered description in
 * https://github.com/bxff/samsung-notes-format (MIT License, © 2025 bxff); the object tree, shape
 * and line layouts follow the format facts documented by the twangodev/sdocx project.
 *
 * Page layout: a header, then layers. A layer is a size-prefixed header, an object count, the
 * objects and a 32-byte integrity trailer. Every object is `type:u8, children:u16, size:u32`,
 * its data (`size` bytes, ending in a 32-byte trailer), then its children, recursively. Object
 * data is a chain of frames: `size:u32, kind:i16, flexibleOffset:u32, mask, mask, fixed, flexible`.
 *
 * Imported: pen strokes (geometry; colour/width default), shapes (templates and paths, with
 * their outline colour and width) and lines. Typed text, images and audio are skipped.
 */
object SamsungNotes {
    private val STROKE_TYPES = setOf(1, 15, 18)
    private const val SHAPE = 7
    private const val LINE = 8
    private const val TRAILER = 32

    /** One imported polyline in Samsung page coordinates. [color]/[width] null = default pen. */
    class RawStroke(
        val xy: FloatArray,
        val color: Int? = null,
        val width: Float? = null,
        val shape: Boolean = false,
        /** Per-point pen pressure (0..1), when the file has it. */
        val pressure: FloatArray? = null,
    ) {
        val left get() = xs().minOrNull()?.toDouble() ?: 0.0
        val top get() = ys().minOrNull()?.toDouble() ?: 0.0
        val right get() = xs().maxOrNull()?.toDouble() ?: 0.0
        val bottom get() = ys().maxOrNull()?.toDouble() ?: 0.0
        private fun xs() = xy.filterIndexed { i, _ -> i % 2 == 0 }
        private fun ys() = xy.filterIndexed { i, _ -> i % 2 == 1 }
    }
    class RawPage(val width: Int, val height: Int, val strokes: List<RawStroke>, val skipped: Int = 0)

    fun import(id: String, title: String, f: File): Pair<Notebook, List<String>> {
        val raw = read(f)
        if (raw.isEmpty() || raw.all { it.strokes.isEmpty() }) {
            throw ImportException("No handwriting found in this Samsung Notes file (typed text and images aren't supported yet). Try exporting it as PDF from Samsung Notes instead.")
        }
        val pages = raw.map { toPage(it) }
        val skipped = raw.sumOf { it.skipped }
        val warnings = buildList {
            add("Samsung Notes import: typed text, images and special pen textures (brush, pencil, fountain pen) aren't imported; everything is drawn with the normal pen.")
            if (skipped > 0) add("$skipped object(s) couldn't be read and were skipped.")
        }
        return Notebook(id = id, title = title, paper = PaperStyle.PLAIN, pages = pages) to warnings
    }

    fun toPage(rp: RawPage): Page {
        val maxRight = rp.strokes.maxOfOrNull { it.right } ?: 0.0
        val srcW = if (rp.width > 0) rp.width.toDouble() else max(maxRight, 1.0)
        val scale = (PAGE_WIDTH / srcW).toFloat()
        val maxBottom = (rp.strokes.maxOfOrNull { it.bottom } ?: 0.0).toFloat() * scale
        val declared = if (rp.height > 0) rp.height * scale else 0f
        val height = max(max(declared, maxBottom + 40f), PAGE_HEIGHT)
        val strokes = rp.strokes.filter { it.xy.size >= 2 }.map { s ->
            val n = s.xy.size / 2
            val pr = s.pressure?.takeIf { it.size == n && it.any { v -> v > 0f } }
            val pts = FloatArray(n * 3)
            for (i in 0 until n) {
                pts[i * 3] = s.xy[i * 2] * scale
                pts[i * 3 + 1] = s.xy[i * 2 + 1] * scale
                pts[i * 3 + 2] = pr?.get(i)?.coerceIn(0.05f, 1f) ?: 1f
            }
            // our renderer draws constant-width strokes at 0.7 × width, pressure strokes up to 1 × width
            val w = s.width?.let { (it * scale / (if (pr != null) 1f else 0.7f)).coerceIn(1f, 60f) } ?: 4f
            val color = s.color?.let { if (it ushr 24 == 0) it or (0xFF shl 24) else it } ?: 0xFF1A1A1A.toInt()
            Stroke(kind = InkKind.PEN, color = color, width = w, pts = pts, pressure = pr != null)
        }
        return Page(strokes = strokes, height = height)
    }

    /** Reads every page, in the order listed in pageIdInfo.dat when present. */
    fun read(f: File): List<RawPage> = ZipFile(f).use { z ->
        val pageEntries = z.entries().asSequence().filter { it.name.endsWith(".page") }.associateBy { it.name.substringAfterLast('/').removeSuffix(".page") }
        val order = z.getEntry("pageIdInfo.dat")?.let { e -> runCatching { pageOrder(z.getInputStream(e).readBytes()) }.getOrNull() }
            ?.filter { it in pageEntries }.orEmpty()
        val ids = order + pageEntries.keys.filter { it !in order }.sorted()
        ids.mapNotNull { id -> runCatching { readPage(z.getInputStream(pageEntries[id]!!).readBytes()) }.getOrNull() }
    }

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    fun pageOrder(data: ByteArray): List<String> {
        val bb = le(data)
        if (data.size < 34) return emptyList()
        bb.position(32)
        val count = bb.short.toInt()
        val out = ArrayList<String>()
        repeat(count) {
            if (bb.remaining() < 2) return out
            val len = bb.short.toInt()
            if (len <= 0 || bb.remaining() < len * 2) return out
            val chars = CharArray(len) { bb.short.toInt().toChar() }
            out += String(chars)
            if (bb.remaining() >= 32) bb.position(bb.position() + 32)
        }
        return out
    }

    fun readPage(page: ByteArray): RawPage {
        val bb = le(page)
        val layerOffset = bb.getInt(0)
        // header: two offsets, two length-prefixed masks, orientation, width, height
        var p = 8
        p += 1 + (page[p].toInt() and 0xFF)
        p += 1 + (page[p].toInt() and 0xFF)
        p += 4
        val pageW = bb.getInt(p)
        val pageH = bb.getInt(p + 4)

        val out = ArrayList<RawStroke>()
        var skipped = 0
        var pos = layerOffset
        val layerCount = bb.getShort(pos).toInt() and 0xFFFF
        pos += 4 // count + current layer index
        for (l in 0 until layerCount) {
            if (pos + 4 > page.size) break
            val headerSize = bb.getInt(pos)
            if (headerSize < 14 || headerSize > 16 * 1024) break
            pos += headerSize
            val objectCount = bb.getInt(pos)
            pos += 4
            for (o in 0 until objectCount) {
                val r = readObject(page, bb, pos, out, 0) ?: break
                pos = r.first
                skipped += r.second
            }
            pos += TRAILER
        }
        return RawPage(pageW, pageH, out, skipped)
    }

    /** Reads one object and its children. Returns (next position, objects skipped) or null if malformed. */
    private fun readObject(page: ByteArray, bb: ByteBuffer, pos: Int, out: MutableList<RawStroke>, depth: Int): Pair<Int, Int>? {
        if (depth > 32 || pos + 7 > page.size) return null
        val type = page[pos].toInt() and 0xFF
        val children = bb.getShort(pos + 1).toInt() and 0xFFFF
        val size = bb.getInt(pos + 3)
        val start = pos + 7
        if (size < TRAILER || start + size > page.size) return null
        var skipped = 0
        try {
            when (type) {
                in STROKE_TYPES -> (runCatching { parseFramedStroke(page, start, start + size - TRAILER) }.getOrNull()
                    ?: parseStroke(page.copyOfRange(start, start + size)))?.let { out += it } ?: run { skipped++ }
                SHAPE -> parseShape(page, start, start + size - TRAILER).also { if (it.isEmpty()) skipped++ }.forEach { out += it }
                LINE -> parseLine(page, start, start + size - TRAILER).also { if (it.isEmpty()) skipped++ }.forEach { out += it }
            }
        } catch (e: RuntimeException) {
            skipped++
        }
        var next = start + size
        repeat(children) {
            val r = readObject(page, bb, next, out, depth + 1) ?: return null
            next = r.first
            skipped += r.second
        }
        return next to skipped
    }

    // ------------------------------------------------------------------------------------
    // Frames (shared by shapes and lines)
    // ------------------------------------------------------------------------------------

    private class Frame(val kind: Int, val props: ByteArray, val fields: ByteArray, val fixed: Int, val fixedEnd: Int, val flex: Int, val end: Int) {
        fun has(mask: ByteArray, bit: Int) = bit / 8 < mask.size && (mask[bit / 8].toInt() shr (bit % 8)) and 1 != 0
        fun field(bit: Int) = has(fields, bit)
        fun prop(bit: Int) = has(props, bit)
    }

    private fun frame(b: ByteArray, bb: ByteBuffer, at: Int): Frame {
        val size = bb.getInt(at)
        require(size >= 12 && at + size <= b.size) { "bad frame" }
        val kind = bb.getShort(at + 4).toInt()
        val flexOff = bb.getInt(at + 6)
        var p = at + 10
        val pn = b[p].toInt() and 0xFF
        val props = b.copyOfRange(p + 1, p + 1 + pn); p += 1 + pn
        val fn = b[p].toInt() and 0xFF
        val fields = b.copyOfRange(p + 1, p + 1 + fn); p += 1 + fn
        val fixedEnd = if (flexOff == 0 && fields.all { it.toInt() == 0 }) size else flexOff
        require(fixedEnd in (p - at)..size) { "bad frame offsets" }
        return Frame(kind, props, fields, p, at + fixedEnd, at + fixedEnd, at + size)
    }

    private class Style(val color: Int?, val width: Float?)

    /** Kind-6 frame: outline colour and width. */
    private fun style(b: ByteArray, bb: ByteBuffer, f: Frame): Style {
        if (f.kind != 6 || f.field(0) || f.field(1)) return Style(null, null)
        var q = f.flex
        var color: Int? = null
        var width: Float? = null
        if (f.field(2)) {
            val len = bb.getInt(q)
            var c = q + 4
            c += 1 + (b[c].toInt() and 0xFF) // property mask
            val colorType = b[c].toInt() and 0xFF
            val argb = bb.getInt(c + 1)
            if (colorType == 0) color = argb
            q += 4 + len
        }
        if (f.field(3)) {
            width = bb.getFloat(q + 4).takeIf { it.isFinite() && it >= 0f }
        }
        return Style(color, width)
    }

    private fun parseShape(b: ByteArray, start: Int, end: Int): List<RawStroke> {
        val bb = le(b)
        val base = frame(b, bb, start)
        require(base.kind == 0)
        val sf = frame(b, bb, base.end)
        val st = style(b, bb, sf)
        val g = frame(b, bb, sf.end)
        require(g.kind == 7 && g.end <= end + TRAILER)
        var p = g.fixed
        val type = bb.getInt(p); p += 4
        val l = bb.getDouble(p); val t = bb.getDouble(p + 8); val r = bb.getDouble(p + 16); val bt = bb.getDouble(p + 24); p += 32
        val rotation = bb.getFloat(p); p += 4
        val pathLen = bb.getInt(p); p += 4
        val polys = if (pathLen > 0) path(b, bb, p, p + pathLen) else template(type, l, t, r, bt, rotation)
        return polys.map { RawStroke(it, st.color, st.width, shape = true) }
    }

    private fun parseLine(b: ByteArray, start: Int, end: Int): List<RawStroke> {
        val bb = le(b)
        val base = frame(b, bb, start)
        require(base.kind == 0)
        val sf = frame(b, bb, base.end)
        val st = style(b, bb, sf)
        val g = frame(b, bb, sf.end)
        require(g.kind == 8 && g.end <= end + TRAILER)
        var p = g.fixed
        p += 2 // line type, routing
        val n = b[p].toInt() and 0xFF; p += 1
        val control = FloatArray(n * 2) { i -> bb.getDouble(p + i * 8).toFloat() }
        p += n * 16
        val begin = floatArrayOf(bb.getDouble(p).toFloat(), bb.getDouble(p + 8).toFloat())
        val endPt = floatArrayOf(bb.getDouble(p + 16).toFloat(), bb.getDouble(p + 24).toFloat())
        // optional custom path (flexible field 3), after fields 0 (8 bytes), 1 (4) and 2 (4)
        if (g.field(3)) {
            var q = g.flex
            if (g.field(0)) q += 8
            if (g.field(1)) q += 4
            if (g.field(2)) q += 4
            val polys = runCatching { path(b, bb, q, g.end) }.getOrDefault(emptyList())
            if (polys.isNotEmpty()) return polys.map { RawStroke(it, st.color, st.width, shape = true) }
        }
        return listOf(RawStroke(begin + control + endPt, st.color, st.width, shape = true))
    }

    /** Template shapes drawn into their box: 1 ellipse, 2 triangle, 3 right triangle, 4 rectangle, 8 diamond. */
    private fun template(type: Int, l: Double, t: Double, r: Double, b: Double, rotation: Float): List<FloatArray> {
        val x0 = l.toFloat(); val y0 = t.toFloat(); val x1 = r.toFloat(); val y1 = b.toFloat()
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        val pts: FloatArray = when (type) {
            1 -> { val n = 96; FloatArray((n + 1) * 2) { i -> val a = (i / 2) * 2 * Math.PI / n; if (i % 2 == 0) cx + (x1 - x0) / 2 * cos(a).toFloat() else cy + (y1 - y0) / 2 * sin(a).toFloat() } }
            2 -> floatArrayOf(cx, y0, x1, y1, x0, y1, cx, y0)
            3 -> floatArrayOf(x0, y0, x1, y1, x0, y1, x0, y0)
            4 -> floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1, x0, y0)
            8 -> floatArrayOf(cx, y0, x1, cy, cx, y1, x0, cy, cx, y0)
            else -> floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1, x0, y0) // unknown template: its box
        }
        if (rotation == 0f) return listOf(densify(pts))
        val a = Math.toRadians(rotation.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        val rot = FloatArray(pts.size) { i ->
            val x = pts[i - i % 2] - cx; val y = pts[i - i % 2 + 1] - cy
            if (i % 2 == 0) cx + x * c - y * s else cy + x * s + y * c
        }
        return listOf(densify(rot))
    }

    /** Native path commands -> polylines (page coordinates). */
    private fun path(b: ByteArray, bb: ByteBuffer, start: Int, end: Int): List<FloatArray> {
        val count = bb.getInt(start)
        require(count in 1..100_000)
        var p = start + 4
        val polys = ArrayList<FloatArray>()
        val cur = ArrayList<Float>()
        var lx = 0f; var ly = 0f; var sx = 0f; var sy = 0f
        fun d(): Float { val v = bb.getDouble(p).toFloat(); p += 8; return v }
        fun flush() { if (cur.size >= 4) polys += cur.toFloatArray(); cur.clear() }
        fun add(x: Float, y: Float) { cur += x; cur += y; lx = x; ly = y }
        repeat(count) {
            require(p < end)
            when (b[p++].toInt()) {
                1 -> { flush(); val x = d(); val y = d(); add(x, y); sx = x; sy = y }
                2 -> add(d(), d())
                3 -> { val x1 = d(); val y1 = d(); val x2 = d(); val y2 = d(); val x0 = lx; val y0 = ly
                    for (k in 1..12) { val t = k / 12f; val u = 1 - t; add(u * u * x0 + 2 * u * t * x1 + t * t * x2, u * u * y0 + 2 * u * t * y1 + t * t * y2) } }
                4 -> { val x1 = d(); val y1 = d(); val x2 = d(); val y2 = d(); val x3 = d(); val y3 = d(); val x0 = lx; val y0 = ly
                    for (k in 1..16) { val t = k / 16f; val u = 1 - t
                        add(u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3, u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3) } }
                5 -> { // arc: left, top, width, height, start angle, sweep (degrees, clockwise, y down)
                    val l = d(); val t = d(); val w = d(); val h = d(); val a0 = d(); val sw = d()
                    val n = max(8, (kotlin.math.abs(sw) / 4).toInt())
                    for (k in 0..n) { val a = Math.toRadians((a0 + sw * k / n).toDouble())
                        add(l + w / 2 + w / 2 * cos(a).toFloat(), t + h / 2 + h / 2 * sin(a).toFloat()) }
                }
                6 -> { add(sx, sy); flush() }
                7 -> { flush(); val x1 = d(); val y1 = d(); val x2 = d(); val y2 = d()
                    val cx = (x1 + x2) / 2; val cy = (y1 + y2) / 2; val rx = kotlin.math.abs(x2 - x1) / 2; val ry = kotlin.math.abs(y2 - y1) / 2
                    for (k in 0..96) { val a = k * 2 * Math.PI / 96; add(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat()) }
                    flush() }
                else -> { flush(); return polys }
            }
        }
        flush()
        return polys
    }

    /** Adds points along straight edges so the eraser and lasso work on imported shapes. */
    private fun densify(v: FloatArray): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until v.size / 2 - 1) {
            val ax = v[i * 2]; val ay = v[i * 2 + 1]; val bx = v[i * 2 + 2]; val by = v[i * 2 + 3]
            val n = max(1, (kotlin.math.hypot(bx - ax, by - ay) / 6f).toInt())
            for (k in 0 until n) { val t = k.toFloat() / n; out += ax + (bx - ax) * t; out += ay + (by - ay) * t }
        }
        out += v[v.size - 2]; out += v[v.size - 1]
        return out.toFloatArray()
    }

    // ------------------------------------------------------------------------------------
    // Strokes
    // ------------------------------------------------------------------------------------

    /**
     * Current stroke layout: base frame (kind 0), then a stroke frame (kind 1) whose fixed part
     * holds the point channels — properties bit 0 = delta-compressed, bit 2 = stylus tilt/orientation
     * channels — and whose flexible fields hold the pen: 0 legacy pen id, 1 settings id,
     * 2 ARGB colour, 3 pen size.
     */
    private fun parseFramedStroke(b: ByteArray, start: Int, end: Int): RawStroke? {
        val bb = le(b)
        val base = frame(b, bb, start)
        if (base.kind != 0) return null
        val f = frame(b, bb, base.end)
        if (f.kind != 1 || f.end > end + TRAILER) return null
        val delta = f.prop(0)
        val stylus = f.prop(2)
        var p = f.fixed
        val n = bb.getShort(p).toInt() and 0xFFFF
        p += 2
        if (n < 1) return null
        val scalars = 2 + if (stylus) 2 else 0
        val need = if (delta) 16 + (n - 1) * 4 + scalars * (4 + (n - 1) * 2) else n * 16 + scalars * n * 4
        if (f.fixedEnd - p != need + 2) return null
        val xy = FloatArray(n * 2)
        if (delta) {
            var x = bb.getDouble(p).toFloat(); var y = bb.getDouble(p + 8).toFloat(); p += 16
            xy[0] = x; xy[1] = y
            for (i in 1 until n) {
                x += delta(bb.getShort(p).toInt() and 0xFFFF); y += delta(bb.getShort(p + 2).toInt() and 0xFFFF); p += 4
                xy[i * 2] = x; xy[i * 2 + 1] = y
            }
        } else {
            for (i in 0 until n) { xy[i * 2] = bb.getDouble(p).toFloat(); xy[i * 2 + 1] = bb.getDouble(p + 8).toFloat(); p += 16 }
        }
        val pressure = FloatArray(n)
        if (delta) {
            var v = bb.getFloat(p); p += 4
            pressure[0] = v
            for (i in 1 until n) {
                val w = bb.getShort(p).toInt() and 0xFFFF; p += 2
                val m = (w and 0x7FFF) / 4096f
                v += if (w and 0x8000 != 0) -m else m
                pressure[i] = v
            }
        } else for (i in 0 until n) { pressure[i] = bb.getFloat(p); p += 4 }
        if (xy.any { !it.isFinite() }) return null

        var q = f.flex
        var color: Int? = null
        var size: Float? = null
        if (f.field(0)) q += 4
        if (f.field(1)) q += 4
        if (f.field(2)) { color = bb.getInt(q); q += 4 }
        if (f.field(3)) size = bb.getFloat(q).takeIf { it.isFinite() && it > 0f }
        return RawStroke(xy, color, size, pressure = pressure.takeIf { pr -> pr.all { it.isFinite() } })
    }

    private fun parseStroke(obj: ByteArray): RawStroke? {
        if (obj.size < 80) return null
        return try {
            val bb = le(obj)
            var pos = 4
            if (bb.getShort(pos).toInt() != 0) return null
            pos += 2
            val varOff = bb.getInt(pos); pos += 4
            val flagLen = obj[pos].toInt() and 0xFF
            pos += 1 + 2 + max(0, flagLen - 2)
            pos += 1 + 4 + 4
            val uuidLen = bb.getShort(pos).toInt()
            pos += 2 + if (uuidLen > 0) uuidLen else 0
            pos += 8
            val left = bb.getDouble(pos); val top = bb.getDouble(pos + 8)
            pos += 32
            val payloadOff = if (varOff in 1 until obj.size) varOff else pos + 5
            val pts = decodeStroke(obj, payloadOff) ?: return null
            if (pts.size / 2 <= 2) return null
            val n = pts.size / 2 - 2 // drop the 2-point terminator
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            for (i in 0 until n) { minX = minOf(minX, pts[i * 2]); minY = minOf(minY, pts[i * 2 + 1]) }
            val xy = FloatArray(n * 2)
            for (i in 0 until n) {
                xy[i * 2] = pts[i * 2] - minX + left.toFloat()
                xy[i * 2 + 1] = pts[i * 2 + 1] - minY + top.toFloat()
            }
            RawStroke(xy)
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    private fun delta(word: Int): Float {
        val m = (word and 0x7FFF) / 32f
        return if (word and 0x8000 != 0) -m else m
    }

    private fun decodeStroke(obj: ByteArray, off: Int): FloatArray? {
        val len = obj.size - off
        if (len < 64) return null
        var padded = true
        for (i in 16 until 32) if (obj[off + i].toInt() != 0) { padded = false; break }
        val countOff = off + if (padded) 50 else 34
        val deltaOff = off + if (padded) 76 else 60
        if (deltaOff + 4 > obj.size) return null
        val bb = le(obj)
        val count = bb.getInt(countOff)
        if (count !in 1..200_000) return null
        val out = FloatArray(count * 2)
        var x = 0f; var y = 0f
        var p = deltaOff
        var k = 1
        while (k < count && p + 4 <= obj.size) {
            x += delta(bb.getShort(p).toInt() and 0xFFFF)
            y += delta(bb.getShort(p + 2).toInt() and 0xFFFF)
            out[k * 2] = x; out[k * 2 + 1] = y
            p += 4; k++
        }
        return out.copyOf(k * 2)
    }
}
