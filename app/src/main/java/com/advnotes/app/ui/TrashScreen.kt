package com.advnotes.app.ui

import com.advnotes.app.i18n.tr
import com.advnotes.app.i18n.trn

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
                title = { Text(tr("Trash")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
                actions = { if (trashed.isNotEmpty()) TextButton(onClick = { emptying = true }) { Text(tr("Empty trash")) } },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (trashed.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DeleteForever, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.size(12.dp))
                    Text(tr("The trash is empty"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr("Deleted notebooks wait here for {0} days before they are removed for good.", TRASH_DAYS),
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
                            tr("Notebooks here are deleted for good {0} days after you trashed them.", TRASH_DAYS),
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
                                        trn(nb.pageCount, "{0} page", "{0} pages") + " · " + tr("trashed") + " " +
                                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.trashedAt)) +
                                            " · " + if (left <= 1) tr("deleted within a day") else tr("deleted in {0} days", left),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { scope.launch { app.repo.restore(nb.id) } }) { Icon(Icons.Default.RestoreFromTrash, tr("Restore")) }
                                IconButton(onClick = { deleting = nb }) { Icon(Icons.Default.DeleteForever, tr("Delete for good")) }
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
            title = { Text(tr("Delete “{0}” for good?", nb.title)) },
            text = { Text(tr("This can't be undone.")) },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { app.repo.delete(nb.id) } }) { Text(tr("Delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel")) } },
        )
    }
    if (emptying) {
        AlertDialog(
            onDismissRequest = { emptying = false },
            title = { Text(tr("Empty the trash?")) },
            text = { Text(trn(trashed.size, "{0} notebook will be deleted for good. This can't be undone.", "{0} notebooks will be deleted for good. This can't be undone.")) },
            confirmButton = { TextButton(onClick = { emptying = false; scope.launch { app.repo.emptyTrash() } }) { Text(tr("Empty trash")) } },
            dismissButton = { TextButton(onClick = { emptying = false }) { Text(tr("Cancel")) } },
        )
    }
}
