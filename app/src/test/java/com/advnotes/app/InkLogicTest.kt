package com.advnotes.app

import com.advnotes.app.data.AppJson
import com.advnotes.app.ink.Geometry
import com.advnotes.app.ink.ShapeKind
import com.advnotes.app.ink.ShapeRecognizer
import com.advnotes.app.model.InkKind
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.Page
import com.advnotes.app.model.PaperStyle
import com.advnotes.app.model.Stroke
import com.advnotes.app.model.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class InkLogicTest {
    private val rnd = Random(42)
    private fun jitter() = (rnd.nextFloat() - 0.5f) * 3f

    private fun polyline(vararg corners: Float, stepsPerEdge: Int = 30): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until corners.size / 2 - 1) {
            val ax = corners[i * 2]; val ay = corners[i * 2 + 1]; val bx = corners[i * 2 + 2]; val by = corners[i * 2 + 3]
            for (k in 0 until stepsPerEdge) {
                val t = k / stepsPerEdge.toFloat()
                out += ax + (bx - ax) * t + jitter(); out += ay + (by - ay) * t + jitter()
            }
        }
        out += corners[corners.size - 2]; out += corners[corners.size - 1]
        return out.toFloatArray()
    }

    @Test fun recognisesWobblyLine() {
        val r = ShapeRecognizer.recognize(polyline(100f, 100f, 400f, 130f))
        assertEquals(ShapeKind.LINE, r?.kind)
    }

    @Test fun snapsNearlyHorizontalLine() {
        val r = ShapeRecognizer.recognize(polyline(100f, 100f, 400f, 108f))!!
        val s = r.strokes[0]
        assertEquals(s[1], s[s.size - 1], 0.5f)
    }

    @Test fun recognisesCircle() {
        val pts = ArrayList<Float>()
        for (i in 0..80) {
            val a = i / 80f * 2f * PI.toFloat()
            pts += 300f + cos(a) * 100f + jitter(); pts += 300f + sin(a) * 100f + jitter()
        }
        assertEquals(ShapeKind.ELLIPSE, ShapeRecognizer.recognize(pts.toFloatArray())?.kind)
    }

    @Test fun recognisesRectangle() {
        val r = ShapeRecognizer.recognize(polyline(100f, 100f, 400f, 100f, 400f, 300f, 100f, 300f, 100f, 102f))
        assertEquals(ShapeKind.RECT, r?.kind)
    }

    @Test fun recognisesTriangle() {
        val r = ShapeRecognizer.recognize(polyline(250f, 100f, 400f, 360f, 100f, 360f, 250f, 103f))
        assertEquals(ShapeKind.TRIANGLE, r?.kind)
    }

    @Test fun leavesScribbleAlone() {
        // an open zig-zag is not a shape
        assertNull(ShapeRecognizer.recognize(polyline(100f, 100f, 150f, 200f, 200f, 100f, 250f, 200f, 300f, 100f)))
    }

    private fun stroke(vararg xy: Float): Stroke {
        val pts = FloatArray(xy.size / 2 * 3)
        for (i in 0 until xy.size / 2) { pts[i * 3] = xy[i * 2]; pts[i * 3 + 1] = xy[i * 2 + 1]; pts[i * 3 + 2] = 1f }
        return Stroke(kind = InkKind.PEN, color = 0, width = 2f, pts = pts)
    }

    @Test fun partialEraseSplitsStroke() {
        val s = stroke(0f, 0f, 200f, 0f)
        val pieces = Geometry.eraseFromStroke(s, 100f, 0f, 10f)!!
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { p -> (0 until p.count).none { kotlin.math.abs(p.x(it) - 100f) < 10f } })
    }

    @Test fun partialEraseMissReturnsNull() {
        assertNull(Geometry.eraseFromStroke(stroke(0f, 0f, 200f, 0f), 100f, 50f, 10f))
    }

    @Test fun lassoSelection() {
        val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        assertTrue(Geometry.strokeInPolygon(stroke(10f, 10f, 90f, 90f), square))
        assertFalse(Geometry.strokeInPolygon(stroke(150f, 10f, 190f, 90f), square))
        assertTrue(Geometry.strokeHitsCircle(stroke(0f, 0f, 100f, 0f), 50f, 3f, 2f))
    }

    @Test fun notebookJsonRoundTrip() {
        val nb = Notebook(
            title = "Calc",
            paper = PaperStyle.OXFORD,
            pages = listOf(Page(strokes = listOf(stroke(1f, 2f, 3f, 4f)), texts = listOf(TextBox(x = 1f, y = 2f, width = 100f, text = "x = 2")))),
        )
        val json = AppJson.encodeToString(Notebook.serializer(), nb)
        val back = AppJson.decodeFromString(Notebook.serializer(), json)
        assertEquals(nb.title, back.title)
        assertEquals(PaperStyle.OXFORD, back.paper)
        assertEquals(3f, back.pages[0].strokes[0].x(1), 0f)
        assertEquals(4f, back.pages[0].strokes[0].y(1), 0f)
        assertEquals("x = 2", back.pages[0].texts[0].text)
        assertNotNull(back.pages[0].id)
    }
}
