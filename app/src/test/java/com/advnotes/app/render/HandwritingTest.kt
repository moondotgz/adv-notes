package com.advnotes.app.render

import com.advnotes.app.ai.Handwriting
import com.advnotes.app.ai.MathSolver
import com.advnotes.app.ai.Subject
import com.advnotes.app.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandwritingTest {
    private fun box(id: String, x: Float, y0: Float, y1: Float) =
        Stroke(id = id, color = 0, width = 2f, pts = floatArrayOf(x, y0, 1f, x + 10f, y1, 1f))

    @Test fun groupsStrokesIntoLines() {
        val strokes = listOf(
            box("a", 0f, 100f, 130f), box("b", 20f, 105f, 128f),     // line 1
            box("d", 0f, 200f, 232f), box("c", 40f, 98f, 131f),      // line 2, then a late stroke on line 1
            box("e", 30f, 202f, 228f),
            box("tall", 60f, 95f, 235f),                              // a bracket spanning both lines
        )
        val lines = Handwriting.lines(strokes)
        assertEquals(2, lines.size)
        assertEquals(listOf("a", "b", "c"), lines[0].map { it.id }.filter { it != "tall" })
        assertEquals(listOf("d", "e"), lines[1].map { it.id }.filter { it != "tall" })
        assertEquals(1, lines.sumOf { l -> l.count { it.id == "tall" } })
    }

    @Test fun subjectPrompts() {
        assertTrue(MathSolver.systemPrompt(Subject.PHYSICS).contains("SI units"))
        assertTrue(MathSolver.systemPrompt(Subject.CHEMISTRY).contains("Balance every equation"))
        val auto = MathSolver.systemPrompt(Subject.AUTO)
        assertTrue(auto.contains("SI units") && auto.contains("Balance") && auto.contains("decide whether"))
        assertTrue(Handwriting.LANGUAGES.any { it.name == "Polish" && it.tag == "pl-PL" })
    }
}
