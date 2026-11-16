package com.advnotes.app.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
) {
    Surface(modifier, tonalElevation = 2.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    for (id in state.open) {
                        val t = tools.firstOrNull { it.id == id } ?: continue
                        FilledTonalIconToggleButton(checked = state.active == id, onCheckedChange = { onOpen(id) }) {
                            Icon(t.icon, t.name)
                        }
                    }
                    IconButton(onClick = onPicker) { Icon(Icons.Default.Add, "Open a tool") }
                }
                state.active?.let { id -> tools.firstOrNull { it.id == id } }?.let { t ->
                    Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp))
                    IconButton(onClick = { onCloseTool(t.id) }) { Icon(Icons.Default.Close, "Close ${t.name}") }
                }
                IconButton(onClick = onHide) { Icon(Icons.Default.KeyboardArrowDown, "Hide panel") }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                for (id in state.open) {
                    val t = tools.firstOrNull { it.id == id } ?: continue
                    key(id) {
                        Box(if (state.active == id) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
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
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Tools", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            Text(
                "Little apps that sit next to your notes. More can be installed in Settings → Tools.",
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
    }
}

