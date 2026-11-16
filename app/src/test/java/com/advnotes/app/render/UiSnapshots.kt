package com.advnotes.app.render

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.MainActivity
import com.advnotes.app.ink.InkCanvasView
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drives the real app UI with simulated S Pen input and saves screenshots to build/snapshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi")
class UiSnapshots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File(System.getProperty("user.dir"), "build/snapshots").apply { mkdirs() }

    @org.junit.Before fun fakeKeystore() {
        com.advnotes.app.data.SettingsStore.secretsOverride = { it.getSharedPreferences("test-secrets", 0) }
    }

    private fun snap(name: String) {
        rule.waitForIdle()
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        rule.waitForIdle()
        rule.runOnUiThread {
            val main = rule.activity.window.decorView
            val w = main.width; val h = main.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            // main window, then any dialog / bottom-sheet windows on top (centred like the real WM does)
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val wmg: Any = wmgClass.getMethod("getInstance").invoke(null)!!
            val rootImpls = wmgClass.getDeclaredField("mRoots").apply { isAccessible = true }.get(wmg) as List<*>
            val roots: List<View> = rootImpls.mapNotNull { r -> r?.let { it::class.java.getMethod("getView").invoke(it) as? View } }
            for (v in roots) {
                if (v.width == 0 || v.height == 0) continue
                c.save()
                if (v !== main) {
                    c.drawColor(0x55000000)
                    val lp = v.layoutParams as? android.view.WindowManager.LayoutParams
                    val bottom = lp != null && (lp.gravity and android.view.Gravity.BOTTOM) == android.view.Gravity.BOTTOM
                    c.translate((w - v.width) / 2f, if (bottom) (h - v.height).toFloat() else (h - v.height) / 2f)
                }
                v.draw(c)
                c.restore()
            }
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun findCanvas(v: View): InkCanvasView? {
        if (v is InkCanvasView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findCanvas(v.getChildAt(i))?.let { return it }
        return null
    }

    private var downTime = 0L
    private fun ev(action: Int, x: Float, y: Float, p: Float, buttons: Int = 0): MotionEvent {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = p; size = 0.1f })
        return MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    /** Draws a pen stroke through [pts] (view px) with the given pressure curve. */
    private fun pen(v: InkCanvasView, pts: List<Pair<Float, Float>>, pressure: (Float) -> Float, holdAtEnd: Boolean = false, buttons: Int = 0) {
        rule.runOnUiThread {
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, pts[0].first, pts[0].second, pressure(0f), buttons))
            for (i in 1 until pts.size) {
                ShadowLooper.idleMainLooper(8, TimeUnit.MILLISECONDS)
                v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, pts[i].first, pts[i].second, pressure(i / (pts.size - 1f)), buttons))
            }
            if (holdAtEnd) ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS)
            val l = pts.last()
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, l.first, l.second, 0f, buttons))
        }
        rule.waitForIdle()
    }

    private fun wave(x0: Float, y0: Float, len: Float, amp: Float, freq: Float) =
        (0..80).map { i -> val t = i / 80f; (x0 + t * len) to (y0 + sin(t * freq) * amp) }

    private fun loop(cx: Float, cy: Float, rx: Float, ry: Float, wobble: Float = 0f) =
        (0..90).map { i -> val a = i / 90f * 6.4f; (cx + cos(a) * rx + sin(a * 7) * wobble) to (cy + sin(a) * ry) }

    @Test fun walkthrough() {
        val app = AdvNotesApp.instance
        runBlocking {
            app.repo.save(Notebook(title = "Calculus II", paper = PaperStyle.NARROW_GRID, cover = 0xFF2D4BD8.toInt()))
            app.repo.save(Notebook(title = "Physics lab", paper = PaperStyle.OXFORD, cover = 0xFF0F8B6F.toInt()))
            app.repo.save(Notebook(title = "Sketches", paper = PaperStyle.PLAIN, paperColor = PaperColor.CREAM, cover = 0xFFD9480F.toInt()))
            app.repo.save(Notebook(title = "Linear algebra", paper = PaperStyle.GRID, cover = 0xFF9C36B5.toInt()))
        }
        rule.waitForIdle()
        snap("01_library")

        rule.onNodeWithText("New notebook", useUnmergedTree = true).performClick()
        snap("02_new_notebook")
        rule.onNodeWithText("Cancel").performClick()

        rule.onNodeWithText("Calculus II").performClick()
        rule.waitForIdle()
        val canvas = findCanvas(rule.activity.window.decorView)!!

        // handwriting-like pressure strokes
        pen(canvas, wave(120f, 260f, 900f, 30f, 14f), { 0.15f + 0.85f * sin(it * 3.14f) })
        pen(canvas, wave(120f, 380f, 700f, 22f, 22f), { 0.6f })
        // a wobbly circle, held at the end -> should snap to a clean ellipse
        pen(canvas, loop(400f, 750f, 180f, 140f, wobble = 6f), { 0.7f }, holdAtEnd = true)
        // a rough line, held -> straight line
        pen(canvas, (0..40).map { i -> (750f + i * 12f) to (650f + i * 9f + sin(i * 1.3f) * 6f) }, { 0.7f }, holdAtEnd = true)
        snap("03_editor_ink")

        val strokesBefore = canvas.page.strokes.size
        assertTrue("strokes were committed", strokesBefore >= 4)

        // S Pen side button held -> temporary eraser across the second wave
        pen(canvas, (0..30).map { i -> (450f + i * 2f) to (330f + i * 3.5f) }, { 0.5f }, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertTrue("side-button erase removed a stroke", canvas.page.strokes.size < strokesBefore)

        // lasso around the first wave: pick the lasso tool, then circle it
        rule.onNodeWithContentDescriptionCompat("Lasso")
        pen(canvas, loop(570f, 260f, 520f, 90f), { 0.5f })
        assertTrue("selection made", canvas.hasSelection())
        snap("04_editor_lasso")

        rule.onNodeWithText("Solve", useUnmergedTree = true).performClick()
        snap("05_ai_sheet")
    }

    @Test fun settings() {
        rule.onNodeWithContentDescriptionCompat("Settings")
        snap("06_settings")
    }

    private fun click(text: String) {
        rule.onAllNodesWithText(text, useUnmergedTree = true)[0].performClick()
        rule.waitForIdle()
    }

    @Test fun toolsAndImport() {
        val app = AdvNotesApp.instance
        // Import a real Samsung Notes file through the importer (same code path as the Import button)
        val sdocx = File.createTempFile("res", "s.sdocx")
        javaClass.getResourceAsStream("/import/sample2.sdocx")!!.use { i -> sdocx.outputStream().use { i.copyTo(it) } }
        runBlocking {
            val (nb, _) = com.advnotes.app.importer.SamsungNotes.import(com.advnotes.app.model.newId(), "Graph notes (Samsung Notes)", sdocx)
            app.repo.save(nb)
            app.repo.save(Notebook(title = "Chemistry", paper = PaperStyle.OXFORD, cover = 0xFF0F8B6F.toInt()))
        }
        rule.waitForIdle()
        snap("10_library_import")

        rule.onNodeWithText("Graph notes (Samsung Notes)").performClick()
        rule.waitForIdle()
        snap("11_imported_samsung_notes")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()

        rule.onNodeWithText("Chemistry").performClick()
        rule.waitForIdle()
        val canvas = findCanvas(rule.activity.window.decorView)!!
        pen(canvas, wave(120f, 260f, 500f, 22f, 16f), { 0.2f + 0.8f * sin(it * 3.14f) })

        // Tools panel -> picker -> calculator
        rule.onNodeWithContentDescriptionCompat("Tools")
        snap("12_tool_picker")
        click("Scientific calculator")
        for (k in listOf("√", "8", ")", "+", "1", "÷", "3", "=")) click(k)
        snap("13_calculator")

        click("Equation")
        snap("14_calculator_equation")

        // AI chat tool
        rule.onNodeWithContentDescriptionCompat("Open a tool")
        click("AI chat")
        snap("15_ai_chat")

        // Solver sheet with subjects
        rule.onNodeWithContentDescriptionCompat("Hide panel")
        rule.onNodeWithContentDescriptionCompat("Lasso")
        pen(canvas, loop(420f, 260f, 330f, 90f), { 0.5f })
        assertTrue(canvas.hasSelection())
        snap("16_selection_bar")
        rule.onNodeWithText("Solve", useUnmergedTree = true).performClick()
        snap("17_solver_subjects")
    }

    @Test fun movableBars() {
        val app = AdvNotesApp.instance
        runBlocking { app.repo.save(Notebook(title = "Lab", paper = PaperStyle.GRID)) }
        rule.waitForIdle()
        rule.onNodeWithText("Lab").performClick()
        rule.waitForIdle()
        // drag the toolbar handle to the left edge
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Move toolbar (drag to an edge)")).performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(-40f, 300f)); moveBy(androidx.compose.ui.geometry.Offset(-200f, 300f)); up()
        }
        rule.waitForIdle()
        assertEquals("LEFT", app.settings.state.value.toolbarDock)
        // open the calculator and drag the panel to the left side
        rule.onNodeWithContentDescriptionCompat("Tools")
        click("Scientific calculator")
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Move panel (drag left or right, or tap to swap sides)")).performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(-200f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(-1000f, 0f)); up()
        }
        rule.waitForIdle()
        assertEquals("LEFT", app.settings.state.value.panelSide)
        snap("18_moved_bars")
        // and the toolbar to the bottom
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Move toolbar (drag to an edge)")).performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(300f, 1000f)); moveBy(androidx.compose.ui.geometry.Offset(100f, 1500f)); up()
        }
        rule.waitForIdle()
        assertEquals("BOTTOM", app.settings.state.value.toolbarDock)
        snap("19_toolbar_bottom")
    }

    /** Regression: SHIFT legends pushed key labels out of the keys with big fonts. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi", fontScale = 1.3f)
    fun calculatorKeysWithLargeFont() {
        runBlocking { AdvNotesApp.instance.repo.save(Notebook(title = "Calc", paper = PaperStyle.GRID)) }
        rule.waitForIdle()
        rule.onNodeWithText("Calc").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescriptionCompat("Tools")
        click("Scientific calculator")
        rule.onNodeWithContentDescriptionCompat("Toggle keyboard") // hide…
        rule.onNodeWithContentDescriptionCompat("Toggle keyboard") // …and show again
        snap("20_calculator_large_font")
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithContentDescriptionCompat(desc: String) {
        onNode(androidx.compose.ui.test.hasContentDescription(desc)).performClick()
        waitForIdle()
    }
}
