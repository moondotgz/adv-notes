package com.advnotes.app.ink

import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.R

/** Typefaces for text boxes on the page (same keys as the app fonts in ui/Theme.kt). */
object TextFonts {
    private val cache = HashMap<String, Typeface>()

    fun typeface(key: String): Typeface = cache.getOrPut(key) {
        val res = when (key) {
            "NUNITO" -> R.font.nunito; "LORA" -> R.font.lora; "ATKINSON" -> R.font.atkinson_regular
            "LEXEND" -> R.font.lexend; "CAVEAT" -> R.font.caveat; "MONO" -> R.font.jetbrains_mono
            else -> 0
        }
        when {
            key == "SERIF" -> Typeface.SERIF
            res != 0 -> runCatching { ResourcesCompat.getFont(AdvNotesApp.instance, res) }.getOrNull() ?: Typeface.DEFAULT
            else -> Typeface.DEFAULT
        }
    }
}
