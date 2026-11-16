package com.advnotes.app.render

import com.advnotes.app.ai.HuggingFace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuggingFaceTest {
    @Test fun quantParsing() {
        assertEquals("Q4_K_M", HuggingFace.GgufFile("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", 1).quant)
        assertEquals("Q8_0", HuggingFace.GgufFile("mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf", 1).quant)
        assertEquals("F16", HuggingFace.GgufFile("mmproj-model-f16.gguf", 1).quant)
        assertEquals("IQ4_XS", HuggingFace.GgufFile("x-IQ4_XS.gguf", 1).quant)
        assertTrue(HuggingFace.GgufFile("big-Q4_K_M-00001-of-00003.gguf", 1).isSplit)
        assertTrue(HuggingFace.GgufFile("mmproj-x.gguf", 1).isProjector)
        val files = listOf("m-Q8_0.gguf", "m-Q4_K_M.gguf", "m-Q2_K.gguf").map { HuggingFace.GgufFile(it, 1) }
        assertEquals("m-Q4_K_M.gguf", HuggingFace.defaultModel(files)!!.name)
        assertEquals("https://huggingface.co/a/b/resolve/main/dir/x%20y.gguf", HuggingFace.downloadUrl("a/b", "dir/x y.gguf"))
    }

    /** Live API check; skipped without network access to huggingface.co. */
    @Test fun liveSearchAndFiles() = runBlocking {
        val repos = runCatching { HuggingFace.search("Qwen2.5-VL-3B-Instruct") }.getOrNull()
        assumeTrue("huggingface.co not reachable", repos != null)
        assertTrue(repos!!.any { it.id == "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF" })
        val trending = HuggingFace.search("")
        assertTrue(trending.isNotEmpty() && trending.all { it.vision })
        val f = HuggingFace.files("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF")
        assertEquals("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", HuggingFace.defaultModel(f.models)!!.name)
        assertEquals("mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf", HuggingFace.defaultProjector(f.projectors)!!.name)
        assertTrue(f.models.first { it.quant == "Q4_K_M" }.size > 1_000_000_000)
    }
}
