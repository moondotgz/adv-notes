package com.advnotes.app.tools

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.advnotes.app.ai.AiClient
import com.advnotes.app.data.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * A "tool" is a small app that lives in the side panel next to a notebook.
 *
 * Native tools implement this interface in Kotlin/Compose (see CalculatorTool, AiChatTool).
 * Web tools are a folder with manifest.json + HTML/JS and are wrapped by [WebTool], so most
 * existing web apps can be ported without writing Kotlin. See docs/TOOLS.md.
 */
interface NotebookTool {
    /** Stable id, e.g. "calculator". Also the name of the tool's storage folder. */
    val id: String
    val name: String
    val description: String
    val icon: ImageVector
    /** True when the tool is useless without AI; it is hidden while AI features are off. */
    val requiresAi: Boolean get() = false

    @Composable
    fun Content(host: ToolHost)
}

/** What a tool can do with the notebook it is open next to. */
interface ToolHost {
    val context: Context
    val toolId: String

    /** Title of the open notebook. */
    val notebookTitle: String

    /** Writes [text] onto the current page (below the selection, or in the visible area). */
    fun insertText(text: String)

    /** Places [image] on the current page as a movable picture, centred in the visible area. */
    fun insertImage(image: Bitmap) {}

    /** The current lasso selection rendered to an image, or null if nothing is selected. */
    fun selectionImage(): Bitmap?

    /** The visible part of the current page rendered to an image. */
    fun pageImage(): Bitmap?

    /** Client for the AI provider chosen in Settings. */
    fun ai(): AiClient

    /** Label of the AI provider/model in use, for display. */
    fun aiLabel(): String

    /** Per-tool persistent key-value storage and files. */
    val storage: ToolStorage

    fun toast(message: String)

    /** Lets the user pick a file (system picker); [onResult] gets null if cancelled. */
    fun pickFile(mimeTypes: List<String>, onResult: (android.net.Uri?) -> Unit)
}

/** Simple persistent storage, one JSON file + a files folder per tool. */
class ToolStorage(context: Context, toolId: String) {
    val dir: File = File(context.filesDir, "tooldata/$toolId").apply { mkdirs() }
    private val file = File(dir, "storage.json")
    private val map: MutableMap<String, String> = runCatching {
        AppJson.decodeFromString(JsonObject.serializer(), file.readText()).mapValues { (it.value as JsonPrimitive).content }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    @Synchronized fun get(key: String): String? = map[key]

    @Synchronized fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
        file.writeText(AppJson.encodeToString(JsonObject.serializer(), JsonObject(map.mapValues { JsonPrimitive(it.value) })))
    }

    fun keys(): Set<String> = synchronized(this) { map.keys.toSet() }
}

/** manifest.json of a web tool. */
@Serializable
data class WebToolManifest(
    val id: String,
    val name: String,
    val description: String = "",
    /** Entry page relative to the tool folder. */
    val entry: String = "index.html",
    /** Material icon hint: calculate, book, chat, science, functions, translate, timer, … */
    val icon: String = "extension",
    val version: String = "1.0",
    val author: String = "",
    /** The tool only works with AI (hidden while AI features are off). */
    val requiresAi: Boolean = false,
)
