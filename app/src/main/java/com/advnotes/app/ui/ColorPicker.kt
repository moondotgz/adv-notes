package com.advnotes.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.i18n.tr

/** Colours the user picked with the colour picker, newest first (kept on this device). */
object CustomColors {
    private const val MAX = 8
    private val prefs get() = AdvNotesApp.instance.getSharedPreferences("custom_colors", Context.MODE_PRIVATE)
    val list = mutableStateListOf<Int>().apply {
        runCatching { prefs.getString("list", "")!!.split(',').filter { it.isNotBlank() }.map { it.toLong(16).toInt() } }
            .getOrNull()?.let { addAll(it) }
    }

    /** Remembers an opaque colour (alpha is dropped; the highlighter adds its own). */
    fun add(color: Int) {
        val c = color or 0xFF000000.toInt()
        list.remove(c); list.add(0, c)
        while (list.size > MAX) list.removeAt(list.size - 1)
        prefs.edit().putString("list", list.joinToString(",") { Integer.toHexString(it) }).apply()
    }
}

/** The ＋ button after a row of colour dots. */
@Composable
fun AddColorButton(size: Dp = 30.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Add, tr("More colours…"), Modifier.size(size * 0.6f)) }
}

/**
 * Custom colours after the presets: the recent ones, then ＋ to open the picker. [withAlpha] turns an
 * opaque colour into the one to use (e.g. the highlighter's see-through version).
 */
@Composable
fun CustomColorDots(selected: Int?, size: Dp = 30.dp, withAlpha: (Int) -> Int = { it }, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    CustomColors.list.forEach { c -> val v = withAlpha(c); ColorDot(v, v == selected, size) { onPick(v) } }
    AddColorButton(size) { open = true }
    if (open) ColorPickerDialog(selected ?: 0xFF1F3FBF.toInt(), onDismiss = { open = false }) { c ->
        open = false; CustomColors.add(c); onPick(withAlpha(c))
    }
}

/** Hue/saturation/brightness picker with a hex field. Returns an opaque colour. */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial or 0xFF000000.toInt(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    var hex by remember { mutableStateOf(toHex(color)) }
    var editingHex by remember { mutableStateOf(false) }
    if (!editingHex && hex != toHex(color)) hex = toHex(color)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Pick a colour")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // saturation (left → right) × brightness (top → bottom) for the current hue
                Box(
                    Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            fun set(o: Offset) { editingHex = false; s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f) }
                            detectTapGestures { set(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { ch, _ -> ch.consume(); editingHex = false; s = (ch.position.x / size.width).coerceIn(0f, 1f); v = 1f - (ch.position.y / size.height).coerceIn(0f, 1f) }
                        },
                ) {
                    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f))))))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        val c = Offset(s * size.width, (1f - v) * size.height)
                        drawCircle(Color.White, 11.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                        drawCircle(Color.Black.copy(alpha = 0.5f), 12.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                    }
                }
                // hue
                Box(
                    Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))
                        .pointerInput(Unit) { detectTapGestures { editingHex = false; h = (it.x / size.width).coerceIn(0f, 1f) * 360f } }
                        .pointerInput(Unit) { detectDragGestures { ch, _ -> ch.consume(); editingHex = false; h = (ch.position.x / size.width).coerceIn(0f, 1f) * 360f } },
                ) {
                    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                        drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
                        val x = h / 360f * size.width
                        drawCircle(Color.White, 10.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(color)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    OutlinedTextField(
                        hex, { t ->
                            editingHex = true; hex = t
                            parseHex(t)?.let { c -> val a = FloatArray(3); android.graphics.Color.colorToHSV(c, a); h = a[0]; s = a[1]; v = a[2] }
                        },
                        singleLine = true, label = { Text("Hex") }, modifier = Modifier.width(160.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(tr("Select")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

private fun toHex(c: Int) = "#%06X".format(c and 0xFFFFFF)
private fun parseHex(t: String): Int? {
    val x = t.trim().removePrefix("#")
    val full = if (x.length == 3) x.map { "$it$it" }.joinToString("") else x
    return if (full.length == 6) full.toLongOrNull(16)?.let { (it or 0xFF000000L).toInt() } else null
}
