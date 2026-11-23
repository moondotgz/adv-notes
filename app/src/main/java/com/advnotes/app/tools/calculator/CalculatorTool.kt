package com.advnotes.app.tools.calculator

import com.advnotes.app.i18n.tr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.advnotes.app.data.AppJson
import com.advnotes.app.tools.NotebookTool
import com.advnotes.app.tools.ToolHost
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Scientific calculator tool. The maths is the C++ engine from github.com/moondotgz/casio
 * (fx-991CEX feature set: exact fractions/√/π, ∫, d/dx, Σ, Π, constants, conversions, complex,
 * base-N, equations, statistics) compiled for Android; this file is only the UI.
 */
object CalculatorTool : NotebookTool {
    override val id = "calculator"
    override val name  get() = tr("Scientific calculator")
    override val description  get() = tr("fx-991-style: exact fractions, √ and π, ∫, d/dx, Σ, constants, units, complex, base-N, equations, statistics")
    override val icon = Icons.Default.Calculate

    @Composable
    override fun Content(host: ToolHost) {
        if (!CasioEngine.available) {
            Text(tr("The calculator engine isn't included in this build."), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            return
        }
        CalculatorScreen(host)
    }
}

private enum class Mode(private val en: String) { CALC("Calculate"), COMPLEX("Complex"), BASE("Base-N"), EQUATION("Equation"), STATS("Statistics") ;
    /** Shown to the user, in the app language. */
    val label: String get() = tr(en)
}

@Serializable
private data class HistoryEntry(
    val input: String,
    val result: String,
    val decimal: String = "",
    val hasExact: Boolean = false,
    val outputs: List<Pair<String, String>> = emptyList(),
    val error: Boolean = false,
)

@Serializable
private data class SavedVar(val name: String, val re: Double, val im: Double)

/** Shows ‹id› constants and ‹a▶b› conversions as their symbols while keeping the raw text. */
private class CalcVisual(private val symbols: Map<String, String>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = StringBuilder()
        val o2t = ArrayList<Int>()   // transformed index -> original index
        val t2o = IntArray(raw.length + 1)
        var i = 0
        while (i < raw.length) {
            val close = if (raw[i] == '‹') raw.indexOf('›', i) else -1
            if (close > i) {
                val id = raw.substring(i + 1, close)
                val shown = symbols[id] ?: id.replace("▶", "▶")
                for (k in i..close) t2o[k] = out.length
                shown.forEach { _ -> o2t += i }
                out.append(shown)
                i = close + 1
            } else {
                t2o[i] = out.length
                o2t += i
                out.append(raw[i])
                i++
            }
        }
        t2o[raw.length] = out.length
        o2t += raw.length
        return TransformedText(AnnotatedString(out.toString()), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = t2o[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int) = o2t[offset.coerceIn(0, o2t.size - 1)]
        })
    }
}

private val POSTFIX_OR_INFIX = setOf("+", "−", "×", "÷", "^(", "²", "³", "⁻¹", "!", "%", "nPr", "nCr", "→", "ᴇ", "°", "∠", "▶r∠θ", "▶a+bi", " and ", " or ", " xor ", " xnor ")

@Composable
private fun CalculatorScreen(host: ToolHost) {
    val storage = host.storage
    var mode by remember { mutableStateOf(runCatching { Mode.valueOf(storage.get("mode") ?: "CALC") }.getOrDefault(Mode.CALC)) }
    var opts by remember {
        mutableStateOf(runCatching { AppJson.decodeFromString(CalcOptions.serializer(), storage.get("opts")!!) }.getOrDefault(CalcOptions()))
    }
    val history = remember {
        mutableStateListOf<HistoryEntry>().apply {
            runCatching { addAll(AppJson.decodeFromString(ListSerializer(HistoryEntry.serializer()), storage.get("history")!!)) }
        }
    }
    LaunchedEffect(Unit) {
        // restore memory variables into the engine
        runCatching {
            AppJson.decodeFromString(ListSerializer(SavedVar.serializer()), storage.get("vars")!!).forEach { CasioEngine.setVariable(it.name, it.re, it.im) }
        }
    }

    fun saveOpts(o: CalcOptions) { opts = o; storage.set("opts", AppJson.encodeToString(CalcOptions.serializer(), o)) }
    fun setMode(m: Mode) {
        mode = m; storage.set("mode", m.name)
        saveOpts(opts.copy(complex = m == Mode.COMPLEX, base = if (m == Mode.BASE) (opts.base.takeIf { it != 0 } ?: 10) else 0))
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mode.entries.forEach { m -> FilterChip(m == mode, { setMode(m) }, label = { Text(m.label) }) }
        }
        when (mode) {
            Mode.CALC, Mode.COMPLEX, Mode.BASE -> CalcMode(host, mode, opts, ::saveOpts, history)
            Mode.EQUATION -> EquationMode(host, opts)
            Mode.STATS -> StatsMode(host, opts)
        }
    }
}

// ----------------------------------------------------------------------------------------
// Calculate / Complex / Base-N
// ----------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalcMode(
    host: ToolHost,
    mode: Mode,
    opts: CalcOptions,
    saveOpts: (CalcOptions) -> Unit,
    history: MutableList<HistoryEntry>,
) {
    val storage = host.storage
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var fresh by remember { mutableStateOf(false) }       // last action was "=": next key starts over / continues from Ans
    var showDecimal by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var keypad by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf(false) }
    var stoMenu by remember { mutableStateOf(false) }
    var rclMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val symbols = remember { CasioEngine.constants.associate { it.id to it.symbol } }
    val visual = remember { CalcVisual(symbols) }
    val last = history.lastOrNull()

    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1) }

    fun persist() {
        while (history.size > 100) history.removeAt(0)
        storage.set("history", AppJson.encodeToString(ListSerializer(HistoryEntry.serializer()), history.toList()))
        val vars = CasioEngine.variables(opts).map { (k, v) -> SavedVar(k, v.first, v.second) }
        storage.set("vars", AppJson.encodeToString(ListSerializer(SavedVar.serializer()), vars))
    }

    fun insert(s: String) {
        error = null
        var cur = input
        if (fresh) {
            cur = if (s in POSTFIX_OR_INFIX) TextFieldValue("Ans", TextRange(3)) else TextFieldValue("")
            fresh = false
        }
        val t = cur.text
        val sel = cur.selection
        val text = t.substring(0, sel.min) + s + t.substring(sel.max)
        input = TextFieldValue(text, TextRange(sel.min + s.length))
    }

    fun delete() {
        error = null
        fresh = false
        val t = input.text
        val sel = input.selection
        if (sel.length > 0) { input = TextFieldValue(t.removeRange(sel.min, sel.max), TextRange(sel.min)); return }
        if (sel.min == 0) return
        // delete a whole ‹constant› or keyword-ish token like "sin(" in one go
        val before = t.substring(0, sel.min)
        val n = when {
            before.endsWith('›') -> before.length - before.lastIndexOf('‹')
            else -> listOf("sin⁻¹(", "cos⁻¹(", "tan⁻¹(", "sinh(", "cosh(", "tanh(", "sin(", "cos(", "tan(", "log(", "ln(", "√(", "∛(", "∫(",
                "d/dx(", "Σ(", "Π(", "root(", "Abs(", "Pol(", "Rec(", "GCD(", "LCM(", "Int(", "Intg(", "Rnd(", "RanInt#(", "Ran#",
                "Arg(", "Conjg(", "ReP(", "ImP(", "Not(", "Neg(", "PreAns", "Ans", "nPr", "nCr", "⁻¹", "^(", "▶r∠θ", "▶a+bi",
                " and ", " or ", " xor ", " xnor ")
                .firstOrNull { before.endsWith(it) }?.length ?: if (before.length >= 2 && before[before.length - 1].isLowSurrogate()) 2 else 1
        }
        input = TextFieldValue(t.removeRange(sel.min - n, sel.min), TextRange(sel.min - n))
    }

    fun move(d: Int) {
        val p = (input.selection.min + d).coerceIn(0, input.text.length)
        input = input.copy(selection = TextRange(p)); fresh = false
    }

    fun evaluate(text: String = input.text) {
        if (text.isBlank()) return
        val r = CasioEngine.evaluate(text, opts)
        if (r.ok) {
            history += HistoryEntry(text, r.result, r.decimal, r.hasExact, r.outputs)
            showDecimal = false
            fresh = true
            error = null
            persist()
        } else {
            error = r.error
            if (r.errorPos in 0..text.length) input = input.copy(selection = TextRange(r.errorPos))
        }
    }

    fun entryText(e: HistoryEntry, decimal: Boolean = false): String {
        val res = if (decimal && e.decimal.isNotEmpty()) e.decimal else e.result
        val shown = CalcVisual(symbols).filter(AnnotatedString(e.input)).text.text
        return if (e.outputs.isNotEmpty()) "$shown: " + e.outputs.joinToString(", ") { "${it.first} = ${it.second}" }
        else "$shown = $res"
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    // Rows of keys: 6 (5 in Base-N) + a 7th in Complex. Leave ~170dp for the setup row, input and
    // a little history; the keys share the rest, between 30dp and 46dp tall.
    val rows = if (mode == Mode.COMPLEX) 9 else 8
    // Heights of the setup row and input box, measured, so the keys get exactly what is left.
    var setupH by remember { mutableStateOf(52.dp) }
    var inputH by remember { mutableStateOf(64.dp) }
    val dens = LocalDensity.current
    val keyH = ((maxHeight - setupH - inputH - 16.dp) / rows - 4.dp).coerceIn(26.dp, 46.dp)
    CompositionLocalProvider(LocalKeyHeight provides keyH) {
    Column(Modifier.fillMaxSize()) {
        // SETUP row
        Row(Modifier.fillMaxWidth().onSizeChanged { setupH = with(dens) { it.height.toDp() } }
            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (mode == Mode.BASE) {
                listOf(10 to "DEC", 16 to "HEX", 2 to "BIN", 8 to "OCT").forEach { (b, l) ->
                    FilterChip(opts.base == b, { saveOpts(opts.copy(base = b)) }, label = { Text(l) })
                }
            } else {
                val angle = listOf("Deg", "Rad", "Gra")[opts.angle]
                OutlinedButton(onClick = { saveOpts(opts.copy(angle = (opts.angle + 1) % 3)) }) { Text(angle) }
                OutlinedButton(onClick = { saveOpts(opts.copy(exact = !opts.exact)) }) { Text(if (opts.exact) tr("Math") else tr("Decimal")) }
                var fmtMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { fmtMenu = true }) {
                        Text(when (opts.format) { 1 -> "Norm2"; 2 -> "Fix ${opts.digits}"; 3 -> "Sci ${if (opts.digits == 0) 10 else opts.digits}"; else -> "Norm1" })
                    }
                    DropdownMenu(fmtMenu, { fmtMenu = false }) {
                        DropdownMenuItem(text = { Text(tr("Norm1")) }, onClick = { saveOpts(opts.copy(format = 0)); fmtMenu = false })
                        DropdownMenuItem(text = { Text(tr("Norm2")) }, onClick = { saveOpts(opts.copy(format = 1)); fmtMenu = false })
                        for (d in listOf(0, 2, 3, 4, 6)) DropdownMenuItem(text = { Text(tr("Fix {0}", d)) }, onClick = { saveOpts(opts.copy(format = 2, digits = d)); fmtMenu = false })
                        for (d in listOf(3, 4, 6, 0)) DropdownMenuItem(text = { Text(tr("Sci {0}", if (d == 0) 10 else d)) }, onClick = { saveOpts(opts.copy(format = 3, digits = d)); fmtMenu = false })
                    }
                }
                if (mode == Mode.COMPLEX) OutlinedButton(onClick = { saveOpts(opts.copy(polar = !opts.polar)) }) { Text(if (opts.polar) "r∠θ" else "a+bi") }
                OutlinedButton(onClick = { saveOpts(opts.copy(mixed = !opts.mixed)) }) { Text(if (opts.mixed) "a b/c" else "d/c") }
            }
            TextButton(onClick = { history.clear(); persist() }) { Text(tr("Clear history")) }
        }

        // History
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            itemsIndexed(history) { i, e ->
                var menu by remember { mutableStateOf(false) }
                val isLast = i == history.size - 1
                Box {
                    Column(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { input = TextFieldValue(e.input, TextRange(e.input.length)); fresh = false }, onLongClick = { menu = true })
                            .padding(vertical = 6.dp),
                    ) {
                        Text(CalcVisual(symbols).filter(AnnotatedString(e.input)).text.text, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.outputs.isNotEmpty()) e.outputs.forEach { (n, v) ->
                            Text("$n = $v", Modifier.fillMaxWidth(), textAlign = TextAlign.End, style = MaterialTheme.typography.titleMedium)
                        } else {
                            Text(
                                if (isLast && showDecimal) e.decimal else e.result,
                                Modifier.fillMaxWidth(), textAlign = TextAlign.End,
                                style = if (isLast) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("Write on page")) }, onClick = { host.insertText(entryText(e, isLast && showDecimal)); menu = false })
                        DropdownMenuItem(text = { Text(tr("Use result")) }, onClick = { insert("(" + resultToInput(e.result) + ")"); menu = false })
                        DropdownMenuItem(text = { Text(tr("Delete")) }, onClick = { history.removeAt(i); persist(); menu = false })
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }

        // Input line
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).onSizeChanged { inputH = with(dens) { it.height.toDp() } + 8.dp },
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it; error = null; fresh = false },
                        readOnly = keypad,
                        textStyle = TextStyle(fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface),
                        visualTransformation = visual,
                        singleLine = false,
                        modifier = Modifier.weight(1f).heightIn(min = 32.dp),
                    )
                    IconButton(onClick = { keypad = !keypad }, Modifier.size(40.dp)) {
                        Icon(if (keypad) Icons.Default.Keyboard else Icons.Default.KeyboardHide, tr("Toggle keyboard"), Modifier.size(20.dp))
                    }
                }
                val hint = error ?: if (last != null && fresh && last.hasExact) "S⇔D shows the decimal" else null
                if (hint != null) Text(hint,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        if (keypad) {
            // A very large system font size would overflow the keys; cap it for the keypad only.
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, 1.15f))) {
            with(KeyScope(shift, onShiftUsed = { shift = false }, insert = ::insert)) {
            Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Key(if (shift) "SHIFT ●" else "SHIFT", accent = KeyAccent.Shift) { shift = !shift }
                    Key("ƒ") { catalog = true }
                    Key("◀") { move(-1) }
                    Key("▶") { move(1) }
                    Key("S⇔D") { if (last != null) showDecimal = !showDecimal }
                    Key("→ page", accent = KeyAccent.Op) { last?.let { host.insertText(entryText(it, showDecimal)) } }
                }
                when (mode) {
                    Mode.BASE -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (d in listOf("A", "B", "C", "D", "E", "F")) Key(d, enabled = opts.base == 16) { insert(d) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            K("and", " and "); K("or", " or "); K("xor", " xor "); K("xnor", " xnor "); K("Not", "Not("); K("Neg", "Neg(")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            K("(", "("); K(")", ")"); K("d", "d"); K("h", "h"); K("b", "b"); K("o", "o")
                        }
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            K("x²", "²", "x³", "³"); K("√", "√(", "∛", "∛("); K("xʸ", "^(", "ⁿ√", "root(")
                            K("x⁻¹", "⁻¹", "x!", "!"); K("log", "log(", "10ˣ", "10^("); K("ln", "ln(", "eˣ", "e^(")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            K("sin", "sin(", "sin⁻¹", "sin⁻¹("); K("cos", "cos(", "cos⁻¹", "cos⁻¹("); K("tan", "tan(", "tan⁻¹", "tan⁻¹(")
                            K("(", "("); K(")", ")"); K(",", ",")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            K("∫", "∫(", "d/dx", "d/dx("); K("Σ", "Σ(", "Π", "Π("); K("π", "π", "e", "e")
                            K("x", "x", "y", "y"); K("(−)", "−", "%", "%")
                            Box(Modifier.weight(1f)) {
                                KeyCap(if (shift) "RCL" else "STO", Modifier.fillMaxWidth()) { if (shift) { rclMenu = true; shift = false } else stoMenu = true }
                                DropdownMenu(stoMenu, { stoMenu = false }) {
                                    for (v in listOf("A", "B", "C", "D", "E", "F", "M", "x", "y")) DropdownMenuItem(text = { Text("→ $v") }, onClick = {
                                        stoMenu = false
                                        val expr = (if (fresh || input.text.isBlank()) "Ans" else input.text) + "→" + v
                                        evaluate(expr)
                                    })
                                }
                                DropdownMenu(rclMenu, { rclMenu = false }) {
                                    CasioEngine.variables(opts).forEach { (n, v) ->
                                        DropdownMenuItem(text = { Text("$n = ${v.third}") }, onClick = { insert(n); rclMenu = false })
                                    }
                                }
                            }
                        }
                        if (mode == Mode.COMPLEX) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            K("i", "i"); K("∠", "∠"); K("Arg", "Arg("); K("Conjg", "Conjg("); K("▶r∠θ", "▶r∠θ"); K("▶a+bi", "▶a+bi")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    D("7"); D("8"); D("9")
                    Key("DEL", accent = KeyAccent.Danger) { delete() }
                    Key("AC", accent = KeyAccent.Danger) { input = TextFieldValue(""); error = null; fresh = false }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    D("4"); D("5"); D("6"); K("×", "×", "nPr", "nPr", KeyAccent.Op); K("÷", "÷", "nCr", "nCr", KeyAccent.Op)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    D("1"); D("2"); D("3"); K("+", "+", accent = KeyAccent.Op); K("−", "−", accent = KeyAccent.Op)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    D("0"); K(".", "."); K("×10ˣ", "ᴇ"); K("Ans", "Ans", "PreAns", "PreAns")
                    Key("=", accent = KeyAccent.Equals) { evaluate() }
                }
            }
            }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { evaluate() }) { Text("=") }
            }
        }
    }

    }
    }
    if (catalog) CatalogDialog(onDismiss = { catalog = false }) { s -> insert(s); catalog = false }
}

/** Key height for the current panel size (shrinks so the whole keypad fits on phones). */
private val LocalKeyHeight = androidx.compose.runtime.staticCompositionLocalOf { 46.dp }

private enum class KeyAccent { Normal, Digit, Op, Shift, Danger, Equals }

private class KeyScope(val shift: Boolean, val onShiftUsed: () -> Unit, val insert: (String) -> Unit) {
    @Composable
    fun RowScope.K(label: String, value: String, shiftLabel: String? = null, shiftValue: String? = null, accent: KeyAccent = KeyAccent.Normal) {
        val useShift = shift && shiftLabel != null
        Key(if (useShift) shiftLabel!! else label, sub = if (!shift) shiftLabel else null, accent = accent) {
            insert(if (useShift) shiftValue!! else value)
            if (shift) onShiftUsed()
        }
    }

    @Composable
    fun RowScope.D(d: String) = Key(d, accent = KeyAccent.Digit) { insert(d) }
}

@Composable
private fun RowScope.Key(
    label: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    accent: KeyAccent = KeyAccent.Normal,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = KeyCap(label, modifier.weight(1f), sub, accent, enabled, onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier,
    sub: String? = null,
    accent: KeyAccent = KeyAccent.Normal,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (accent) {
        KeyAccent.Digit -> cs.surfaceContainerHighest to cs.onSurface
        KeyAccent.Op -> cs.secondaryContainer to cs.onSecondaryContainer
        KeyAccent.Shift -> Color(0xFFF2A93B) to Color(0xFF2B1A00)
        KeyAccent.Danger -> cs.errorContainer to cs.onErrorContainer
        KeyAccent.Equals -> cs.primary to cs.onPrimary
        KeyAccent.Normal -> cs.surfaceContainerHigh to cs.onSurface
    }
    // Tight line boxes so two labels fit regardless of the system font (some fonts have very tall line heights).
    val tight = TextStyle(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
    val mainSize = if (label.length > 4) 12.sp else if (LocalKeyHeight.current < 40.dp) 15.sp else 16.sp
    Box(
        modifier.height(LocalKeyHeight.current).clip(RoundedCornerShape(10.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .combinedClickable(enabled = enabled, onClick = onClick),
    ) {
        // The SHIFT legend sits at the top; the main label is centred in the space below it,
        // so neither can push the other out of the key.
        // Tall keys: legend above the label. Short keys (phones): legend tucked into the top-left corner.
        val compact = LocalKeyHeight.current < 40.dp
        if (sub != null) Text(
            sub,
            if (compact) Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 2.dp)
            else Modifier.align(Alignment.TopCenter).padding(top = 3.dp),
            style = tight.copy(fontSize = if (compact) 8.sp else 9.sp, lineHeight = 10.sp), color = Color(0xFFD08A1E), maxLines = 1,
        )
        Text(
            label,
            Modifier.align(Alignment.Center).padding(top = if (sub != null && !compact) 12.dp else if (sub != null) 7.dp else 0.dp),
            style = tight.copy(fontSize = mainSize, lineHeight = mainSize * 1.1f), color = fg,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun CatalogDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val functions = listOf(
        "Abs(" to "Absolute value", "Pol(" to "Rectangular → polar  Pol(x, y)", "Rec(" to "Polar → rectangular  Rec(r, θ)",
        "GCD(" to "Greatest common divisor", "LCM(" to "Least common multiple", "Int(" to "Integer part", "Intg(" to "Largest integer ≤ x",
        "Rnd(" to "Round to display", "Ran#" to "Random number 0–1", "RanInt#(" to "Random integer  RanInt#(a, b)",
        "log(" to "log(a, b): logarithm base a", "root(" to "root(n, x): n-th root", "nPr" to "Permutations", "nCr" to "Combinations",
        "!" to "Factorial", "%" to "Percent", "°" to "Degrees", "ʳ" to "Radians", "ᵍ" to "Gradians", "÷R" to "Division with remainder",
        "sinh(" to "Hyperbolic sine", "cosh(" to "Hyperbolic cosine", "tanh(" to "Hyperbolic tangent",
        "Min(" to "Min (spreadsheet ranges)", "Max(" to "Max", "Mean(" to "Mean", "Sum(" to "Sum",
        "∫(" to "∫(f(x), a, b)", "d/dx(" to "d/dx(f(x), a)", "Σ(" to "Σ(f(x), a, b)", "Π(" to "Π(f(x), a, b)",
        "→" to "Store into variable",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Functions", "Constants", "Units").forEachIndexed { i, t -> FilterChip(tab == i, { tab = i }, label = { Text(t) }) }
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                when (tab) {
                    0 -> items(functions) { (v, d) -> CatalogRow(v.removeSuffix("("), d) { onPick(v) } }
                    1 -> items(CasioEngine.constants) { c ->
                        CatalogRow(c.symbol, "${c.name} · ${"%.6g".format(c.value)} ${c.unit}") { onPick("‹${c.id}›") }
                    }
                    else -> items(CasioEngine.conversions) { c -> CatalogRow(c.id, c.category) { onPick("‹${c.id}›") } }
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------------------------------
// Equation mode
// ----------------------------------------------------------------------------------------

private fun parseNumber(s: String, opts: CalcOptions): Double? {
    s.trim().replace('−', '-').replace(',', '.').toDoubleOrNull()?.let { return it }
    if (s.isBlank()) return 0.0
    val r = CasioEngine.evaluate(s, opts.copy(exact = false, base = 0, complex = false))
    return if (r.ok) r.decimal.replace('−', '-').replace("×10", "E").let { d ->
        // "1.2×10⁻⁵" -> "1.2E-5"
        val sup = "⁰¹²³⁴⁵⁶⁷⁸⁹"
        d.map { c -> sup.indexOf(c).let { i -> if (i >= 0) ('0' + i) else if (c == '⁻') '-' else c } }.joinToString("")
    }.toDoubleOrNull() else null
}

@Composable
private fun EquationMode(host: ToolHost, opts: CalcOptions) {
    // kind: 2..4 = polynomial degree, -2..-4 = linear system with n unknowns
    var kind by remember { mutableStateOf(2) }
    val fields = remember { mutableStateListOf<String>().apply { repeat(20) { add("") } } }
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val vars = listOf("x", "y", "z", "t")

    fun header(): String = if (kind > 0) {
        val names = "abcde".take(kind + 1)
        names.mapIndexed { i, c -> val p = kind - i; c + when (p) { 0 -> ""; 1 -> "x"; 2 -> "x²"; 3 -> "x³"; else -> "x⁴" } }.joinToString(" + ") + " = 0"
    } else "a·x + b·y … = c"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(2 to "ax²+…", 3 to "ax³+…", 4 to "ax⁴+…", -2 to "2 unknowns", -3 to "3 unknowns", -4 to "4 unknowns").forEach { (k, l) ->
                FilterChip(kind == k, { kind = k; result = emptyList(); error = null }, label = { Text(l) })
            }
        }
        Text(header(), style = MaterialTheme.typography.titleMedium)
        val numberKb = KeyboardOptions(keyboardType = KeyboardType.Text)
        if (kind > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0..kind) OutlinedTextField(fields[i], { fields[i] = it }, Modifier.weight(1f), label = { Text("abcde"[i].toString()) },
                    singleLine = true, keyboardOptions = numberKb)
            }
        } else {
            val n = -kind
            for (r in 0 until n) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (c in 0..n) OutlinedTextField(
                    fields[r * 5 + c], { fields[r * 5 + c] = it }, Modifier.weight(1f),
                    label = { Text(if (c < n) vars[c] else "=") }, singleLine = true, keyboardOptions = numberKb,
                )
            }
        }
        Text(tr("Fields accept expressions too, e.g. 1/3, √(2), π."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {
            error = null
            if (kind > 0) {
                val c = (0..kind).map { parseNumber(fields[it], opts) }
                if (c.any { it == null }) { error = tr("Check the coefficients"); return@Button }
                val r = CasioEngine.polynomial(c.map { it!! }, opts)
                result = if (r == null) { error = tr("a must not be 0"); emptyList() } else
                    r.first.mapIndexed { i, v -> "x${"₁₂₃₄"[i]} = $v" } +
                        (if (r.second.size == 2) listOf("vertex: (${r.second[0]}, ${r.second[1]})") else emptyList())
            } else {
                val n = -kind
                val a = ArrayList<Double>(); val b = ArrayList<Double>()
                for (r in 0 until n) {
                    for (c in 0 until n) a += parseNumber(fields[r * 5 + c], opts) ?: run { error = tr("Check the coefficients"); return@Button }
                    b += parseNumber(fields[r * 5 + n], opts) ?: run { error = tr("Check the coefficients"); return@Button }
                }
                val r = CasioEngine.linear(n, a, b, opts)
                result = when (r?.first) {
                    "unique" -> r.second.mapIndexed { i, v -> "${vars[i]} = $v" }
                    "none" -> listOf("No solution")
                    "infinite" -> listOf("Infinitely many solutions")
                    else -> { error = tr("Couldn't solve"); emptyList() }
                }
            }
        }) { Text(tr("Solve")) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (result.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) { result.forEach { Text(it, style = MaterialTheme.typography.titleMedium) } }
            }
            OutlinedButton(onClick = {
                val eq = if (kind > 0) {
                    (0..kind).joinToString(" + ") { i ->
                        val p = kind - i
                        "(${fields[i].ifBlank { "0" }})" + when (p) { 0 -> ""; 1 -> "x"; 2 -> "x²"; 3 -> "x³"; else -> "x⁴" }
                    } + " = 0"
                } else (0 until -kind).joinToString("\n") { r ->
                    (0 until -kind).joinToString(" + ") { c -> "(${fields[r * 5 + c].ifBlank { "0" }})${vars[c]}" } + " = ${fields[r * 5 - kind].ifBlank { "0" }}"
                }
                host.insertText(eq + "\n" + result.joinToString("\n"))
            }) { Text(tr("Write on page")) }
        }
    }
}

// ----------------------------------------------------------------------------------------
// Statistics mode
// ----------------------------------------------------------------------------------------

@Composable
private fun StatsMode(host: ToolHost, opts: CalcOptions) {
    var type by remember { mutableStateOf(0) }
    var typeMenu by remember { mutableStateOf(false) }
    val xs = remember { mutableStateListOf("", "", "", "") }
    val ys = remember { mutableStateListOf("", "", "", "") }
    var result by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { typeMenu = true }) { Text(CasioEngine.REGRESSIONS[type]) }
            DropdownMenu(typeMenu, { typeMenu = false }) {
                CasioEngine.REGRESSIONS.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n) }, onClick = { type = i; typeMenu = false; result = emptyList() }) }
            }
        }
        for (i in xs.indices) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(xs[i], { xs[i] = it }, Modifier.weight(1f), label = { Text("x") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (type != 0) OutlinedTextField(ys[i], { ys[i] = it }, Modifier.weight(1f), label = { Text("y") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { xs += ""; ys += "" }) { Text(tr("+ Row")) }
            OutlinedButton(onClick = { if (xs.size > 1) { xs.removeAt(xs.size - 1); ys.removeAt(ys.size - 1) } }) { Text(tr("− Row")) }
            Button(onClick = {
                error = null
                val rows = xs.indices.filter { xs[it].isNotBlank() }
                val x = rows.map { parseNumber(xs[it], opts) }
                val y = rows.map { parseNumber(ys[it], opts) }
                if (x.any { it == null } || (type != 0 && y.any { it == null })) { error = tr("Check the data"); return@Button }
                CasioEngine.statistics(type, x.map { it!! }, if (type == 0) emptyList() else y.map { it!! }, opts)
                    .onSuccess { result = it }.onFailure { error = it.message; result = emptyList() }
            }) { Text(tr("Calculate")) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (result.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    result.forEach { (n, v) ->
                        Row { Text(n, Modifier.width(72.dp), fontWeight = FontWeight.SemiBold); Text(v) }
                    }
                }
            }
            OutlinedButton(onClick = {
                val key = if (type == 0) listOf("n", "x̄", "σx", "sx", "minX", "Q1", "Med", "Q3", "maxX") else listOf("n", "a", "b", "c", "r")
                val lines = result.filter { it.first in key }.joinToString(", ") { "${it.first} = ${it.second}" }
                host.insertText(CasioEngine.REGRESSIONS[type] + ": " + lines)
            }) { Text(tr("Write key results on page")) }
        }
    }
}

/** Turns a displayed result back into calculator input ("1.2×10⁻⁵" -> "1.2ᴇ−5"). */
private fun resultToInput(r: String): String {
    val i = r.indexOf("×10")
    if (i < 0) return r
    val sup = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    val exp = r.substring(i + 3).map { c -> sup.indexOf(c).let { k -> if (k >= 0) '0' + k else if (c == '⁻') '−' else c } }.joinToString("")
    return r.substring(0, i) + "ᴇ" + exp
}
