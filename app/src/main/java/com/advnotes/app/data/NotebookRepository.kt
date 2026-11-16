package com.advnotes.app.data

import android.content.Context
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.NotebookSummary
import com.advnotes.app.model.TRASH_MILLIS
import com.advnotes.app.model.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Sync bookkeeping kept next to the notebooks. */
@Serializable
data class SyncState(
    /** notebookId -> Drive file id */
    val remoteIds: Map<String, String> = emptyMap(),
    /** notebookId -> updatedAt of the version that was last synced */
    val syncedAt: Map<String, Long> = emptyMap(),
    /** notebookIds deleted locally that still need deleting on Drive */
    val tombstones: Set<String> = emptySet(),
    val lastSync: Long = 0,
)

/**
 * Stores each notebook as one JSON file in app-private storage. Writes are atomic
 * (write to temp file, then rename) so a crash never leaves a half-written notebook.
 */
class NotebookRepository(context: Context) {
    private val dir = File(context.filesDir, "notebooks").apply { mkdirs() }
    private val syncFile = File(context.filesDir, "sync_state.json")
    private val mutex = Mutex()

    private val _summaries = MutableStateFlow<List<NotebookSummary>>(emptyList())
    val summaries: StateFlow<List<NotebookSummary>> = _summaries

    private fun file(id: String) = File(dir, "$id.json")
    private val assetRoot = File(context.filesDir, "assets").apply { mkdirs() }

    /** Folder holding a notebook's imported files (PDFs, images). */
    fun assetDir(notebookId: String) = File(assetRoot, notebookId)
    fun assetFile(notebookId: String, name: String) = File(assetDir(notebookId), name)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { AppJson.decodeFromString<Notebook>(f.readText()).summary() }.getOrNull()
        }
        _summaries.value = list.sortedByDescending { it.updatedAt }
    }

    suspend fun load(id: String): Notebook? = withContext(Dispatchers.IO) {
        val f = file(id)
        if (!f.exists()) null else runCatching { AppJson.decodeFromString<Notebook>(f.readText()) }.getOrNull()
    }

    suspend fun loadAll(): List<Notebook> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { AppJson.decodeFromString<Notebook>(f.readText()) }.getOrNull()
        }
    }

    /** Raw JSON for upload. */
    suspend fun rawJson(id: String): String? = withContext(Dispatchers.IO) {
        file(id).takeIf { it.exists() }?.readText()
    }

    suspend fun save(nb: Notebook, touch: Boolean = true): Notebook = withContext(Dispatchers.IO) {
        mutex.withLock {
            val toSave = if (touch) nb.copy(updatedAt = System.currentTimeMillis()) else nb
            val tmp = File(dir, "${nb.id}.json.tmp")
            tmp.writeText(AppJson.encodeToString(Notebook.serializer(), toSave))
            if (!tmp.renameTo(file(nb.id))) {
                file(nb.id).delete(); tmp.renameTo(file(nb.id))
            }
            val s = toSave.summary()
            _summaries.value = (_summaries.value.filter { it.id != nb.id } + s).sortedByDescending { it.updatedAt }
            toSave
        }
    }

    /** Moves a notebook to the trash (it syncs like any edit, so other devices trash it too). */
    suspend fun trash(id: String) { load(id)?.let { save(it.copy(trashedAt = System.currentTimeMillis())) } }

    suspend fun restore(id: String) { load(id)?.let { save(it.copy(trashedAt = null)) } }

    /** Deletes notebooks that have been in the trash for more than [TRASH_DAYS] days. Returns how many. */
    suspend fun purgeTrash(now: Long = System.currentTimeMillis()): Int {
        val old = _summaries.value.filter { s -> s.trashedAt?.let { now - it > TRASH_MILLIS } == true }
        old.forEach { delete(it.id) }
        return old.size
    }

    suspend fun emptyTrash() { _summaries.value.filter { it.trashedAt != null }.forEach { delete(it.id) } }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file(id).delete()
            assetDir(id).deleteRecursively()
            _summaries.value = _summaries.value.filter { it.id != id }
        }
        updateSyncState { st ->
            if (st.remoteIds.containsKey(id)) st.copy(tombstones = st.tombstones + id) else st
        }
    }

    /** Delete without leaving a tombstone (used when the deletion came from Drive). */
    suspend fun deleteLocalOnly(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file(id).delete()
            assetDir(id).deleteRecursively()
            _summaries.value = _summaries.value.filter { it.id != id }
        }
    }

    suspend fun syncState(): SyncState = withContext(Dispatchers.IO) {
        if (!syncFile.exists()) SyncState()
        else runCatching { AppJson.decodeFromString<SyncState>(syncFile.readText()) }.getOrDefault(SyncState())
    }

    suspend fun updateSyncState(block: (SyncState) -> SyncState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cur = if (!syncFile.exists()) SyncState()
            else runCatching { AppJson.decodeFromString<SyncState>(syncFile.readText()) }.getOrDefault(SyncState())
            syncFile.writeText(AppJson.encodeToString(SyncState.serializer(), block(cur)))
        }
    }
}
