package com.advnotes.app.importer

import android.util.Xml
import com.advnotes.app.model.InkKind
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.Page
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle
import com.advnotes.app.model.Stroke
import com.advnotes.app.model.TextBox
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.max

/**
 * Reader for Xournal++ (`.xopp`, gzip-compressed XML) and classic Xournal (`.xoj`) notes.
 * Coordinates are PostScript points; a stroke's `width` attribute is either one width or a
 * nominal width followed by one width per point (pressure).
 */
object Xournal {
    private val NAMED = mapOf(
        "black" to 0xFF000000.toInt(), "blue" to 0xFF3333CC.toInt(), "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(), "gray" to 0xFF808080.toInt(), "lightblue" to 0xFF00C0FF.toInt(),
        "lightgreen" to 0xFF00FF00.toInt(), "magenta" to 0xFFFF00FF.toInt(), "orange" to 0xFFFF8000.toInt(),
        "yellow" to 0xFFFFFF00.toInt(), "white" to 0xFFFFFFFF.toInt(),
    )

    fun parseColor(s: String?, default: Int = 0xFF000000.toInt()): Int {
        if (s == null) return default
        NAMED[s.lowercase()]?.let { return it }
        val hex = s.removePrefix("#")
        return when (hex.length) {
            8 -> { val v = hex.toLong(16); (((v and 0xFF) shl 24) or (v ushr 8)).toInt() } // RRGGBBAA -> AARRGGBB
            6 -> (0xFF000000 or hex.toLong(16)).toInt()
            else -> default
        }
    }

    fun import(id: String, title: String, xml: String): Pair<Notebook, List<String>> {
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        val pages = ArrayList<Page>()
        val warnings = LinkedHashSet<String>()
        var scale = 1f
        var height = 0f
        var strokes = ArrayList<Stroke>()
        var texts = ArrayList<TextBox>()
        var paper: PaperStyle = PaperStyle.PLAIN
        var paperColor: PaperColor? = null
        var firstPaper: PaperStyle? = null
        var firstColor: PaperColor? = null

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (p.name) {
                "page" -> {
                    val w = p.getAttributeValue(null, "width")?.toFloatOrNull() ?: 595f
                    val h = p.getAttributeValue(null, "height")?.toFloatOrNull() ?: 842f
                    scale = PAGE_WIDTH / w
                    height = h * scale
                    strokes = ArrayList(); texts = ArrayList(); paper = PaperStyle.PLAIN
                }
                "background" -> {
                    when (p.getAttributeValue(null, "type")) {
                        "solid" -> {
                            paper = when (p.getAttributeValue(null, "style")) {
                                "graph", "staves" -> PaperStyle.NARROW_GRID
                                "lined" -> PaperStyle.COLLEGE_RULED
                                "ruled" -> PaperStyle.WIDE_RULED
                                "dotted" -> PaperStyle.DOTTED
                                "isodotted", "isograph" -> PaperStyle.ISOMETRIC
                                else -> PaperStyle.PLAIN
                            }
                            val c = parseColor(p.getAttributeValue(null, "color"), 0xFFFFFFFF.toInt())
                            paperColor = PaperColor.entries.minByOrNull { colorDistance(it.argb, c) }
                        }
                        "pdf" -> warnings += "PDF backgrounds in Xournal++ files aren't embedded in the file; only the ink was imported."
                        "pixmap" -> warnings += "Image backgrounds were skipped."
                    }
                }
                "stroke" -> {
                    val tool = p.getAttributeValue(null, "tool") ?: "pen"
                    val color = parseColor(p.getAttributeValue(null, "color"))
                    val widths = p.getAttributeValue(null, "width")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }.orEmpty()
                    val coords = p.nextText().trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                    val n = coords.size / 2
                    if (n == 0) { ev = p.next(); continue }
                    val nominal = widths.firstOrNull() ?: 1.4f
                    val perPoint = if (widths.size > 1) widths.drop(1) else emptyList()
                    val maxW = max(nominal, perPoint.maxOrNull() ?: 0f)
                    val pts = FloatArray(n * 3)
                    for (i in 0 until n) {
                        pts[i * 3] = coords[i * 2] * scale
                        pts[i * 3 + 1] = coords[i * 2 + 1] * scale
                        pts[i * 3 + 2] = if (perPoint.isNotEmpty()) (perPoint.getOrElse(i) { perPoint.last() } / maxW).coerceIn(0.05f, 1f) else 1f
                    }
                    val hl = tool == "highlighter"
                    strokes += Stroke(
                        kind = if (hl) InkKind.HIGHLIGHTER else InkKind.PEN,
                        color = if (hl) (color and 0x00FFFFFF) or 0x66000000 else color,
                        width = if (perPoint.isNotEmpty()) maxW * scale else nominal * scale / 0.7f,
                        pts = pts,
                        pressure = perPoint.isNotEmpty(),
                    )
                }
                "text" -> {
                    val x = (p.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f) * scale
                    val y = (p.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f) * scale
                    val size = (p.getAttributeValue(null, "size")?.toFloatOrNull() ?: 12f) * scale
                    val color = parseColor(p.getAttributeValue(null, "color"))
                    val text = p.nextText()
                    if (text.isNotBlank()) texts += TextBox(x = x, y = y, width = max(PAGE_WIDTH - x - 20f, 100f), text = text, size = size, color = color)
                }
                "image", "teximage" -> warnings += "Embedded images and LaTeX objects were skipped."
            } else if (ev == XmlPullParser.END_TAG && p.name == "page") {
                if (firstPaper == null) { firstPaper = paper; firstColor = paperColor }
                pages += Page(strokes = strokes, texts = texts, height = height, paper = if (paper != firstPaper) paper else null)
            }
            ev = p.next()
        }
        if (pages.isEmpty()) throw ImportException("No pages found in this Xournal file.")
        return Notebook(
            id = id, title = title, paper = firstPaper ?: PaperStyle.PLAIN,
            paperColor = firstColor ?: PaperColor.WHITE, pages = pages,
        ) to warnings.toList()
    }

    private fun colorDistance(a: Int, b: Int): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        return listOf(16, 8, 0).sumOf { val d = ch(a, it) - ch(b, it); d * d }
    }
}
