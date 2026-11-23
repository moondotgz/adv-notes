package com.advnotes.app.tools.market

import com.advnotes.app.i18n.tr

import com.advnotes.app.ai.AiException
import com.advnotes.app.ai.Http
import com.advnotes.app.ai.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * The tool marketplace: a public GitHub repository (moondotgz/adv-notes-tools) with an index.json
 * listing each tool and every file in it, with sizes and SHA-256 hashes. The app downloads the
 * files one by one from raw.githubusercontent.com and checks each hash before installing.
 */
object Marketplace {
    const val REPO = "moondotgz/adv-notes-tools"
    const val DEFAULT_BASE = "https://raw.githubusercontent.com/$REPO/main/"

    /** Where index.json and the tool folders live; overridable for tests and for forks. */
    @Volatile var baseUrl: String = DEFAULT_BASE

    @Serializable
    data class Index(val format: Int = 1, val tools: List<Entry> = emptyList())

    @Serializable
    data class Entry(
        val id: String,
        val name: String,
        val description: String = "",
        val icon: String = "extension",
        val version: String = "1.0",
        val author: String = "",
        val category: String = "",
        /** Folder of the tool relative to the repository root, e.g. "tools/pdfreader". */
        val path: String,
        val files: List<FileEntry>,
        /** Lowest app version the tool works with, e.g. "1.0.0". */
        val minApp: String = "",
    ) {
        val size: Long get() = files.sumOf { it.size }
    }

    @Serializable
    data class FileEntry(val path: String, val size: Long, val sha256: String)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun url(rel: String) = baseUrl.trimEnd('/') + "/" + rel.trimStart('/').split('/').joinToString("/") {
        java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }

    private var cache: Pair<Long, Index>? = null

    suspend fun index(force: Boolean = false): Index {
        cache?.let { (t, i) -> if (!force && System.currentTimeMillis() - t < 10 * 60_000) return i }
        val req = Request.Builder().url(url("index.json")).header("Cache-Control", "no-cache").build()
        val body = Http.client.newCall(req).await().use { r ->
            if (r.code == 404) throw AiException(tr("The tool marketplace isn't available yet."))
            if (!r.isSuccessful) throw AiException(tr("Couldn't load the tool marketplace (HTTP {0}).", r.code))
            r.body?.string().orEmpty()
        }
        val idx = runCatching { json.decodeFromString(Index.serializer(), body) }
            .getOrElse { throw AiException(tr("The tool marketplace sent an index this app can't read.")) }
        cache = System.currentTimeMillis() to idx
        return idx
    }

    /**
     * Downloads every file of [e] into [dest] (created fresh), checking sizes and hashes.
     * [progress] receives bytes done out of the total.
     */
    suspend fun download(e: Entry, dest: File, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        require(e.files.any { it.path == "manifest.json" }) { "Tool ${e.id} has no manifest.json" }
        val total = e.size
        require(total < 200L * 1024 * 1024) { "Tool is too large (max 200 MB)" }
        dest.deleteRecursively(); dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        var done = 0L
        for (f in e.files) {
            val out = File(dest, f.path).canonicalFile
            require(out.path.startsWith(root)) { "Bad file path ${f.path}" }
            out.parentFile?.mkdirs()
            val md = MessageDigest.getInstance("SHA-256")
            var n = 0L
            Http.client.newCall(Request.Builder().url(url("${e.path}/${f.path}")).build()).await().use { r ->
                if (!r.isSuccessful) throw AiException(tr("Download failed for {0} (HTTP {1})", f.path, r.code))
                r.body!!.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val k = input.read(buf)
                            if (k < 0) break
                            n += k
                            require(n <= f.size) { "${f.path} is bigger than the index says" }
                            md.update(buf, 0, k); o.write(buf, 0, k)
                            progress(done + n, total)
                        }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            require(n == f.size && hex.equals(f.sha256, ignoreCase = true)) { "${f.path} didn't match its checksum; try again" }
            done += n
        }
    }

    /** Compares dotted versions numerically ("1.10" > "1.9"); non-numeric parts compare as text. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.trim().removePrefix("v").split('.', '-'); val y = b.trim().removePrefix("v").split('.', '-')
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { "0" }; val q = y.getOrElse(i) { "0" }
            val c = when {
                p.toIntOrNull() != null && q.toIntOrNull() != null -> p.toInt().compareTo(q.toInt())
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return 0
    }

    fun sizeLabel(b: Long): String = when {
        b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }
}
