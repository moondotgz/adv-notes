package com.advnotes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.model.NotebookSummary
import com.advnotes.app.model.TRASH_DAYS
import com.advnotes.app.model.TRASH_MILLIS
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Days left before a trashed notebook is deleted for good (at least 1 while it still exists). */
fun trashDaysLeft(trashedAt: Long, now: Long = System.currentTimeMillis()): Int =
    (((trashedAt + TRASH_MILLIS) - now + 86_399_999L) / 86_400_000L).toInt().coerceIn(0, TRASH_DAYS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val app = AdvNotesApp.instance
    val all by app.repo.summaries.collectAsState()
    val trashed = remember(all) { all.filter { it.trashedAt != null }.sortedByDescending { it.trashedAt } }
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<NotebookSummary?>(null) }
    var emptying by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (trashed.isNotEmpty()) TextButton(onClick = { emptying = true }) { Text("Empty trash") } },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (trashed.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DeleteForever, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.size(12.dp))
                    Text("The trash is empty", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Deleted notebooks wait here for $TRASH_DAYS days before they are removed for good.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    item {
                        Text(
                            "Notebooks here are deleted for good $TRASH_DAYS days after you trashed them.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(trashed, key = { it.id }) { nb ->
                        val left = trashDaysLeft(nb.trashedAt!!)
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(width = 10.dp, height = 44.dp).background(Color(nb.cover), RoundedCornerShape(5.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(nb.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                    Text(
                                        "${nb.pageCount} page${if (nb.pageCount == 1) "" else "s"} · trashed " +
                                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.trashedAt)) +
                                            " · " + if (left <= 1) "deleted within a day" else "deleted in $left days",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { scope.launch { app.repo.restore(nb.id) } }) { Icon(Icons.Default.RestoreFromTrash, "Restore") }
                                IconButton(onClick = { deleting = nb }) { Icon(Icons.Default.DeleteForever, "Delete for good") }
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { nb ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${nb.title}” for good?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { app.repo.delete(nb.id) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (emptying) {
        AlertDialog(
            onDismissRequest = { emptying = false },
            title = { Text("Empty the trash?") },
            text = { Text("${trashed.size} notebook${if (trashed.size == 1) "" else "s"} will be deleted for good. This can't be undone.") },
            confirmButton = { TextButton(onClick = { emptying = false; scope.launch { app.repo.emptyTrash() } }) { Text("Empty trash") } },
            dismissButton = { TextButton(onClick = { emptying = false }) { Text("Cancel") } },
        )
    }
}
