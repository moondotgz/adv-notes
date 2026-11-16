package com.advnotes.app.render

import androidx.test.core.app.ApplicationProvider
import com.advnotes.app.tools.ToolFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolFilesTest {
    @Test fun servesBundledToolWithJavaScriptTypes() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val h = ToolFiles.Assets(ctx)
        assertEquals("text/javascript", h.handle("tools/pdfreader/pdfjs/pdf.min.js")!!.mimeType)
        assertEquals("text/javascript", h.handle("tools/pdfreader/pdfjs/pdf.worker.min.js")!!.mimeType)
        assertEquals("text/html", h.handle("tools/pdfreader/index.html")!!.mimeType)
        assertEquals("text/javascript", h.handle("tools/lib/advnotes.js")!!.mimeType)
        assertNull(h.handle("tools/pdfreader/missing.js"))
        assertEquals("application/wasm", ToolFiles.mimeFor("a/b.WASM"))
        assertEquals("text/javascript", ToolFiles.mimeFor("x.mjs"))
    }

    @Test fun directoryRefusesEscapes() {
        val root = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "tooldata").apply { mkdirs() }
        File(root, "pdfreader/files").mkdirs()
        File(root, "pdfreader/files/a b.pdf").writeText("%PDF")
        val h = ToolFiles.Directory(root)
        assertEquals("application/pdf", h.handle("pdfreader/files/a b.pdf")!!.mimeType)
        assertNull(h.handle("../shared_prefs/settings.xml"))
        assertNotNull(h.handle("pdfreader/files/a b.pdf"))
    }
}
