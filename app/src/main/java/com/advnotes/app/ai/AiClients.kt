package com.advnotes.app.ai

import com.advnotes.app.i18n.tr

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

/** One chat turn. [images] are PNG bytes (only sent on user turns). */
data class ChatMessage(val fromUser: Boolean, val text: String, val images: List<ByteArray> = emptyList())

/** A request to a vision-capable model: instructions plus a conversation. */
data class VisionRequest(val system: String, val messages: List<ChatMessage>) {
    /** Single-turn convenience: one prompt and an optional image. */
    constructor(system: String, prompt: String, png: ByteArray?) :
        this(system, listOf(ChatMessage(true, prompt, listOfNotNull(png))))

    val lastImage: ByteArray? get() = messages.lastOrNull { it.images.isNotEmpty() }?.images?.last()
}

interface AiClient {
    /** Streams text through [onText] and returns the full answer. */
    suspend fun complete(req: VisionRequest, onText: (String) -> Unit): String
}

private val JSON = "application/json".toMediaType()

object AiClients {
    /** Used when AI features are switched off in Settings: refuses every request, so nothing is ever sent. */
    private object Disabled : AiClient {
        override suspend fun complete(req: VisionRequest, onText: (String) -> Unit): String =
            throw AiException(com.advnotes.app.i18n.tr("AI features are turned off in Settings."))
    }

    fun forProvider(
        p: ProviderType,
        s: SettingsStore,
        chatGpt: ChatGptAuth,
        claudeCode: ClaudeCodeAuth,
        local: LocalLlama,
    ): AiClient =
        if (!s.state.value.aiEnabled) Disabled else when (p) {
        ProviderType.OPENAI -> OpenAiChatClient("https://api.openai.com/v1", s.apiKey(p), s.model(p), official = true)
        ProviderType.OPENROUTER -> OpenAiChatClient("https://openrouter.ai/api/v1", s.apiKey(p), s.model(p), official = false)
        ProviderType.CUSTOM_OPENAI -> OpenAiChatClient(s.baseUrl(p), s.apiKey(p), s.model(p), official = false)
        ProviderType.ANTHROPIC -> AnthropicClient("https://api.anthropic.com", s.apiKey(p), s.model(p), official = true)
        ProviderType.CUSTOM_ANTHROPIC -> AnthropicClient(s.baseUrl(p), s.apiKey(p), s.model(p), official = false)
        ProviderType.CHATGPT -> ChatGptCodexClient(chatGpt, s.model(p))
        ProviderType.CLAUDE_CODE -> ClaudeCodeClient(claudeCode, s.model(p))
        ProviderType.LOCAL -> local
    }

    /** Lists models from the provider's models endpoint, where one exists. */
    suspend fun listModels(p: ProviderType, s: SettingsStore): List<ModelInfo> {
        ModelCache.get(p, s)?.let { return it }
        val key = s.apiKey(p)
        val req = when (p) {
            ProviderType.OPENAI -> Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key")
            ProviderType.OPENROUTER -> Request.Builder().url("https://openrouter.ai/api/v1/models")
            ProviderType.CUSTOM_OPENAI -> Request.Builder().url(s.baseUrl(p).trimEnd('/') + "/models").apply {
                if (key.isNotBlank()) header("Authorization", "Bearer $key")
            }
            ProviderType.ANTHROPIC, ProviderType.CUSTOM_ANTHROPIC -> {
                val base = if (p == ProviderType.ANTHROPIC) "https://api.anthropic.com" else s.baseUrl(p)
                Request.Builder().url(AnthropicClient.endpoint(base, "models") + "?limit=1000")
                    .header("x-api-key", key)
                    .header("anthropic-version", if (p == ProviderType.ANTHROPIC) AnthropicClient.VERSION else AnthropicClient.COMPAT_VERSION)
                    .apply { if (p == ProviderType.CUSTOM_ANTHROPIC && key.isNotBlank()) header("Authorization", "Bearer $key") }
            }
            ProviderType.CHATGPT -> return ChatGptCodexClient.KNOWN_MODELS.map { ModelInfo(it, it, vision = true) }
            ProviderType.CLAUDE_CODE -> return ClaudeCodeClient.KNOWN_MODELS.map { ModelInfo(it, it, vision = true) }
            ProviderType.LOCAL -> return emptyList()
        }.build()
        val resp = Http.client.newCall(req).await()
        val list = resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(errorMessage(r.code, body))
            val arr = Http.json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
            arr.mapNotNull { it.obj()?.let { o -> ModelInfo.parse(o) } }
        }
        ModelCache.put(p, s, list)
        return list
    }
}

/**
 * A model offered by a provider. OpenRouter also reports what it accepts and what it costs;
 * other providers only give the id (and Anthropic a display name).
 */
data class ModelInfo(
    val id: String,
    val name: String,
    /** Accepts image input; null when the provider doesn't say. */
    val vision: Boolean? = null,
    val contextLength: Long? = null,
    /** USD per million input / output tokens. */
    val inputPerM: Double? = null,
    val outputPerM: Double? = null,
    /** Unix seconds; used for "newest" sorting. */
    val created: Long = 0,
    /** Routers (e.g. openrouter/auto) bill whatever model they pick. */
    val variablePrice: Boolean = false,
) {
    val free get() = inputPerM == 0.0 && outputPerM == 0.0

    /** "$0.04 / $0.18 per 1M tokens" or "Free". */
    val priceLabel: String?
        get() = when {
            variablePrice -> "Price depends on the model it routes to"
            inputPerM == null || outputPerM == null -> null
            free -> "Free"
            else -> "${usd(inputPerM)} in · ${usd(outputPerM)} out per 1M tokens"
        }

    val contextLabel: String?
        get() = contextLength?.takeIf { it > 0 }?.let { if (it >= 1_000_000) "${"%.1f".format(it / 1e6).removeSuffix(".0")}M context" else "${it / 1000}k context" }

    companion object {
        private fun usd(v: Double) = when {
            v >= 10 -> "$%.0f".format(v)
            v >= 0.1 -> "$%.2f".format(v)
            else -> "$%.3f".format(v)
        }

        fun parse(o: kotlinx.serialization.json.JsonObject): ModelInfo? {
            val id = o["id"].str() ?: return null
            val name = o["name"].str() ?: o["display_name"].str() ?: id
            val arch = o["architecture"].obj()
            val inputs = (arch?.get("input_modalities") as? JsonArray)?.mapNotNull { it.str() }
            val pricing = o["pricing"].obj()
            fun perM(k: String) = pricing?.get(k).str()?.toDoubleOrNull()?.takeIf { it >= 0 }?.times(1_000_000)
            val created = (o["created"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                ?: o["created_at"].str()?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() } ?: 0L
            return ModelInfo(
                id = id,
                name = name,
                vision = inputs?.contains("image"),
                contextLength = (o["context_length"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull(),
                inputPerM = perM("prompt"),
                outputPerM = perM("completion"),
                created = created,
                variablePrice = pricing?.get("prompt").str()?.toDoubleOrNull()?.let { it < 0 } == true,
            )
        }
    }
}

/** Model lists change rarely; keep them for 30 minutes per provider/key/base URL. */
private object ModelCache {
    private val cache = HashMap<String, Pair<Long, List<ModelInfo>>>()
    private fun key(p: ProviderType, s: SettingsStore) = "${p.name}|${s.baseUrl(p)}|${s.apiKey(p).hashCode()}"
    @Synchronized fun get(p: ProviderType, s: SettingsStore) = cache[key(p, s)]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.second
    @Synchronized fun put(p: ProviderType, s: SettingsStore, l: List<ModelInfo>) { cache[key(p, s)] = System.currentTimeMillis() to l }
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
        if (baseUrl.isBlank()) throw AiException(tr("Set a base URL for this provider in Settings."))
        if (model.isBlank()) throw AiException(tr("Set a model name for this provider in Settings."))
        if (official && apiKey.isBlank()) throw AiException(tr("Add your API key in Settings."))
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            // Newer OpenAI models only accept max_completion_tokens; compatible servers expect max_tokens.
            put(if (official) "max_completion_tokens" else "max_tokens", 8000)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", req.system) }
                for (m in req.messages) addJsonObject {
                    put("role", if (m.fromUser) "user" else "assistant")
                    if (!m.fromUser || m.images.isEmpty()) put("content", m.text)
                    else putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", m.text) }
                        for (img in m.images) addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/png;base64," + img.b64()) }
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
        if (baseUrl.isBlank()) throw AiException(tr("Set a base URL for this provider in Settings."))
        if (model.isBlank()) throw AiException(tr("Set a model name for this provider in Settings."))
        if (official && apiKey.isBlank()) throw AiException(tr("Add your Anthropic API key in Settings."))
        val useFallbacks = official && FALLBACK_MODELS.any { model.startsWith(it) }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16000)
            put("stream", true)
            put("system", req.system)
            if (useFallbacks) put("fallbacks", "default")
            putJsonArray("messages") {
                for (m in req.messages) addJsonObject {
                    put("role", if (m.fromUser) "user" else "assistant")
                    putJsonArray("content") {
                        for (img in m.images) addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64"); put("media_type", "image/png"); put("data", img.b64())
                            }
                        }
                        addJsonObject { put("type", "text"); put("text", m.text.ifEmpty { " " }) }
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
        if (refused && out.isBlank()) throw AiException(tr("The model declined to answer this request."))
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
        val token = auth.validAccessToken() ?: throw AiException(tr("Sign in with ChatGPT in Settings first."))
        val account = auth.accountId()
        val body = buildJsonObject {
            put("model", model.ifBlank { "gpt-5" })
            put("instructions", req.system)
            put("stream", true)
            put("store", false)
            putJsonArray("input") {
                for (m in req.messages) addJsonObject {
                    put("type", "message")
                    put("role", if (m.fromUser) "user" else "assistant")
                    putJsonArray("content") {
                        if (m.fromUser) {
                            addJsonObject { put("type", "input_text"); put("text", m.text) }
                            for (img in m.images) addJsonObject { put("type", "input_image"); put("image_url", "data:image/png;base64," + img.b64()) }
                        } else addJsonObject { put("type", "output_text"); put("text", m.text) }
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

/**
 * Uses a Claude Pro/Max/Team/Enterprise subscription through the Claude Code OAuth sign-in
 * (the same public client and headers the Claude Code CLI uses). Unofficial: Anthropic may
 * change the backend at any time.
 */
class ClaudeCodeClient(private val auth: ClaudeCodeAuth, private val model: String) : AiClient {
    companion object {
        val KNOWN_MODELS = listOf(
            "claude-opus-5-5",
            "claude-sonnet-5-5",
            "claude-haiku-5-5",
            "claude-fable-5",
        )
        private const val URL = "https://api.anthropic.com/v1/messages?beta=true"
        private const val IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."
    }

    override suspend fun complete(req: VisionRequest, onText: (String) -> Unit): String {
        val token = auth.validAccessToken() ?: throw AiException(tr("Sign in with Claude in Settings first."))
        val body = buildJsonObject {
            put("model", model.ifBlank { "claude-opus-5-5" })
            put("max_tokens", 16000)
            put("stream", true)
            // OAuth tokens are gated to the Claude Code identity, which must be the first system block.
            putJsonArray("system") {
                addJsonObject { put("type", "text"); put("text", IDENTITY) }
                if (req.system.isNotBlank()) addJsonObject { put("type", "text"); put("text", req.system) }
            }
            putJsonArray("messages") {
                for (m in req.messages) addJsonObject {
                    put("role", if (m.fromUser) "user" else "assistant")
                    putJsonArray("content") {
                        for (img in m.images) addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64"); put("media_type", "image/png"); put("data", img.b64())
                            }
                        }
                        addJsonObject { put("type", "text"); put("text", m.text.ifEmpty { " " }) }
                    }
                }
            }
        }
        val rb = Request.Builder()
            .url(URL)
            .post(body.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $token")
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "claude-code-20250219," + ClaudeCodeAuth.OAUTH_BETA)
            .header("user-agent", ClaudeCodeAuth.USER_AGENT)
            .header("x-app", "cli")
            .header("Accept", "text/event-stream")

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
        if (refused && out.isBlank()) throw AiException(tr("The model declined to answer this request."))
        return out.toString()
    }
}
