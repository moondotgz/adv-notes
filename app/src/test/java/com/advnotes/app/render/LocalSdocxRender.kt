package com.advnotes.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.advnotes.app.importer.SamsungNotes
import com.advnotes.app.ink.StrokeRenderer
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Dev helper: renders a local .sdocx given by -Dsdocx=path (skipped otherwise). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LocalSdocxRender {
    @Test fun render() {
        val path = System.getProperty("sdocx")
        assumeTrue(path != null && File(path).exists())
        val out = File(System.getProperty("user.dir"), "build/snapshots/local").apply { mkdirs() }
        SamsungNotes.read(File(path!!)).forEachIndexed { i, rp ->
            println("SDOCX page ${i + 1}: ${rp.width}x${rp.height} strokes=${rp.strokes.count { !it.shape }} shapes=${rp.strokes.count { it.shape }} skipped=${rp.skipped}")
            val page = SamsungNotes.toPage(rp)
            val s = 0.6f
            val bmp = Bitmap.createBitmap((PAGE_WIDTH * s).toInt(), (page.height * s).toInt().coerceAtMost(4000), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.scale(s, s)
            StrokeRenderer().drawPage(c, page, PaperStyle.NARROW_GRID, PaperColor.WHITE)
            File(out, "page${i + 1}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
