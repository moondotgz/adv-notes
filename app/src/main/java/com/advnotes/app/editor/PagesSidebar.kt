package com.advnotes.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.advnotes.app.ink.Backgrounds
import com.advnotes.app.ink.StrokeRenderer
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.Page
import com.advnotes.app.model.PageGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun renderThumb(nb: Notebook, page: Page, widthPx: Int, pressureCurve: Float, onBgReady: () -> Unit): Bitmap {
    val h = (widthPx * page.height / PAGE_WIDTH).roundToInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = widthPx / PAGE_WIDTH
    c.scale(s, s)
    val bg = page.background?.let { Backgrounds.getThumb(nb.id, it, widthPx * 2, onBgReady) }
    StrokeRenderer(pressureCurve).drawPage(c, page, nb.paper, nb.paperColor, background = bg,
        images = { Backgrounds.getThumb(nb.id, com.advnotes.app.model.PageBackground(it.asset), widthPx, onBgReady) })
    return bmp
}

/**
 * Left-hand list of page previews. Tap a page to go to it; the star bookmarks a page into named groups, and the
 * chips on top filter the list down to one group.
 */
@Composable
fun PagesSidebar(
    nb: Notebook,
    pageIndex: Int,
    pressureCurve: Float,
    onGo: (Int) -> Unit,
    onClose: () -> Unit,
    vm: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf<String?>(null) }          // group id, or null for all pages
    var chooserFor by remember { mutableStateOf<Int?>(null) }          // page index whose groups are being edited
    var newGroup by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PageGroup?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf<Set<String>?>(null) }
    // Forget picks for pages that no longer exist.
    val liveIds = nb.pages.map { it.id }.toSet()
    if (!liveIds.containsAll(picked)) picked = picked intersect liveIds
    val group = nb.groups.firstOrNull { it.id == filter }
    if (filter != null && group == null) filter = null                 // the group was deleted

    val indices = remember(nb.pages, nb.bookmarks, filter) {
        if (filter == null) nb.pages.indices.toList()
        else { val ids = nb.bookmarks.filter { it.groupId == filter }.map { it.pageId }.toSet(); nb.pages.indices.filter { nb.pages[it].id in ids } }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(pageIndex, filter) {
        val at = indices.indexOf(pageIndex)
        if (at >= 0) listState.animateScrollToItem(at)
    }

    Surface(modifier, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selecting) "${picked.size} selected" else "Pages", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (selecting) {
                    IconButton(onClick = { confirmDelete = picked }, Modifier.size(40.dp), enabled = picked.isNotEmpty() && !nb.lockPages) {
                        Icon(Icons.Default.Delete, "Delete selected pages")
                    }
                    TextButton(onClick = { selecting = false; picked = emptySet() }) { Text("Done") }
                } else if (!nb.lockPages) TextButton(onClick = { selecting = true }) { Text("Select") }
                IconButton(onClick = onClose, Modifier.size(40.dp)) { Icon(Icons.Default.Close, "Close pages") }
            }
            LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(filter == null, { filter = null }, label = { Text("All") }) }
                items(nb.groups, key = { it.id }) { g ->
                    FilterChip(
                        filter == g.id, { filter = g.id },
                        label = { Text(g.name, maxLines = 1) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(Color(g.color), CircleShape)) },
                    )
                }
                item { AssistChip(onClick = { newGroup = true }, label = { Text("+ Group") }) }
            }
            if (group != null) {
                val tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${indices.size} page${if (indices.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, softWrap = false, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { renaming = group }, contentPadding = tight) { Text("Rename", maxLines = 1, softWrap = false) }
                    TextButton(onClick = { vm.deleteGroup(group.id) }, contentPadding = tight) { Text("Delete", maxLines = 1, softWrap = false) }
                }
            }
            if (indices.isEmpty()) {
                Text(
                    "No pages in this group yet. Tap the star on a page to add it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(indices, key = { nb.pages[it].id }) { i ->
                    val id = nb.pages[i].id
                    PageItem(
                        nb, i, selected = i == pageIndex, pressureCurve,
                        onGo = { if (selecting) picked = if (id in picked) picked - id else picked + id else onGo(i) },
                        onStar = { chooserFor = i },
                        picked = if (selecting) id in picked else null,
                        onDelete = if (nb.lockPages) null else ({ confirmDelete = setOf(id) }),
                    )
                }
            }
        }
    }

    chooserFor?.let { i ->
        val page = nb.pages.getOrNull(i)
        if (page == null) chooserFor = null
        else GroupChooser(nb, page, i, vm) { chooserFor = null }
    }
    confirmDelete?.let { ids ->
        val n = ids.size
        val label = if (n == 1) "page ${nb.pages.indexOfFirst { it.id in ids } + 1}" else "$n pages"
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete $label?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePages(ids); confirmDelete = null
                    picked = picked - ids; if (selecting && n > 1) selecting = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (newGroup) NameDialog("New group", "e.g. Exam, To review", "") { name ->
        newGroup = false
        if (name != null) vm.addGroup(name)?.let { filter = it }
    }
    renaming?.let { g ->
        NameDialog("Rename group", "Group name", g.name) { name -> renaming = null; if (name != null) vm.renameGroup(g.id, name) }
    }
}

@Composable
private fun PageItem(
    nb: Notebook, index: Int, selected: Boolean, pressureCurve: Float, onGo: () -> Unit, onStar: () -> Unit,
    picked: Boolean? = null, onDelete: (() -> Unit)? = null,
) {
    val page = nb.pages[index]
    val widthPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    var tick by remember { mutableIntStateOf(0) }
    val img by produceState<ImageBitmap?>(null, page, nb.paper, nb.paperColor, tick) {
        value = withContext(Dispatchers.Default) { renderThumb(nb, page, widthPx, pressureCurve) { tick++ }.asImageBitmap() }
    }
    val groups = nb.bookmarks.filter { it.pageId == page.id }.mapNotNull { b -> nb.groups.firstOrNull { it.id == b.groupId } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(PAGE_WIDTH / page.height).clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(
                    if (selected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(6.dp),
                )
                .clickable(onClick = onGo),
        ) {
            img?.let { Image(it, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            IconButton(
                onClick = onStar,
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(30.dp).background(Color.Black.copy(alpha = 0.38f), CircleShape),
            ) {
                Icon(
                    if (groups.isNotEmpty()) Icons.Default.Star else Icons.Default.StarBorder,
                    if (groups.isNotEmpty()) "Edit bookmark groups" else "Bookmark page",
                    Modifier.size(18.dp),
                    tint = groups.firstOrNull()?.let { Color(it.color).copy(alpha = 1f) }?.takeIf { groups.isNotEmpty() } ?: Color.White,
                )
            }
            if (picked != null) {
                if (picked) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
                Icon(
                    if (picked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                    Modifier.align(Alignment.TopStart).padding(5.dp).size(24.dp).background(Color.White, CircleShape),
                    tint = if (picked) MaterialTheme.colorScheme.primary else Color.Gray,
                )
            } else if (onDelete != null) IconButton(
                onClick = onDelete,
                Modifier.align(Alignment.TopStart).padding(3.dp).size(30.dp).background(Color.Black.copy(alpha = 0.38f), CircleShape),
            ) { Icon(Icons.Default.Delete, "Delete page ${index + 1}", Modifier.size(18.dp), tint = Color.White) }
            if (groups.isNotEmpty()) Row(Modifier.align(Alignment.BottomStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                groups.forEach { g -> Box(Modifier.size(9.dp).background(Color(g.color), CircleShape).border(1.dp, Color.White, CircleShape)) }
            }
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupChooser(nb: Notebook, page: Page, index: Int, vm: EditorViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Bookmark page ${index + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (nb.groups.isEmpty()) Text("Make a group first, like “Exam” or “To review”, then pages can be added to it.", style = MaterialTheme.typography.bodySmall)
                nb.groups.forEach { g ->
                    val inGroup = nb.bookmarks.any { it.pageId == page.id && it.groupId == g.id }
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleBookmark(page.id, g.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(inGroup, { vm.toggleBookmark(page.id, g.id) })
                        Box(Modifier.size(10.dp).background(Color(g.color), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(g.name)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("New group") })
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        vm.addGroup(name)?.let { vm.toggleBookmark(page.id, it) }
                        name = ""
                    }) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

@Composable
private fun NameDialog(title: String, hint: String, initial: String, onResult: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(hint) }) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onResult(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel") } },
    )
}
