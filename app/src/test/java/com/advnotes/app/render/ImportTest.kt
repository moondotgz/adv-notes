package com.advnotes.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.advnotes.app.importer.ImportFormat
import com.advnotes.app.importer.Importer
import com.advnotes.app.importer.SamsungNotes
import com.advnotes.app.importer.Xournal
import com.advnotes.app.ink.StrokeRenderer
import com.advnotes.app.model.InkKind
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImportTest {
    private val out = File(System.getProperty("user.dir"), "build/snapshots").apply { mkdirs() }

    private fun res(name: String): File {
        val f = File.createTempFile("res", name)
        javaClass.getResourceAsStream("/import/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    @Test fun detectsFormats() {
        assertEquals(ImportFormat.SAMSUNG_NOTES, Importer.detect(res("sample1.sdocx")))
        assertEquals(ImportFormat.XOURNAL, Importer.detect(res("sample.xopp.xml")))
        val pdf = File.createTempFile("tmpx", ".pdf").apply { writeText("%PDF-1.7\n") }
        assertEquals(ImportFormat.PDF, Importer.detect(pdf))
        val junk = File.createTempFile("tmpx", ".bin").apply { writeText("hello world, not a note") }
        assertEquals(null, Importer.detect(junk))
    }

    @Test fun samsungNotesStrokes() {
        for (name in listOf("sample1.sdocx", "sample2.sdocx")) {
            val (nb, _) = SamsungNotes.import("nb", "t", res(name))
            val page = nb.pages[0]
            assertTrue("$name has strokes", page.strokes.isNotEmpty() && page.strokes.sumOf { it.count } > 200)
            // every stroke must land on the page
            for (s in page.strokes) for (i in 0 until s.count) {
                assertTrue(s.x(i) in -5f..PAGE_WIDTH + 5f)
                assertTrue(s.y(i) in -5f..page.height + 5f)
            }
            render(page, nb.paper, "import_$name")
        }
    }

    @Test fun xournal() {
        val (nb, warnings) = Xournal.import("nb", "t", res("sample.xopp.xml").readText())
        assertEquals(2, nb.pages.size)
        assertEquals(PaperStyle.NARROW_GRID, nb.paper)
        assertEquals(PaperStyle.COLLEGE_RULED, nb.pages[1].paper)
        val p = nb.pages[0]
        assertEquals(3, p.strokes.size)
        assertTrue(p.strokes[0].pressure)
        assertEquals(InkKind.HIGHLIGHTER, p.strokes[1].kind)
        assertEquals(0xFF3333CC.toInt(), p.strokes[2].color)
        assertEquals(0xFFFF0000.toInt(), nb.pages[1].strokes[0].color)
        assertEquals("E = mc²", p.texts[0].text)
        assertTrue(warnings.isEmpty())
        render(p, nb.paper, "import_xournal")
    }

    private fun render(page: com.advnotes.app.model.Page, paper: PaperStyle, name: String) {
        val scale = 0.6f
        val bmp = Bitmap.createBitmap((PAGE_WIDTH * scale).toInt(), (page.height * scale).toInt().coerceAtMost(3000), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        StrokeRenderer().drawPage(c, page, paper, PaperColor.WHITE)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
