package com.advnotes.app.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.ai.AiClients
import com.advnotes.app.ai.MathSolver
import com.advnotes.app.ai.SolveMode
import com.advnotes.app.ink.ToolState
import com.advnotes.app.model.MM
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.PAGE_WIDTH
import com.advnotes.app.model.Page
import com.advnotes.app.model.PaperStyle
import com.advnotes.app.model.TextBox
import com.advnotes.app.sync.SyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AiState {
    data object Idle : AiState
    data class Running(val text: String) : AiState
    data class Done(val text: String) : AiState
    data class Error(val message: String) : AiState
}

data class EditorUi(
    val notebook: Notebook? = null,
    val pageIndex: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as AdvNotesApp
    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui

    /** Pen, highlighter, eraser and shape settings are shared by all notebooks and kept across restarts. */
    private val toolPrefs = app.getSharedPreferences("tool_state", android.content.Context.MODE_PRIVATE)
    private val _tool = MutableStateFlow(
        runCatching { com.advnotes.app.data.AppJson.decodeFromString(ToolState.serializer(), toolPrefs.getString("state", null)!!) }
            .getOrDefault(ToolState())
    )
    val tool: StateFlow<ToolState> = _tool

    private val _ai = MutableStateFlow<AiState>(AiState.Idle)
    val ai: StateFlow<AiState> = _ai
    private var aiJob: Job? = null

    private val undo = HashMap<String, ArrayDeque<Page>>()
    private val redo = HashMap<String, ArrayDeque<Page>>()
    private var saveJob: Job? = null
    private var dirty = false

    fun open(id: String) {
        if (_ui.value.notebook?.id == id) return
        viewModelScope.launch {
            val nb = a.repo.load(id) ?: return@launch
            val savedId = lastPagePrefs.getString(id, null)
            val start = nb.pages.indexOfFirst { it.id == savedId }.takeIf { it >= 0 } ?: 0
            _ui.value = EditorUi(nb, start)
        }
    }

    /** Remembers the page each notebook was left on (device-local, not synced). */
    private val lastPagePrefs = app.getSharedPreferences("last_page", android.content.Context.MODE_PRIVATE)

    init {
        viewModelScope.launch {
            _ui.collect { s ->
                val nb = s.notebook ?: return@collect
                val pid = nb.pages.getOrNull(s.pageIndex)?.id ?: return@collect
                if (lastPagePrefs.getString(nb.id, null) != pid) lastPagePrefs.edit().putString(nb.id, pid).apply()
            }
        }
    }

    val currentPage: Page? get() = _ui.value.notebook?.pages?.getOrNull(_ui.value.pageIndex)

    fun setTool(t: ToolState) {
        if (_tool.value == t) return
        _tool.value = t
        toolPrefs.edit().putString("state", com.advnotes.app.data.AppJson.encodeToString(ToolState.serializer(), t)).apply()
    }

    private fun refreshUndoFlags() {
        val id = currentPage?.id
        _ui.update { it.copy(canUndo = !undo[id].isNullOrEmpty(), canRedo = !redo[id].isNullOrEmpty()) }
    }

    private fun replacePage(p: Page) {
        _ui.update { s ->
            val nb = s.notebook ?: return@update s
            s.copy(notebook = nb.copy(pages = nb.pages.map { if (it.id == p.id) p else it }))
        }
        scheduleSave()
    }

    /** Called by the canvas after every finished edit. */
    fun commit(p: Page) {
        val old = _ui.value.notebook?.pages?.firstOrNull { it.id == p.id } ?: return
        if (old === p) return
        undo.getOrPut(p.id) { ArrayDeque() }.apply { addLast(old); if (size > 200) removeFirst() }
        redo[p.id]?.clear()
        replacePage(p)
        refreshUndoFlags()
    }

    fun undo() {
        val cur = currentPage ?: return
        val prev = undo[cur.id]?.removeLastOrNull() ?: return
        redo.getOrPut(cur.id) { ArrayDeque() }.addLast(cur)
        replacePage(prev); refreshUndoFlags()
    }

    fun redo() {
        val cur = currentPage ?: return
        val next = redo[cur.id]?.removeLastOrNull() ?: return
        undo.getOrPut(cur.id) { ArrayDeque() }.addLast(cur)
        replacePage(next); refreshUndoFlags()
    }

    fun goTo(index: Int) {
        val n = _ui.value.notebook?.pages?.size ?: return
        _ui.update { it.copy(pageIndex = index.coerceIn(0, n - 1)) }
        refreshUndoFlags()
    }

    /** A blank page that keeps the paper of [like] (pages inherit the style of the page next to them). */
    private fun blankLike(like: Page?) = Page(paper = like?.paper)

    fun addPage() = insertPage(before = false)

    /** Inserts a blank page before or after the current one and shows it. */
    fun insertPage(before: Boolean) {
        _ui.update { s ->
            val nb = s.notebook ?: return@update s
            if (nb.lockPages) return@update s
            val at = if (before) s.pageIndex else s.pageIndex + 1
            val pages = nb.pages.toMutableList().apply { add(at, blankLike(nb.pages.getOrNull(s.pageIndex))) }
            s.copy(notebook = nb.copy(pages = pages), pageIndex = at)
        }
        scheduleSave(); refreshUndoFlags()
    }

    /**
     * Swipe navigation: moves by [step] pages (2 for spreads), appending blank pages when
     * swiping forward past the end.
     */
    fun swipe(dir: Int, step: Int) {
        val s = _ui.value
        val nb = s.notebook ?: return
        val base = s.pageIndex - s.pageIndex % step
        val target = base + dir * step
        if (target < 0) {
            // Pulling back past the first page adds pages in front.
            if (nb.lockPages) return
            val extra = List(step) { blankLike(nb.pages.firstOrNull()) }
            _ui.update { it.copy(notebook = nb.copy(pages = extra + nb.pages), pageIndex = 0) }
            scheduleSave(); refreshUndoFlags()
            return
        }
        if (target >= nb.pages.size && nb.lockPages) return
        if (target >= nb.pages.size) {
            val extra = List(target - nb.pages.size + 1) { blankLike(nb.pages.lastOrNull()) }
            _ui.update { it.copy(notebook = nb.copy(pages = nb.pages + extra)) }
            scheduleSave()
        }
        goTo(target)
    }

    /** Appends a blank page at the end without moving. */
    fun appendPage() {
        _ui.update { s ->
            val nb = s.notebook ?: return@update s
            if (nb.lockPages) s else s.copy(notebook = nb.copy(pages = nb.pages + blankLike(nb.pages.lastOrNull())))
        }
        scheduleSave()
    }

    /** Imports PDF/image pages (or another note's pages) after the current page. */
    fun insertPagesFrom(uris: List<android.net.Uri>, onResult: (String?) -> Unit) {
        val nb = _ui.value.notebook ?: return
        viewModelScope.launch {
            try {
                saveNow()
                val r = a.importer.build(uris, nb.id)
                _ui.update { s ->
                    val cur = s.notebook ?: return@update s
                    val pages = cur.pages.toMutableList().apply { addAll(s.pageIndex + 1, r.notebook.pages) }
                    s.copy(notebook = cur.copy(pages = pages), pageIndex = s.pageIndex + 1)
                }
                scheduleSave(); refreshUndoFlags()
                onResult(r.warnings.firstOrNull() ?: "Inserted ${r.notebook.pages.size} page(s)")
            } catch (e: Exception) {
                onResult(e.message ?: "Import failed")
            }
        }
    }

    fun deletePage() {
        _ui.update { s ->
            val nb = s.notebook ?: return@update s
            val gone = nb.pages.getOrNull(s.pageIndex)?.id
            val pages = nb.pages.toMutableList().apply { removeAt(s.pageIndex) }.ifEmpty { listOf(Page()) }
            s.copy(
                notebook = nb.copy(pages = pages, bookmarks = nb.bookmarks.filter { it.pageId != gone }),
                pageIndex = s.pageIndex.coerceAtMost(pages.size - 1),
            )
        }
        scheduleSave(); refreshUndoFlags()
    }

    /** Deletes the pages with these ids, keeping the view on the nearest remaining page. */
    fun deletePages(ids: Set<String>) {
        if (ids.isEmpty()) return
        _ui.update { s ->
            val nb = s.notebook ?: return@update s
            val curId = nb.pages.getOrNull(s.pageIndex)?.id
            val before = nb.pages.take(s.pageIndex).count { it.id in ids }
            val pages = nb.pages.filter { it.id !in ids }.ifEmpty { listOf(Page()) }
            val idx = pages.indexOfFirst { it.id == curId }.takeIf { it >= 0 } ?: (s.pageIndex - before)
            ids.forEach { undo.remove(it); redo.remove(it) }
            s.copy(
                notebook = nb.copy(pages = pages, bookmarks = nb.bookmarks.filter { it.pageId !in ids }),
                pageIndex = idx.coerceIn(0, pages.size - 1),
            )
        }
        scheduleSave(); refreshUndoFlags()
    }

    /**
     * Places a picture on the current page, centred in [view] (the visible part of the page, in page
     * units) and at most 60% of its width. [onPlaced] gets the new element's id so the canvas can select it.
     */
    fun insertImage(uri: android.net.Uri, view: android.graphics.RectF, onPlaced: (String?) -> Unit) =
        insertImage(view, onPlaced) { decodeUpright(uri, 2000) }

    /** Places a picture produced by [source] (run off the main thread), e.g. a chart drawn by a tool. */
    fun insertImage(view: android.graphics.RectF, onPlaced: (String?) -> Unit, source: () -> Bitmap?) {
        val nb = _ui.value.notebook ?: return
        val pageId = currentPage?.id ?: return
        viewModelScope.launch {
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bmp = source() ?: return@runCatching null
                    val png = bmp.hasAlpha()
                    val name = "img-" + com.advnotes.app.model.newId() + if (png) ".png" else ".jpg"
                    val f = a.repo.assetFile(nb.id, name).apply { parentFile?.mkdirs() }
                    f.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
                    Triple(name, bmp.width, bmp.height)
                }.getOrNull()
            }
            if (saved == null) { onPlaced(null); return@launch }
            val (name, bw, bh) = saved
            val cur = _ui.value.notebook?.pages?.firstOrNull { it.id == pageId } ?: return@launch
            val maxW = (view.width() * 0.6f).coerceAtLeast(120f)
            val maxH = (view.height() * 0.6f).coerceAtLeast(120f)
            val k = minOf(maxW / bw, maxH / bh)
            val w = bw * k; val h = bh * k
            val im = com.advnotes.app.model.ImageBox(asset = name, x = view.centerX() - w / 2, y = view.centerY() - h / 2, w = w, h = h)
            if (currentPage?.id == pageId) commit(cur.copy(images = cur.images + im))
            onPlaced(im.id)
        }
    }

    /** Decodes an image the right way up, with its longer side at most [maxSide] pixels. */
    private fun decodeUpright(uri: android.net.Uri, maxSide: Int): Bitmap? {
        val cr = a.contentResolver
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val f = maxSide.toFloat() / maxOf(w, h)
                if (f < 1f) dec.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    enum class PaperScope { ALL, THIS_PAGE, FROM_HERE }

    /**
     * Applies a paper style. FROM_HERE restyles this page and every page after it; new pages take
     * the style of the page they are added next to, so pages added later keep it too.
     */
    fun setPaper(style: PaperStyle, scope: PaperScope) {
        _ui.update { s ->
            val nb = s.notebook ?: return@update s
            when (scope) {
                PaperScope.ALL -> s.copy(notebook = nb.copy(paper = style, pages = nb.pages.map { it.copy(paper = null) }))
                PaperScope.THIS_PAGE -> s.copy(notebook = nb.copy(pages = nb.pages.mapIndexed { i, p -> if (i == s.pageIndex) p.copy(paper = style) else p }))
                PaperScope.FROM_HERE -> s.copy(notebook = nb.copy(pages = nb.pages.mapIndexed { i, p -> if (i >= s.pageIndex) p.copy(paper = style) else p }))
            }
        }
        scheduleSave()
    }

    // ---- bookmark groups (page sidebar) -------------------------------------------------------

    private fun editNotebook(block: (Notebook) -> Notebook) {
        _ui.update { s -> s.copy(notebook = s.notebook?.let(block)) }
        scheduleSave()
    }

    /** Creates a group and returns its id (a blank name is ignored). */
    fun addGroup(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val nb = _ui.value.notebook ?: return null
        val g = com.advnotes.app.model.PageGroup(name = n, color = com.advnotes.app.model.GROUP_COLORS[nb.groups.size % com.advnotes.app.model.GROUP_COLORS.size])
        editNotebook { it.copy(groups = it.groups + g) }
        return g.id
    }

    fun renameGroup(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        editNotebook { nb -> nb.copy(groups = nb.groups.map { if (it.id == id) it.copy(name = n) else it }) }
    }

    fun deleteGroup(id: String) =
        editNotebook { nb -> nb.copy(groups = nb.groups.filter { it.id != id }, bookmarks = nb.bookmarks.filter { it.groupId != id }) }

    /** Adds the page to the group, or removes it if it is already there. */
    fun toggleBookmark(pageId: String, groupId: String) = editNotebook { nb ->
        val has = nb.bookmarks.any { it.pageId == pageId && it.groupId == groupId }
        nb.copy(
            bookmarks = if (has) nb.bookmarks.filterNot { it.pageId == pageId && it.groupId == groupId }
            else nb.bookmarks + com.advnotes.app.model.PageBookmark(pageId, groupId),
        )
    }

    fun setLockPages(locked: Boolean) {
        _ui.update { s -> s.copy(notebook = s.notebook?.copy(lockPages = locked)) }
        scheduleSave()
    }

    fun rename(title: String) {
        _ui.update { s -> s.copy(notebook = s.notebook?.copy(title = title)) }
        scheduleSave()
    }

    fun upsertText(x: Float, y: Float, existing: TextBox?, text: String, size: Float, color: Int) {
        val p = currentPage ?: return
        val texts = when {
            existing != null && text.isBlank() -> p.texts.filter { it.id != existing.id }
            existing != null -> p.texts.map { if (it.id == existing.id) it.copy(text = text, size = size, color = color) else it }
            text.isBlank() -> return
            else -> p.texts + TextBox(x = x, y = y, width = (PAGE_WIDTH - x - 10f * MM).coerceAtLeast(120f), text = text, size = size, color = color)
        }
        commit(p.copy(texts = texts))
    }

    // ---- AI ----------------------------------------------------------------------------

    /** Remembered across solves in this notebook session. */
    var lastSubject = com.advnotes.app.ai.Subject.AUTO

    fun solve(bitmap: Bitmap, mode: SolveMode, extra: String, subject: com.advnotes.app.ai.Subject = lastSubject) {
        lastSubject = subject
        aiJob?.cancel()
        _ai.value = AiState.Running("")
        aiJob = viewModelScope.launch {
            try {
                val provider = a.settings.state.value.provider
                val client = AiClients.forProvider(provider, a.settings, a.chatGpt, a.claudeCode, a.local)
                val req = MathSolver.request(bitmap, mode, extra, subject)
                val text = client.complete(req) { partial -> _ai.value = AiState.Running(partial) }
                _ai.value = if (text.isBlank()) AiState.Error("The model returned an empty answer.") else AiState.Done(text.trim())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ai.value = AiState.Error(e.message ?: e.toString())
            }
        }
    }

    fun cancelAi() { aiJob?.cancel(); _ai.value = AiState.Idle }

    /** Writes the answer onto the page next to (or below) the selection. */
    fun insertAnswer(text: String, near: RectF?, below: Boolean = false) {
        val p = currentPage ?: return
        val margin = 8f * MM
        val (x, y, w) = if (!below && near != null && PAGE_WIDTH - near.right > 70f * MM) {
            Triple(near.right + 4f * MM, near.top, PAGE_WIDTH - near.right - 4f * MM - margin)
        } else {
            val top = (near?.bottom ?: 40f * MM) + 4f * MM
            val left = (near?.left ?: margin).coerceAtMost(PAGE_WIDTH - 90f * MM)
            Triple(left, top, PAGE_WIDTH - left - margin)
        }
        commit(p.copy(texts = p.texts + TextBox(x = x, y = y, width = w, text = text, size = 20f, color = _tool.value.penColor)))
        _ai.value = AiState.Idle
    }

    /** Adds a text box at page position (x, y), e.g. from a tool. */
    fun insertTextAt(text: String, x: Float, y: Float) {
        val p = currentPage ?: return
        if (text.isBlank()) return
        val left = x.coerceIn(8f * MM, PAGE_WIDTH - 60f * MM)
        commit(p.copy(texts = p.texts + TextBox(x = left, y = y.coerceIn(0f, p.height - 20f), width = PAGE_WIDTH - left - 8f * MM, text = text, size = 20f, color = _tool.value.penColor)))
    }

    // ---- tools side panel ----------------------------------------------------------------

    data class PanelState(val visible: Boolean = false, val open: List<String> = emptyList(), val active: String? = null)

    private val _panel = MutableStateFlow(PanelState())
    val panel: StateFlow<PanelState> = _panel

    fun togglePanel() = _panel.update { it.copy(visible = !it.visible) }
    fun closePanel() = _panel.update { it.copy(visible = false) }
    fun openTool(id: String) = _panel.update { it.copy(visible = true, open = if (id in it.open) it.open else it.open + id, active = id) }
    fun showPicker() = _panel.update { it.copy(visible = true, active = null) }
    fun closeTool(id: String) = _panel.update { st ->
        val open = st.open - id
        st.copy(open = open, active = if (st.active == id) open.lastOrNull() else st.active)
    }

    // ---- persistence -------------------------------------------------------------------

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            saveNow()
        }
    }

    suspend fun saveNow() {
        if (!dirty) return
        val nb = _ui.value.notebook ?: return
        dirty = false
        withContext(NonCancellable) {
            val saved = a.repo.save(nb)
            _ui.update { s -> if (s.notebook?.id == saved.id) s.copy(notebook = s.notebook.copy(updatedAt = saved.updatedAt)) else s }
        }
    }

    fun onLeave() {
        viewModelScope.launch {
            saveNow()
            if (a.settings.state.value.autoSync) SyncWorker.syncSoon(a)
        }
    }

    override fun onCleared() {
        if (dirty) {
            val nb = _ui.value.notebook
            if (nb != null) kotlinx.coroutines.runBlocking { a.repo.save(nb) }
        }
        super.onCleared()
    }
}
