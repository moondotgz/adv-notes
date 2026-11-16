package com.advnotes.app.ai

import android.graphics.Bitmap
import com.advnotes.app.ink.Geometry
import com.advnotes.app.model.Stroke
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.tasks.await

/** A handwriting language. [tag] is the BCP-47 tag ML Kit uses; null means "detect" (AI only). */
data class HwLanguage(val name: String, val tag: String?)

enum class HwEngine(val label: String) { ON_DEVICE("On-device"), AI("AI provider") }

object Handwriting {
    val LANGUAGES = listOf(
        HwLanguage("Auto-detect (AI only)", null),
        HwLanguage("English", "en-US"),
        HwLanguage("Polish", "pl-PL"),
        HwLanguage("German", "de-DE"),
        HwLanguage("French", "fr-FR"),
        HwLanguage("Spanish", "es-ES"),
        HwLanguage("Italian", "it-IT"),
        HwLanguage("Portuguese", "pt-BR"),
        HwLanguage("Dutch", "nl-NL"),
        HwLanguage("Czech", "cs-CZ"),
        HwLanguage("Slovak", "sk-SK"),
        HwLanguage("Ukrainian", "uk-UA"),
        HwLanguage("Russian", "ru-RU"),
        HwLanguage("Swedish", "sv-SE"),
        HwLanguage("Norwegian", "nb-NO"),
        HwLanguage("Danish", "da-DK"),
        HwLanguage("Finnish", "fi-FI"),
        HwLanguage("Turkish", "tr-TR"),
        HwLanguage("Greek", "el-GR"),
        HwLanguage("Hungarian", "hu-HU"),
        HwLanguage("Romanian", "ro-RO"),
        HwLanguage("Arabic", "ar"),
        HwLanguage("Hebrew", "he-IL"),
        HwLanguage("Hindi", "hi-IN"),
        HwLanguage("Japanese", "ja-JP"),
        HwLanguage("Chinese (Simplified)", "zh-CN"),
        HwLanguage("Korean", "ko-KR"),
    )

    fun byName(name: String?) = LANGUAGES.firstOrNull { it.name == name }

    /**
     * Groups strokes into text lines (top to bottom). A stroke joins a line when it overlaps the
     * line vertically by at least half of the smaller height; stroke order within a line is kept.
     */
    fun lines(strokes: List<Stroke>): List<List<Stroke>> {
        if (strokes.isEmpty()) return emptyList()
        data class B(val s: Stroke, val top: Float, val bottom: Float)
        val boxes = strokes.map { val r = Geometry.bounds(it); B(it, r.top, r.bottom) }
        val heights = boxes.map { it.bottom - it.top }.sorted()
        val median = heights[heights.size / 2].coerceAtLeast(4f)
        class Line(var top: Float, var bottom: Float, val members: MutableList<B>)
        val lines = ArrayList<Line>()
        // Tall strokes (brackets, integral signs) are placed last so they don't merge lines.
        for (b in boxes.sortedBy { (it.bottom - it.top) > median * 2.5f }) {
            val h = (b.bottom - b.top).coerceAtLeast(1f)
            val best = lines.maxByOrNull { l -> overlap(l.top, l.bottom, b.top, b.bottom) / minOf(h, (l.bottom - l.top).coerceAtLeast(1f)) }
            val ratio = best?.let { overlap(it.top, it.bottom, b.top, b.bottom) / minOf(h, (it.bottom - it.top).coerceAtLeast(1f)) } ?: 0f
            if (best != null && ratio >= 0.5f) {
                best.members += b
                if ((b.bottom - b.top) <= median * 2.5f) { best.top = minOf(best.top, b.top); best.bottom = maxOf(best.bottom, b.bottom) }
            } else lines += Line(b.top, b.bottom, mutableListOf(b))
        }
        val order = strokes.withIndex().associate { it.value.id to it.index }
        return lines.sortedBy { it.top }.map { l -> l.members.map { it.s }.sortedBy { order[it.id] } }
    }

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0f)

    /** On-device recognition with ML Kit Digital Ink (downloads the language model on first use). */
    suspend fun recognizeOnDevice(strokes: List<Stroke>, lang: HwLanguage, onStatus: (String) -> Unit): String {
        val tag = lang.tag ?: throw AiException("Pick a language for on-device recognition (auto-detect needs the AI engine).")
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
            ?: throw AiException("${lang.name} isn't available on-device. Use the AI engine instead.")
        val model = DigitalInkRecognitionModel.builder(id).build()
        val manager = RemoteModelManager.getInstance()
        if (!manager.isModelDownloaded(model).await()) {
            onStatus("Downloading the ${lang.name} handwriting model (one-time, a few MB)…")
            manager.download(model, DownloadConditions.Builder().build()).await()
        }
        onStatus("Recognizing…")
        val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        try {
            val out = StringBuilder()
            var pre = ""
            for (line in lines(strokes)) {
                val ink = Ink.builder()
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                for (s in line) {
                    val sb = Ink.Stroke.builder()
                    for (i in 0 until s.count) {
                        sb.addPoint(Ink.Point.create(s.x(i), s.y(i)))
                        l = minOf(l, s.x(i)); r = maxOf(r, s.x(i)); t = minOf(t, s.y(i)); b = maxOf(b, s.y(i))
                    }
                    ink.addStroke(sb.build())
                }
                val ctx = RecognitionContext.builder()
                    .setPreContext(pre.takeLast(20))
                    .setWritingArea(WritingArea((r - l).coerceAtLeast(1f) * 1.2f, (b - t).coerceAtLeast(1f) * 1.5f))
                    .build()
                val text = recognizer.recognize(ink.build(), ctx).await().candidates.firstOrNull()?.text.orEmpty()
                if (out.isNotEmpty()) out.append('\n')
                out.append(text)
                pre = text
            }
            return out.toString()
        } finally {
            recognizer.close()
        }
    }

    /** Transcription with the configured AI provider (works for printed text and any language). */
    suspend fun recognizeAi(client: AiClient, image: Bitmap, lang: HwLanguage, onText: (String) -> Unit): String {
        val system = "You transcribe handwriting from a note-taking app. Output only the transcribed text, nothing else: " +
            "no quotes, no commentary, no Markdown. Keep the original line breaks. Write math with Unicode symbols " +
            "(², √, ×, ÷, π, →) and chemical formulas with Unicode subscripts (H₂O). Don't correct spelling or translate."
        val prompt = if (lang.tag == null) "Transcribe this handwriting in whatever language it is written in."
        else "Transcribe this handwriting. It is written in ${lang.name}."
        return client.complete(VisionRequest(system, prompt, MathSolver.png(image)), onText).trim()
    }
}
