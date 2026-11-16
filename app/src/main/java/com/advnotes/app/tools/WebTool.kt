package com.advnotes.app.tools

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
    override val name get() = manifest.name
    override val description get() = manifest.description

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content(host: ToolHost) {
        val dark = isSystemInDarkTheme()
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        DisposableEffect(Unit) { onDispose { scope.cancel() } }
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
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)

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
                            return true
                        }
                    }
                    val bridge = Bridge(this, host, scope, if (dark) "dark" else "light")
                    addJavascriptInterface(bridge, "AdvNotesNative")
                    val shim = runCatching { ctx.assets.open("tools/lib/advnotes.js").bufferedReader().readText() }.getOrDefault("")
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(this, shim, setOf("https://appassets.androidplatform.net"))
                    }
                    loadUrl("https://appassets.androidplatform.net$basePath${manifest.entry}")
                }
            },
        )
    }

    /** Methods callable from JavaScript as AdvNotesNative.*. They run on a WebView binder thread. */
    private class Bridge(
        private val web: WebView,
        private val host: ToolHost,
        private val scope: CoroutineScope,
        private val theme: String,
    ) {
        private val main = Handler(Looper.getMainLooper())

        private fun <T> onMain(block: () -> T): T? {
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

        private fun js(code: String) = main.post { web.evaluateJavascript(code, null) }
        private fun resolve(id: Int, ok: Boolean, payloadJson: String) = js("AdvNotes._resolve($id, $ok, $payloadJson)")
        private fun quote(s: String) = JsonPrimitive(s).toString()

        @JavascriptInterface fun theme() = theme
        @JavascriptInterface fun notebookTitle() = host.notebookTitle
        @JavascriptInterface fun insertText(text: String) { main.post { host.insertText(text) } }
        @JavascriptInterface fun toast(msg: String) { main.post { host.toast(msg) } }
        @JavascriptInterface fun storageGet(key: String): String? = host.storage.get(key)
        @JavascriptInterface fun storageSet(key: String, value: String?) = host.storage.set(key, value)
        @JavascriptInterface fun selectionImage(): String? = onMain { dataUrl(host.selectionImage()) }
        @JavascriptInterface fun pageImage(): String? = onMain { dataUrl(host.pageImage()) }

        @JavascriptInterface
        fun request(id: Int, method: String, argsJson: String) {
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
                                imageArg == "selection" -> onMain { host.selectionImage() }?.let { png(it) }
                                imageArg == "page" -> onMain { host.pageImage() }?.let { png(it) }
                                else -> decodeDataUrl(imageArg)
                            }
                            val history = (args["history"] as? JsonArray)?.mapNotNull { h ->
                                val o = h.jsonObject
                                val text = (o["text"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                                ChatMessage((o["role"] as? JsonPrimitive)?.content != "assistant", text)
                            }.orEmpty()
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
                    resolve(id, true, result)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
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
            ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } }
            buildJsonObject { put("name", name); put("url", urlFor(name)) }.toString()
        }
    }
}
