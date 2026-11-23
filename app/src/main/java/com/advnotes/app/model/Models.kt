package com.advnotes.app.model

import com.advnotes.app.i18n.tr

import kotlinx.serialization.Serializable
import java.util.UUID

/** Logical page size. All stroke coordinates are stored in these units (A4 aspect ratio). */
const val PAGE_WIDTH = 1000f
const val PAGE_HEIGHT = 1414f

/** 1 mm in page units (A4 is 210 mm wide). */
const val MM = PAGE_WIDTH / 210f

fun newId(): String = UUID.randomUUID().toString()

@Serializable
enum class PaperStyle(private val en: String) {
    PLAIN("Plain"),
    NARROW_GRID("Narrow grid"),
    GRID("Grid"),
    DOTTED("Dotted"),
    OXFORD("Oxford"),
    COLLEGE_RULED("College ruled"),
    WIDE_RULED("Wide ruled"),
    CORNELL("Cornell"),
    ISOMETRIC("Isometric"),
;
    /** Shown to the user, in the app language. */
    val label: String get() = tr(en)
}

@Serializable
enum class PaperColor(private val en: String, val argb: Int, val dark: Boolean = false) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    CREAM("Cream", 0xFFFBF6E9.toInt()),
    MINT("Mint", 0xFFF0F7F1.toInt()),
    PINK("Pink", 0xFFFCE8EF.toInt()),
    SLATE("Dark", 0xFF22252B.toInt(), dark = true),
;
    /** Shown to the user, in the app language. */
    val label: String get() = tr(en)
}

@Serializable
enum class InkKind { PEN, HIGHLIGHTER }

/**
 * A single ink stroke. [pts] is packed as x0, y0, p0, x1, y1, p1, ... where p is the pressure (0..1).
 * [width] is the nominal width in page units at full pressure.
 */
@Serializable
data class Stroke(
    val id: String = newId(),
    val kind: InkKind = InkKind.PEN,
    val color: Int,
    val width: Float,
    val pts: FloatArray,
    /** When false, pressure is ignored and the stroke is drawn with a constant width. */
    val pressure: Boolean = true,
) {
    val count: Int get() = pts.size / 3
    fun x(i: Int) = pts[i * 3]
    fun y(i: Int) = pts[i * 3 + 1]
    fun p(i: Int) = pts[i * 3 + 2]

    fun translated(dx: Float, dy: Float): Stroke {
        val n = pts.copyOf()
        var i = 0
        while (i < n.size) { n[i] += dx; n[i + 1] += dy; i += 3 }
        return copy(pts = n)
    }

    fun scaled(sx: Float, sy: Float, ox: Float, oy: Float): Stroke {
        val n = pts.copyOf()
        var i = 0
        while (i < n.size) { n[i] = ox + (n[i] - ox) * sx; n[i + 1] = oy + (n[i + 1] - oy) * sy; i += 3 }
        val s = (kotlin.math.abs(sx) + kotlin.math.abs(sy)) / 2f
        return copy(pts = n, width = width * s)
    }

    override fun equals(other: Any?) = other is Stroke && other.id == id && other.pts === pts && other.color == color
    override fun hashCode() = id.hashCode()
}

@Serializable
data class TextBox(
    val id: String = newId(),
    val x: Float,
    val y: Float,
    val width: Float,
    val text: String,
    val size: Float = 22f,
    val color: Int = 0xFF1A1A1A.toInt(),
) {
    fun translated(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
}

/**
 * An imported page image: [asset] is a file in the notebook's asset folder. For a PDF asset
 * [pdfPage] is the 0-based page index; for an image it is -1.
 */
@Serializable
data class PageBackground(val asset: String, val pdfPage: Int = -1)

/** A picture placed on the page; [asset] is a file in the notebook's asset folder. Coordinates in page units. */
@Serializable
data class ImageBox(
    val id: String = newId(),
    val asset: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    fun translated(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
}

@Serializable
data class Page(
    val id: String = newId(),
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextBox> = emptyList(),
    /** Per-page override; null means use the notebook's paper style. */
    val paper: PaperStyle? = null,
    /** Page height in page units; the width is always [PAGE_WIDTH]. Imported pages keep their aspect ratio. */
    val height: Float = PAGE_HEIGHT,
    val background: PageBackground? = null,
    /** Pictures placed on the page, drawn above the paper/background and below the ink. */
    val images: List<ImageBox> = emptyList(),
)

@Serializable
data class Notebook(
    val id: String = newId(),
    val title: String,
    val paper: PaperStyle = PaperStyle.NARROW_GRID,
    val paperColor: PaperColor = PaperColor.WHITE,
    val cover: Int = 0xFF2D4BD8.toInt(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pages: List<Page> = listOf(Page()),
    /** When true the notebook keeps exactly its pages: no add-page button and swiping past the end adds nothing. */
    val lockPages: Boolean = false,
    /** Set when the notebook is in the trash; it is deleted for good [TRASH_DAYS] days later. */
    val trashedAt: Long? = null,
    /** Named groups that pages can be bookmarked into (shown in the page sidebar). */
    val groups: List<PageGroup> = emptyList(),
    val bookmarks: List<PageBookmark> = emptyList(),
)

const val TRASH_DAYS = 30
const val TRASH_MILLIS = TRASH_DAYS * 24L * 60 * 60 * 1000

@Serializable
data class PageGroup(val id: String = newId(), val name: String, val color: Int)

/** Page [pageId] is bookmarked in group [groupId]. Kept on the notebook, not the page, so undo never touches it. */
@Serializable
data class PageBookmark(val pageId: String, val groupId: String)

val GROUP_COLORS = listOf(
    0xFFE64980.toInt(), 0xFF1C7ED6.toInt(), 0xFF0F8B6F.toInt(), 0xFFF08C00.toInt(),
    0xFF9C36B5.toInt(), 0xFFE03131.toInt(), 0xFF495057.toInt(),
)

/** Lightweight listing info so the library doesn't need to keep every stroke in memory. */
@Serializable
data class NotebookSummary(
    val id: String,
    val title: String,
    val paper: PaperStyle,
    val paperColor: PaperColor,
    val cover: Int,
    val pageCount: Int,
    val updatedAt: Long,
    val trashedAt: Long? = null,
)

fun Notebook.summary() = NotebookSummary(id, title, paper, paperColor, cover, pages.size, updatedAt, trashedAt)

val COVER_COLORS = listOf(
    0xFF2D4BD8.toInt(), 0xFF0F8B6F.toInt(), 0xFFD9480F.toInt(), 0xFF9C36B5.toInt(),
    0xFFE03131.toInt(), 0xFF1C7ED6.toInt(), 0xFF495057.toInt(), 0xFFF08C00.toInt(),
    0xFFE64980.toInt(),
)
