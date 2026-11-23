package com.advnotes.app.ai

import com.advnotes.app.i18n.tr

import android.content.Context
import android.net.Uri
import com.advnotes.app.BuildConfig
import com.advnotes.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** JNI bridge to llama.cpp + libmtmd (multimodal). See app/src/main/cpp/llama_jni.cpp. */
object LlamaBridge {
    interface TokenCallback {
        /** Return false to stop generation. */
        fun onToken(piece: String): Boolean
    }

    /** Tests (no native library on the JVM) can force the UI to treat the runner as available. */
    @androidx.annotation.VisibleForTesting
    var forceAvailable = false

    private val loaded: Boolean by lazy {
        BuildConfig.HAS_LLAMA && runCatching { System.loadLibrary("advnotes_llama"); true }.getOrDefault(false)
    }
    val available: Boolean get() = forceAvailable || loaded

    /** Called from native code with llama.cpp's log output. */
    @JvmStatic
    fun onNativeLog(level: Int, text: String) = LlamaLog.appendRaw(text)

    external fun load(modelPath: String, mmprojPath: String, nCtx: Int, nThreads: Int): Long
    external fun generate(handle: Long, prompt: String, image: ByteArray?, maxTokens: Int, cb: TokenCallback): String
    external fun free(handle: Long)
    external fun systemInfo(): String
}

/** A local vision model: the language model GGUF plus its multimodal projector (mmproj) GGUF. */
data class LocalModel(val name: String, val dir: File) {
    val model get() = File(dir, "model.gguf")
    val mmproj get() = File(dir, "mmproj.gguf")
    val ready get() = model.exists() && mmproj.exists()
    val sizeBytes get() = (if (model.exists()) model.length() else 0) + (if (mmproj.exists()) mmproj.length() else 0)
}

/**
 * Runs vision models fully on-device with llama.cpp. Models live in app storage under models/<name>/.
 */
class LocalLlama(private val context: Context, private val settings: SettingsStore) : AiClient {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val lock = Mutex()
    private var handle = 0L
    private var loadedName: String? = null

    fun models(): List<LocalModel> =
        root.listFiles { f -> f.isDirectory }.orEmpty().map { LocalModel(it.name, it) }.sortedBy { it.name }

    fun dirFor(name: String) = File(root, name.replace(Regex("[^A-Za-z0-9._ -]"), "_"))

    suspend fun delete(m: LocalModel) = lock.withLock {
        if (loadedName == m.name) unloadLocked()
        withContext(Dispatchers.IO) { m.dir.deleteRecursively() }
    }

    /** Downloads a file with progress (0..1). Resumes partial downloads. */
    suspend fun download(url: String, dest: File, token: String? = null, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).apply {
            if (have > 0) header("Range", "bytes=$have-")
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        Http.client.newCall(req).await().use { r ->
            if (r.code == 401 || r.code == 403) throw AiException(tr("This model is gated: accept its licence on huggingface.co and add your Hugging Face token."))
            if (!r.isSuccessful) throw AiException(tr("Download failed: HTTP {0}", r.code))
            val append = r.code == 206
            val total = (r.body!!.contentLength().takeIf { it > 0 } ?: -1L) + (if (append) have else 0L)
            var done = if (append) have else 0L
            r.body!!.byteStream().use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        part.renameTo(dest)
    }

    /** Copies a user-picked file (SAF) into the model folder. */
    suspend fun import(uri: Uri, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val total = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        context.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
    }

    private fun unloadLocked() {
        if (handle != 0L) { LlamaBridge.free(handle); handle = 0L; loadedName = null }
    }

    suspend fun unload() = lock.withLock { unloadLocked() }

    override suspend fun complete(req: VisionRequest, onText: (String) -> Unit): String {
        if (!LlamaBridge.available) throw AiException(tr("This build doesn't include the on-device runner."))
        val name = settings.state.value.localModel ?: throw AiException(tr("Pick a local model in Settings."))
        val m = models().firstOrNull { it.name == name && it.ready }
            ?: throw AiException(tr("Local model \"{0}\" isn't fully downloaded.", name))
        return lock.withLock {
            withContext(Dispatchers.Default) {
                if (loadedName != m.name) {
                    onText("Loading model…")
                    ensureLoadedLocked(m)
                    onText("")
                }
                val ctx = currentCoroutineContext()
                val sb = StringBuilder()
                // Small local models: flatten the conversation into one prompt and send the latest image.
                val prompt = buildString {
                    append(req.system).append("\n\n")
                    val turns = req.messages
                    if (turns.size > 1) for (m in turns.dropLast(1)) append(if (m.fromUser) "User: " else "Assistant: ").append(m.text).append("\n")
                    append(turns.last().text)
                }
                LlamaLog.event("generating (prompt ${prompt.length} chars, image ${req.lastImage?.size ?: 0} bytes)")
                val t0 = System.nanoTime()
                val out = LlamaBridge.generate(handle, prompt, req.lastImage, 1500, object : LlamaBridge.TokenCallback {
                    override fun onToken(piece: String): Boolean {
                        sb.append(piece)
                        onText(sb.toString())
                        return ctx.isActive
                    }
                })
                LlamaLog.event("generated ${out.length} chars in %.1f s".format((System.nanoTime() - t0) / 1e9))
                out
            }
        }
    }

    /** Loads [m] if it isn't already, logging what happens. Call with [lock] held. */
    private fun ensureLoadedLocked(m: LocalModel) {
        if (loadedName == m.name && handle != 0L) return
        unloadLocked()
        val threads = settings.state.value.localThreads
        LlamaLog.event("loading ${m.name}: model %.2f GB, projector %.0f MB, $threads threads".format(m.model.length() / 1e9, m.mmproj.length() / 1e6))
        LlamaLog.event("system: " + runCatching { LlamaBridge.systemInfo().trim() }.getOrDefault("unknown"))
        val t0 = System.nanoTime()
        handle = LlamaBridge.load(m.model.path, m.mmproj.path, 4096, threads)
        val secs = (System.nanoTime() - t0) / 1e9
        if (handle == 0L) {
            LlamaLog.event("FAILED to load ${m.name} after %.1f s (see the lines above; usually out of memory or an unsupported file)".format(secs))
            throw AiException(tr("Couldn't load the model (out of memory or unsupported file). Check Settings → View logs."))
        }
        loadedName = m.name
        LlamaLog.event("loaded ${m.name} OK in %.1f s".format(secs))
    }

    /** Loads the selected model without running anything, for the "Test load" button. */
    suspend fun testLoad(): String {
        if (!LlamaBridge.available) return "This build doesn't include the on-device runner."
        val name = settings.state.value.localModel ?: return "Pick a local model first."
        val m = models().firstOrNull { it.name == name && it.ready } ?: return "Local model \"$name\" isn't fully downloaded."
        return lock.withLock {
            withContext(Dispatchers.Default) {
                try { ensureLoadedLocked(m); "✓ ${m.name} loaded" } catch (e: AiException) { "✗ ${e.message}" }
            }
        }
    }
}
