package com.advnotes.app.render

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.sharedUris
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTest {
    private val a = Uri.parse("content://x/a.pdf")
    private val b = Uri.parse("content://x/b.pdf")

    @Test fun readsOneSharedFile() {
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, a)
        assertEquals(listOf(a), sharedUris(i))
    }

    @Test fun readsSeveralSharedFiles() {
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
        assertEquals(listOf(a, b), sharedUris(i))
    }

    @Test fun fallsBackToClipData() {
        val i = Intent(Intent.ACTION_SEND_MULTIPLE)
        i.clipData = ClipData.newRawUri("files", a).apply { addItem(ClipData.Item(b)) }
        assertEquals(listOf(a, b), sharedUris(i))
    }

    @Test fun ignoresOtherIntents() {
        assertTrue(sharedUris(Intent(Intent.ACTION_MAIN)).isEmpty())
    }

    private fun xopp(name: String): File {
        val f = File.createTempFile("share", name)
        javaClass.getResourceAsStream("/import/sample.xopp.xml")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    @Test fun eachSharedFileGetsItsOwnNotebook() = runBlocking {
        val r = AdvNotesApp.instance.importer.importShared(listOf(Uri.fromFile(xopp(".xopp")), Uri.fromFile(xopp(".xopp"))))
        assertEquals(2, r.notebooks.size)
        assertTrue(r.errors.isEmpty())
        assertTrue(r.notebooks[0].notebook.id != r.notebooks[1].notebook.id)
    }

    @Test fun aSharedSamsungNoteBecomesItsOwnNotebookWithItsStrokes() = runBlocking {
        val f = File.createTempFile("share", ".sdocx")
        javaClass.getResourceAsStream("/import/sample1.sdocx")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        val r = AdvNotesApp.instance.importer.importShared(listOf(Uri.fromFile(f), Uri.fromFile(xopp(".xopp"))))
        assertEquals(2, r.notebooks.size)
        assertTrue(r.errors.isEmpty())
        val samsung = r.notebooks.first { it.format == com.advnotes.app.importer.ImportFormat.SAMSUNG_NOTES }
        assertTrue(samsung.notebook.pages.any { it.strokes.isNotEmpty() })
    }

    @Test fun oneBadFileDoesNotStopTheRest() = runBlocking {
        val junk = File.createTempFile("share", ".bin").apply { writeText("definitely not a note") }
        val r = AdvNotesApp.instance.importer.importShared(listOf(Uri.fromFile(junk), Uri.fromFile(xopp(".xopp"))))
        assertEquals(1, r.notebooks.size)
        assertEquals(1, r.errors.size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfImportPaperTest {
    @Test fun pdfPagesKeepTheirBackgroundAndNewPagesGetTheChosenPaper() {
        val nb = com.advnotes.app.importer.Importer.pdfNotebook(
            "id", "Notes", "a.pdf", listOf(595 to 842, 842 to 595), com.advnotes.app.model.PaperStyle.COLLEGE_RULED,
        )
        assertEquals(com.advnotes.app.model.PaperStyle.COLLEGE_RULED, nb.paper)
        assertEquals(2, nb.pages.size)
        assertEquals(listOf(0, 1), nb.pages.map { it.background!!.pdfPage })
        assertTrue(nb.pages.all { it.paper == null })                      // imported pages don't override the paper
        assertTrue(nb.pages[1].height < nb.pages[0].height)                // landscape page keeps its shape
        // pages added later have no background and fall back to the notebook paper
        assertEquals(null, com.advnotes.app.model.Page().background)
        assertEquals(false, nb.lockPages)
    }

    @Test fun lockedPdfNotebookCanBeRequested() {
        val nb = com.advnotes.app.importer.Importer.pdfNotebook(
            "id", "Notes", "a.pdf", listOf(595 to 842), com.advnotes.app.model.PaperStyle.PLAIN, lockPages = true,
        )
        assertEquals(true, nb.lockPages)
    }

    @Test fun detectsPdfsByContent() = runBlocking {
        val pdf = File.createTempFile("pdfx", ".bin").apply { writeText("%PDF-1.7\nrest") }
        val txt = File.createTempFile("txtx", ".pdf").apply { writeText("not a pdf") }
        assertTrue(AdvNotesApp.instance.importer.isPdf(Uri.fromFile(pdf)))
        assertTrue(!AdvNotesApp.instance.importer.isPdf(Uri.fromFile(txt)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockPagesTest {
    private fun pump(vm: com.advnotes.app.editor.EditorViewModel) {
        repeat(100) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (vm.ui.value.notebook != null) return
            Thread.sleep(30)
        }
    }

    @Test fun lockedNotebookDoesNotGrowAndUnlockingRestoresIt() {
        val app = AdvNotesApp.instance
        val nb = runBlocking {
            app.repo.save(com.advnotes.app.model.Notebook(title = "locked", lockPages = true, pages = listOf(com.advnotes.app.model.Page(), com.advnotes.app.model.Page())))
        }
        val vm = com.advnotes.app.editor.EditorViewModel(app)
        vm.open(nb.id); pump(vm)
        assertEquals(true, vm.ui.value.notebook!!.lockPages)

        vm.addPage(); vm.appendPage()
        vm.goTo(1); vm.swipe(1, 1); vm.swipe(1, 2)
        assertEquals(2, vm.ui.value.notebook!!.pages.size)

        vm.setLockPages(false)
        vm.addPage()
        assertEquals(3, vm.ui.value.notebook!!.pages.size)
        vm.swipe(1, 1)
        assertTrue(vm.ui.value.notebook!!.pages.size >= 3)
    }
}
