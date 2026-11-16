package com.advnotes.app.ai

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

    val available: Boolean by lazy {
        BuildConfig.HAS_LLAMA && runCatching { System.loadLibrary("advnotes_llama"); true }.getOrDefault(false)
    }

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

data class ModelPreset(val name: String, val modelUrl: String, val mmprojUrl: String, val approxSize: String)

/**
 * Runs vision models fully on-device with llama.cpp. Models live in app storage under models/<name>/.
 */
class LocalLlama(private val context: Context, private val settings: SettingsStore) : AiClient {
    companion object {
        val PRESETS = listOf(
            ModelPreset(
                "Qwen2.5-VL-3B-Instruct (Q4_K_M)",
                "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
                "≈2.6 GB",
            ),
            ModelPreset(
                "Gemma-3-4B-it (Q4_K_M)",
                "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
                "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/main/mmproj-model-f16.gguf",
                "≈3.3 GB",
            ),
            ModelPreset(
                "SmolVLM2-2.2B-Instruct (Q4_K_M)",
                "https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF/resolve/main/SmolVLM2-2.2B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF/resolve/main/mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf",
                "≈1.5 GB",
            ),
        )
    }

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
    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        Http.client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw AiException("Download failed: HTTP ${r.code}")
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
        if (!LlamaBridge.available) throw AiException("This build doesn't include the on-device runner.")
        val name = settings.state.value.localModel ?: throw AiException("Pick a local model in Settings.")
        val m = models().firstOrNull { it.name == name && it.ready }
            ?: throw AiException("Local model \"$name\" isn't fully downloaded.")
        return lock.withLock {
            withContext(Dispatchers.Default) {
                if (loadedName != m.name) {
                    unloadLocked()
                    onText("Loading model…")
                    handle = LlamaBridge.load(m.model.path, m.mmproj.path, 4096, settings.state.value.localThreads)
                    if (handle == 0L) throw AiException("Couldn't load the model (out of memory or unsupported file).")
                    loadedName = m.name
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
                LlamaBridge.generate(handle, prompt, req.lastImage, 1500, object : LlamaBridge.TokenCallback {
                    override fun onToken(piece: String): Boolean {
                        sb.append(piece)
                        onText(sb.toString())
                        return ctx.isActive
                    }
                })
            }
        }
    }
}
