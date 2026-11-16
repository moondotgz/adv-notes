package com.advnotes.app.tools

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.InputStream

/**
 * Serves web-tool files with explicit content types. Android's default guesses can leave `.js` /
 * `.mjs` without a JavaScript type, and WebView then refuses to run ES modules (pdf.js), which
 * makes a tool silently dead.
 */
object ToolFiles {
    private val MIME = mapOf(
        "html" to "text/html", "htm" to "text/html",
        "js" to "text/javascript", "mjs" to "text/javascript", "cjs" to "text/javascript",
        "css" to "text/css", "json" to "application/json", "map" to "application/json",
        "wasm" to "application/wasm", "pdf" to "application/pdf", "txt" to "text/plain",
        "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "ico" to "image/x-icon",
        "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
        "bcmap" to "application/octet-stream", "pfb" to "application/octet-stream",
    )

    fun mimeFor(path: String): String = MIME[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    private fun response(path: String, stream: InputStream?): WebResourceResponse? {
        stream ?: return null
        val mime = mimeFor(path)
        val textual = mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml"
        return WebResourceResponse(mime, if (textual) "utf-8" else null, stream).apply {
            responseHeaders = mapOf("Cache-Control" to "no-cache", "Access-Control-Allow-Origin" to "*")
        }
    }

    /** Files bundled in the APK under assets/<path>. */
    class Assets(private val context: Context) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? =
            response(path, runCatching { context.assets.open(path.removePrefix("/")) }.getOrNull())
    }

    /** Files under [root] (installed tools, tool data); refuses paths escaping it. */
    class Directory(private val root: File) : WebViewAssetLoader.PathHandler {
        private val canonicalRoot = root.canonicalPath + File.separator
        override fun handle(path: String): WebResourceResponse? {
            val f = File(root, path).canonicalFile
            if (!f.path.startsWith(canonicalRoot) || !f.isFile) return null
            return response(path, f.inputStream())
        }
    }
}
