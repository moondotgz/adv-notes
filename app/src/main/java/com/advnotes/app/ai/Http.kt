package com.advnotes.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // reasoning models can think for a while before streaming
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

class AiException(message: String) : Exception(message)

/** Executes the call, cancelling it if the coroutine is cancelled. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) { _, value, _ -> value.close() } }
    })
}

/**
 * Sends [request] and feeds each SSE `data:` payload to [onData]. Throws [AiException] with the
 * server's error message on HTTP errors.
 */
suspend fun streamSse(request: Request, onData: suspend (event: String?, data: String) -> Unit) {
    val resp = Http.client.newCall(request).await()
    resp.use { r ->
        if (!r.isSuccessful) throw AiException(errorMessage(r.code, r.body?.string().orEmpty()))
        withContext(Dispatchers.IO) {
            val src = r.body!!.source()
            var event: String? = null
            val data = StringBuilder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = src.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            val d = data.toString()
                            data.clear()
                            if (d != "[DONE]") onData(event, d)
                        }
                        event = null
                    }
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }
            }
            if (data.isNotEmpty() && data.toString() != "[DONE]") onData(event, data.toString())
        }
    }
}

fun errorMessage(code: Int, body: String): String {
    val detail = runCatching {
        val o = Http.json.parseToJsonElement(body).jsonObject
        val err = o["error"]
        when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> o["message"]?.jsonPrimitive?.contentOrNull ?: o["detail"]?.toString()
        }
    }.getOrNull() ?: body.take(400)
    val hint = when (code) {
        401, 403 -> " (check your API key / sign-in)"
        404 -> " (check the model name and base URL)"
        429 -> " (rate limited or out of credits)"
        else -> ""
    }
    return "HTTP $code: $detail$hint"
}

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
