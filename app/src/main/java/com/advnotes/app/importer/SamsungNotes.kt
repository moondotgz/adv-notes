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
import kotlin.math.max

/**
 * Reader for Samsung Notes `.sdocx` handwriting.
 *
 * The format is undocumented. This follows the reverse-engineered description and reference
 * decoder in https://github.com/bxff/samsung-notes-format (MIT License, © 2025 bxff):
 * a ZIP holding one binary `<uuid>.page` per page; each page has layers of objects; stroke
 * objects carry a bounding box and a stream of 16-bit (dx, dy) deltas where
 * magnitude = (word & 0x7FFF) / 32 and bit 15 is the sign.
 *
 * Only stroke geometry is decoded, so strokes come in with a default pen colour and width.
 * Typed text, images and audio in the note are not imported.
 */
object SamsungNotes {
    private val STROKE_TYPES = setOf(1, 15)
    private val SIZED_TYPES = setOf(1, 2, 3, 4, 7, 8, 10, 11, 13, 14, 15, 17, 19, 20, 21, 23)

    class RawStroke(val left: Double, val top: Double, val right: Double, val bottom: Double, val xy: FloatArray)
    class RawPage(val width: Int, val height: Int, val strokes: List<RawStroke>)

    fun import(id: String, title: String, f: File): Pair<Notebook, List<String>> {
        val raw = read(f)
        if (raw.isEmpty() || raw.all { it.strokes.isEmpty() }) {
            throw ImportException("No handwriting found in this Samsung Notes file (typed text and images aren't supported yet). Try exporting it as PDF from Samsung Notes instead.")
        }
        val pages = raw.map { toPage(it) }
        val warnings = listOf("Samsung Notes import brings in handwriting shapes only: pen colours and thicknesses are set to defaults, and typed text or images are skipped.")
        return Notebook(id = id, title = title, paper = PaperStyle.PLAIN, pages = pages) to warnings
    }

    fun toPage(rp: RawPage): Page {
        val maxRight = rp.strokes.maxOfOrNull { it.right } ?: 0.0
        val srcW = if (rp.width > 0) rp.width.toDouble() else max(maxRight, 1.0)
        val scale = (PAGE_WIDTH / srcW).toFloat()
        val maxBottom = (rp.strokes.maxOfOrNull { it.bottom } ?: 0.0).toFloat() * scale
        val declared = if (rp.height > 0) rp.height * scale else 0f
        val height = max(max(declared, maxBottom + 40f), PAGE_HEIGHT)
        val strokes = rp.strokes.map { s ->
            val pts = FloatArray(s.xy.size / 2 * 3)
            for (i in 0 until s.xy.size / 2) {
                pts[i * 3] = s.xy[i * 2] * scale
                pts[i * 3 + 1] = s.xy[i * 2 + 1] * scale
                pts[i * 3 + 2] = 1f
            }
            Stroke(kind = InkKind.PEN, color = 0xFF1A1A1A.toInt(), width = 4f, pts = pts, pressure = false)
        }
        return Page(strokes = strokes, height = height)
    }

    /** Reads every page, in the order listed in pageIdInfo.dat when present. */
    fun read(f: File): List<RawPage> = ZipFile(f).use { z ->
        val pageEntries = z.entries().asSequence().filter { it.name.endsWith(".page") }.associateBy { it.name.substringAfterLast('/').removeSuffix(".page") }
        val order = z.getEntry("pageIdInfo.dat")?.let { e -> runCatching { pageOrder(z.getInputStream(e).readBytes()) }.getOrNull() }
            ?.filter { it in pageEntries } .orEmpty()
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
        val base = bb.getInt(0)
        val pageW = bb.getInt(0x16)
        val pageH = bb.getInt(0x1A)
        val strokes = ArrayList<RawStroke>()
        var pos = base
        val layerCount = bb.getShort(pos).toInt() and 0xFFFF
        pos += 4
        repeat(layerCount) {
            pos += 4
            pos += 4 // next-layer offset
            pos += 4 // 4 flag bytes
            val contentFlags = page[pos - 1].toInt() and 0xFF
            pos += 4 // layer id
            if (contentFlags and 0x01 != 0) pos += 1
            if (contentFlags and 0x02 != 0) pos += 4
            for (bit in intArrayOf(0x04, 0x08)) {
                if (contentFlags and bit != 0) {
                    val n = bb.getShort(pos).toInt()
                    pos += 2 + if (n > 0) n * 2 else 0
                }
            }
            if (contentFlags and 0x10 != 0) pos += 8
            if (contentFlags and 0x20 != 0) pos += 4
            val objectCount = bb.getInt(pos)
            pos += 4
            for (o in 0 until objectCount) {
                val type = page[pos].toInt() and 0xFF
                pos += 3
                if (type !in SIZED_TYPES) continue
                val size = bb.getInt(pos)
                pos += 4
                if (size < 0 || pos + size > page.size) break
                if (type in STROKE_TYPES) parseStroke(page.copyOfRange(pos, pos + size))?.let { strokes += it }
                pos += size
            }
            pos += 32 // layer hash
        }
        return RawPage(pageW, pageH, strokes)
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
            val right = bb.getDouble(pos + 16); val bottom = bb.getDouble(pos + 24)
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
            RawStroke(left, top, right, bottom, xy)
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
