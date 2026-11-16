package com.advnotes.app.tools.calculator

import com.advnotes.app.ai.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

/** Calculator settings, mirroring the engine's SETUP. */
@Serializable
data class CalcOptions(
    val angle: Int = 0,          // 0 Deg, 1 Rad, 2 Gra
    val format: Int = 0,         // 0 Norm1, 1 Norm2, 2 Fix, 3 Sci
    val digits: Int = 0,
    val exact: Boolean = true,   // Math output (fractions, √, π)
    val mixed: Boolean = false,
    val polar: Boolean = false,
    val complex: Boolean = false,
    val base: Int = 0,           // Base-N: 2, 8, 10, 16
) {
    fun toArray() = intArrayOf(angle, format, digits, if (exact) 1 else 0, if (mixed) 1 else 0, if (polar) 1 else 0, if (complex) 1 else 0, base)
}

data class CalcResult(
    val ok: Boolean,
    val result: String = "",
    val decimal: String = "",
    val hasExact: Boolean = false,
    val outputs: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
    val errorPos: Int = -1,
)

data class SciConstant(val id: String, val symbol: String, val name: String, val category: String, val value: Double, val unit: String)
data class UnitConversion(val id: String, val category: String)

/**
 * Kotlin wrapper around the C++ engine of github.com/moondotgz/casio (libadvnotes_casio.so).
 * The engine keeps its own state (variables A–F, M, x, y, Ans, PreAns) between calls.
 */
object CasioEngine {
    val available: Boolean by lazy { runCatching { System.loadLibrary("advnotes_casio"); true }.getOrDefault(false) }

    private external fun nEvaluate(expr: String, opts: IntArray): String
    private external fun nPolynomial(coeffs: DoubleArray, opts: IntArray): String
    private external fun nLinear(n: Int, a: DoubleArray, b: DoubleArray, opts: IntArray): String
    private external fun nStatistics(type: Int, x: DoubleArray, y: DoubleArray, opts: IntArray): String
    private external fun nVariables(opts: IntArray): String
    private external fun nSetVariable(name: String, re: Double, im: Double)
    private external fun nReset()
    private external fun nConstants(): String
    private external fun nConversions(): String

    private fun obj(s: String) = Http.json.parseToJsonElement(s).jsonObject
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()

    private fun error(o: JsonObject) = CalcResult(false, error = o.str("error"), errorPos = o["pos"]?.jsonPrimitive?.intOrNull ?: -1)

    @Synchronized
    fun evaluate(expr: String, opts: CalcOptions): CalcResult {
        val o = obj(nEvaluate(expr, opts.toArray()))
        if (o["ok"]?.jsonPrimitive?.booleanOrNull != true) return error(o)
        val outs = o["outputs"]?.jsonArray?.map { val e = it.jsonObject; e.str("name") to e.str("value") }.orEmpty()
        return CalcResult(true, o.str("result"), o.str("decimal"), o["hasExact"]?.jsonPrimitive?.booleanOrNull == true, outs)
    }

    /** Roots of a polynomial (highest degree first) — plus "vertex" for quadratics. */
    @Synchronized
    fun polynomial(coeffs: List<Double>, opts: CalcOptions): Pair<List<String>, List<String>>? {
        val o = obj(nPolynomial(coeffs.toDoubleArray(), opts.toArray()))
        if (o["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        return o["roots"]!!.jsonArray.map { it.jsonPrimitive.content } to (o["vertex"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
    }

    /** status ("unique" / "none" / "infinite") and the solution. */
    @Synchronized
    fun linear(n: Int, a: List<Double>, b: List<Double>, opts: CalcOptions): Pair<String, List<String>>? {
        val o = obj(nLinear(n, a.toDoubleArray(), b.toDoubleArray(), opts.toArray()))
        if (o["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        return o.str("status") to o["x"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    /** Statistics (type 0 = one-variable, 1..7 regressions) as name/value pairs, or an error message. */
    @Synchronized
    fun statistics(type: Int, x: List<Double>, y: List<Double>, opts: CalcOptions): Result<List<Pair<String, String>>> {
        val o = obj(nStatistics(type, x.toDoubleArray(), y.toDoubleArray(), opts.toArray()))
        if (o["ok"]?.jsonPrimitive?.booleanOrNull != true) return Result.failure(IllegalArgumentException(o.str("error")))
        return Result.success(o["values"]!!.jsonArray.map { val e = it.jsonObject; e.str("name") to e.str("value") })
    }

    /** Variables as name -> (re, im, display text). */
    @Synchronized
    fun variables(opts: CalcOptions): Map<String, Triple<Double, Double, String>> =
        obj(nVariables(opts.toArray())).mapValues { (_, v) ->
            val e = v.jsonObject
            Triple(e["re"]!!.jsonPrimitive.double, e["im"]!!.jsonPrimitive.double, e.str("text"))
        }

    @Synchronized fun setVariable(name: String, re: Double, im: Double = 0.0) = nSetVariable(name, re, im)
    @Synchronized fun reset() = nReset()

    val constants: List<SciConstant> by lazy {
        Http.json.parseToJsonElement(nConstants()).jsonArray.map {
            val e = it.jsonObject
            SciConstant(e.str("id"), e.str("symbol"), e.str("name"), e.str("category"), e["value"]!!.jsonPrimitive.double, e.str("unit"))
        }
    }

    val conversions: List<UnitConversion> by lazy {
        Http.json.parseToJsonElement(nConversions()).jsonArray.map { val e = it.jsonObject; UnitConversion(e.str("id"), e.str("category")) }
    }

    val REGRESSIONS = listOf("1-Variable", "y=a+bx", "y=a+bx+cx²", "y=a+b·ln x", "y=a·e^(bx)", "y=a·b^x", "y=a·x^b", "y=a+b/x")
}
