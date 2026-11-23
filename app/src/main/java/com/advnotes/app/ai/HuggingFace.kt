package com.advnotes.app.ai

import com.advnotes.app.i18n.tr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Hugging Face Hub search for GGUF models usable by the on-device runner. */
object HuggingFace {
    private const val API = "https://huggingface.co/api/models"

    data class Repo(val id: String, val downloads: Long, val likes: Long, val vision: Boolean, val updated: String)

    data class GgufFile(val path: String, val size: Long) {
        val name get() = path.substringAfterLast('/')
        val isProjector get() = name.contains("mmproj", ignoreCase = true)
        /** Multi-part files (…-00001-of-00003.gguf) can't be loaded as a single model.gguf. */
        val isSplit get() = Regex("-\\d{5}-of-\\d{5}\\.gguf$").containsMatchIn(name)
        /** Quantisation label such as Q4_K_M, Q8_0, F16. */
        val quant: String get() = Regex("(?i)(IQ\\d_[A-Z0-9_]+|Q\\d(_[A-Z0-9]+)*|BF16|F16|F32)").findAll(name).lastOrNull()?.value?.uppercase() ?: ""
    }

    data class RepoFiles(val models: List<GgufFile>, val projectors: List<GgufFile>)

    private fun request(url: String, token: String?): Request =
        Request.Builder().url(url).apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }.build()

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

    /**
     * Blank query: currently trending vision models in GGUF. Otherwise all GGUF repos matching the
     * query, most downloaded first.
     */
    suspend fun search(query: String, token: String? = null): List<Repo> {
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("filter", "gguf")
            .addQueryParameter("limit", "40")
            .apply {
                if (query.isBlank()) {
                    addQueryParameter("pipeline_tag", "image-text-to-text")
                    addQueryParameter("sort", "trendingScore")
                } else {
                    addQueryParameter("search", query.trim())
                    addQueryParameter("sort", "downloads")
                }
            }.build().toString()
        val body = Http.client.newCall(request(url, token)).await().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(tr("Hugging Face: ") + errorMessage(r.code, b))
            b
        }
        return Http.json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            Repo(
                id = o.str("id"),
                downloads = o.num("downloads"),
                likes = o.num("likes"),
                vision = o.str("pipeline_tag") == "image-text-to-text" || "image-text-to-text" in tags,
                updated = o.str("lastModified").ifEmpty { o.str("createdAt") }.take(10),
            )
        }
    }

    /** GGUF files in a repo, split into language models and vision projectors (mmproj). */
    suspend fun files(repo: String, token: String? = null): RepoFiles {
        val body = Http.client.newCall(request("$API/$repo/tree/main?recursive=true", token)).await().use { r ->
            val b = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw AiException(tr("This model is gated: accept its licence on huggingface.co and add your Hugging Face token below."))
            if (!r.isSuccessful) throw AiException(tr("Hugging Face: ") + errorMessage(r.code, b))
            b
        }
        val all = Http.json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val path = o.str("path")
            if (o.str("type") != "file" || !path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            val size = (o["lfs"] as? JsonObject)?.num("size")?.takeIf { it > 0 } ?: o.num("size")
            GgufFile(path, size)
        }
        return RepoFiles(all.filter { !it.isProjector }.sortedBy { it.size }, all.filter { it.isProjector }.sortedBy { it.size })
    }

    fun downloadUrl(repo: String, path: String) =
        "https://huggingface.co/$repo/resolve/main/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** Sensible defaults: Q4_K_M (good quality/size balance on phones) and a Q8_0 projector. */
    fun defaultModel(files: List<GgufFile>): GgufFile? {
        val usable = files.filter { !it.isSplit }
        for (q in listOf("Q4_K_M", "Q4_K_S", "Q4_0", "IQ4_XS", "Q5_K_M")) usable.firstOrNull { it.quant == q }?.let { return it }
        return usable.firstOrNull()
    }

    fun defaultProjector(files: List<GgufFile>): GgufFile? =
        files.firstOrNull { it.quant == "Q8_0" } ?: files.firstOrNull { it.quant == "F16" } ?: files.firstOrNull()
}
