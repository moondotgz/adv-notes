package com.advnotes.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.export.PdfExporter
import com.advnotes.app.model.COVER_COLORS
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.NotebookSummary
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.PaperStyle
import com.advnotes.app.sync.SyncStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    sharedFiles: List<android.net.Uri> = emptyList(),
    onShareConsumed: () -> Unit = {},
) {
    val app = AdvNotesApp.instance
    val notebooks by app.repo.summaries.collectAsState()
    val sync by app.drive.status.collectAsState()
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NotebookSummary?>(null) }
    var deleting by remember { mutableStateOf<NotebookSummary?>(null) }

    var importing by remember { mutableStateOf(false) }
    // PDFs wait here for "how should new pages look?" before they are imported.
    class PendingImport(val uris: List<android.net.Uri>, val done: () -> Unit)
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }

    suspend fun runImport(uris: List<android.net.Uri>, pdf: com.advnotes.app.importer.PdfImportOptions?, done: () -> Unit) {
        importing = true
        try {
            val result = app.importer.importShared(uris, pdf)
            done()
            when {
                result.notebooks.isEmpty() -> snackbar.showSnackbar(result.errors.firstOrNull() ?: "Import failed")
                result.notebooks.size == 1 && result.errors.isEmpty() -> {
                    result.notebooks[0].warnings.firstOrNull()?.let { scope.launch { snackbar.showSnackbar(it) } }
                    onOpen(result.notebooks[0].notebook.id)
                }
                else -> snackbar.showSnackbar(
                    "Imported ${result.notebooks.size} notebook(s)" + (result.errors.firstOrNull()?.let { " · $it" } ?: "")
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            done()
            snackbar.showSnackbar(e.message ?: "Import failed")
        } finally {
            importing = false
        }
    }

    /** Starts an import; asks about the paper for new pages first when any file is a PDF. */
    suspend fun beginImport(uris: List<android.net.Uri>, done: () -> Unit = {}) {
        if (uris.any { app.importer.isPdf(it) }) pendingImport = PendingImport(uris, done)
        else runImport(uris, null, done)
    }

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) scope.launch { beginImport(uris) } }

    LaunchedEffect(sharedFiles) {
        if (sharedFiles.isNotEmpty()) beginImport(sharedFiles, onShareConsumed)
    }

    val driveAuth = rememberDriveAuthLauncher { ok ->
        if (ok) scope.launch { app.drive.sync() }
    }

    var update by remember { mutableStateOf<com.advnotes.app.update.Update?>(null) }
    LaunchedEffect(Unit) {
        if (app.settings.state.value.checkUpdates) update = com.advnotes.app.update.Updater.checkQuietly()
    }
    update?.let { UpdateDialog(it) { update = null } }

    LaunchedEffect(Unit) {
        app.repo.refresh()
        if (settings.autoSync) app.drive.sync()
    }
    LaunchedEffect(sync) {
        when (val s = sync) {
            is SyncStatus.Error -> snackbar.showSnackbar("Sync failed: ${s.message}")
            SyncStatus.NeedsSignIn -> {
                val r = snackbar.showSnackbar("Google Drive needs permission again", actionLabel = "Reconnect")
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) driveAuth()
            }
            else -> Unit
        }
    }

    var query by remember { mutableStateOf("") }
    var byName by remember { mutableStateOf(false) }
    val shown = remember(notebooks, query, byName) {
        notebooks.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
            .let { l -> if (byName) l.sortedBy { it.title.lowercase() } else l.sortedByDescending { it.updatedAt } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New notebook") },
                shape = MaterialTheme.shapes.large,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(164.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 8.dp, 20.dp, 112.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Notebooks", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                when {
                                    notebooks.isEmpty() -> "Nothing here yet"
                                    else -> "${notebooks.size} notebook${if (notebooks.size == 1) "" else "s"}" +
                                        ((sync as? SyncStatus.Done)?.let { " · synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.at)) } ?: "")
                                },
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FileOpen, "Import (PDF, images, Samsung Notes, Xournal++)")
                        }
                        if (settings.driveAccount != null) {
                            FilledTonalIconButton(onClick = { scope.launch { app.drive.sync() } }, enabled = sync !is SyncStatus.Running) {
                                when (sync) {
                                    SyncStatus.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    is SyncStatus.Done -> Icon(Icons.Default.CloudDone, "Synced")
                                    is SyncStatus.Error, SyncStatus.NeedsSignIn -> Icon(Icons.Default.CloudOff, "Sync problem")
                                    SyncStatus.Idle -> Icon(Icons.Default.CloudSync, "Sync now")
                                }
                            }
                        }
                        FilledTonalIconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    }
                    if (notebooks.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SearchPill(query, { query = it }, Modifier.weight(1f))
                            FilterChip(!byName, { byName = false }, label = { Text("Recent") })
                            FilterChip(byName, { byName = true }, label = { Text("A–Z") })
                        }
                    }
                }
            }
            if (notebooks.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    EmptyLibrary(onNew = { creating = true }, onImport = { importLauncher.launch(arrayOf("*/*")) })
                }
            }
            items(shown, key = { it.id }) { nb ->
                NotebookCard(
                    nb,
                    onClick = { onOpen(nb.id) },
                    onEdit = { editing = nb },
                    onExport = {
                        scope.launch {
                            app.repo.load(nb.id)?.let {
                                PdfExporter.share(ctx, PdfExporter.export(ctx, it, settings.pressureCurve))
                            }
                        }
                    },
                    onDelete = { deleting = nb },
                )
            }
        }
    }

    if (creating) {
        NotebookDialog(null) { title, paper, color, cover ->
            creating = false
            if (title != null) scope.launch {
                val nb = app.repo.save(Notebook(title = title, paper = paper, paperColor = color, cover = cover))
                onOpen(nb.id)
            }
        }
    }
    editing?.let { s ->
        NotebookDialog(s) { title, paper, color, cover ->
            editing = null
            if (title != null) scope.launch {
                app.repo.load(s.id)?.let { app.repo.save(it.copy(title = title, paper = paper, paperColor = color, cover = cover)) }
            }
        }
    }
    pendingImport?.let { p ->
        var paper by remember(p) { mutableStateOf(runCatching { PaperStyle.valueOf(settings.pdfPaper) }.getOrDefault(PaperStyle.NARROW_GRID)) }
        var addPages by remember(p) { mutableStateOf(!settings.pdfLockPages) }
        AlertDialog(
            onDismissRequest = { pendingImport = null; p.done() },
            title = { Text(if (p.uris.size > 1) "Import PDFs" else "Import PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "The imported pages keep the PDF's own look, so a Samsung Notes export keeps its note style.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Allow adding new pages", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (addPages) "New pages use this Adv Notes paper:" else "The notebook keeps exactly the PDF's pages.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(addPages, { addPages = it })
                    }
                    if (addPages) PaperPicker(paper) { paper = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.settings.update { it.copy(pdfPaper = paper.name, pdfLockPages = !addPages) }
                    pendingImport = null
                    scope.launch { runImport(p.uris, com.advnotes.app.importer.PdfImportOptions(paper, lockPages = !addPages), p.done) }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null; p.done() }) { Text("Cancel") } },
        )
    }
    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importing…") },
            text = { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${s.title}”?") },
            text = { Text("This removes the notebook and its ${s.pageCount} page(s)" + if (settings.driveAccount != null) ", also from Google Drive at the next sync." else ".") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { app.repo.delete(s.id) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(nb: NotebookSummary, onClick: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val base = nb.cover
    val light = Color(androidx.core.graphics.ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), 0.18f))
    val dark = Color(androidx.core.graphics.ColorUtils.blendARGB(base, 0xFF000000.toInt(), 0.28f))
    Column(Modifier.clip(MaterialTheme.shapes.large).combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .shadow(6.dp, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 22.dp, bottomEnd = 22.dp))
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 22.dp, bottomEnd = 22.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(light, Color(base), dark))),
            ) {
                // spine
                Box(Modifier.fillMaxHeight().width(14.dp).background(Color(0x22000000)))
                Box(Modifier.fillMaxHeight().padding(start = 14.dp).width(1.dp).background(Color(0x33FFFFFF)))
                // paper label
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    shadowElevation = 2.dp,
                    color = Color(nb.paperColor.argb),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp, start = 26.dp, end = 14.dp)
                        .fillMaxWidth().aspectRatio(1.35f),
                ) { PaperPreview(nb.paper, nb.paperColor, Modifier.fillMaxSize(), zoom = 3f) }
                Text(
                    "${nb.pageCount} page${if (nb.pageCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium, color = Color(0xEEFFFFFF),
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 12.dp),
                )
                IconButton(onClick = { menu = true }, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Icon(Icons.Default.MoreVert, "More", tint = Color(0xEEFFFFFF))
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Export PDF") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(nb.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.updatedAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SearchPill(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.height(48.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text("Search notebooks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.text.BasicTextField(
                    value, onChange, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear search") }
        }
    }
}

@Composable
private fun EmptyLibrary(onNew: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(112.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(20.dp))
        Text("Start your first notebook", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Write with your S Pen on grid, Oxford or plain paper — or bring in a PDF, images,\na Samsung Notes or a Xournal++ file.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Button(onClick = onNew) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New notebook") }
            androidx.compose.material3.OutlinedButton(onClick = onImport) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import") }
        }
    }
}

/** Create/edit dialog. Calls [onDone] with title == null when cancelled. */
@Composable
fun NotebookDialog(
    existing: NotebookSummary?,
    onDone: (title: String?, PaperStyle, PaperColor, Int) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var paper by remember { mutableStateOf(existing?.paper ?: PaperStyle.NARROW_GRID) }
    var color by remember { mutableStateOf(existing?.paperColor ?: PaperColor.WHITE) }
    var cover by remember { mutableStateOf(existing?.cover ?: COVER_COLORS.random()) }
    AlertDialog(
        onDismissRequest = { onDone(null, paper, color, cover) },
        title = { Text(if (existing == null) "New notebook" else "Edit notebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Paper", style = MaterialTheme.typography.labelLarge)
                PaperPicker(paper) { paper = it }
                Text("Paper colour", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperColor.entries.forEach { c ->
                        FilterChip(selected = c == color, onClick = { color = c }, label = { Text(c.label) })
                    }
                }
                Text("Cover", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(COVER_COLORS) { c -> ColorDot(c, c == cover) { cover = c } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(title.trim().ifEmpty { "Untitled" }, paper, color, cover) }) {
                Text(if (existing == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null, paper, color, cover) }) { Text("Cancel") } },
    )
}

@Composable
fun PaperPicker(selected: PaperStyle, onPick: (PaperStyle) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(PaperStyle.entries) { st ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val border = if (st == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                Box(
                    Modifier
                        .size(64.dp, 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(border)
                        .padding(if (st == selected) 3.dp else 1.dp)
                        .clickable { onPick(st) }
                ) {
                    PaperPreview(st, PaperColor.WHITE, Modifier.fillMaxSize(), zoom = 3.2f)
                }
                Spacer(Modifier.height(4.dp))
                Text(st.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

