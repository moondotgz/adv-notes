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

    private val _tool = MutableStateFlow(ToolState())
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
            _ui.value = EditorUi(nb, 0)
        }
    }

    val currentPage: Page? get() = _ui.value.notebook?.pages?.getOrNull(_ui.value.pageIndex)

    fun setTool(t: ToolState) { _tool.value = t }

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

    fun addPage() {
        _ui.update { s ->
            val nb = s.notebook ?: return@update s
            val pages = nb.pages.toMutableList().apply { add(s.pageIndex + 1, Page()) }
            s.copy(notebook = nb.copy(pages = pages), pageIndex = s.pageIndex + 1)
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
        if (target < 0) return
        if (target >= nb.pages.size) {
            val extra = List(target - nb.pages.size + 1) { Page() }
            _ui.update { it.copy(notebook = nb.copy(pages = nb.pages + extra)) }
            scheduleSave()
        }
        goTo(target)
    }

    /** Appends a blank page at the end without moving. */
    fun appendPage() {
        _ui.update { s -> val nb = s.notebook ?: return@update s; s.copy(notebook = nb.copy(pages = nb.pages + Page())) }
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
            val pages = nb.pages.toMutableList().apply { removeAt(s.pageIndex) }.ifEmpty { listOf(Page()) }
            s.copy(notebook = nb.copy(pages = pages), pageIndex = s.pageIndex.coerceAtMost(pages.size - 1))
        }
        scheduleSave(); refreshUndoFlags()
    }

    fun setPaper(style: PaperStyle, wholeNotebook: Boolean) {
        _ui.update { s ->
            val nb = s.notebook ?: return@update s
            if (wholeNotebook) s.copy(notebook = nb.copy(paper = style, pages = nb.pages.map { it.copy(paper = null) }))
            else s.copy(notebook = nb.copy(pages = nb.pages.mapIndexed { i, p -> if (i == s.pageIndex) p.copy(paper = style) else p }))
        }
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
                val client = AiClients.forProvider(provider, a.settings, a.chatGpt, a.local)
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
