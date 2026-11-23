package com.advnotes.app.tools.market

import com.advnotes.app.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.tools.ToolRegistry
import com.advnotes.app.tools.WebTool
import kotlinx.coroutines.launch

/** Full-screen browser for the tool marketplace. [onOpen] opens an installed tool (optional). */
@Composable
fun MarketplaceDialog(onDismiss: () -> Unit, onOpen: ((String) -> Unit)? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MarketplaceScreen(onBack = onDismiss, onOpen = onOpen?.let { o -> { id: String -> onDismiss(); o(id) } })
    }
}

private sealed interface Load {
    data object Loading : Load
    data class Ready(val index: Marketplace.Index) : Load
    data class Failed(val message: String) : Load
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(onBack: () -> Unit, onOpen: ((String) -> Unit)? = null) {
    val app = AdvNotesApp.instance
    val tools by app.tools.tools.collectAsState()
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val progress = remember { mutableStateMapOf<String, Float>() }
    val errors = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(refresh) {
        load = Load.Loading
        load = try { Load.Ready(Marketplace.index(force = refresh > 0)) } catch (e: Exception) { Load.Failed(e.message ?: "Couldn't load the marketplace") }
    }

    fun installed(id: String) = (tools.firstOrNull { it.id == id } as? WebTool)?.takeIf { it.installed }?.manifest?.version

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Tool marketplace")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
                actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, tr("Refresh")) } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (val l = load) {
                Load.Loading -> CircularProgressIndicator(Modifier.padding(48.dp))
                is Load.Failed -> Column(Modifier.padding(32.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(12.dp))
                    Text(l.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        tr("Check your connection and try again. Tools come from github.com/{0}.", Marketplace.REPO),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(16.dp))
                    FilledTonalButton(onClick = { refresh++ }) { Text(tr("Try again")) }
                }
                is Load.Ready -> {
                    val categories = l.index.tools.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
                    val shown = l.index.tools.filter { e ->
                        (category == null || e.category == category) &&
                            (query.isBlank() || listOf(e.name, e.description, e.localName, e.localDescription, e.author, e.category).any { it.contains(query.trim(), ignoreCase = true) })
                    }
                    LazyColumn(
                        Modifier.fillMaxSize().widthIn(max = 760.dp),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            TextField(
                                query, { query = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text(tr("Search tools")) },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                singleLine = true, shape = RoundedCornerShape(28.dp),
                                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            )
                            if (categories.size > 1) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(category == null, { category = null }, { Text(tr("All")) })
                                categories.forEach { c -> FilterChip(category == c, { category = if (category == c) null else c }, { Text(c) }) }
                            }
                        }
                        if (shown.isEmpty()) item {
                            Text(
                                if (l.index.tools.isEmpty()) tr("No tools published yet.") else tr("No tools match."),
                                Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(shown, key = { it.id }) { e ->
                            val have = installed(e.id)
                            ToolCard(
                                e, have, progress[e.id], errors[e.id],
                                onInstall = {
                                    errors.remove(e.id); progress[e.id] = 0f
                                    scope.launch {
                                        try { app.tools.install(e) { d, t -> progress[e.id] = if (t > 0) d.toFloat() / t else 0f } }
                                        catch (x: Exception) { errors[e.id] = x.message ?: "Install failed" }
                                        finally { progress.remove(e.id) }
                                    }
                                },
                                onRemove = { app.tools.uninstall(e.id) },
                                onOpen = onOpen?.let { o -> { o(e.id) } },
                            )
                        }
                        item {
                            Text(
                                tr("Tools are web pages that run in a sandbox next to your notes. Anyone can publish one: see github.com/{0}.", Marketplace.REPO),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    e: Marketplace.Entry,
    installed: String?,
    progress: Float?,
    error: String?,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val update = installed != null && Marketplace.compareVersions(e.version, installed) > 0
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(ToolRegistry.iconFor(e.icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.localName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(e.author.ifBlank { null }, "v${e.version}", Marketplace.sizeLabel(e.size), e.category.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (e.localDescription.isNotBlank()) Text(e.localDescription, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            if (progress != null) {
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 12.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (installed != null) {
                        if (!update) Text(tr("Installed"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        else Text(tr("Update: v{0} → v{1}", installed, e.version), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onRemove) { Text(tr("Remove")) }
                        if (update) Button(onClick = onInstall) { Text(tr("Update")) }
                        else if (onOpen != null) FilledTonalButton(onClick = onOpen) { Text(tr("Open")) }
                    } else {
                        Button(onClick = onInstall) { Text(tr("Install")) }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
