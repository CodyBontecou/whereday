package com.bontecou.whereday

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Paper and ink tokens from the current iOS `TEDesign.swift` (Whereday Reimagined).
 * Navy ink on paper in the day appearance; amber ink on night paper in the dark one.
 */
@Immutable internal data class WheredayInk(
    val dark: Boolean, val accent: Color, val surface: Color, val card: Color, val border: Color,
    val textPrimary: Color, val textMuted: Color, val inkRust: Color, val placeInks: List<Color>,
    val dialTrack: Color, val dialTick: Color, val inkCard: Color, val onInkCard: Color, val onInkCardMuted: Color, val liveDot: Color,
) {
    fun placeInk(index: Int) = placeInks[Math.floorMod(index, placeInks.size)]
    companion object {
        val light = WheredayInk(false, Color(0xFF1E2B45), Color(0xFFF3EFE6), Color(0xFFFBF9F4), Color(0xFFD8D2C3),
            Color(0xFF1E2B45), Color(0xFF66645D), Color(0xFFA8381F),
            listOf(Color(0xFF1E2B45), Color(0xFFC2462A), Color(0xFFB7862B), Color(0xFF5E7F5A)),
            Color(0xFFE4DED0), Color(0xFF9A9585), Color(0xFF1E2B45), Color(0xFFF3EFE6), Color(0xFFC9CEDA), Color(0xFFF08A6B))
        val night = WheredayInk(true, Color(0xFFE0B565), Color(0xFF0F1626), Color(0xFF18213A), Color(0xFF2C3754),
            Color(0xFFECE6D8), Color(0xFF9AA3B8), Color(0xFFF0805F),
            listOf(Color(0xFFECE6D8), Color(0xFFF0805F), Color(0xFFE0B565), Color(0xFF9DC097)),
            Color(0xFF222C46), Color(0xFF4A5573), Color(0xFFE0B565), Color(0xFF0F1626), Color(0xFF5A4A2A), Color(0xFFC2462A))
    }
}

internal val LocalWheredayInk = staticCompositionLocalOf { WheredayInk.light }

/** Newsreader (variable weight) for editorial text and IBM Plex Mono for data, both OFL and bundled. */
internal object WheredayFonts {
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private fun serif(weight: FontWeight, style: FontStyle = FontStyle.Normal) = Font(
        if(style == FontStyle.Italic) R.font.newsreader_italic else R.font.newsreader, weight, style,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    val serif = FontFamily(serif(FontWeight.Normal), serif(FontWeight.Medium), serif(FontWeight.SemiBold), serif(FontWeight.Bold),
        serif(FontWeight.Normal, FontStyle.Italic), serif(FontWeight.Medium, FontStyle.Italic))
    val mono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal), Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold), Font(R.font.ibm_plex_mono_semibold, FontWeight.Bold))
}

/** iOS text-style point sizes at the default content size, scaled by the system font scale on Android. */
internal object WheredayType {
    fun editorial(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = WheredayFonts.serif, fontSize = size.sp, fontWeight = weight, lineHeight = (size * 1.2f).sp)
    fun mono(size: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) = TextStyle(fontFamily = WheredayFonts.mono, fontSize = size.sp, fontWeight = weight, letterSpacing = tracking.sp, lineHeight = (size * 1.35f).sp)
    val largeTitle = editorial(34); val title = editorial(28); val title2 = editorial(22); val title3 = editorial(20); val headline = editorial(17, FontWeight.Medium)
    val body = editorial(17)
    val caption = mono(12); val caption2 = mono(11)
    /** Section labels: uppercase, tracked mono. */
    val kicker = mono(11, FontWeight.SemiBold, 1.5f)
}

private fun typography(): Typography {
    val base = Typography()
    fun serif(style: TextStyle) = style.copy(fontFamily = WheredayFonts.serif)
    fun mono(style: TextStyle) = style.copy(fontFamily = WheredayFonts.mono)
    return base.copy(
        displayLarge = serif(base.displayLarge), displayMedium = serif(base.displayMedium), displaySmall = serif(base.displaySmall),
        headlineLarge = serif(base.headlineLarge), headlineMedium = serif(base.headlineMedium), headlineSmall = serif(base.headlineSmall),
        titleLarge = serif(base.titleLarge), titleMedium = serif(base.titleMedium).copy(fontWeight = FontWeight.Medium), titleSmall = serif(base.titleSmall),
        labelLarge = base.labelLarge, labelMedium = mono(base.labelMedium), labelSmall = mono(base.labelSmall),
    )
}

private fun colors(ink: WheredayInk) = if(ink.dark) darkColorScheme(
    primary = ink.accent, onPrimary = ink.surface, primaryContainer = ink.card, onPrimaryContainer = ink.textPrimary,
    secondary = ink.accent, onSecondary = ink.surface, secondaryContainer = ink.border, onSecondaryContainer = ink.textPrimary,
    tertiary = ink.inkRust, onTertiary = ink.surface, background = ink.surface, onBackground = ink.textPrimary,
    surface = ink.surface, onSurface = ink.textPrimary, surfaceVariant = ink.card, onSurfaceVariant = ink.textMuted,
    surfaceContainerLowest = ink.surface, surfaceContainerLow = ink.card, surfaceContainer = ink.card, surfaceContainerHigh = ink.card, surfaceContainerHighest = ink.border,
    outline = ink.border, outlineVariant = ink.border, error = Color(0xFFFF6B5E),
) else lightColorScheme(
    primary = ink.accent, onPrimary = ink.surface, primaryContainer = ink.card, onPrimaryContainer = ink.textPrimary,
    secondary = ink.accent, onSecondary = ink.surface, secondaryContainer = ink.border, onSecondaryContainer = ink.textPrimary,
    tertiary = ink.inkRust, onTertiary = ink.surface, background = ink.surface, onBackground = ink.textPrimary,
    surface = ink.surface, onSurface = ink.textPrimary, surfaceVariant = ink.card, onSurfaceVariant = ink.textMuted,
    surfaceContainerLowest = ink.card, surfaceContainerLow = ink.card, surfaceContainer = ink.card, surfaceContainerHigh = ink.card, surfaceContainerHighest = ink.border,
    outline = ink.border, outlineVariant = ink.border, error = Color(0xFFB3261E),
)

@Composable internal fun WheredayTheme(dark: Boolean, content: @Composable () -> Unit) {
    val ink = if(dark) WheredayInk.night else WheredayInk.light
    CompositionLocalProvider(LocalWheredayInk provides ink) {
        MaterialTheme(colorScheme = colors(ink), typography = remember { typography() }, shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
    }
}

/** TE section header: uppercase, tracked, monospaced. */
@Composable internal fun TESectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(title.uppercase(), modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
        style = WheredayType.kicker, color = LocalWheredayInk.current.textMuted)
}

/** A quiet paper surface for grouped controls. */
@Composable internal fun TECard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ink = LocalWheredayInk.current
    Column(modifier.background(ink.card, RoundedCornerShape(10.dp)).border(1.dp, ink.border, RoundedCornerShape(10.dp)), content = content)
}

internal enum class TEEmphasis { primary, secondary, destructive }

/** Export-style ink actions: filled primary, paper secondary and rust destructive, all 16 dp rounded. */
@Composable internal fun TEActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, emphasis: TEEmphasis = TEEmphasis.secondary, enabled: Boolean = true) {
    val ink = LocalWheredayInk.current
    val tone = when { !enabled -> ink.textMuted; emphasis == TEEmphasis.destructive -> ink.inkRust; else -> ink.accent }
    val filled = emphasis == TEEmphasis.primary && enabled
    Button(onClick, modifier.fillMaxWidth().heightIn(min = if(filled) 52.dp else 44.dp), enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if(filled) tone else ink.card, contentColor = if(filled) ink.surface else tone,
            disabledContainerColor = ink.card, disabledContentColor = ink.textMuted),
        border = BorderStroke(1.dp, if(filled || emphasis == TEEmphasis.destructive) tone else ink.border),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Text(text, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
    }
}
