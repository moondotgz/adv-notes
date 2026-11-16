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
    @Test fun servesAssetsWithJavaScriptTypes() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val h = ToolFiles.Assets(ctx)
        assertEquals("text/javascript", h.handle("tools/lib/advnotes.js")!!.mimeType)
        assertNull(h.handle("tools/lib/missing.js"))
        // downloaded tools load ../lib/advnotes.js from /installed/lib/
        assertEquals("text/javascript", ToolFiles.Assets(ctx, "tools/lib/").handle("advnotes.js")!!.mimeType)
        assertEquals("text/html", ToolFiles.mimeFor("tools/x/index.html"))
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
