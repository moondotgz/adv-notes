package com.advnotes.app.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * App translations. The English text is the key: `tr("Delete page")`, `tr("Delete {0}?", name)`.
 * Each language is a JSON file in assets/i18n/<tag>.json (e.g. pt-BR.json):
 *
 *     { "_meta": { "name": "Português (Brasil)", "machineTranslated": true },
 *       "Delete page": "Excluir página", ... }
 *
 * Missing keys fall back to English. See docs/TRANSLATING.md.
 */
object I18n {
    data class Language(val tag: String, val name: String, val machineTranslated: Boolean)

    /** "" = follow the system language. */
    private const val PREF = "i18n"
    private var ctx: Context? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Compose reads this, so changing the language recomposes every screen that uses [tr]. */
    private var table by mutableStateOf<Map<String, String>>(emptyMap())
    var choice by mutableStateOf("")
        private set
    var available: List<Language> = emptyList()
        private set

    fun init(context: Context) {
        ctx = context.applicationContext
        available = listOf(Language("en", "English", false)) + runCatching {
            context.assets.list("i18n").orEmpty().filter { it.endsWith(".json") && it != "en.json" }.mapNotNull { f ->
                val meta = readFile(f.removeSuffix(".json"))?.get("_meta")?.jsonObject ?: return@mapNotNull null
                Language(
                    f.removeSuffix(".json"),
                    (meta["name"] as? JsonPrimitive)?.content ?: f.removeSuffix(".json"),
                    (meta["machineTranslated"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }.sortedBy { it.name }
        }.getOrDefault(emptyList())
        choice = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("lang", "") ?: ""
        load()
    }

    /** Picks a language by tag ("" for the system language) and remembers it. */
    fun set(tag: String) {
        choice = tag
        ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putString("lang", tag)?.apply()
        load()
    }

    /** The language in effect: the user's choice, or the best match for the system locale. */
    fun active(): Language? {
        if (choice.isNotEmpty()) return available.firstOrNull { it.tag == choice }
        val loc = Locale.getDefault()
        return available.firstOrNull { it.tag.equals(loc.toLanguageTag(), true) }
            ?: available.firstOrNull { it.tag.substringBefore('-').equals(loc.language, true) }
    }

    private fun load() {
        val lang = active()
        table = if (lang == null || lang.tag == "en") emptyMap()
        else readFile(lang.tag)?.filterKeys { !it.startsWith("_") }
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { k to it } }?.toMap()
            ?: emptyMap()
    }

    private fun readFile(tag: String): JsonObject? = runCatching {
        ctx!!.assets.open("i18n/$tag.json").bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
    }.getOrNull()

    fun translate(key: String, args: Array<out Any?>): String {
        var s = table[key] ?: key
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }
}

/** Singular/plural: trn(n, "{0} page", "{0} pages"). Both forms are keys; {0} is the count. */
fun trn(n: Int, one: String, other: String, vararg args: Any?): String = I18n.translate(if (n == 1) one else other, arrayOf(n, *args))

/** Translates a user-facing English string. Placeholders {0}, {1}… are filled from [args]. */
fun tr(key: String, vararg args: Any?): String = I18n.translate(key, args)
