package com.advnotes.app.tools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.advnotes.app.ai.ChatMessage
import com.advnotes.app.ai.MathSolver
import com.advnotes.app.ai.VisionRequest
import com.advnotes.app.data.AppJson
import com.advnotes.app.model.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** General-purpose AI chat for everything the solver doesn't cover. */
object AiChatTool : NotebookTool {
    override val id = "aichat"
    override val name = "AI chat"
    override val description = "Ask anything: explanations, essays, languages, quizzes, flashcards; can look at your selection or page"
    override val icon = Icons.AutoMirrored.Filled.Chat

    private const val SYSTEM = """You are a helpful study assistant inside a handwriting note-taking app.
Help with anything the student asks: explanations, summaries, essays and writing feedback, languages and translation,
quizzes and flashcards, planning, coding. If an image is attached it is part of their notebook (handwriting or a printed page).
Answer in plain text without Markdown headings, tables or LaTeX; use "•" for lists and Unicode for math (x², √, π, →).
Be concise unless asked for detail. Answer in the language the student writes in."""

    @Serializable
    data class Msg(val user: Boolean, val text: String, val attached: String? = null)

    @Serializable
    data class Chat(val id: String = newId(), val title: String = "New chat", val messages: List<Msg> = emptyList(), val updated: Long = System.currentTimeMillis())

    private val chatsSer = ListSerializer(Chat.serializer())

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content(host: ToolHost) {
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current
        val chats = remember {
            mutableStateListOf<Chat>().apply { runCatching { addAll(AppJson.decodeFromString(chatsSer, host.storage.get("chats")!!)) } }
        }
        var current by remember { mutableStateOf(chats.maxByOrNull { it.updated } ?: Chat()) }
        var input by remember { mutableStateOf("") }
        var attach by remember { mutableStateOf<String?>(null) }   // null, "selection", "page"
        var streaming by remember { mutableStateOf<String?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var historyMenu by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()

        fun save(c: Chat) {
            current = c
            val i = chats.indexOfFirst { it.id == c.id }
            if (i >= 0) chats[i] = c else chats.add(c)
            while (chats.size > 30) chats.remove(chats.minByOrNull { it.updated })
            host.storage.set("chats", AppJson.encodeToString(chatsSer, chats.toList()))
        }

        fun send(text: String, attachment: String? = attach) {
            if (text.isBlank() || streaming != null) return
            error = null
            val image = when (attachment) {
                "selection" -> host.selectionImage() ?: run { error = "Select something with the lasso first."; return }
                "page" -> host.pageImage()
                else -> null
            }
            val msgs = current.messages + Msg(true, text.trim(), attachment)
            val title = if (current.messages.isEmpty()) text.trim().take(40) else current.title
            save(current.copy(messages = msgs, title = title, updated = System.currentTimeMillis()))
            input = ""; attach = null
            streaming = ""
            job = scope.launch {
                try {
                    // Only the newest turn carries the image; earlier ones are text.
                    val history = msgs.mapIndexed { i, m ->
                        ChatMessage(m.user, m.text, if (i == msgs.size - 1 && image != null) listOf(MathSolver.png(image)) else emptyList())
                    }
                    val answer = host.ai().complete(VisionRequest(SYSTEM, history)) { streaming = it }
                    save(current.copy(messages = current.messages + Msg(false, answer.trim()), updated = System.currentTimeMillis()))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    streaming?.takeIf { it.isNotBlank() }?.let { save(current.copy(messages = current.messages + Msg(false, "$it …"))) }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                }
                streaming = null
            }
        }

        LaunchedEffect(current.messages.size, streaming != null) {
            val n = current.messages.size + (if (streaming != null) 1 else 0)
            if (n > 0) listState.animateScrollToItem(n - 1)
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(host.aiLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { historyMenu = true }, enabled = chats.isNotEmpty()) { Icon(Icons.Default.History, "Past chats") }
                    DropdownMenu(historyMenu, { historyMenu = false }, Modifier.heightIn(max = 400.dp)) {
                        chats.sortedByDescending { it.updated }.forEach { c ->
                            DropdownMenuItem(text = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { current = c; historyMenu = false })
                        }
                    }
                }
                IconButton(onClick = { job?.cancel(); current = Chat(); error = null }) { Icon(Icons.Default.Add, "New chat") }
            }
            HorizontalDivider()

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.messages.isEmpty() && streaming == null) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ask anything — this chat can also look at your lasso selection or the visible page.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf(
                            "Explain my selection simply" to "selection",
                            "Quiz me on this page with 5 questions" to "page",
                            "Make flashcards (front – back) from this page" to "page",
                            "Translate my selection into English" to "selection",
                            "Check the grammar of my selection" to "selection",
                        ).forEach { (p, a) -> AssistChip(onClick = { send(p, a) }, label = { Text(p) }) }
                    }
                }
                itemsIndexed(current.messages) { _, m -> Bubble(m.user, m.text, m.attached,
                    onInsert = { host.insertText(m.text) }, onCopy = { clipboard.setText(AnnotatedString(m.text)) }) }
                streaming?.let { s ->
                    item {
                        if (s.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)); Text("Thinking…")
                        } else Bubble(false, s, null, {}, {})
                    }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(attach == "selection", { attach = if (attach == "selection") null else "selection" }, label = { Text("📎 Selection") })
                FilterChip(attach == "page", { attach = if (attach == "page") null else "page" }, label = { Text("📎 Visible page") })
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f).heightIn(max = 140.dp), placeholder = { Text("Message") })
                if (streaming != null) IconButton(onClick = { job?.cancel() }) { Icon(Icons.Default.Stop, "Stop") }
                else IconButton(onClick = { send(input) }, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Bubble(user: Boolean, text: String, attached: String?, onInsert: () -> Unit, onCopy: () -> Unit) {
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            Box {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 520.dp).combinedClickable(onClick = {}, onLongClick = { if (!user) menu = true }),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (attached != null) Text("📎 " + if (attached == "page") "visible page" else "selection",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (user) Text(text) else SelectionContainer { Text(text) }
                        if (!user) Row {
                            Text("Write on page", Modifier.combinedClickable(onClick = onInsert).padding(top = 6.dp, end = 12.dp),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            Text("Copy", Modifier.combinedClickable(onClick = onCopy).padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Write on page") }, onClick = { onInsert(); menu = false })
                    DropdownMenuItem(text = { Text("Copy") }, onClick = { onCopy(); menu = false })
                }
            }
        }
    }
}
