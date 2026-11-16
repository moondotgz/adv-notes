package com.advnotes.app.ai

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DownloadState {
    data class Running(val label: String, val progress: Float) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** Keeps model downloads/imports running while the user navigates around the app. */
class ModelDownloads(private val local: LocalLlama) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _state = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = _state
    private val _version = MutableStateFlow(0)
    /** Bumped whenever the set of installed models changes. */
    val version: StateFlow<Int> = _version

    private fun set(name: String, s: DownloadState?) = _state.update { if (s == null) it - name else it + (name to s) }

    fun download(preset: ModelPreset) = run(preset.name) { name ->
        val dir = local.dirFor(name)
        set(name, DownloadState.Running("Projector", 0f))
        local.download(preset.mmprojUrl, java.io.File(dir, "mmproj.gguf")) { set(name, DownloadState.Running("Projector", it)) }
        set(name, DownloadState.Running("Model", 0f))
        local.download(preset.modelUrl, java.io.File(dir, "model.gguf")) { set(name, DownloadState.Running("Model", it)) }
    }

    fun import(name: String, model: Uri, mmproj: Uri) = run(name) { n ->
        val dir = local.dirFor(n)
        set(n, DownloadState.Running("Projector", 0f))
        local.import(mmproj, java.io.File(dir, "mmproj.gguf")) { set(n, DownloadState.Running("Projector", it)) }
        set(n, DownloadState.Running("Model", 0f))
        local.import(model, java.io.File(dir, "model.gguf")) { set(n, DownloadState.Running("Model", it)) }
    }

    fun cancel(name: String) { jobs.remove(name)?.cancel(); set(name, null) }

    private fun run(name: String, block: suspend (String) -> Unit) {
        if (jobs[name]?.isActive == true) return
        jobs[name] = scope.launch {
            try {
                block(name)
                set(name, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                set(name, null)
            } catch (e: Exception) {
                set(name, DownloadState.Failed(e.message ?: e.toString()))
            } finally {
                _version.update { it + 1 }
            }
        }
    }
}
