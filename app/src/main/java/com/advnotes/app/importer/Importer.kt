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

    /** Imports into a new notebook and saves it. */
    suspend fun import(uris: List<Uri>): Result = withContext(Dispatchers.IO) {
        val r = build(uris, newId())
        r.copy(notebook = repo.save(r.notebook))
    }

    /**
     * Converts the files into pages whose assets live in notebook [notebookId] (an existing
     * notebook when inserting pages). Nothing is saved.
     */
    suspend fun build(uris: List<Uri>, notebookId: String): Result = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) throw ImportException("No file selected")
        val tmp = uris.map { copyToTemp(it) }
        try {
            val first = tmp[0]
            val title = displayName(uris[0])?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported"
            val fmt = detect(first.file)
            if (uris.size > 1 && fmt != ImportFormat.IMAGE) throw ImportException("Pick several files only for images.")
            val id = notebookId
            val res = when (fmt) {
                ImportFormat.PDF -> importPdf(id, title, first.file)
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

    private fun importPdf(id: String, title: String, src: File): Pair<Notebook, List<String>> {
        val name = "${newId()}.pdf"
        val dest = repo.assetFile(id, name).apply { parentFile?.mkdirs() }
        src.copyTo(dest, overwrite = true)
        val sizes = try { Backgrounds.pdfPageSizes(dest) } catch (e: SecurityException) {
            dest.delete(); throw ImportException("This PDF is password-protected.")
        }
        if (sizes.isEmpty()) throw ImportException("The PDF has no pages.")
        val pages = sizes.mapIndexed { i, (w, h) ->
            Page(height = PAGE_WIDTH * h / w.coerceAtLeast(1), background = PageBackground(name, i))
        }
        return Notebook(id = id, title = title, paper = PaperStyle.PLAIN, pages = pages) to emptyList()
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
