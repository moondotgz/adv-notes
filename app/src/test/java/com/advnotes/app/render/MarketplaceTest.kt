package com.advnotes.app.render

import androidx.test.core.app.ApplicationProvider
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.tools.WebTool
import com.advnotes.app.tools.market.Marketplace
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MarketplaceTest {
    @After fun reset() { Marketplace.baseUrl = Marketplace.DEFAULT_BASE }

    @Test fun versions() {
        assertTrue(Marketplace.compareVersions("1.10", "1.9") > 0)
        assertEquals(0, Marketplace.compareVersions("1.0", "1.0.0"))
        assertTrue(Marketplace.compareVersions("v2.0.0", "1.9.9") > 0)
        assertTrue(Marketplace.compareVersions("1.0.0", "1.0.1") < 0)
    }

    @Test fun downloadsAndVerifies() = FakeMarketplace().use { m ->
        Marketplace.baseUrl = m.baseUrl
        val idx = runBlocking { Marketplace.index(force = true) }
        assertEquals(listOf("hello", "periodic", "timer"), idx.tools.map { it.id })
        val e = idx.tools.first { it.id == "hello" }
        val dest = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "dl")
        var last = 0L
        runBlocking { Marketplace.download(e, dest) { d, _ -> last = d } }
        assertEquals(e.size, last)
        assertEquals("""{"answer":42}""", File(dest, "assets/data.json").readText())
    }

    @Test fun rejectsBadChecksum() = FakeMarketplace(tamper = true).use { m ->
        Marketplace.baseUrl = m.baseUrl
        val e = runBlocking { Marketplace.index(force = true) }.tools.first()
        val dest = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "bad")
        val err = runCatching { runBlocking { Marketplace.download(e, dest) { _, _ -> } } }.exceptionOrNull()
        assertTrue(err?.message.orEmpty(), err?.message.orEmpty().contains("checksum"))
    }

    @Test fun installsThroughRegistry() = FakeMarketplace().use { m ->
        Marketplace.baseUrl = m.baseUrl
        val app = AdvNotesApp.instance
        val e = runBlocking { Marketplace.index(force = true) }.tools.first { it.id == "periodic" }
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { app.tools.install(e) }
        while (!job.isCompleted) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        val t = app.tools.find("periodic") as WebTool
        assertTrue(t.installed)
        assertEquals("2.0.0", app.tools.installedVersion("periodic"))
        app.tools.uninstall("periodic")
        assertFalse(app.tools.tools.value.any { it.id == "periodic" })
    }

}
