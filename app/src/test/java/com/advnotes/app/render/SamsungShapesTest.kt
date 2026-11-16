package com.advnotes.app.render

import com.advnotes.app.importer.SamsungNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.hypot

/**
 * Synthetic Samsung Notes pages built with the documented object layout: objects with children
 * (groups), shapes and lines. Regression for "everything after a shape/group disappears".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamsungShapesTest {
    private class W {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = apply { o.write(v) }
        fun u16(v: Int) = apply { o.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()) }
        fun i32(v: Int) = apply { o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun f32(v: Float) = apply { o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()) }
        fun f64(v: Double) = apply { o.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()) }
        fun bytes(b: ByteArray) = apply { o.write(b) }
        fun get(): ByteArray = o.toByteArray()
    }

    private fun mask(bits: List<Int>): ByteArray {
        if (bits.isEmpty()) return byteArrayOf(0)
        val b = ByteArray(1 + 1)
        b[0] = 1
        bits.forEach { b[1] = (b[1].toInt() or (1 shl it)).toByte() }
        return b
    }

    /** size:u32, kind:i16, flexibleOffset:u32, props mask, fields mask, fixed, flexible */
    private fun frame(kind: Int, fixed: ByteArray, flexible: ByteArray = ByteArray(0), fields: List<Int> = emptyList()): ByteArray {
        val props = mask(emptyList()); val f = mask(fields)
        val header = 4 + 2 + 4 + props.size + f.size
        val flexOff = if (flexible.isEmpty() && fields.isEmpty()) 0 else header + fixed.size
        val size = header + fixed.size + flexible.size
        return W().i32(size).u16(kind).i32(flexOff).bytes(props).bytes(f).bytes(fixed).bytes(flexible).get()
    }

    private fun base(l: Double, t: Double, r: Double, b: Double) =
        frame(0, W().i32(1).u16(0).bytes(ByteArray(8)).f64(l).f64(t).f64(r).f64(b).i32(0).u8(0).get())

    private fun style(argb: Int, width: Float): ByteArray {
        val color = W().u8(0).u8(0).i32(argb).get()                         // mask(0 bytes), color type 0, ARGB
        val line = W().f32(width).u8(0).u8(0).u8(0).u8(0).u8(0).u8(0).u8(0).u8(0).get()
        val flexible = W().i32(color.size).bytes(color).i32(line.size).bytes(line).get()
        val fixed = W().i32(0).i32(4).i32(0).u8(0).get()                    // no magnetic points, empty connections
        return frame(6, fixed, flexible, fields = listOf(2, 3))
    }

    private fun obj(type: Int, data: ByteArray, children: List<ByteArray> = emptyList()): ByteArray {
        val w = W().u8(type).u16(children.size).i32(data.size + 32).bytes(data).bytes(ByteArray(32))
        children.forEach { w.bytes(it) }
        return w.get()
    }

    private fun ellipse(l: Double, t: Double, r: Double, b: Double) = obj(7,
        base(l, t, r, b) + style(0xFF101010.toInt(), 6f) +
            frame(7, W().i32(1).f64(l).f64(t).f64(r).f64(b).f32(0f).i32(0).u8(0).f64(l).f64(t).f64(r).f64(b).get()))

    private fun line(x0: Double, y0: Double, x1: Double, y1: Double) = obj(8,
        base(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1)) + style(0xFFD62828.toInt(), 2f) +
            frame(8, W().u8(0).u8(0).u8(0).f64(x0).f64(y0).f64(x1).f64(y1)
                .f64(x0).f64(y0).f64(x1).f64(y1).f64(x0).f64(y0).f64(x1).f64(y1).i32(0).get()))

    /** An arc drawn as a path shape (type 0 with path: move + arc). */
    private fun arcShape() = obj(7,
        base(400.0, 400.0, 500.0, 500.0) + style(0xFF000000.toInt(), 3f) +
            frame(7, W().i32(0).f64(400.0).f64(400.0).f64(500.0).f64(500.0).f32(0f)
                .let { val path = W().i32(1).u8(5).f64(400.0).f64(400.0).f64(100.0).f64(100.0).f64(200.0).f64(90.0).get(); it.i32(path.size).bytes(path) }
                .u8(0).f64(400.0).f64(400.0).f64(500.0).f64(500.0).get()))

    /** The real stroke objects (bytes) from sample1.sdocx. */
    private fun realStrokes(): List<ByteArray> {
        val f = File.createTempFile("res", "s.sdocx")
        javaClass.getResourceAsStream("/import/sample1.sdocx")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        val page = ZipFile(f).use { z -> z.getInputStream(z.entries().asSequence().first { it.name.endsWith(".page") }).readBytes() }
        val bb = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        var pos = bb.getInt(0) + 4
        pos += bb.getInt(pos)
        val n = bb.getInt(pos); pos += 4
        return (0 until n).map {
            val size = bb.getInt(pos + 3)
            page.copyOfRange(pos, pos + 7 + size).also { pos += 7 + size }
        }
    }

    private fun page(objects: List<ByteArray>): ByteArray {
        // header: layerOffset, flexibleOffset, 2 masks, orientation, width, height, ...
        val head = W().i32(0).i32(0).u8(0).u8(0).i32(0).i32(1440).i32(2000).i32(0).i32(0).u16(0).get()
        val layerHeader = W().i32(14).i32(0).u8(0).u8(0).i32(0).get()
        val layers = W().u16(1).u16(0).bytes(layerHeader).i32(objects.size).also { w -> objects.forEach { w.bytes(it) } }.bytes(ByteArray(32)).get()
        val bytes = head + layers
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, head.size)
        return bytes
    }

    @Test fun groupsShapesAndLinesDontHideLaterObjects() {
        val strokes = realStrokes()
        val group = obj(4, base(0.0, 0.0, 1.0, 1.0), children = listOf(ellipse(300.0, 300.0, 900.0, 900.0), line(600.0, 600.0, 400.0, 350.0)))
        val raw = SamsungNotes.readPage(page(listOf(strokes[0], group, arcShape(), strokes[1], strokes[2])))
        assertEquals(0, raw.skipped)
        assertEquals(1440, raw.width)
        val shapes = raw.strokes.filter { it.shape }
        val ink = raw.strokes.filter { !it.shape }
        assertEquals("all three real strokes survive, including the two after the group", 3, ink.size)
        assertEquals(3, shapes.size)

        val circle = shapes[0]
        assertEquals(300.0, circle.left, 1.0); assertEquals(900.0, circle.right, 1.0)
        val c = 600f
        for (i in 0 until circle.xy.size / 2) assertEquals(300f, hypot(circle.xy[i * 2] - c, circle.xy[i * 2 + 1] - c), 1f)
        assertEquals(0xFF101010.toInt(), circle.color); assertEquals(6f, circle.width!!, 0f)

        val ln = shapes[1]
        assertEquals(600f, ln.xy[0], 0f); assertEquals(600f, ln.xy[1], 0f)
        assertEquals(400f, ln.xy[ln.xy.size - 2], 0f); assertEquals(350f, ln.xy[ln.xy.size - 1], 0f)
        assertEquals(0xFFD62828.toInt(), ln.color)

        // quarter arc from 200° sweeping 90°: starts left-up of centre (450,450)
        val arc = shapes[2]
        assertTrue(arc.xy.size >= 16)
        for (i in 0 until arc.xy.size / 2) assertEquals(50f, hypot(arc.xy[i * 2] - 450f, arc.xy[i * 2 + 1] - 450f), 0.5f)

        val nbPage = SamsungNotes.toPage(raw)
        assertEquals(6, nbPage.strokes.size)
    }
}
