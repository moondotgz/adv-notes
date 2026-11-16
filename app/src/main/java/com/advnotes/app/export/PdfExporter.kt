package com.advnotes.app.export

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.advnotes.app.ink.StrokeRenderer
import com.advnotes.app.model.Notebook
import com.advnotes.app.ink.Backgrounds
import com.advnotes.app.model.PAGE_WIDTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfExporter {
    /** A4 width in PostScript points; heights follow each page's aspect ratio. */
    private const val PT_W = 595

    suspend fun export(context: Context, nb: Notebook, pressureCurve: Float): File = withContext(Dispatchers.Default) {
        val doc = PdfDocument()
        val renderer = StrokeRenderer(pressureCurve)
        nb.pages.forEachIndexed { i, page ->
            val h = (PT_W * page.height / PAGE_WIDTH).toInt()
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PT_W, h, i + 1).create())
            val c = p.canvas
            c.scale(PT_W / PAGE_WIDTH, PT_W / PAGE_WIDTH)
            val bg = page.background?.let { Backgrounds.renderNow(nb.id, it, 1600) }
            renderer.drawPage(c, page, nb.paper, nb.paperColor, background = bg)
            doc.finishPage(p)
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safe = nb.title.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifBlank { "notebook" }
        val f = File(dir, "$safe.pdf")
        withContext(Dispatchers.IO) { f.outputStream().use { doc.writeTo(it) } }
        doc.close()
        f
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
