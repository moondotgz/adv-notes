package com.advnotes.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Fallback palette for Android < 12 (no Material You); newer devices use the wallpaper colours.
private val Light = lightColorScheme(
    primary = Color(0xFF3557D6), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2FF), onPrimaryContainer = Color(0xFF0E1C5C),
    secondary = Color(0xFF5B5D72), secondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFF0F8B6F), tertiaryContainer = Color(0xFFB6F2DD),
    surface = Color(0xFFFBF8FF), surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2FA), surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF), surfaceContainerHighest = Color(0xFFE3E1E9),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB8C4FF), onPrimary = Color(0xFF1B2C70),
    primaryContainer = Color(0xFF30428F), onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFFC4C5DD), secondaryContainer = Color(0xFF444659),
    tertiary = Color(0xFF7FD8BF), tertiaryContainer = Color(0xFF005142),
    surface = Color(0xFF121318), surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21), surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F), surfaceContainerHighest = Color(0xFF34343A),
)

/** Softer, rounder shapes than the Material defaults. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Material type scale with a bit more weight on titles; the font stays the system font. */
private fun appTypography(family: androidx.compose.ui.text.font.FontFamily): Typography {
    val t0 = Typography()
    val t = if (family == androidx.compose.ui.text.font.FontFamily.Default) t0 else t0.copy(
        displayLarge = t0.displayLarge.copy(fontFamily = family), displayMedium = t0.displayMedium.copy(fontFamily = family),
        displaySmall = t0.displaySmall.copy(fontFamily = family), headlineLarge = t0.headlineLarge.copy(fontFamily = family),
        headlineMedium = t0.headlineMedium.copy(fontFamily = family), headlineSmall = t0.headlineSmall.copy(fontFamily = family),
        titleLarge = t0.titleLarge.copy(fontFamily = family), titleMedium = t0.titleMedium.copy(fontFamily = family),
        titleSmall = t0.titleSmall.copy(fontFamily = family), bodyLarge = t0.bodyLarge.copy(fontFamily = family),
        bodyMedium = t0.bodyMedium.copy(fontFamily = family), bodySmall = t0.bodySmall.copy(fontFamily = family),
        labelLarge = t0.labelLarge.copy(fontFamily = family), labelMedium = t0.labelMedium.copy(fontFamily = family),
        labelSmall = t0.labelSmall.copy(fontFamily = family),
    )
    return t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Whether the app is showing its dark theme (follows Settings → Appearance, not only the system). */
val LocalAppDark = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Font names as translation keys (shown with tr(name) in Settings); listed here so scripts/i18n.py finds them. */
@Suppress("unused") private fun fontNameKeys() = listOf(
    com.advnotes.app.i18n.tr("System default"), com.advnotes.app.i18n.tr("Serif"), com.advnotes.app.i18n.tr("Nunito (rounded)"),
    com.advnotes.app.i18n.tr("Lora (book)"), com.advnotes.app.i18n.tr("Lexend (easy reading)"), com.advnotes.app.i18n.tr("Caveat (handwriting)"),
)

/** Preset palette colours offered in Settings (0 = wallpaper colours). */
val THEME_SEEDS = listOf(
    0xFF3557D6, 0xFF0F8B6F, 0xFF7B4BD8, 0xFFD6336C, 0xFFE8590C, 0xFF0C8599, 0xFFC92A2A, 0xFF5C5F66,
).map { it.toInt() }

/** App fonts: key → display name and family. Bundled fonts are OFL (see assets/licenses/fonts). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val APP_FONTS: List<Triple<String, String, androidx.compose.ui.text.font.FontFamily>> by lazy {
    fun variable(res: Int) = androidx.compose.ui.text.font.FontFamily(
        listOf(400, 500, 600, 700).map { w ->
            androidx.compose.ui.text.font.Font(
                res, FontWeight(w),
                variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
            )
        }
    )
    listOf(
        Triple("SYSTEM", "System default", androidx.compose.ui.text.font.FontFamily.Default),
        Triple("SERIF", "Serif", androidx.compose.ui.text.font.FontFamily.Serif),
        Triple("NUNITO", "Nunito (rounded)", variable(com.advnotes.app.R.font.nunito)),
        Triple("LORA", "Lora (book)", variable(com.advnotes.app.R.font.lora)),
        Triple("ATKINSON", "Atkinson Hyperlegible", androidx.compose.ui.text.font.FontFamily(
            androidx.compose.ui.text.font.Font(com.advnotes.app.R.font.atkinson_regular, FontWeight.Normal),
            androidx.compose.ui.text.font.Font(com.advnotes.app.R.font.atkinson_bold, FontWeight.Bold),
        )),
        Triple("LEXEND", "Lexend (easy reading)", variable(com.advnotes.app.R.font.lexend)),
        Triple("CAVEAT", "Caveat (handwriting)", variable(com.advnotes.app.R.font.caveat)),
        Triple("MONO", "JetBrains Mono", variable(com.advnotes.app.R.font.jetbrains_mono)),
    )
}

/**
 * A Material-style palette from one colour: tones of its hue for primary/secondary/tertiary and
 * near-neutral tones for surfaces. Simpler than Material You's algorithm but in the same spirit.
 */
fun schemeFromSeed(seed: Int, dark: Boolean): androidx.compose.material3.ColorScheme {
    val hsl = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(seed, it) }
    val h = hsl[0]; val sat = hsl[1]
    fun tone(t: Int, s: Float = sat, hue: Float = h): Color {
        val l = t / 100f
        // keep very light/dark tones from looking neon or muddy
        val ss = s * (1f - kotlin.math.abs(l - 0.5f) * 0.6f)
        return Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf((hue + 360f) % 360f, ss.coerceIn(0f, 1f), l)))
    }
    val sec = sat * 0.35f; val neu = (sat * 0.12f).coerceAtMost(0.12f); val neuV = (sat * 0.2f).coerceAtMost(0.18f)
    val ter = h + 60f
    return if (!dark) lightColorScheme(
        primary = tone(40), onPrimary = Color.White, primaryContainer = tone(90), onPrimaryContainer = tone(10),
        secondary = tone(40, sec), onSecondary = Color.White, secondaryContainer = tone(90, sec), onSecondaryContainer = tone(10, sec),
        tertiary = tone(40, sat * 0.6f, ter), onTertiary = Color.White, tertiaryContainer = tone(90, sat * 0.6f, ter), onTertiaryContainer = tone(10, sat * 0.6f, ter),
        background = tone(98, neu), onBackground = tone(10, neu), surface = tone(98, neu), onSurface = tone(10, neu),
        surfaceVariant = tone(90, neuV), onSurfaceVariant = tone(30, neuV), outline = tone(50, neuV), outlineVariant = tone(80, neuV),
        surfaceContainerLowest = Color.White, surfaceContainerLow = tone(96, neu), surfaceContainer = tone(94, neu),
        surfaceContainerHigh = tone(92, neu), surfaceContainerHighest = tone(90, neu), surfaceBright = tone(98, neu), surfaceDim = tone(87, neu),
        inverseSurface = tone(20, neu), inverseOnSurface = tone(95, neu), inversePrimary = tone(80),
    ) else darkColorScheme(
        primary = tone(80), onPrimary = tone(20), primaryContainer = tone(30), onPrimaryContainer = tone(90),
        secondary = tone(80, sec), onSecondary = tone(20, sec), secondaryContainer = tone(30, sec), onSecondaryContainer = tone(90, sec),
        tertiary = tone(80, sat * 0.6f, ter), onTertiary = tone(20, sat * 0.6f, ter), tertiaryContainer = tone(30, sat * 0.6f, ter), onTertiaryContainer = tone(90, sat * 0.6f, ter),
        background = tone(6, neu), onBackground = tone(90, neu), surface = tone(6, neu), onSurface = tone(90, neu),
        surfaceVariant = tone(30, neuV), onSurfaceVariant = tone(80, neuV), outline = tone(60, neuV), outlineVariant = tone(30, neuV),
        surfaceContainerLowest = tone(4, neu), surfaceContainerLow = tone(10, neu), surfaceContainer = tone(12, neu),
        surfaceContainerHigh = tone(17, neu), surfaceContainerHighest = tone(22, neu), surfaceBright = tone(24, neu), surfaceDim = tone(6, neu),
        inverseSurface = tone(90, neu), inverseOnSurface = tone(20, neu), inversePrimary = tone(40),
    )
}

@Composable
fun AdvNotesTheme(content: @Composable () -> Unit) {
    val settings by com.advnotes.app.AdvNotesApp.instance.settings.state.collectAsState()
    val dark = when (settings.themeMode) { "LIGHT" -> false; "DARK" -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        settings.themeSeed != 0 -> schemeFromSeed(settings.themeSeed, dark)
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    val family = APP_FONTS.firstOrNull { it.first == settings.appFont }?.third ?: androidx.compose.ui.text.font.FontFamily.Default
    // status/navigation bar icons follow the app theme, not only the system's
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAppDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = appTypography(family), shapes = AppShapes, content = content)
    }
}
