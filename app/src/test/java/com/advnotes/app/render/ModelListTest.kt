package com.advnotes.app.render

import com.advnotes.app.AdvNotesApp
import com.advnotes.app.ai.AiClients
import com.advnotes.app.ai.Http
import com.advnotes.app.ai.ModelInfo
import com.advnotes.app.data.ProviderType
import com.advnotes.app.data.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelListTest {
    @Test fun parsesOpenRouterEntry() {
        val o = Http.json.parseToJsonElement("""{"id":"x/vl","name":"X: VL","created":1794838706,"context_length":1048576,
            "architecture":{"input_modalities":["text","image"]},"pricing":{"prompt":"0.00000004375","completion":"0.000000175625"}}""").jsonObject
        val m = ModelInfo.parse(o)!!
        assertEquals(true, m.vision)
        assertEquals(0.04375, m.inputPerM!!, 1e-9)
        assertEquals("$0.044 in · $0.18 out per 1M tokens", m.priceLabel)
        assertEquals("1M context", m.contextLabel)
        val free = ModelInfo.parse(Http.json.parseToJsonElement("""{"id":"a:free","pricing":{"prompt":"0","completion":"0"}}""").jsonObject)!!
        assertEquals("Free", free.priceLabel)
        val anthropic = ModelInfo.parse(Http.json.parseToJsonElement("""{"id":"claude-opus-5-5","display_name":"Claude Opus 5.5","created_at":"2026-08-01T00:00:00Z"}""").jsonObject)!!
        assertEquals("Claude Opus 5.5", anthropic.name); assertEquals(null, anthropic.vision); assertTrue(anthropic.created > 0)
    }

    @Test fun liveOpenRouter() = runBlocking {
        SettingsStore.secretsOverride = { it.getSharedPreferences("test-secrets", 0) }
        val list = runCatching { AiClients.listModels(ProviderType.OPENROUTER, AdvNotesApp.instance.settings) }.getOrNull()
        assumeTrue("openrouter.ai not reachable", list != null)
        assertTrue(list!!.size > 50)
        assertTrue(list.count { it.vision == true } > 20)
        assertTrue(list.all { it.inputPerM != null || it.variablePrice })
        assertTrue(list.first { it.id == "openrouter/auto" }.priceLabel!!.startsWith("Price depends"))
    }
}
