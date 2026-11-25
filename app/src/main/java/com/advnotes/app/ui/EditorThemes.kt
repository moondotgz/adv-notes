package com.advnotes.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** One variant of an editor theme, from its official palette. Surfaces go from [lowest] to [highest]. */
class ThemeVariant(
    val dark: Boolean,
    val lowest: Long, val bg: Long, val low: Long, val container: Long, val high: Long, val highest: Long,
    val fg: Long, val muted: Long, val outline: Long,
    val primary: Long, val secondary: Long, val tertiary: Long, val error: Long,
)

/** A popular code-editor theme; [light] is null for dark-only themes. */
class EditorTheme(val key: String, val name: String, val dark: ThemeVariant?, val light: ThemeVariant?) {
    fun variant(wantDark: Boolean): ThemeVariant = (if (wantDark) dark ?: light else light ?: dark)!!
}

private fun d(lowest: Long, bg: Long, low: Long, c: Long, high: Long, highest: Long, fg: Long, muted: Long, outline: Long, p: Long, s: Long, t: Long, e: Long) =
    ThemeVariant(true, lowest, bg, low, c, high, highest, fg, muted, outline, p, s, t, e)
private fun l(lowest: Long, bg: Long, low: Long, c: Long, high: Long, highest: Long, fg: Long, muted: Long, outline: Long, p: Long, s: Long, t: Long, e: Long) =
    ThemeVariant(false, lowest, bg, low, c, high, highest, fg, muted, outline, p, s, t, e)

val EDITOR_THEMES = listOf(
    EditorTheme("monokai", "Monokai",
        d(0xFF1E1F1C, 0xFF272822, 0xFF2D2E27, 0xFF34352D, 0xFF3E3D32, 0xFF49483E, 0xFFF8F8F2, 0xFFA59F85, 0xFF75715E, 0xFFF92672, 0xFF66D9EF, 0xFFA6E22E, 0xFFF92672), null),
    EditorTheme("dracula", "Dracula",
        d(0xFF1E1F29, 0xFF282A36, 0xFF2E303E, 0xFF343746, 0xFF3C3F52, 0xFF44475A, 0xFFF8F8F2, 0xFFBFC3D9, 0xFF6272A4, 0xFFBD93F9, 0xFFFF79C6, 0xFF50FA7B, 0xFFFF5555), null),
    EditorTheme("rosepine", "Rosé Pine",
        d(0xFF13111E, 0xFF191724, 0xFF1F1D2E, 0xFF232136, 0xFF26233A, 0xFF2E2A45, 0xFFE0DEF4, 0xFF908CAA, 0xFF6E6A86, 0xFFEBBCBA, 0xFFC4A7E7, 0xFF9CCFD8, 0xFFEB6F92),
        l(0xFFFFFAF3, 0xFFFAF4ED, 0xFFF4EDE8, 0xFFF2E9E1, 0xFFEBE1D9, 0xFFDFDAD9, 0xFF575279, 0xFF797593, 0xFF9893A5, 0xFFD7827E, 0xFF907AA9, 0xFF286983, 0xFFB4637A)),
    EditorTheme("nord", "Nord",
        d(0xFF242933, 0xFF2E3440, 0xFF343B48, 0xFF3B4252, 0xFF434C5E, 0xFF4C566A, 0xFFECEFF4, 0xFFD8DEE9, 0xFF616E88, 0xFF88C0D0, 0xFF81A1C1, 0xFFA3BE8C, 0xFFBF616A), null),
    EditorTheme("gruvbox", "Gruvbox",
        d(0xFF1D2021, 0xFF282828, 0xFF32302F, 0xFF3C3836, 0xFF504945, 0xFF665C54, 0xFFEBDBB2, 0xFFBDAE93, 0xFF928374, 0xFFFABD2F, 0xFF83A598, 0xFF8EC07C, 0xFFFB4934),
        l(0xFFF9F5D7, 0xFFFBF1C7, 0xFFF2E5BC, 0xFFEBDBB2, 0xFFE2D0A6, 0xFFD5C4A1, 0xFF3C3836, 0xFF665C54, 0xFF928374, 0xFFAF3A03, 0xFF076678, 0xFF427B58, 0xFF9D0006)),
    EditorTheme("catppuccin", "Catppuccin",
        d(0xFF11111B, 0xFF1E1E2E, 0xFF181825, 0xFF24273A, 0xFF313244, 0xFF45475A, 0xFFCDD6F4, 0xFFA6ADC8, 0xFF6C7086, 0xFFCBA6F7, 0xFF89B4FA, 0xFF94E2D5, 0xFFF38BA8),
        l(0xFFFFFFFF, 0xFFEFF1F5, 0xFFE6E9EF, 0xFFDCE0E8, 0xFFCCD0DA, 0xFFBCC0CC, 0xFF4C4F69, 0xFF6C6F85, 0xFF9CA0B0, 0xFF8839EF, 0xFF1E66F5, 0xFF179299, 0xFFD20F39)),
    EditorTheme("solarized", "Solarized",
        d(0xFF00212B, 0xFF002B36, 0xFF04313D, 0xFF073642, 0xFF0D4250, 0xFF1A4D5A, 0xFF93A1A1, 0xFF839496, 0xFF586E75, 0xFF268BD2, 0xFF2AA198, 0xFF859900, 0xFFDC322F),
        l(0xFFFFFBEE, 0xFFFDF6E3, 0xFFF5EFDC, 0xFFEEE8D5, 0xFFE4DDC8, 0xFFD9D2BC, 0xFF586E75, 0xFF657B83, 0xFF93A1A1, 0xFF268BD2, 0xFF2AA198, 0xFF859900, 0xFFDC322F)),
    EditorTheme("tokyonight", "Tokyo Night",
        d(0xFF16161E, 0xFF1A1B26, 0xFF1F2030, 0xFF24283B, 0xFF292E42, 0xFF343A55, 0xFFC0CAF5, 0xFFA9B1D6, 0xFF565F89, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFF7768E), null),
    EditorTheme("onedark", "One Dark",
        d(0xFF1E2227, 0xFF282C34, 0xFF2C313A, 0xFF30353F, 0xFF3A3F4B, 0xFF3E4451, 0xFFABB2BF, 0xFF9DA5B4, 0xFF5C6370, 0xFF61AFEF, 0xFFC678DD, 0xFF98C379, 0xFFE06C75), null),
)

/** Text/icon colour that reads well on [bg]. */
private fun on(bg: Color, darkInk: Color, lightInk: Color) = if (bg.luminance() > 0.45f) darkInk else lightInk

fun schemeFor(v: ThemeVariant): ColorScheme {
    val bg = Color(v.bg); val fg = Color(v.fg)
    val p = Color(v.primary); val s = Color(v.secondary); val t = Color(v.tertiary); val e = Color(v.error)
    fun cont(c: Color) = c.copy(alpha = if (v.dark) 0.28f else 0.18f).compositeOver(bg)
    return if (v.dark) darkColorScheme(
        primary = p, onPrimary = on(p, Color(v.bg), Color.White), primaryContainer = cont(p), onPrimaryContainer = fg,
        secondary = s, onSecondary = on(s, Color(v.bg), Color.White), secondaryContainer = cont(s), onSecondaryContainer = fg,
        tertiary = t, onTertiary = on(t, Color(v.bg), Color.White), tertiaryContainer = cont(t), onTertiaryContainer = fg,
        error = e, onError = on(e, Color(v.bg), Color.White), errorContainer = cont(e), onErrorContainer = fg,
        background = bg, onBackground = fg, surface = bg, onSurface = fg,
        surfaceVariant = Color(v.high), onSurfaceVariant = Color(v.muted), outline = Color(v.outline), outlineVariant = Color(v.highest),
        surfaceContainerLowest = Color(v.lowest), surfaceContainerLow = Color(v.low), surfaceContainer = Color(v.container),
        surfaceContainerHigh = Color(v.high), surfaceContainerHighest = Color(v.highest), surfaceBright = Color(v.highest), surfaceDim = Color(v.lowest),
        inverseSurface = fg, inverseOnSurface = bg, inversePrimary = p, scrim = Color.Black,
    ) else lightColorScheme(
        primary = p, onPrimary = on(p, fg, Color.White), primaryContainer = cont(p), onPrimaryContainer = fg,
        secondary = s, onSecondary = on(s, fg, Color.White), secondaryContainer = cont(s), onSecondaryContainer = fg,
        tertiary = t, onTertiary = on(t, fg, Color.White), tertiaryContainer = cont(t), onTertiaryContainer = fg,
        error = e, onError = on(e, fg, Color.White), errorContainer = cont(e), onErrorContainer = fg,
        background = bg, onBackground = fg, surface = bg, onSurface = fg,
        surfaceVariant = Color(v.high), onSurfaceVariant = Color(v.muted), outline = Color(v.outline), outlineVariant = Color(v.highest),
        surfaceContainerLowest = Color(v.lowest), surfaceContainerLow = Color(v.low), surfaceContainer = Color(v.container),
        surfaceContainerHigh = Color(v.high), surfaceContainerHighest = Color(v.highest), surfaceBright = Color(v.lowest), surfaceDim = Color(v.highest),
        inverseSurface = fg, inverseOnSurface = bg, inversePrimary = p, scrim = Color.Black,
    )
}
