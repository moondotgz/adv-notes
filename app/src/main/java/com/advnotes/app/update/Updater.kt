package com.advnotes.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.advnotes.app.BuildConfig
import com.advnotes.app.ai.Http
import com.advnotes.app.ai.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.IOException

data class Update(val version: String, val apkUrl: String, val size: Long, val notes: String)

/** Looks for a newer GitHub release, downloads its APK and hands it to the system installer. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/moondotgz/adv-notes/releases/latest"

    /** True when [remote] (e.g. "v1.3.0") is a higher dotted version than [local]. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newer release, or null when up to date. Throws on network errors. */
    suspend fun check(current: String = BuildConfig.VERSION_NAME): Update? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        Http.client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub returned ${r.code}")
            val o = Http.json.parseToJsonElement(r.body!!.string()).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@use null
            if (!isNewer(tag, current)) return@use null
            val assets = o["assets"] as? JsonArray ?: return@use null
            val apk = assets.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: return@use null
            Update(
                version = tag.removePrefix("v"),
                apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
                size = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L,
                notes = o["body"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    /** Like [check] but returns null on any failure (for the silent launch-time check). */
    suspend fun checkQuietly(): Update? = try { check() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    suspend fun download(context: Context, u: Update, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "adv-notes-${u.version}.apk")
        Http.client.newCall(Request.Builder().url(u.apkUrl).build()).await().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: u.size
            out.outputStream().use { sink ->
                body.byteStream().use { src ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        out
    }

    /** Opens the system installer. Returns false when the user first has to allow installs from this app. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
