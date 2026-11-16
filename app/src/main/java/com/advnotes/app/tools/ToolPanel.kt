package com.advnotes.app.tools

import androidx.compose.material.icons.filled.Storefront
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.advnotes.app.editor.EditorViewModel

/**
 * The side panel next to the page. Open tools stay composed while you switch tabs, so a web
 * tool's page or a calculator's input survives switching back and forth.
 */
@Composable
fun ToolPanel(
    state: EditorViewModel.PanelState,
    tools: List<NotebookTool>,
    hostFor: (NotebookTool) -> ToolHost,
    onOpen: (String) -> Unit,
    onCloseTool: (String) -> Unit,
    onPicker: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    grip: (@Composable () -> Unit)? = null,
) {
    var logFor by remember { mutableStateOf<String?>(null) }
    logFor?.let { ToolLogDialog(it) { logFor = null } }
    Surface(modifier, tonalElevation = 2.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                if (grip != null) Box(Modifier.align(Alignment.CenterStart)) { grip() }
                // tool tabs, centred
                Row(
                    Modifier.align(Alignment.Center).padding(horizontal = 48.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (id in state.open) {
                        val t = tools.firstOrNull { it.id == id } ?: continue
                        FilledTonalIconToggleButton(checked = state.active == id, onCheckedChange = { onOpen(id) }) {
                            Icon(t.icon, t.name)
                        }
                    }
                    IconButton(onClick = onPicker) { Icon(Icons.Default.Add, "Open a tool") }
                    state.active?.let { id -> tools.firstOrNull { it.id == id } }?.let { t ->
                        if (t is WebTool) IconButton(onClick = { logFor = t.id }) { Icon(Icons.Default.BugReport, "Tool log") }
                        IconButton(onClick = { onCloseTool(t.id) }) { Icon(Icons.Default.Close, "Close ${t.name}") }
                    }
                }
                IconButton(onClick = onHide, Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Default.KeyboardArrowDown, "Hide panel") }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                for (id in state.open) {
                    val t = tools.firstOrNull { it.id == id } ?: continue
                    key(id) {
                        // Hidden tools keep their full size but are placed off-screen: WebViews shrunk to
                        // 0dp keep drawing their last frame over whatever is shown instead.
                        val shown = state.active == id
                        Box(Modifier.fillMaxSize().layout { m, c ->
                            val p = m.measure(c)
                            layout(p.width, p.height) { p.place(if (shown) 0 else 100_000, 0) }
                        }.then(if (shown) Modifier else Modifier.alpha(0f))) {
                            t.Content(hostFor(t))
                        }
                    }
                }
                if (state.active == null) ToolPicker(tools, onOpen)
            }
        }
    }
}

@Composable
private fun ToolPicker(tools: List<NotebookTool>, onOpen: (String) -> Unit) {
    var market by remember { mutableStateOf(false) }
    if (market) com.advnotes.app.tools.market.MarketplaceDialog(onDismiss = { market = false }, onOpen = onOpen)
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        item {
            Text("Tools", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            Text(
                "Little apps that sit next to your notes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.size(8.dp))
        }
        items(tools, key = { it.id }) { t ->
            ListItem(
                headlineContent = { Text(t.name) },
                supportingContent = { Text(t.description, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(t.icon, null) },
                modifier = Modifier.clickable { onOpen(t.id) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Get more tools") },
                supportingContent = { Text("PDF reader and more from the marketplace") },
                leadingContent = { Icon(androidx.compose.material.icons.Icons.Default.Storefront, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { market = true },
            )
        }
    }
}


@Composable
private fun ToolLogDialog(toolId: String, onDismiss: () -> Unit) {
    val version by ToolLog.version.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val text = remember(version) { ToolLog.text(toolId) }
    val scroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(version) { scroll.scrollTo(scroll.maxValue) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tool log") },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(scroll)) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text.ifBlank { "Nothing logged yet." },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row {
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text("Copy") }
                TextButton(onClick = { ToolLog.clear(toolId) }) { Text("Clear") }
            }
        },
    )
}
