package com.advnotes.app.tools

import android.content.Context
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import com.advnotes.app.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * All tools available in the side panel: native built-ins, web tools bundled in the APK
 * (assets/tools/<id>) and web tools the user installed from a .zip (files/tools/<id>).
 */
class ToolRegistry(private val context: Context, private val builtins: List<NotebookTool>) {
    private val installedDir = File(context.filesDir, "tools").apply { mkdirs() }
    private val _tools = MutableStateFlow<List<NotebookTool>>(emptyList())
    val tools: StateFlow<List<NotebookTool>> = _tools

    init { reload() }

    fun reload() {
        val bundled = runCatching { context.assets.list("tools").orEmpty() }.getOrDefault(emptyArray())
            .filter { !it.startsWith("_") }
            .mapNotNull { dir ->
                runCatching {
                    val m = AppJson.decodeFromString(WebToolManifest.serializer(), context.assets.open("tools/$dir/manifest.json").bufferedReader().readText())
                    WebTool(m, "/assets/tools/$dir/", iconFor(m.icon), installed = false)
                }.getOrNull()
            }
        val installed = installedDir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            runCatching {
                val m = AppJson.decodeFromString(WebToolManifest.serializer(), File(dir, "manifest.json").readText())
                WebTool(m, "/installed/${dir.name}/", iconFor(m.icon), installed = true)
            }.getOrNull()
        }
        // Installed tools may override bundled ones with the same id (handy while developing a port).
        val byId = LinkedHashMap<String, NotebookTool>()
        (builtins + bundled + installed).forEach { byId[it.id] = it }
        _tools.value = byId.values.toList()
    }

    fun find(id: String) = _tools.value.firstOrNull { it.id == id }

    /** Installs (or updates) a web tool from a .zip containing manifest.json. Returns the manifest. */
    suspend fun install(zip: Uri): WebToolManifest = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "tool-install-${System.nanoTime()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(zip)!!.use { input ->
                ZipInputStream(input).use { z ->
                    var total = 0L
                    while (true) {
                        val e = z.nextEntry ?: break
                        val out = File(staging, e.name).canonicalFile
                        // zip-slip guard
                        require(out.path.startsWith(staging.canonicalPath + File.separator)) { "Bad path in zip: ${e.name}" }
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = z.read(buf)
                                if (n < 0) break
                                total += n
                                require(total < 200L * 1024 * 1024) { "Tool is too large (max 200 MB)" }
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            // manifest.json at the root, or inside a single top-level folder
            val root = if (File(staging, "manifest.json").exists()) staging
            else staging.listFiles().orEmpty().singleOrNull { it.isDirectory && File(it, "manifest.json").exists() }
                ?: throw IllegalArgumentException("No manifest.json found in the zip")
            val m = AppJson.decodeFromString(WebToolManifest.serializer(), File(root, "manifest.json").readText())
            require(Regex("[a-z0-9][a-z0-9_-]{0,40}").matches(m.id)) { "Invalid tool id \"${m.id}\" (use lowercase letters, digits, - and _)" }
            require(builtins.none { it.id == m.id }) { "\"${m.id}\" is a built-in tool id" }
            require(File(root, m.entry).exists()) { "Entry page ${m.entry} is missing" }
            val dest = File(installedDir, m.id)
            dest.deleteRecursively()
            root.copyRecursively(dest, overwrite = true)
            withContext(Dispatchers.Main) { reload() }
            m
        } finally {
            staging.deleteRecursively()
        }
    }

    fun uninstall(id: String) {
        File(installedDir, id).deleteRecursively()
        reload()
    }

    companion object {
        fun iconFor(name: String): ImageVector = when (name.lowercase()) {
            "calculate", "calculator" -> Icons.Default.Calculate
            "book", "pdf", "reader", "menu_book" -> Icons.AutoMirrored.Filled.MenuBook
            "chat", "ai" -> Icons.AutoMirrored.Filled.Chat
            "science", "chemistry", "lab" -> Icons.Default.Science
            "functions", "math", "graph" -> Icons.Default.Functions
            "translate", "language" -> Icons.Default.Translate
            "timer", "clock" -> Icons.Default.Timer
            "map" -> Icons.Default.Map
            else -> Icons.Default.Extension
        }
    }
}
