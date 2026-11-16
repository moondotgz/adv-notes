package com.advnotes.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.advnotes.app.ink.PaperRenderer
import com.advnotes.app.ink.ShapeFactory
import com.advnotes.app.ink.ShapeKind
import com.advnotes.app.ink.StrokeRenderer
import com.advnotes.app.model.InkKind
import com.advnotes.app.model.PAGE_HEIGHT
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.Page
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle
import com.advnotes.app.model.Stroke
import com.advnotes.app.model.TextBox
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/** Renders paper styles and a sample page to PNGs (build/snapshots) for visual inspection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RenderSnapshots {
    private val out = File(System.getProperty("user.dir"), "build/snapshots").apply { mkdirs() }

    private fun save(bmp: Bitmap, name: String) {
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun paperStyles() {
        val cols = 3
        val w = 300; val h = (w * PAGE_HEIGHT / PAGE_WIDTH).toInt()
        val rows = (PaperStyle.entries.size + cols - 1) / cols
        val bmp = Bitmap.createBitmap(cols * (w + 20) + 20, rows * (h + 20) + 20, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFDDDDDD.toInt())
        PaperStyle.entries.forEachIndexed { i, st ->
            c.save()
            c.translate(20f + (i % cols) * (w + 20f), 20f + (i / cols) * (h + 20f))
            c.scale(w / PAGE_WIDTH, w / PAGE_WIDTH)
            c.clipRect(0f, 0f, PAGE_WIDTH, PAGE_HEIGHT)
            PaperRenderer.draw(c, st, if (i == 8) PaperColor.SLATE else PaperColor.WHITE)
            c.restore()
        }
        save(bmp, "paper_styles")
    }

    private fun handStroke(x0: Float, y0: Float, len: Float, color: Int, width: Float, kind: InkKind = InkKind.PEN): Stroke {
        val n = 60
        val pts = FloatArray(n * 3)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            pts[i * 3] = x0 + t * len
            pts[i * 3 + 1] = y0 + sin(t * 9f) * 18f
            pts[i * 3 + 2] = (0.2f + 0.8f * sin(t * 3.1416f))
        }
        return Stroke(kind = kind, color = color, width = width, pts = pts)
    }

    private fun shape(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float) = ShapeFactory.make(kind, x0, y0, x1, y1).map { xy ->
        val pts = FloatArray(xy.size / 2 * 3)
        for (i in 0 until xy.size / 2) { pts[i * 3] = xy[i * 2]; pts[i * 3 + 1] = xy[i * 2 + 1]; pts[i * 3 + 2] = 1f }
        Stroke(color = 0xFF1F3FBF.toInt(), width = 4f, pts = pts, pressure = false)
    }

    @Test fun samplePage() {
        val page = Page(
            strokes = listOf(
                handStroke(120f, 300f, 600f, 0x66FFE14D, 30f, InkKind.HIGHLIGHTER),
                handStroke(120f, 300f, 600f, 0xFF1A1A1A.toInt(), 6f),
                handStroke(120f, 420f, 500f, 0xFFD62828.toInt(), 10f),
            ) + shape(ShapeKind.RECT, 120f, 520f, 380f, 700f) + shape(ShapeKind.ELLIPSE, 450f, 520f, 700f, 700f) +
                shape(ShapeKind.AXES, 120f, 760f, 480f, 1080f) + shape(ShapeKind.TRIANGLE, 560f, 780f, 860f, 1060f) +
                shape(ShapeKind.ARROW, 560f, 1150f, 860f, 1250f),
            texts = listOf(TextBox(x = 120f, y = 160f, width = 700f, text = "Problem: x² − 5x + 6 = 0\nAnswer: x = 2 or x = 3", size = 26f, color = 0xFF1F3FBF.toInt())),
        )
        val scale = 0.8f
        val bmp = Bitmap.createBitmap((PAGE_WIDTH * scale).toInt(), (PAGE_HEIGHT * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        StrokeRenderer().drawPage(c, page, PaperStyle.OXFORD, PaperColor.WHITE)
        save(bmp, "sample_page")
    }

    /** Regression: round caps/joins used to cancel against the outline and leave white holes. */
    @Test fun noHolesInPenStrokes() {
        fun st(vararg xyp: Float) = Stroke(color = 0xFF000000.toInt(), width = 14f, pts = xyp)
        val strokes = listOf(
            st(100f, 100f, 1f, 300f, 100f, 0.8f),                    // left -> right
            st(300f, 200f, 1f, 100f, 200f, 0.8f),                    // right -> left
            st(100f, 300f, 0.3f, 300f, 360f, 1f, 100f, 420f, 0.5f), // sharp V turn
            st(300f, 460f, 0.9f, 100f, 520f, 1f, 300f, 580f, 0.4f), // V the other way
            st(200f, 650f, 1f, 200f, 640f, 1f, 205f, 650f, 1f),      // tiny hook
        ) + (0 until 2).map { dir ->                                 // closed loops both ways
            val pts = ArrayList<Float>()
            for (k in 0..40) {
                val a = (if (dir == 0) k else 40 - k) / 40f * 6.6f
                pts += 200f + kotlin.math.cos(a) * 60f; pts += 800f + dir * 180f + kotlin.math.sin(a) * 60f
                pts += 0.4f + 0.6f * (k / 40f)
            }
            st(*pts.toFloatArray())
        }
        val bmp = Bitmap.createBitmap(400, 1100, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        val r = StrokeRenderer()
        strokes.forEach { r.draw(c, it) }
        save(bmp, "stroke_holes")
        for (s in strokes) for (i in 0 until s.count) {
            val px = bmp.getPixel(s.x(i).toInt(), s.y(i).toInt())
            val lum = (px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)
            org.junit.Assert.assertTrue("hole at point $i of stroke ${strokes.indexOf(s)}", lum < 200)
        }
    }
}
