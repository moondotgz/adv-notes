package com.advnotes.app.render

import com.advnotes.app.tools.calculator.CalcOptions
import com.advnotes.app.tools.calculator.CasioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the C++ engine through the real JNI bridge (host build, see buildHostCasio). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalculatorEngineTest {
    @Before fun engine() { assumeTrue("host calculator library not built", CasioEngine.available); CasioEngine.reset() }

    private val o = CalcOptions()

    @Test fun exactResults() {
        assertEquals("7/12", CasioEngine.evaluate("1/3+1/4", o).result)
        assertEquals("2√(2)", CasioEngine.evaluate("√(8)", o).result)
        assertEquals("1/2", CasioEngine.evaluate("sin(30)", o).result)
        assertEquals("0.5", CasioEngine.evaluate("sin(30)", o).decimal)
        assertEquals("9", CasioEngine.evaluate("∫(x²,0,3)", o).result)
        assertEquals("1.204×10²⁴", CasioEngine.evaluate("6.02ᴇ23×2", o).result)
    }

    @Test fun errorsAndState() {
        val r = CasioEngine.evaluate("1÷0", o)
        assertFalse(r.ok); assertEquals("Math ERROR", r.error)
        CasioEngine.evaluate("5→A", o)
        assertEquals("26", CasioEngine.evaluate("A²+1", o).result)
        assertEquals(5.0, CasioEngine.variables(o)["A"]!!.first, 0.0)
        assertEquals("26", CasioEngine.evaluate("Ans", o).result)
    }

    @Test fun modes() {
        assertEquals("5+5i", CasioEngine.evaluate("(1+2i)×(3−i)", o.copy(complex = true)).result)
        assertEquals("100", CasioEngine.evaluate("FF+1", o.copy(base = 16)).result)
        assertEquals(listOf("3", "2"), CasioEngine.polynomial(listOf(1.0, -5.0, 6.0), o)!!.first)
        assertEquals("unique" to listOf("6", "4"), CasioEngine.linear(2, listOf(1.0, 1.0, 1.0, -1.0), listOf(10.0, 2.0), o))
        val st = CasioEngine.statistics(1, listOf(1.0, 2.0, 3.0, 4.0), listOf(3.0, 5.0, 7.0, 9.0), o).getOrThrow().toMap()
        assertEquals("1", st["a"]); assertEquals("2", st["b"])
        assertTrue(CasioEngine.constants.size >= 40)
        assertEquals("127/5", CasioEngine.evaluate("10‹in▶cm›", o).result)
    }
}
