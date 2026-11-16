package com.advnotes.app.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Per-tool diagnostics for web tools: console output, JS errors, served files and renderer crashes. */
object ToolLog {
    private const val MAX_CHARS = 100_000
    private val logs = HashMap<String, StringBuilder>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Synchronized
    fun add(toolId: String, line: String) {
        val sb = logs.getOrPut(toolId) { StringBuilder() }
        sb.append(clock.format(Date())).append(' ').append(line).append('\n')
        if (sb.length > MAX_CHARS) sb.delete(0, sb.length - MAX_CHARS * 3 / 4)
        _version.value++
    }

    @Synchronized fun text(toolId: String): String = logs[toolId]?.toString().orEmpty()

    @Synchronized
    fun clear(toolId: String) { logs[toolId]?.setLength(0); _version.value++ }
}
