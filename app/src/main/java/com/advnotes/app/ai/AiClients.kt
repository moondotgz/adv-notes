package com.advnotes.app.ai

import android.util.Base64
import com.advnotes.app.data.ProviderType
import com.advnotes.app.data.SettingsStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A request to a vision-capable model: one image plus instructions. */
data class VisionRequest(val system: String, val prompt: String, val png: ByteArray)

interface AiClient {
    /** Streams text through [onText] and returns the full answer. */
    suspend fun complete(req: VisionRequest, onText: (String) -> Unit): String
}

private val JSON = "application/json".toMediaType()

object AiClients {
    fun forProvider(p: ProviderType, s: SettingsStore, chatGpt: ChatGptAuth, local: LocalLlama): AiClient = when (p) {
        ProviderType.OPENAI -> OpenAiChatClient("https://api.openai.com/v1", s.apiKey(p), s.model(p), official = true)
        ProviderType.OPENROUTER -> OpenAiChatClient("https://openrouter.ai/api/v1", s.apiKey(p), s.model(p), official = false)
        ProviderType.CUSTOM_OPENAI -> OpenAiChatClient(s.baseUrl(p), s.apiKey(p), s.model(p), official = false)
        ProviderType.ANTHROPIC -> AnthropicClient("https://api.anthropic.com", s.apiKey(p), s.model(p), official = true)
        ProviderType.CUSTOM_ANTHROPIC -> AnthropicClient(s.baseUrl(p), s.apiKey(p), s.model(p), official = false)
        ProviderType.CHATGPT -> ChatGptCodexClient(chatGpt, s.model(p))
        ProviderType.LOCAL -> local
    }

    /** Lists model ids from the provider's models endpoint, where one exists. */
    suspend fun listModels(p: ProviderType, s: SettingsStore): List<String> {
        val key = s.apiKey(p)
        val req = when (p) {
            ProviderType.OPENAI -> Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key")
            ProviderType.OPENROUTER -> Request.Builder().url("https://openrouter.ai/api/v1/models")
            ProviderType.CUSTOM_OPENAI -> Request.Builder().url(s.baseUrl(p).trimEnd('/') + "/models").apply {
                if (key.isNotBlank()) header("Authorization", "Bearer $key")
            }
            ProviderType.ANTHROPIC, ProviderType.CUSTOM_ANTHROPIC -> {
                val base = if (p == ProviderType.ANTHROPIC) "https://api.anthropic.com" else s.baseUrl(p)
                Request.Builder().url(AnthropicClient.endpoint(base, "models"))
                    .header("x-api-key", key)
                    .header("anthropic-version", if (p == ProviderType.ANTHROPIC) AnthropicClient.VERSION else AnthropicClient.COMPAT_VERSION)
                    .apply { if (p == ProviderType.CUSTOM_ANTHROPIC && key.isNotBlank()) header("Authorization", "Bearer $key") }
            }
            ProviderType.CHATGPT -> return ChatGptCodexClient.KNOWN_MODELS
            ProviderType.LOCAL -> return emptyList()
        }.build()
        val resp = Http.client.newCall(req).await()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(errorMessage(r.code, body))
            val arr = Http.json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
            return arr.mapNotNull { it.obj()?.get("id").str() }.sorted()
        }
    }
}

private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)

/** OpenAI Chat Completions — also used for OpenRouter and any OpenAI-compatible server. */
class OpenAiChatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val official: Boolean,
) : AiClient {
    override suspend fun complete(req: VisionRequest, onText: (String) -> Unit): String {
        if (baseUrl.isBlank()) throw AiException("Set a base URL for this provider in Settings.")
        if (model.isBlank()) throw AiException("Set a model name for this provider in Settings.")
        if (official && apiKey.isBlank()) throw AiException("Add your API key in Settings.")
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            // Newer OpenAI models only accept max_completion_tokens; compatible servers expect max_tokens.
            put(if (official) "max_completion_tokens" else "max_tokens", 8000)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", req.system) }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", req.prompt) }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/png;base64," + req.png.b64()) }
                        }
                    }
                }
            }
        }
        val rb = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) rb.header("Authorization", "Bearer $apiKey")
        if (baseUrl.contains("openrouter.ai")) rb.header("X-Title", "Adv Notes")

        val out = StringBuilder()
        streamSse(rb.build()) { _, data ->
            val o = runCatching { Http.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@streamSse
            o["error"]?.let { throw AiException(it.obj()?.get("message").str() ?: it.toString()) }
            val delta = o["choices"]?.jsonArray?.firstOrNull()?.obj()?.get("delta").obj()
            val text = delta?.get("content").str()
            if (!text.isNullOrEmpty()) { out.append(text); onText(out.toString()) }
        }
        return out.toString()
    }
}

/** Anthropic Messages API (raw HTTP so custom Anthropic-compatible base URLs work too). */
class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val official: Boolean,
) : AiClient {
    companion object {
        const val VERSION = "2026-10-21"
        /** Older version string that third-party Anthropic-compatible servers widely accept. */
        const val COMPAT_VERSION = "2023-06-01"

        fun endpoint(base: String, path: String): String {
            val b = base.trimEnd('/')
            return if (b.endsWith("/v1")) "$b/$path" else "$b/v1/$path"
        }

        /** Models that support server-side refusal fallbacks. */
        private val FALLBACK_MODELS = listOf("claude-fable-5", "claude-opus-5", "claude-sonnet-5-5")
    }

    override suspend fun complete(req: VisionRequest, onText: (String) -> Unit): String {
        if (baseUrl.isBlank()) throw AiException("Set a base URL for this provider in Settings.")
        if (model.isBlank()) throw AiException("Set a model name for this provider in Settings.")
        if (official && apiKey.isBlank()) throw AiException("Add your Anthropic API key in Settings.")
        val useFallbacks = official && FALLBACK_MODELS.any { model.startsWith(it) }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16000)
            put("stream", true)
            put("system", req.system)
            if (useFallbacks) put("fallbacks", "default")
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64"); put("media_type", "image/png"); put("data", req.png.b64())
                            }
                        }
                        addJsonObject { put("type", "text"); put("text", req.prompt) }
                    }
                }
            }
        }
        val rb = Request.Builder()
            .url(endpoint(baseUrl, "messages"))
            .post(body.toString().toRequestBody(JSON))
            .header("anthropic-version", if (official) VERSION else COMPAT_VERSION)
            .header("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) {
            rb.header("x-api-key", apiKey)
            if (!official) rb.header("Authorization", "Bearer $apiKey")
        }
        if (useFallbacks) rb.header("anthropic-beta", "server-side-fallback-2026-07-01")

        val out = StringBuilder()
        var refused = false
        streamSse(rb.build()) { _, data ->
            val o = runCatching { Http.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@streamSse
            when (o["type"].str()) {
                "content_block_delta" -> {
                    val d = o["delta"].obj()
                    if (d?.get("type").str() == "text_delta") {
                        out.append(d?.get("text").str().orEmpty()); onText(out.toString())
                    }
                }
                "message_delta" -> if (o["delta"].obj()?.get("stop_reason").str() == "refusal") refused = true
                "error" -> throw AiException(o["error"].obj()?.get("message").str() ?: data)
            }
        }
        if (refused && out.isBlank()) throw AiException("The model declined to answer this request.")
        return out.toString()
    }
}

/**
 * Uses a ChatGPT Plus/Pro subscription through the Codex backend (the same endpoint the Codex CLI
 * uses after "Sign in with ChatGPT"). Unofficial: OpenAI may change it at any time.
 */
class ChatGptCodexClient(private val auth: ChatGptAuth, private val model: String) : AiClient {
    companion object {
        val KNOWN_MODELS = listOf("gpt-5", "gpt-5-codex", "gpt-5.1", "gpt-5.1-codex")
        private const val URL = "https://chatgpt.com/backend-api/codex/responses"
    }

    override suspend fun complete(req: VisionRequest, onText: (String) -> Unit): String {
        val token = auth.validAccessToken() ?: throw AiException("Sign in with ChatGPT in Settings first.")
        val account = auth.accountId()
        val body = buildJsonObject {
            put("model", model.ifBlank { "gpt-5" })
            put("instructions", req.system)
            put("stream", true)
            put("store", false)
            putJsonArray("input") {
                addJsonObject {
                    put("type", "message")
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "input_text"); put("text", req.prompt) }
                        addJsonObject { put("type", "input_image"); put("image_url", "data:image/png;base64," + req.png.b64()) }
                    }
                }
            }
            putJsonArray("include") { add("reasoning.encrypted_content") }
        }
        val rb = Request.Builder().url(URL)
            .post(body.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $token")
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", "codex_cli_rs")
            .header("Accept", "text/event-stream")
        if (account != null) rb.header("chatgpt-account-id", account)

        val out = StringBuilder()
        streamSse(rb.build()) { _, data ->
            val o = runCatching { Http.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@streamSse
            when (o["type"].str()) {
                "response.output_text.delta" -> { out.append(o["delta"].str().orEmpty()); onText(out.toString()) }
                "response.failed", "error" -> {
                    val msg = o["response"].obj()?.get("error").obj()?.get("message").str()
                        ?: o["error"].obj()?.get("message").str() ?: o["message"].str() ?: data
                    throw AiException(msg)
                }
            }
        }
        return out.toString()
    }
}
