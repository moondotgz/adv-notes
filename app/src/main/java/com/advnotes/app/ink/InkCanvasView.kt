package com.advnotes.app.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.advnotes.app.data.FingerMode
import com.advnotes.app.data.PenButtonAction
import com.advnotes.app.model.InkKind
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.Page
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle
import com.advnotes.app.model.Stroke
import com.advnotes.app.model.TextBox
import com.advnotes.app.model.newId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Tool { PEN, HIGHLIGHTER, ERASER, LASSO, LINE, SHAPE, TEXT }
enum class EraserMode { STROKE, PARTIAL }

@kotlinx.serialization.Serializable
data class ToolState(
    val tool: Tool = Tool.PEN,
    val penColor: Int = 0xFF1A1A1A.toInt(),
    val penWidth: Float = 3.5f,
    val hlColor: Int = 0x66FFE14D,
    val hlWidth: Float = 22f,
    val hlStraight: Boolean = false,
    val eraserMode: EraserMode = EraserMode.STROKE,
    val eraserSize: Float = 28f,
    val shape: ShapeKind = ShapeKind.RECT,
)

data class InputPrefs(
    val fingerMode: FingerMode = FingerMode.AUTO,
    val penButton: PenButtonAction = PenButtonAction.ERASER,
    val pressureCurve: Float = 1f,
    val holdToShape: Boolean = true,
)

/** Info for the floating selection toolbar, in view pixels. */
data class SelectionInfo(val left: Float, val top: Float, val right: Float, val bottom: Float, val count: Int)

/**
 * The page surface. Handles stylus/finger input, pressure, palm rejection, zoom/pan and all
 * tools. Every finished edit is reported through [Listener.onCommit] as a new immutable [Page],
 * which lets the owner keep a simple undo stack of page snapshots.
 */
@SuppressLint("ViewConstructor")
class InkCanvasView(context: Context) : View(context) {

    interface Listener {
        fun onCommit(page: Page)
        fun onSelectionChanged(info: SelectionInfo?)
        fun onTextRequest(x: Float, y: Float, existing: TextBox?)
        fun onStylusSeen()
        /** A pan past the page edge along [swipeAxis]: +1 = next page, -1 = previous. */
        fun onSwipePage(dir: Int) {}
        /** Any touch began on this canvas. */
        fun onTouchStart() {}
    }

    /** 0 = no page swiping, 1 = horizontal, 2 = vertical. */
    var swipeAxis = 0
    private var overX = 0f
    private var overY = 0f
    /** Whether this gesture ever pinched; pinching never turns pages. */
    private var navPinched = false
    private var navStartScale = 1f
    private var swipeAnim: android.animation.ValueAnimator? = null
    /** Label for the page a swipe would go to (+1 forward, -1 back), or null when there is none. */
    var swipeLabel: (Int) -> String? = { null }
    private val swipeThreshold get() = 170f * density
    private val labelPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true } }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var listener: Listener? = null
    val renderer = StrokeRenderer()

    var page: Page = Page()
        set(value) {
            if (value === field) return
            field = value
            // A page coming from outside (undo/redo, page switch) invalidates the selection box.
            if (!committing) clearSelection()
            pendingSelectImage?.let { id ->
                if (value.images.any { it.id == id }) { pendingSelectImage = null; post { setSelection(emptySet(), emptySet(), setOf(id)) } }
            }
            layerDirty = true
            invalidate()
        }
    var paperStyle: PaperStyle = PaperStyle.PLAIN
        set(value) { if (field != value) { field = value; layerDirty = true; invalidate() } }
    var paperColor: PaperColor = PaperColor.WHITE
        set(value) { if (field != value) { field = value; layerDirty = true; invalidate() } }
    var tool: ToolState = ToolState()
        set(value) {
            if (field.tool != value.tool && value.tool != Tool.LASSO && selection != null) clearSelection()
            field = value; invalidate()
        }
    var prefs: InputPrefs = InputPrefs()
        set(value) {
            if (field.pressureCurve != value.pressureCurve) {
                renderer.pressureCurve = value.pressureCurve; renderer.clearCache(); layerDirty = true; invalidate()
            }
            field = value
        }
    var stylusSeen = false

    /** Owning notebook, used to resolve imported page backgrounds. */
    var notebookId: String = ""

    /** Placed pictures, loaded in the background; the page redraws when one is ready. */
    private val liveImages: (com.advnotes.app.model.ImageBox) -> Bitmap? = { im ->
        Backgrounds.get(notebookId, com.advnotes.app.model.PageBackground(im.asset)) { layerDirty = true; invalidate() }
    }
    private val nowImages: (com.advnotes.app.model.ImageBox) -> Bitmap? = { im ->
        Backgrounds.renderNow(notebookId, com.advnotes.app.model.PageBackground(im.asset))
    }

    private fun backgroundBitmap(): Bitmap? {
        val bg = page.background ?: return null
        return Backgrounds.get(notebookId, bg) { layerDirty = true; invalidate() }
    }

    // ---- viewport: view = page * scale + (tx, ty) ----
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var viewportInit = false

    // ---- cached layer of committed content ----
    private var layer: Bitmap? = null
    private var layerDirty = true
    private var directDraw = false

    // ---- paints ----
    /** Background around the page; set from the app theme. */
    var themeDeskColor: Int? = null
        set(value) { if (field != value) { field = value; layerDirty = true; invalidate() } }
    private val deskColor get() = themeDeskColor ?: if (paperColor.dark) 0xFF0E0F12.toInt() else 0xFFE4E6EB.toInt()

    /** Space taken by floating UI (the toolbar pill) on each side, in px; the page starts clear of it. */
    var insetLeft = 0f
    var insetTop = 0f
    var insetRight = 0f
    var insetBottom = 0f
    fun setInsets(l: Float, t: Float, r: Float, b: Float) {
        if (l == insetLeft && t == insetTop && r == insetRight && b == insetBottom) return
        insetLeft = l; insetTop = t; insetRight = r; insetBottom = b
        if (width > 0) fitPage()
    }
    private val shadowPaint = Paint().apply { color = 0x22000000 }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = 0xFF3B6CFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val lassoFill = Paint().apply { color = 0x143B6CFF }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF3B6CFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B6CFF.toInt() }
    private val handleInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = 0x99000000.toInt()
    }
    private val density = resources.displayMetrics.density

    // ---- gesture state ----
    private enum class Mode { IDLE, DRAW, SNAPPED, ERASE, LASSO, SHAPE, TEXT, MOVE_SEL, SCALE_SEL, NAVIGATE }

    private var mode = Mode.IDLE
    private var activePointer = -1
    private var activeIsFinger = false
    private var effTool = Tool.PEN

    // drawing
    private val pts = FloatArrayBuilder()
    private var drawKind = InkKind.PEN
    private var drawColor = 0
    private var drawWidth = 0f
    private var drawPressure = true
    private var smX = 0f; private var smY = 0f; private var smP = 0f
    private var snapped: List<FloatArray> = emptyList()
    private var snappedKind: ShapeKind? = null
    private val handler = Handler(Looper.getMainLooper())
    private var holdX = 0f; private var holdY = 0f
    private val holdRunnable = Runnable { onHold() }

    // shape tool / lasso / text
    private var startX = 0f; private var startY = 0f
    private var curX = 0f; private var curY = 0f
    private val lassoPts = ArrayList<Float>()

    // eraser
    private var eraseStrokes: MutableList<Stroke>? = null
    private var eraseTexts: MutableList<TextBox>? = null
    private var eraseChanged = false
    private var lastEX = 0f; private var lastEY = 0f

    // selection
    private class Selection(val strokeIds: Set<String>, val textIds: Set<String>, val bounds: RectF, val imageIds: Set<String> = emptySet()) {
        val ids get() = strokeIds + textIds + imageIds
    }
    /** Image to select as soon as a page containing it arrives (right after inserting one). */
    var pendingSelectImage: String? = null
    private var selection: Selection? = null
    private var selDx = 0f; private var selDy = 0f; private var selScale = 1f

    // navigation
    private var navCx = 0f; private var navCy = 0f; private var navSpan = 0f
    private var navDownTime = 0L; private var navDownX = 0f; private var navDownY = 0f
    private var navMoved = false
    private var lastTapTime = 0L

    // hover
    private var hoverX = -1f; private var hoverY = -1f

    // ------------------------------------------------------------------------------------
    // Viewport
    // ------------------------------------------------------------------------------------

    fun fitPage() {
        if (width == 0) return
        val margin = 16f * density
        val w = width - insetLeft - insetRight
        val h = height - insetTop - insetBottom
        scale = (w - 2 * margin) / PAGE_WIDTH
        // On wide screens don't let the page get absurdly large: fit height if width-fit is >1.6x
        val hScale = (h - 2 * margin) / page.height
        if (scale > hScale * 1.6f) scale = hScale * 1.6f
        tx = insetLeft + (w - PAGE_WIDTH * scale) / 2f
        ty = insetTop + margin
        afterViewportChange()
    }

    fun scrollToTop() {
        ty = insetTop + 16f * density
        clampViewport(); afterViewportChange()
    }

    private fun clampViewport() {
        val pw = PAGE_WIDTH * scale; val ph = page.height * scale
        val m = 48f * density
        val l = insetLeft; val t = insetTop
        val w = width - insetLeft - insetRight; val h = height - insetTop - insetBottom
        tx = if (pw + 2 * m <= w) l + (w - pw) / 2f else tx.coerceIn(l + w - pw - m, l + m)
        ty = if (ph + 2 * m <= h) ty.coerceIn(t + m, t + max(m, h - ph - m)) else ty.coerceIn(t + h - ph - m, t + m)
    }

    private fun afterViewportChange() {
        layerDirty = true
        notifySelection()
        invalidate()
    }

    private fun toPageX(vx: Float) = (vx - tx) / scale
    private fun toPageY(vy: Float) = (vy - ty) / scale
    private fun toViewX(px: Float) = px * scale + tx
    private fun toViewY(py: Float) = py * scale + ty

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layer?.recycle(); layer = null
        if (w > 0 && h > 0) {
            layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (!viewportInit) { viewportInit = true; fitPage() }
            else if (w != oldw) {
                // width changed (panel opened/closed, rotation): refit, keeping the same spot of the document on screen
                val docY = (insetTop - ty) / scale
                fitPage()
                ty = insetTop - docY * scale
                clampViewport(); afterViewportChange()
            } else { clampViewport(); afterViewportChange() }
        }
    }

    // ------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------

    private fun excluded(): Set<String> = selection?.ids ?: emptySet()

    private fun drawScene(c: Canvas, pageToDraw: Page) {
        c.drawColor(deskColor)
        c.save()
        c.translate(tx, ty)
        c.scale(scale, scale)
        val sh = 3f / scale
        c.drawRect(sh, sh * 2, PAGE_WIDTH + sh, page.height + sh * 2, shadowPaint)
        c.clipRect(0f, 0f, PAGE_WIDTH, page.height)
        renderer.drawPage(c, pageToDraw, paperStyle, paperColor, excluded(), background = backgroundBitmap(), images = liveImages)
        c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val rubber = overX != 0f || overY != 0f
        if (rubber) { canvas.save(); canvas.translate(rubberOf(overX), rubberOf(overY)) }
        drawContent(canvas)
        if (rubber) { canvas.restore(); if (mode == Mode.NAVIGATE) drawSwipeHint(canvas) }
    }

    /** Resisting displacement: follows the finger at first, then stiffens. */
    private fun rubberOf(o: Float): Float {
        if (o == 0f) return 0f
        val lim = swipeThreshold * 0.9f
        val a = abs(o)
        return Math.signum(o) * lim * (1f - 1f / (a * 0.55f / lim + 1f))
    }

    private fun drawSwipeHint(c: Canvas) {
        val over = if (swipeAxis == 1) overX else overY
        if (abs(over) < 24f * density) return
        val dir = if (over < 0) 1 else -1
        val label = swipeLabel(dir) ?: return
        val armed = abs(over) >= swipeThreshold
        val progress = (abs(over) / swipeThreshold).coerceIn(0f, 1f)
        val text = if (armed) "Release: $label" else label
        val shift = rubberOf(over)
        val gap = abs(shift)
        val cx: Float; val cy: Float
        if (swipeAxis == 1) { cy = height / 2f; cx = if (dir > 0) width - gap / 2f else gap / 2f }
        else { cx = width / 2f; cy = if (dir > 0) height - gap / 2f else gap / 2f }
        val tw = labelPaint.measureText(text)
        val ph = 34f * density
        val pw = tw + 28f * density
        val dark = paperColor.dark
        val alpha = (progress * 255).toInt()
        pillPaint.color = if (armed) 0xFF3B82F6.toInt() else if (dark) 0xFF2A2D33.toInt() else 0xFFFFFFFF.toInt()
        pillPaint.alpha = alpha
        labelPaint.color = if (armed) 0xFFFFFFFF.toInt() else if (dark) 0xFFE6E6E6.toInt() else 0xFF33363C.toInt()
        labelPaint.alpha = alpha
        c.save()
        if (swipeAxis == 1) c.rotate(if (dir > 0) -90f else 90f, cx, cy)
        val rr = RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
        c.drawRoundRect(rr, ph / 2, ph / 2, pillPaint)
        c.drawText(text, cx, cy - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
        c.restore()
    }

    /** Animates the rubber-band offset from its current value to zero. */
    private fun settleOver(fromX: Float, fromY: Float, duration: Long = 260) {
        swipeAnim?.cancel()
        if (fromX == 0f && fromY == 0f) { overX = 0f; overY = 0f; invalidate(); return }
        swipeAnim = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            this.duration = duration
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { overX = fromX * (it.animatedValue as Float); overY = fromY * (it.animatedValue as Float); invalidate() }
            start()
        }
    }

    private fun drawContent(canvas: Canvas) {
        val erasing = mode == Mode.ERASE && eraseStrokes != null
        val shown = if (erasing) page.copy(strokes = eraseStrokes!!, texts = eraseTexts!!) else page
        val l = layer
        if (directDraw || erasing || l == null) {
            drawScene(canvas, shown)
        } else {
            if (layerDirty) {
                drawScene(Canvas(l), page)
                layerDirty = false
            }
            canvas.drawBitmap(l, 0f, 0f, null)
        }

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, PAGE_WIDTH, page.height)

        // selected items, with the in-progress move/scale applied
        selection?.let { sel ->
            canvas.save()
            canvas.translate(selDx, selDy)
            canvas.scale(selScale, selScale, sel.bounds.left, sel.bounds.top)
            for (im in page.images) if (im.id in sel.imageIds) renderer.drawImage(canvas, im, liveImages(im))
            for (s in page.strokes) if (s.kind == InkKind.HIGHLIGHTER && s.id in sel.strokeIds) renderer.draw(canvas, s)
            for (s in page.strokes) if (s.kind == InkKind.PEN && s.id in sel.strokeIds) renderer.draw(canvas, s)
            for (t in page.texts) if (t.id in sel.textIds) renderer.drawText(canvas, t)
            canvas.restore()
        }

        // live stroke
        when (mode) {
            Mode.DRAW -> if (pts.points > 0) renderer.draw(canvas, liveStroke(), live = true)
            Mode.SNAPPED -> snapped.forEach { renderer.draw(canvas, shapeStroke(it, drawKind, drawColor, drawWidth), live = true) }
            Mode.SHAPE -> shapeNow().forEach {
                renderer.draw(canvas, shapeStroke(it, InkKind.PEN, tool.penColor, tool.penWidth), live = true)
            }
            else -> Unit
        }
        canvas.restore()

        // overlays in view space
        if (mode == Mode.LASSO && lassoPts.size >= 4) {
            val p = Path()
            p.moveTo(toViewX(lassoPts[0]), toViewY(lassoPts[1]))
            var i = 2
            while (i < lassoPts.size) { p.lineTo(toViewX(lassoPts[i]), toViewY(lassoPts[i + 1])); i += 2 }
            canvas.drawPath(p, lassoPaint)
            p.close()
            canvas.drawPath(p, lassoFill)
        }
        selection?.let { sel ->
            val r = selViewRect(sel)
            canvas.drawRect(r, selPaint)
            val hr = 9f * density
            canvas.drawCircle(r.right, r.bottom, hr, handlePaint)
            canvas.drawCircle(r.right, r.bottom, hr * 0.5f, handleInner)
        }
        if (mode == Mode.ERASE) {
            canvas.drawCircle(toViewX(lastEX), toViewY(lastEY), tool.eraserSize / 2f, cursorPaint)
        } else if (hoverX >= 0 && mode == Mode.IDLE) {
            val r = when (tool.tool) {
                Tool.ERASER -> tool.eraserSize / 2f
                Tool.HIGHLIGHTER -> tool.hlWidth * scale / 2f
                else -> max(2f, tool.penWidth * scale / 2f)
            }
            canvas.drawCircle(hoverX, hoverY, r, cursorPaint)
        }
    }

    private fun selViewRect(sel: Selection): RectF {
        val b = sel.bounds
        val l = b.left + selDx; val t = b.top + selDy
        val r = b.left + b.width() * selScale + selDx; val bt = b.top + b.height() * selScale + selDy
        return RectF(toViewX(l), toViewY(t), toViewX(r), toViewY(bt))
    }

    private fun liveStroke(): Stroke {
        if (drawKind == InkKind.HIGHLIGHTER && tool.hlStraight && pts.points >= 2) {
            val a = floatArrayOf(pts[0], pts[1], pts[pts.size - 3], pts[pts.size - 2])
            return shapeStroke(a, drawKind, drawColor, drawWidth)
        }
        return Stroke(id = "live", kind = drawKind, color = drawColor, width = drawWidth, pts = pts.toArray(), pressure = drawPressure)
    }

    /** The shape being dragged out; the Line tool snaps to 0°/45°/90° when within a few degrees. */
    private fun shapeNow(): List<FloatArray> {
        if (tool.tool != Tool.LINE) return ShapeFactory.make(tool.shape, startX, startY, curX, curY)
        var ex = curX; var ey = curY
        val dx = ex - startX; val dy = ey - startY
        val len = hypot(dx, dy)
        if (len > 0f) {
            val ang = Math.toDegrees(atan2(dy, dx).toDouble())
            val snap = Math.round(ang / 45.0) * 45.0
            if (abs(ang - snap) < 4.0) {
                val rad = Math.toRadians(snap)
                ex = startX + (len * cos(rad)).toFloat(); ey = startY + (len * sin(rad)).toFloat()
            }
        }
        return ShapeFactory.make(ShapeKind.LINE, startX, startY, ex, ey)
    }

    private fun shapeStroke(xy: FloatArray, kind: InkKind, color: Int, width: Float): Stroke {
        val dense = if (xy.size <= 4) ShapeFactory.poly(xy) else xy
        val out = FloatArray(dense.size / 2 * 3)
        for (i in 0 until dense.size / 2) { out[i * 3] = dense[i * 2]; out[i * 3 + 1] = dense[i * 2 + 1]; out[i * 3 + 2] = 1f }
        return Stroke(id = newId(), kind = kind, color = color, width = width, pts = out, pressure = false)
    }

    // ------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------

    private fun isStylus(tt: Int) = tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER

    private fun fingersNavigate(): Boolean = when (prefs.fingerMode) {
        FingerMode.NAVIGATE -> true
        FingerMode.DRAW -> false
        FingerMode.AUTO -> stylusSeen
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!isStylus(e.getToolType(0))) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { hoverX = e.x; hoverY = e.y; markStylus() }
            else -> { hoverX = -1f; hoverY = -1f }
        }
        invalidate()
        return true
    }

    private fun markStylus() {
        if (!stylusSeen) { stylusSeen = true; listener?.onStylusSeen() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onTouchStart()
                hoverX = -1f
                val tt = e.getToolType(0)
                if (isStylus(tt)) {
                    markStylus()
                    beginInput(e, 0, finger = false)
                } else if (fingersNavigate()) {
                    beginNavigate(e, -1)
                } else {
                    beginInput(e, 0, finger = true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                when {
                    mode == Mode.NAVIGATE -> resetNavAnchor(e, -1)
                    activeIsFinger && mode != Mode.IDLE -> {
                        // A second finger means zoom/pan: abandon the finger stroke.
                        cancelInput()
                        beginNavigate(e, -1)
                    }
                    // stylus is down: ignore extra touches (palm rejection)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NAVIGATE) navigate(e) else inputMove(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.NAVIGATE) resetNavAnchor(e, e.actionIndex)
                else if (e.getPointerId(e.actionIndex) == activePointer) endInput(e)
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.NAVIGATE) endNavigate(e) else if (mode != Mode.IDLE) endInput(e)
                mode = Mode.IDLE
                directDraw = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                swipeAnim?.cancel(); overX = 0f; overY = 0f
                cancelInput()
                mode = Mode.IDLE
                directDraw = false
                invalidate()
            }
        }
        return true
    }

    // ---- navigation ----

    private fun centroid(e: MotionEvent, skip: Int): Triple<Float, Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        if (n == 0) return Triple(0f, 0f, 0f)
        val cx = sx / n; val cy = sy / n
        var span = 0f
        for (i in 0 until e.pointerCount) if (i != skip) span += hypot(e.getX(i) - cx, e.getY(i) - cy)
        return Triple(cx, cy, if (n > 1) span / n else 0f)
    }

    private fun beginNavigate(e: MotionEvent, skip: Int) {
        mode = Mode.NAVIGATE
        directDraw = true
        navDownTime = e.eventTime; navDownX = e.x; navDownY = e.y; navMoved = e.pointerCount > 1
        swipeAnim?.cancel(); overX = 0f; overY = 0f
        navPinched = e.pointerCount > 1; navStartScale = scale
        resetNavAnchor(e, skip)
    }

    private fun resetNavAnchor(e: MotionEvent, skip: Int) {
        val (cx, cy, span) = centroid(e, skip)
        navCx = cx; navCy = cy; navSpan = span
        if (e.pointerCount - (if (skip >= 0) 1 else 0) > 1) { navMoved = true; navPinched = true; overX = 0f; overY = 0f }
    }

    private fun navigate(e: MotionEvent) {
        val (cx, cy, span) = centroid(e, -1)
        if (hypot(e.x - navDownX, e.y - navDownY) > 12 * density) navMoved = true
        var newScale = scale
        if (span > 10f && navSpan > 10f) newScale = (scale * span / navSpan).coerceIn(0.25f, 8f)
        val px = (navCx - tx) / scale; val py = (navCy - ty) / scale
        scale = newScale
        tx = cx - px * scale + (if (swipeAxis == 1) overX else 0f)
        ty = cy - py * scale + (if (swipeAxis == 2) overY else 0f)
        navCx = cx; navCy = cy; navSpan = span
        val rawTx = tx; val rawTy = ty
        clampViewport()
        // Only a plain one-finger drag at the page edge builds up a page swipe.
        val canSwipe = !navPinched && e.pointerCount == 1
        overX = if (canSwipe && swipeAxis == 1) rawTx - tx else 0f
        overY = if (canSwipe && swipeAxis == 2) rawTy - ty else 0f
        notifySelection()
        invalidate()
    }

    private fun endNavigate(e: MotionEvent) {
        val over = if (swipeAxis == 1) overX else overY
        val dir = if (over < 0) 1 else -1
        val fire = swipeAxis != 0 && !navPinched && abs(over) >= swipeThreshold && swipeLabel(dir) != null
        if (fire) {
            overX = 0f; overY = 0f
            listener?.onSwipePage(dir)
            // The new page slides in from the side the finger was pulling from.
            val inset = (if (swipeAxis == 1) width else height) * 0.35f / 0.55f
            settleOver(if (swipeAxis == 1) -Math.signum(over) * inset * 0.5f else 0f,
                if (swipeAxis == 2) -Math.signum(over) * inset * 0.5f else 0f, 280)
        } else settleOver(overX, overY)
        if (!navMoved && e.eventTime - navDownTime < 250) {
            if (e.eventTime - lastTapTime < 350) { fitPage(); lastTapTime = 0 } else lastTapTime = e.eventTime
        }
        layerDirty = true
    }

    // ---- tools ----

    private fun beginInput(e: MotionEvent, index: Int, finger: Boolean) {
        activePointer = e.getPointerId(index)
        activeIsFinger = finger
        val vx = e.getX(index); val vy = e.getY(index)
        val x = toPageX(vx); val y = toPageY(vy)
        startX = x; startY = y; curX = x; curY = y

        val tt = e.getToolType(index)
        val btn = e.buttonState
        val buttonHeld = !finger && (btn and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
        effTool = when {
            tt == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            buttonHeld && prefs.penButton == PenButtonAction.ERASER -> Tool.ERASER
            buttonHeld && prefs.penButton == PenButtonAction.LASSO -> Tool.LASSO
            else -> tool.tool
        }

        // Interact with an existing selection first.
        selection?.let { sel ->
            val r = selViewRect(sel)
            val hr = 22f * density
            if (hypot(vx - r.right, vy - r.bottom) < hr) { mode = Mode.SCALE_SEL; return }
            if (r.contains(vx, vy)) { mode = Mode.MOVE_SEL; return }
            clearSelection()
        }

        when (effTool) {
            Tool.PEN, Tool.HIGHLIGHTER -> {
                mode = Mode.DRAW
                drawKind = if (effTool == Tool.PEN) InkKind.PEN else InkKind.HIGHLIGHTER
                drawColor = if (effTool == Tool.PEN) tool.penColor else tool.hlColor
                drawWidth = if (effTool == Tool.PEN) tool.penWidth else tool.hlWidth
                drawPressure = !finger && effTool == Tool.PEN && tt == MotionEvent.TOOL_TYPE_STYLUS
                pts.clear()
                smX = x; smY = y; smP = pressureOf(e, index, finger)
                pts.add(x, y, smP)
                holdX = vx; holdY = vy
                handler.removeCallbacks(holdRunnable)
                handler.postDelayed(holdRunnable, HOLD_MS)
            }
            Tool.ERASER -> {
                mode = Mode.ERASE
                eraseStrokes = page.strokes.toMutableList()
                eraseTexts = page.texts.toMutableList()
                eraseChanged = false
                lastEX = x; lastEY = y
                eraseAt(x, y)
            }
            Tool.LASSO -> {
                mode = Mode.LASSO
                lassoPts.clear(); lassoPts += x; lassoPts += y
            }
            Tool.SHAPE, Tool.LINE -> mode = Mode.SHAPE
            Tool.TEXT -> mode = Mode.TEXT
        }
        invalidate()
    }

    private fun pressureOf(e: MotionEvent, index: Int, finger: Boolean, h: Int = -1): Float {
        if (finger) return 0.6f
        val p = if (h >= 0) e.getHistoricalPressure(index, h) else e.getPressure(index)
        return p.coerceIn(0.02f, 1f)
    }

    private fun inputMove(e: MotionEvent) {
        val index = e.findPointerIndex(activePointer)
        if (index < 0) return
        val vx = e.getX(index); val vy = e.getY(index)
        val x = toPageX(vx); val y = toPageY(vy)
        when (mode) {
            Mode.DRAW -> {
                for (h in 0 until e.historySize) {
                    addPoint(toPageX(e.getHistoricalX(index, h)), toPageY(e.getHistoricalY(index, h)), pressureOf(e, index, activeIsFinger, h))
                }
                addPoint(x, y, pressureOf(e, index, activeIsFinger))
                if (hypot(vx - holdX, vy - holdY) > HOLD_SLOP * density) {
                    holdX = vx; holdY = vy
                    handler.removeCallbacks(holdRunnable)
                    handler.postDelayed(holdRunnable, HOLD_MS)
                }
            }
            Mode.SNAPPED -> {
                if (snappedKind == ShapeKind.LINE) {
                    var ex = x; var ey = y
                    val ang = atan2(ey - startY, ex - startX)
                    val snap = (Math.round(ang / (Math.PI / 4)) * (Math.PI / 4)).toFloat()
                    if (abs(ang - snap) < 0.06f) {
                        val d = hypot(ex - startX, ey - startY)
                        ex = startX + cos(snap) * d; ey = startY + sin(snap) * d
                    }
                    snapped = listOf(floatArrayOf(startX, startY, ex, ey))
                }
            }
            Mode.ERASE -> {
                // sample along the path so fast swipes don't skip strokes
                val r = tool.eraserSize / 2f / scale
                val d = hypot(x - lastEX, y - lastEY)
                val steps = max(1, (d / (r * 0.6f)).toInt())
                for (s in 1..steps) eraseAt(lastEX + (x - lastEX) * s / steps, lastEY + (y - lastEY) * s / steps)
                lastEX = x; lastEY = y
            }
            Mode.LASSO -> {
                if (hypot(x - lassoPts[lassoPts.size - 2], y - lassoPts[lassoPts.size - 1]) > 2f / scale) {
                    lassoPts += x; lassoPts += y
                }
            }
            Mode.SHAPE, Mode.TEXT -> { curX = x; curY = y }
            Mode.MOVE_SEL -> { selDx = x - startX; selDy = y - startY; notifySelection() }
            Mode.SCALE_SEL -> selection?.let { sel ->
                val b = sel.bounds
                val w0 = max(b.width(), 1f); val h0 = max(b.height(), 1f)
                val sx = (b.width() + (x - startX)) / w0
                val sy = (b.height() + (y - startY)) / h0
                selScale = max(sx, sy).coerceIn(0.1f, 10f)
                notifySelection()
            }
            else -> Unit
        }
        invalidate()
    }

    private fun addPoint(x: Float, y: Float, p: Float) {
        // light low-pass filter for smoother lines, stronger on pressure
        smX += (x - smX) * 0.6f
        smY += (y - smY) * 0.6f
        smP += (p - smP) * 0.35f
        val n = pts.size
        if (n >= 3 && hypot(smX - pts[n - 3], smY - pts[n - 2]) < 0.5f / scale.coerceAtLeast(0.5f)) {
            pts.set(n - 1, max(pts[n - 1], smP)); return
        }
        pts.add(smX, smY, smP)
    }

    private fun onHold() {
        if (mode != Mode.DRAW || pts.points < 4) return
        val xy = FloatArray(pts.points * 2) { i -> pts[(i / 2) * 3 + (i % 2)] }
        if (drawKind == InkKind.HIGHLIGHTER) {
            startX = xy[0]; startY = xy[1]
            snapped = listOf(floatArrayOf(startX, startY, xy[xy.size - 2], xy[xy.size - 1]))
            snappedKind = ShapeKind.LINE
            mode = Mode.SNAPPED
        } else {
            if (!prefs.holdToShape) return
            val res = ShapeRecognizer.recognize(xy) ?: return
            snapped = res.strokes
            snappedKind = res.kind
            if (res.kind == ShapeKind.LINE) {
                val s = res.strokes[0]
                startX = s[0]; startY = s[1]
                snapped = listOf(floatArrayOf(s[0], s[1], s[s.size - 2], s[s.size - 1]))
            }
            mode = Mode.SNAPPED
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun eraseAt(x: Float, y: Float) {
        val strokes = eraseStrokes ?: return
        val texts = eraseTexts ?: return
        val r = tool.eraserSize / 2f / scale
        if (tool.eraserMode == EraserMode.STROKE) {
            if (strokes.removeAll { Geometry.strokeHitsCircle(it, x, y, r) }) eraseChanged = true
            if (texts.removeAll { t ->
                    x >= t.x && x <= t.x + t.width && y >= t.y && y <= t.y + renderer.textHeight(t)
                }) eraseChanged = true
        } else {
            var i = 0
            while (i < strokes.size) {
                val s = strokes[i]
                if (Geometry.strokeHitsCircle(s, x, y, r)) {
                    val pieces = Geometry.eraseFromStroke(s, x, y, r)
                    if (pieces != null) {
                        strokes.removeAt(i)
                        strokes.addAll(i, pieces)
                        i += pieces.size
                        eraseChanged = true
                        continue
                    }
                }
                i++
            }
        }
    }

    private fun endInput(e: MotionEvent) {
        handler.removeCallbacks(holdRunnable)
        when (mode) {
            Mode.DRAW -> {
                if (pts.points > 0) {
                    val s = liveStroke().let {
                        if (it.id == "live") it.copy(id = newId()) else it
                    }
                    commit(page.copy(strokes = page.strokes + s))
                }
                pts.clear()
            }
            Mode.SNAPPED -> {
                val newStrokes = snapped.map { shapeStroke(it, drawKind, drawColor, drawWidth) }
                commit(page.copy(strokes = page.strokes + newStrokes))
                snapped = emptyList(); snappedKind = null
            }
            Mode.ERASE -> {
                if (eraseChanged) commit(page.copy(strokes = eraseStrokes!!.toList(), texts = eraseTexts!!.toList()))
                eraseStrokes = null; eraseTexts = null
            }
            Mode.LASSO -> {
                finishLasso()
                lassoPts.clear()
            }
            Mode.SHAPE -> {
                if (hypot(curX - startX, curY - startY) > 3f) {
                    val s = shapeNow()
                        .map { shapeStroke(it, InkKind.PEN, tool.penColor, tool.penWidth) }
                    commit(page.copy(strokes = page.strokes + s))
                }
            }
            Mode.TEXT -> {
                if (hypot(curX - startX, curY - startY) * scale < 12 * density) {
                    val hit = page.texts.lastOrNull { t ->
                        startX >= t.x && startX <= t.x + t.width && startY >= t.y && startY <= t.y + renderer.textHeight(t)
                    }
                    listener?.onTextRequest(startX, startY, hit)
                }
            }
            Mode.MOVE_SEL, Mode.SCALE_SEL -> applySelectionTransform()
            else -> Unit
        }
        mode = Mode.IDLE
        activePointer = -1
        invalidate()
    }

    private fun cancelInput() {
        handler.removeCallbacks(holdRunnable)
        pts.clear(); snapped = emptyList(); lassoPts.clear()
        eraseStrokes = null; eraseTexts = null
        selDx = 0f; selDy = 0f; selScale = 1f
        mode = Mode.IDLE
        activePointer = -1
    }

    private var committing = false

    private fun commit(p: Page) {
        committing = true
        page = p
        committing = false
        listener?.onCommit(p)
    }

    // ------------------------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------------------------

    private fun finishLasso() {
        if (lassoPts.size < 6) return
        val poly = lassoPts.toFloatArray()
        val strokes = page.strokes.filter { Geometry.strokeInPolygon(it, poly) }
        val texts = page.texts.filter { t ->
            Geometry.pointInPolygon(t.x + t.width / 2, t.y + renderer.textHeight(t) / 2, poly)
        }
        val images = page.images.filter { im -> Geometry.pointInPolygon(im.x + im.w / 2, im.y + im.h / 2, poly) }
        if (strokes.isEmpty() && texts.isEmpty() && images.isEmpty()) {
            // On an imported page, circling printed content selects that region (for Solve / To text).
            if (page.background != null) {
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                for (i in poly.indices step 2) {
                    l = min(l, poly[i]); r = max(r, poly[i]); t = min(t, poly[i + 1]); b = max(b, poly[i + 1])
                }
                if (r - l > 10f && b - t > 10f) {
                    selection = Selection(emptySet(), emptySet(), RectF(l, t, r, b))
                    selDx = 0f; selDy = 0f; selScale = 1f
                    notifySelection(); invalidate()
                }
            }
            return
        }
        setSelection(strokes.map { it.id }.toSet(), texts.map { it.id }.toSet(), images.map { it.id }.toSet())
    }

    private fun setSelection(strokeIds: Set<String>, textIds: Set<String>, imageIds: Set<String> = emptySet()) {
        val b = RectF()
        var first = true
        val tmp = RectF()
        for (s in page.strokes) if (s.id in strokeIds) {
            Geometry.bounds(s, tmp); if (first) { b.set(tmp); first = false } else b.union(tmp)
        }
        for (t in page.texts) if (t.id in textIds) {
            tmp.set(t.x, t.y, t.x + t.width, t.y + renderer.textHeight(t))
            if (first) { b.set(tmp); first = false } else b.union(tmp)
        }
        for (im in page.images) if (im.id in imageIds) {
            tmp.set(im.x, im.y, im.x + im.w, im.y + im.h)
            if (first) { b.set(tmp); first = false } else b.union(tmp)
        }
        if (first) { clearSelection(); return }
        b.inset(-6f, -6f)
        selection = Selection(strokeIds, textIds, b, imageIds)
        selDx = 0f; selDy = 0f; selScale = 1f
        layerDirty = true
        notifySelection()
        invalidate()
    }

    private fun applySelectionTransform() {
        val sel = selection ?: return
        if (selDx == 0f && selDy == 0f && selScale == 1f) return
        if (sel.ids.isEmpty()) { selDx = 0f; selDy = 0f; selScale = 1f; return } // region selections don't move
        val ox = sel.bounds.left; val oy = sel.bounds.top
        val sc = selScale; val dx = selDx; val dy = selDy
        val strokes = page.strokes.map { s ->
            if (s.id in sel.strokeIds) (if (sc != 1f) s.scaled(sc, sc, ox, oy) else s).translated(dx, dy) else s
        }
        val texts = page.texts.map { t ->
            if (t.id in sel.textIds) {
                val nx = ox + (t.x - ox) * sc + dx
                val ny = oy + (t.y - oy) * sc + dy
                t.copy(x = nx, y = ny, width = t.width * sc, size = t.size * sc)
            } else t
        }
        val images = page.images.map { im ->
            if (im.id in sel.imageIds) im.copy(x = ox + (im.x - ox) * sc + dx, y = oy + (im.y - oy) * sc + dy, w = im.w * sc, h = im.h * sc) else im
        }
        selDx = 0f; selDy = 0f; selScale = 1f
        commit(page.copy(strokes = strokes, texts = texts, images = images))
        setSelection(sel.strokeIds, sel.textIds, sel.imageIds)
    }

    fun hasSelection() = selection != null

    fun clearSelection() {
        if (selection == null) return
        selection = null
        selDx = 0f; selDy = 0f; selScale = 1f
        layerDirty = true
        listener?.onSelectionChanged(null)
        invalidate()
    }

    fun deleteSelection() {
        val sel = selection ?: return
        selection = null
        commit(page.copy(strokes = page.strokes.filter { it.id !in sel.strokeIds }, texts = page.texts.filter { it.id !in sel.textIds },
            images = page.images.filter { it.id !in sel.imageIds }))
        listener?.onSelectionChanged(null)
    }

    fun duplicateSelection() {
        val sel = selection ?: return
        val off = 24f
        val ns = page.strokes.filter { it.id in sel.strokeIds }.map { it.translated(off, off).copy(id = newId()) }
        val nt = page.texts.filter { it.id in sel.textIds }.map { it.translated(off, off).copy(id = newId()) }
        val ni = page.images.filter { it.id in sel.imageIds }.map { it.translated(off, off).copy(id = newId()) }
        commit(page.copy(strokes = page.strokes + ns, texts = page.texts + nt, images = page.images + ni))
        setSelection(ns.map { it.id }.toSet(), nt.map { it.id }.toSet(), ni.map { it.id }.toSet())
    }

    fun recolorSelection(color: Int) {
        val sel = selection ?: return
        val strokes = page.strokes.map { s ->
            if (s.id in sel.strokeIds) {
                if (s.kind == InkKind.HIGHLIGHTER) s.copy(color = (color and 0x00FFFFFF) or (s.color and 0xFF000000.toInt()))
                else s.copy(color = color)
            } else s
        }
        val texts = page.texts.map { if (it.id in sel.textIds) it.copy(color = color) else it }
        commit(page.copy(strokes = strokes, texts = texts))
        layerDirty = true
        invalidate()
    }

    fun selectionBoundsPage(): RectF? = selection?.bounds?.let { RectF(it) }

    /** Renders just the selected content onto a plain background, for the AI solver. */
    fun selectionBitmap(maxSide: Int = 1280): Bitmap? {
        val sel = selection ?: return null
        val b = RectF(sel.bounds).apply { inset(-16f, -16f) }
        val s = min(maxSide / max(b.width(), b.height()), 4f)
        val w = (b.width() * s).roundToInt().coerceAtLeast(1)
        val h = (b.height() * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(paperColor.argb)
        c.scale(s, s)
        c.translate(-b.left, -b.top)
        // A region selection (no ink) sends everything in the area; otherwise only the selected ink,
        // on top of the imported page background if there is one.
        val excluded = if (sel.ids.isEmpty()) emptySet() else
            page.strokes.filter { it.id !in sel.strokeIds }.map { it.id }.toSet() + page.texts.filter { it.id !in sel.textIds }.map { it.id } +
                page.images.filter { it.id !in sel.imageIds }.map { it.id }
        val bg = page.background?.let { Backgrounds.renderNow(notebookId, it) }
        renderer.drawPage(c, page, paperStyle, paperColor, excluded, drawPaper = false, background = bg, images = nowImages)
        return bmp
    }

    /** The part of the page currently on screen, in page units. */
    fun visiblePageRect(): RectF = RectF(
        toPageX(0f).coerceIn(0f, PAGE_WIDTH), toPageY(0f).coerceIn(0f, page.height),
        toPageX(width.toFloat()).coerceIn(0f, PAGE_WIDTH), toPageY(height.toFloat()).coerceIn(0f, page.height),
    )

    /** Renders a region of the page (paper, background, ink) to a bitmap. */
    fun regionBitmap(region: RectF, maxSide: Int = 1600): Bitmap? {
        if (region.width() < 1f || region.height() < 1f) return null
        val s = min(maxSide / max(region.width(), region.height()), 4f)
        val bmp = Bitmap.createBitmap((region.width() * s).roundToInt().coerceAtLeast(1), (region.height() * s).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(s, s)
        c.translate(-region.left, -region.top)
        val bg = page.background?.let { Backgrounds.renderNow(notebookId, it) }
        renderer.drawPage(c, page, paperStyle, paperColor, background = bg, images = nowImages)
        return bmp
    }

    /** Selected pen/highlighter strokes in drawing order (for handwriting recognition). */
    fun selectedTexts(): List<TextBox> {
        val sel = selection ?: return emptyList()
        return page.texts.filter { it.id in sel.textIds }
    }

    fun scrollToBottom() {
        ty = -1e6f
        clampViewport(); afterViewportChange()
    }

    fun selectedStrokes(): List<Stroke> {
        val sel = selection ?: return emptyList()
        return page.strokes.filter { it.id in sel.strokeIds }
    }

    /** Replaces the selected ink with a text box (used by handwriting → text). */
    fun replaceSelectionWithText(text: String, size: Float) {
        val sel = selection ?: return
        val b = sel.bounds
        val tb = TextBox(x = b.left, y = b.top, width = max(b.width(), 200f), text = text, size = size, color = 0xFF1A1A1A.toInt())
        selection = null
        commit(page.copy(strokes = page.strokes.filter { it.id !in sel.strokeIds }, texts = page.texts.filter { it.id !in sel.textIds } + tb))
        listener?.onSelectionChanged(null)
    }

    private fun notifySelection() {
        val sel = selection ?: return
        val r = selViewRect(sel)
        listener?.onSelectionChanged(SelectionInfo(r.left, r.top, r.right, r.bottom, sel.strokeIds.size + sel.textIds.size + sel.imageIds.size))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(holdRunnable)
    }

    companion object {
        private const val HOLD_MS = 550L
        private const val HOLD_SLOP = 5f
    }
}
