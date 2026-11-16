package com.advnotes.app.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.model.PageBackground
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Renders imported page backgrounds (PDF pages or images) to bitmaps, with an in-memory cache.
 * The editor asks asynchronously ([get]) and is called back when a render finishes; export and AI
 * crops use [renderNow].
 */
object Backgrounds {
    /** Rendered width in pixels. Enough for crisp text when zoomed in ~2x on a tablet. */
    const val RENDER_WIDTH = 2000

    private val cache = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun key(notebookId: String, bg: PageBackground) = "$notebookId/${bg.asset}#${bg.pdfPage}"

    fun file(notebookId: String, bg: PageBackground): File = AdvNotesApp.instance.repo.assetFile(notebookId, bg.asset)

    /** Cached bitmap, or null while it renders in the background ([onReady] runs on the main thread). */
    fun get(notebookId: String, bg: PageBackground, onReady: () -> Unit): Bitmap? {
        val k = key(notebookId, bg)
        cache.get(k)?.let { return it }
        synchronized(pending) { if (!pending.add(k)) return null }
        executor.execute {
            val bmp = runCatching { render(file(notebookId, bg), bg.pdfPage, RENDER_WIDTH) }.getOrNull()
            synchronized(pending) { pending.remove(k) }
            if (bmp != null) {
                cache.put(k, bmp)
                main.post(onReady)
            }
        }
        return null
    }

    // Small renders for the page sidebar: their own cache and thread, so they never delay or evict page renders.
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val thumbPending = HashSet<String>()
    private val thumbExecutor = Executors.newSingleThreadExecutor()

    /** Cached small bitmap of the background, or null while it renders ([onReady] runs on the main thread). */
    fun getThumb(notebookId: String, bg: PageBackground, width: Int, onReady: () -> Unit): Bitmap? {
        val k = key(notebookId, bg) + "@$width"
        thumbCache.get(k)?.let { return it }
        synchronized(thumbPending) { if (!thumbPending.add(k)) return null }
        thumbExecutor.execute {
            val bmp = runCatching { render(file(notebookId, bg), bg.pdfPage, width) }.getOrNull()
            synchronized(thumbPending) { thumbPending.remove(k) }
            if (bmp != null) { thumbCache.put(k, bmp); main.post(onReady) }
        }
        return null
    }

    /** Synchronous render (call off the main thread). */
    fun renderNow(notebookId: String, bg: PageBackground, width: Int = RENDER_WIDTH): Bitmap? {
        cache.get(key(notebookId, bg))?.let { if (it.width >= width) return it }
        return runCatching { render(file(notebookId, bg), bg.pdfPage, width) }.getOrNull()
    }

    fun render(f: File, pdfPage: Int, width: Int): Bitmap? {
        if (!f.exists()) return null
        if (pdfPage < 0) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= width) sample *= 2
            return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (pdfPage >= r.pageCount) return null
                r.openPage(pdfPage).use { p ->
                    val h = (width.toFloat() * p.height / p.width).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    /** Page sizes of a PDF as (width, height) in points. */
    fun pdfPageSizes(f: File): List<Pair<Int, Int>> =
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } } }
        }

    fun clear() { cache.evictAll(); thumbCache.evictAll() }

}
