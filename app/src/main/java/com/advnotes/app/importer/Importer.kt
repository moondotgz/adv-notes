package com.advnotes.app.importer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.advnotes.app.data.NotebookRepository
import com.advnotes.app.ink.Backgrounds
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.PAGE_HEIGHT
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.Page
import com.advnotes.app.model.PageBackground
import com.advnotes.app.model.PaperStyle
import com.advnotes.app.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

class ImportException(message: String) : Exception(message)

/** What a file turned out to be. */
enum class ImportFormat(val label: String) {
    PDF("PDF"), IMAGE("Image"), SAMSUNG_NOTES("Samsung Notes"), XOURNAL("Xournal++"),
}

/** How an imported PDF behaves: the Adv Notes paper for pages added later, and whether pages may be added at all. */
data class PdfImportOptions(val paper: PaperStyle = PaperStyle.PLAIN, val lockPages: Boolean = false)

/**
 * Imports files from other apps into a new notebook. The format is detected from the file's
 * contents, not its name:
 *  - PDF (also how GoodNotes, Notability, OneNote and Samsung Notes export): each page becomes a
 *    page background you can write on.
 *  - Images (PNG/JPEG/WebP): one page per image.
 *  - Samsung Notes `.sdocx`: handwriting imported as editable strokes.
 *  - Xournal++ `.xopp`: strokes (with pressure), highlighter and text.
 */
class Importer(private val context: Context, private val repo: NotebookRepository) {

    data class Result(val notebook: Notebook, val format: ImportFormat, val warnings: List<String>)

    class SharedResult(val notebooks: List<Result>, val errors: List<String>)

    /**
     * Files shared in from another app: images go together into one notebook, every other file
     * (PDF, .sdocx, .xopp) into its own. One bad file doesn't stop the rest.
     */
    suspend fun importShared(uris: List<Uri>, pdf: PdfImportOptions? = null): SharedResult {
        val (images, others) = uris.partition { isImage(it) }
        val groups = (if (images.isNotEmpty()) listOf(images) else emptyList()) + others.map { listOf(it) }
        val done = ArrayList<Result>()
        val errors = ArrayList<String>()
        for (g in groups) {
            try { done += import(g, pdf) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // Name the MIME type on "unsupported" so an unexpected share type is easy to report.
                val type = if (e.message?.startsWith("Unsupported") == true) context.contentResolver.getType(g[0])?.let { " (shared as $it)" }.orEmpty() else ""
                errors += (displayName(g[0]) ?: "A file") + ": " + (e.message ?: "import failed") + type
            }
        }
        return SharedResult(done, errors)
    }

    private fun header(uri: Uri): ByteArray = runCatching {
        context.contentResolver.openInputStream(uri)?.use { i -> ByteArray(16).let { b -> b.copyOf(i.read(b).coerceAtLeast(0)) } }
    }.getOrNull() ?: ByteArray(0)

    /** True when the file starts with %PDF (by content, not by name or MIME type). */
    suspend fun isPdf(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val h = header(uri)
        h.size >= 4 && h[0] == 0x25.toByte() && h[1] == 0x50.toByte() && h[2] == 0x44.toByte() && h[3] == 0x46.toByte()
    }

    private suspend fun isImage(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val h = header(uri)
        fun starts(vararg b: Int) = h.size >= b.size && b.indices.all { h[it] == b[it].toByte() }
        starts(0x89, 0x50, 0x4E, 0x47) || starts(0xFF, 0xD8, 0xFF) || (starts(0x52, 0x49, 0x46, 0x46) && h.size >= 12 && String(h, 8, 4) == "WEBP") ||
            context.contentResolver.getType(uri)?.startsWith("image/") == true
    }

    /**
     * Imports into a new notebook and saves it. For a PDF the pages keep the PDF's own look; [pdf] says which Adv
     * Notes paper pages added later use and whether pages may be added at all (plain, unlocked when null).
     */
    suspend fun import(uris: List<Uri>, pdf: PdfImportOptions? = null): Result = withContext(Dispatchers.IO) {
        val r = build(uris, newId(), pdf)
        r.copy(notebook = repo.save(r.notebook))
    }

    /**
     * Converts the files into pages whose assets live in notebook [notebookId] (an existing
     * notebook when inserting pages). Nothing is saved.
     */
    suspend fun build(uris: List<Uri>, notebookId: String, pdf: PdfImportOptions? = null): Result = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) throw ImportException("No file selected")
        val tmp = uris.map { copyToTemp(it) }
        try {
            val first = tmp[0]
            val title = displayName(uris[0])?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported"
            val fmt = detect(first.file)
            if (uris.size > 1 && fmt != ImportFormat.IMAGE) throw ImportException("Pick several files only for images.")
            val id = notebookId
            val res = when (fmt) {
                ImportFormat.PDF -> importPdf(id, title, first.file, pdf ?: PdfImportOptions())
                ImportFormat.IMAGE -> importImages(id, title, tmp.map { it.file })
                ImportFormat.SAMSUNG_NOTES -> SamsungNotes.import(id, title, first.file)
                ImportFormat.XOURNAL -> Xournal.import(id, title, openXopp(first.file))
                null -> throw ImportException(
                    "Unsupported file. You can import PDFs, images, Samsung Notes (.sdocx) and Xournal++ (.xopp)."
                )
            }
            Result(res.first, fmt, res.second)
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("Import failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            tmp.forEach { it.file.delete() }
        }
    }

    private data class Tmp(val file: File)

    private fun copyToTemp(uri: Uri): Tmp {
        val f = File.createTempFile("import", null, context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            ?: throw ImportException("Can't open the file")
        return Tmp(f)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun openXopp(f: File): String {
        val gz = f.inputStream().use { val h = ByteArray(2); it.read(h); h[0] == 0x1f.toByte() && h[1] == 0x8b.toByte() }
        return if (gz) GZIPInputStream(f.inputStream()).bufferedReader().readText() else f.readText()
    }

    private fun importPdf(id: String, title: String, src: File, opts: PdfImportOptions): Pair<Notebook, List<String>> {
        val name = "${newId()}.pdf"
        val dest = repo.assetFile(id, name).apply { parentFile?.mkdirs() }
        src.copyTo(dest, overwrite = true)
        val sizes = try { Backgrounds.pdfPageSizes(dest) } catch (e: SecurityException) {
            dest.delete(); throw ImportException("This PDF is password-protected.")
        }
        if (sizes.isEmpty()) throw ImportException("The PDF has no pages.")
        return pdfNotebook(id, title, name, sizes, opts.paper, opts.lockPages) to emptyList()
    }

    private fun importImages(id: String, title: String, files: List<File>): Pair<Notebook, List<String>> {
        val pages = files.map { src ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, opts)
            if (opts.outWidth <= 0) throw ImportException("Couldn't read one of the images.")
            val name = newId() + ".img"
            src.copyTo(repo.assetFile(id, name).apply { parentFile?.mkdirs() }, overwrite = true)
            Page(height = (PAGE_WIDTH * opts.outHeight / opts.outWidth).coerceAtLeast(100f), background = PageBackground(name))
        }
        return Notebook(id = id, title = title, paper = PaperStyle.PLAIN, pages = pages) to emptyList()
    }

    companion object {
        /**
         * One page per PDF page, each drawn from the PDF. The PDF page is opaque over the paper, so it keeps its own
         * look (a Samsung Notes export keeps its note style); [newPagePaper] is what pages added later get.
         */
        internal fun pdfNotebook(id: String, title: String, assetName: String, sizes: List<Pair<Int, Int>>, newPagePaper: PaperStyle, lockPages: Boolean = false): Notebook {
            val pages = sizes.mapIndexed { i, (w, h) ->
                Page(height = PAGE_WIDTH * h / w.coerceAtLeast(1), background = PageBackground(assetName, i))
            }
            return Notebook(id = id, title = title, paper = newPagePaper, pages = pages, lockPages = lockPages)
        }

        fun detect(f: File): ImportFormat? {
            val head = ByteArray(16)
            val n = f.inputStream().use { it.read(head) }
            if (n < 4) return null
            fun starts(vararg b: Int) = b.indices.all { head[it] == b[it].toByte() }
            return when {
                starts(0x25, 0x50, 0x44, 0x46) -> ImportFormat.PDF                         // %PDF
                starts(0x89, 0x50, 0x4E, 0x47) || starts(0xFF, 0xD8, 0xFF) -> ImportFormat.IMAGE
                starts(0x52, 0x49, 0x46, 0x46) && String(head, 8, 4) == "WEBP" -> ImportFormat.IMAGE
                starts(0x1F, 0x8B) -> ImportFormat.XOURNAL                                 // gzip (.xopp)
                starts(0x50, 0x4B) -> runCatching {
                    ZipFile(f).use { z ->
                        if (z.entries().asSequence().any { it.name.endsWith(".page") }) ImportFormat.SAMSUNG_NOTES else null
                    }
                }.getOrNull()
                String(head, 0, n).trimStart().startsWith("<?xml") -> ImportFormat.XOURNAL
                else -> null
            }
        }

        /** Default page height used when a source doesn't give one. */
        const val DEFAULT_HEIGHT = PAGE_HEIGHT
    }
}
