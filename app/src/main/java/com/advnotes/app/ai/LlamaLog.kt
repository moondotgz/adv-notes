package com.advnotes.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log of the on-device runner: llama.cpp's own output plus the app's load/generate events. */
object LlamaLog {
    private const val MAX_CHARS = 200_000
    private val sb = StringBuilder()
    private val _version = MutableStateFlow(0)
    /** Changes whenever the log does, so a viewer can refresh. */
    val version: StateFlow<Int> = _version

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun appendRaw(text: String) {
        sb.append(text)
        if (sb.length > MAX_CHARS) sb.delete(0, sb.length - MAX_CHARS * 3 / 4)
        _version.value++
    }

    /** One line from the app itself, timestamped. */
    fun event(line: String) {
        val nl = if (sb.isNotEmpty() && sb.last() != '\n') "\n" else ""
        appendRaw("$nl[${clock.format(Date())}] app: $line\n")
    }

    @Synchronized fun text(): String = sb.toString()

    @Synchronized
    fun clear() { sb.setLength(0); _version.value++ }
}
