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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
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
fun LibraryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
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

    val driveAuth = rememberDriveAuthLauncher { ok ->
        if (ok) scope.launch { app.drive.sync() }
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notebooks", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (settings.driveAccount != null) {
                        IconButton(onClick = { scope.launch { app.drive.sync() } }, enabled = sync !is SyncStatus.Running) {
                            when (sync) {
                                SyncStatus.Running -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                is SyncStatus.Done -> Icon(Icons.Default.CloudDone, "Synced")
                                is SyncStatus.Error, SyncStatus.NeedsSignIn -> Icon(Icons.Default.CloudOff, "Sync problem")
                                SyncStatus.Idle -> Icon(Icons.Default.CloudSync, "Sync now")
                            }
                        }
                    } else {
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Cloud, "Set up Google Drive") }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New notebook") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (notebooks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No notebooks yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap “New notebook” to start writing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(notebooks, key = { it.id }) { nb ->
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
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
        Box {
            Card(
                shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f),
            ) {
                Box(Modifier.fillMaxSize().background(Color(nb.cover))) {
                    // spine
                    Box(Modifier.fillMaxSize().padding(start = 10.dp).width(2.dp).background(Color(0x33000000)))
                    PaperPreview(
                        nb.paper, nb.paperColor,
                        Modifier.align(Alignment.Center).fillMaxWidth(0.62f).aspectRatio(1.2f),
                        zoom = 3f,
                    )
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Export PDF") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(nb.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${nb.pageCount} page${if (nb.pageCount == 1) "" else "s"} · " +
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.updatedAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

