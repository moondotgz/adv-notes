package com.advnotes.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.advnotes.app.ink.PaperRenderer
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle

/** Small preview of a paper style. [zoom] > 1 shows the top-left part enlarged so lines are visible. */
@Composable
fun PaperPreview(style: PaperStyle, color: PaperColor, modifier: Modifier = Modifier, zoom: Float = 2.2f) {
    Canvas(modifier.clip(RoundedCornerShape(6.dp))) {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            val s = size.width / PAGE_WIDTH * zoom
            nc.clipRect(0f, 0f, size.width, size.height)
            nc.scale(s, s)
            PaperRenderer.draw(nc, style, color)
            nc.restore()
        }
    }
}

@Composable
fun ColorDot(color: Int, selected: Boolean, size: Dp = 30.dp, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
            .clickable(onClick = onClick)
    ) {
        Canvas(Modifier.size(size)) {
            drawCircle(Color(0xFFFFFFFF), radius = this.size.minDimension / 2 - 4.dp.toPx())
            drawCircle(Color(color), radius = this.size.minDimension / 2 - 4.dp.toPx())
        }
    }
}

val PEN_COLORS = listOf(
    0xFF1A1A1A, 0xFF1F3FBF, 0xFFD62828, 0xFF138A36, 0xFF7B2CBF, 0xFFE85D04, 0xFF6C757D, 0xFFFFFFFF,
).map { it.toInt() }

val HIGHLIGHTER_COLORS = listOf(
    0x66FFE14D, 0x6656E39F, 0x66FF7EB6, 0x6674C0FC, 0x66FFA94D, 0x66B197FC,
).map { it.toInt() }
