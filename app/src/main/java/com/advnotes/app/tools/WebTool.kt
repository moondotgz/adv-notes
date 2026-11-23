package com.advnotes.app.tools

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.material3.surfaceColorAtElevation
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.advnotes.app.ai.ChatMessage
import com.advnotes.app.ai.VisionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hosts a web tool (HTML/JS) in a WebView. Files are served from https://appassets.androidplatform.net:
 *  - /assets/tools/<id>/…   tools bundled in the APK (app/src/main/assets/tools)
 *  - /installed/<id>/…      tools installed from a .zip (files/tools)
 *  - /tooldata/<id>/files/… files the tool stored (e.g. PDFs the user picked)
 * and the page gets the `AdvNotes` JavaScript API (assets/tools/lib/advnotes.js).
 */
class WebTool(
    val manifest: WebToolManifest,
    /** URL path prefix of the tool's folder, e.g. "/assets/tools/pdfreader/". */
    private val basePath: String,
    override val icon: ImageVector,
    val installed: Boolean,
) : NotebookTool {
    override val id get() = manifest.id
    override val name get() = meta("name") ?: manifest.name
    override val description get() = meta("description") ?: manifest.description

    // ---- translations: <tool>/i18n/<tag>.json, keyed by the English text (see docs/TOOLS.md) ----
    private val stringsCache = HashMap<String, String?>()

    /** The tool's translation file for [tag] (or for its base language), as JSON text; null when there is none. */
    fun stringsJson(tag: String): String? = stringsCache.getOrPut(tag) {
        if (tag == "en") null else readToolFile("i18n/$tag.json") ?: readToolFile("i18n/${tag.substringBefore('-')}.json")
    }

    private fun meta(key: String): String? = runCatching {
        val s = stringsJson(com.advnotes.app.i18n.I18n.tag()) ?: return null
        val m = com.advnotes.app.ai.Http.json.parseToJsonElement(s).jsonObject["_meta"]?.jsonObject ?: return null
        (m[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readToolFile(rel: String): String? = runCatching {
        val ctx = com.advnotes.app.AdvNotesApp.instance
        if (basePath.startsWith("/installed/")) File(ctx.filesDir, "tools/" + basePath.removePrefix("/installed/") + rel).takeIf { it.isFile }?.readText()
        else ctx.assets.open(basePath.removePrefix("/assets/") + rel).bufferedReader().use { it.readText() }
    }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content(host: ToolHost) {
        val dark = isSystemInDarkTheme()
        // The app's live Material colours (wallpaper-based on Android 12+), handed to tools as CSS variables.
        val cs = androidx.compose.material3.MaterialTheme.colorScheme
        val panelBg = cs.surfaceColorAtElevation(2.dp)
        val colorsJson = remember(cs, panelBg) {
            fun hex(c: androidx.compose.ui.graphics.Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)
            buildJsonObject {
                put("background", hex(panelBg))
                listOf(
                    "primary" to cs.primary, "onPrimary" to cs.onPrimary,
                    "primaryContainer" to cs.primaryContainer, "onPrimaryContainer" to cs.onPrimaryContainer,
                    "secondaryContainer" to cs.secondaryContainer, "onSecondaryContainer" to cs.onSecondaryContainer,
                    "tertiary" to cs.tertiary, "tertiaryContainer" to cs.tertiaryContainer,
                    "surface" to cs.surface, "onSurface" to cs.onSurface, "onSurfaceVariant" to cs.onSurfaceVariant,
                    "surfaceContainerLow" to cs.surfaceContainerLow, "surfaceContainer" to cs.surfaceContainer,
                    "surfaceContainerHigh" to cs.surfaceContainerHigh, "surfaceContainerHighest" to cs.surfaceContainerHighest,
                    "outline" to cs.outline, "outlineVariant" to cs.outlineVariant,
                    "error" to cs.error, "onError" to cs.onError, "errorContainer" to cs.errorContainer,
                ).forEach { (k, c) -> put(k, hex(c)) }
            }.toString()
        }
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        DisposableEffect(Unit) { onDispose { scope.cancel() } }
        androidx.compose.runtime.key(com.advnotes.app.i18n.I18n.tag()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val files = ctx.filesDir
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", ToolFiles.Assets(ctx))
                    // downloaded tools load the API shim as ../lib/advnotes.js, like bundled ones
                    .addPathHandler("/installed/lib/", ToolFiles.Assets(ctx, "tools/lib/"))
                    .addPathHandler("/installed/", ToolFiles.Directory(File(files, "tools").apply { mkdirs() }))
                    .addPathHandler("/tooldata/", ToolFiles.Directory(File(files, "tooldata").apply { mkdirs() }))
                    .build()
                WebView(ctx).apply {
                    // Without explicit params the WebView gets wrap_content, and Chromium then lays pages out
                    // against a zero-height viewport (height:100% / 100vh collapse to 0).
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val r = loader.shouldInterceptRequest(request.url)
                            if (request.url.host == "appassets.androidplatform.net") {
                                val range = request.requestHeaders["Range"]?.let { " Range: $it" }.orEmpty()
                                ToolLog.add(manifest.id, "${request.method} ${request.url.path}$range -> ${if (r == null) "NOT FOUND" else r.mimeType}")
                            }
                            return r
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                            ToolLog.add(manifest.id, "load error ${error.errorCode} ${error.description} for ${request.url}")
                        }

                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                            ToolLog.add(manifest.id, "RENDERER ${if (detail.didCrash()) "CRASHED" else "was killed (probably out of memory)"}; reopen the tool")
                            return true
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // Keep tools inside their sandbox; open other links in the browser.
                            if (request.url.host == "appassets.androidplatform.net") return false
                            runCatching {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            return true
                        }
                    }
                    // Needed for alert/confirm/prompt dialogs and console logging from tools.
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            android.util.Log.d("WebTool/${manifest.id}", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                            ToolLog.add(manifest.id, "js ${m.messageLevel().name.lowercase()}: ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                            return true
                        }
                    }
                    setBackgroundColor(panelBg.toArgb())   // no white flash before the page paints
                    val lang = com.advnotes.app.i18n.I18n.tag()
                    val bridge = Bridge(this, host, scope, if (dark) "dark" else "light", colorsJson,
                        lang, com.advnotes.app.i18n.I18n.name(), stringsJson(lang) ?: "{}")
                    addJavascriptInterface(bridge, "AdvNotesNative")
                    val shim = runCatching { ctx.assets.open("tools/lib/advnotes.js").bufferedReader().readText() }.getOrDefault("")
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(this, shim, setOf("https://appassets.androidplatform.net"))
                    }
                    ToolLog.add(manifest.id, "opening $basePath${manifest.entry} (WebView ${WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName ?: "?"})")
                    loadUrl("https://appassets.androidplatform.net$basePath${manifest.entry}")
                }
            },
        )
        }
    }

    /** Methods callable from JavaScript as AdvNotesNative.*. They run on a WebView binder thread. */
    private class Bridge(
        private val web: WebView,
        private val host: ToolHost,
        private val scope: CoroutineScope,
        private val theme: String,
        private val colorsJson: String,
        private val lang: String,
        private val langName: String,
        private val stringsJsonText: String,
    ) {
        private val main = Handler(Looper.getMainLooper())

        private fun <T> onMain(block: () -> T): T? {
            // Already on the main thread (coroutines on Dispatchers.Main): posting and waiting would deadlock.
            if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
            var r: T? = null
            val latch = CountDownLatch(1)
            main.post { r = runCatching(block).getOrNull(); latch.countDown() }
            latch.await(5, TimeUnit.SECONDS)
            return r
        }

        private fun dataUrl(b: Bitmap?): String? = b?.let {
            val bytes = ByteArrayOutputStream().use { o -> it.compress(Bitmap.CompressFormat.PNG, 100, o); o.toByteArray() }
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        private fun decodeDataUrl(s: String): ByteArray? =
            s.substringAfter("base64,", "").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) }

        private fun js(code: String) = main.post {
            web.evaluateJavascript(code) { if (!code.startsWith("AdvNotes._progress")) ToolLog.add(host.toolId, "eval ${code.take(40)} -> $it") }
        }
        private fun resolve(id: Int, ok: Boolean, payloadJson: String) = js("AdvNotes._resolve($id, $ok, $payloadJson)")
        private fun quote(s: String) = JsonPrimitive(s).toString()

        @JavascriptInterface fun theme() = theme
        /** The app's colour scheme as JSON: {"primary":"#RRGGBB", ...}. */
        @JavascriptInterface fun colors() = colorsJson
        /** App language tag ("en", "pt-BR"…), its display name, and this tool's translations for it (JSON). */
        @JavascriptInterface fun language() = lang
        @JavascriptInterface fun languageName() = langName
        @JavascriptInterface fun strings() = stringsJsonText
        @JavascriptInterface fun notebookTitle() = host.notebookTitle
        @JavascriptInterface fun insertText(text: String) { main.post { host.insertText(text) } }
        @JavascriptInterface fun toast(msg: String) { main.post { host.toast(msg) } }
        /** Places a PNG/JPEG data URL on the page as a picture. Returns false if it couldn't be decoded. */
        @JavascriptInterface fun insertImage(dataUrl: String): Boolean {
            val bytes = decodeDataUrl(dataUrl) ?: return false
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            main.post { host.insertImage(bmp) }
            return true
        }
        @JavascriptInterface fun storageGet(key: String): String? = host.storage.get(key)
        @JavascriptInterface fun storageSet(key: String, value: String?) = host.storage.set(key, value)
        @JavascriptInterface fun selectionImage(): String? = onMain { dataUrl(host.selectionImage()) }
        @JavascriptInterface fun pageImage(): String? = onMain { dataUrl(host.pageImage()) }

        @JavascriptInterface
        fun request(id: Int, method: String, argsJson: String) {
            ToolLog.add(host.toolId, "bridge #$id $method ${argsJson.take(80)}")
            scope.launch {
                try {
                    val args = com.advnotes.app.ai.Http.json.parseToJsonElement(argsJson).jsonObject
                    val result: String = when (method) {
                        "ai" -> {
                            val prompt = (args["prompt"] as? JsonPrimitive)?.content.orEmpty()
                            val system = (args["system"] as? JsonPrimitive)?.content
                                ?: "You are a helpful assistant inside a note-taking app. Answer in plain text without Markdown."
                            val imageArg = (args["image"] as? JsonPrimitive)?.content
                            val image: ByteArray? = when {
                                imageArg == null -> null
                                imageArg == "selection" -> onMain { host.selectionImage() }?.let { withContext(Dispatchers.Default) { png(it) } }
                                    ?: throw IllegalStateException("Select something with the lasso first.")
                                imageArg == "page" -> onMain { host.pageImage() }?.let { withContext(Dispatchers.Default) { png(it) } }
                                    ?: throw IllegalStateException("Couldn't capture the visible page.")
                                else -> decodeDataUrl(imageArg)
                            }
                            val history = (args["history"] as? JsonArray)?.mapNotNull { h ->
                                val o = h.jsonObject
                                val text = (o["text"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                                ChatMessage((o["role"] as? JsonPrimitive)?.content != "assistant", text)
                            }.orEmpty()
                            ToolLog.add(host.toolId, "bridge #$id ai image=${imageArg ?: "none"} -> ${image?.size ?: 0} bytes")
                            val msgs = history + ChatMessage(true, prompt, listOfNotNull(image))
                            val text = host.ai().complete(VisionRequest(system, msgs)) { partial -> js("AdvNotes._progress($id, ${quote(partial)})") }
                            quote(text)
                        }
                        "pickFile" -> {
                            val accept = (args["accept"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: listOf("*/*")
                            val uri = pick(accept)
                            if (uri == null) "null" else copyIn(uri)
                        }
                        "listFiles" -> buildJsonArray {
                            filesDir().listFiles().orEmpty().sortedBy { it.name }.forEach { f ->
                                add(buildJsonObject { put("name", f.name); put("url", urlFor(f.name)); put("size", f.length()) })
                            }
                        }.toString()
                        "deleteFile" -> {
                            val name = (args["name"] as? JsonPrimitive)?.content.orEmpty()
                            File(filesDir(), File(name).name).delete().toString()
                        }
                        else -> throw IllegalArgumentException("Unknown method $method")
                    }
                    ToolLog.add(host.toolId, "bridge #$id $method ok (${result.length} chars)")
                    resolve(id, true, result)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    ToolLog.add(host.toolId, "bridge #$id $method cancelled")
                    throw e
                } catch (e: Exception) {
                    ToolLog.add(host.toolId, "bridge #$id $method FAILED: ${e.message ?: e}\n" + e.stackTraceToString().lines().take(8).joinToString("\n"))
                    resolve(id, false, quote(e.message ?: e.toString()))
                }
            }
        }

        private fun png(b: Bitmap) = ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }

        private fun filesDir() = File(host.storage.dir, "files").apply { mkdirs() }

        private fun urlFor(name: String) =
            "https://appassets.androidplatform.net/tooldata/${host.toolId}/files/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

        private suspend fun pick(accept: List<String>): Uri? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            main.post { host.pickFile(accept) { uri -> if (cont.isActive) cont.resumeWith(Result.success(uri)) } }
        }

        private suspend fun copyIn(uri: Uri): String = withContext(Dispatchers.IO) {
            val ctx = host.context
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }?.let { File(it).name }?.ifBlank { null } ?: "file-${System.currentTimeMillis()}"
            val dest = File(filesDir(), name)
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Couldn't read the picked file ($uri)")
            input.use { i -> dest.outputStream().use { i.copyTo(it) } }
            buildJsonObject { put("name", name); put("url", urlFor(name)) }.toString()
        }
    }
}
