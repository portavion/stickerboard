package com.portavion.stickerboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Bricolage Grotesque, bundled as one variable font. Used for headings and the wordmark only. */
@OptIn(ExperimentalTextApi::class)
val Bricolage = FontFamily(
    listOf(FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map { weight ->
        Font(
            R.font.bricolage_grotesque,
            weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    }
)

/** Applies the stickerboard palette (colors.xml in values and values-night) and type scale, following the system dark setting. */
@Composable
fun StickerboardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val accent = colorResource(R.color.sb_accent)
    val onAccent = colorResource(R.color.sb_on_accent)
    val accentSoft = colorResource(R.color.sb_accent_soft)
    val onAccentSoft = colorResource(R.color.sb_on_accent_soft)
    val bg = colorResource(R.color.sb_bg)
    val ink = colorResource(R.color.sb_ink)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = ink,
        tertiary = colorResource(R.color.sb_highlight),
        onTertiary = colorResource(R.color.sb_on_highlight),
        background = bg,
        onBackground = ink,
        surface = bg,
        onSurface = ink,
        surfaceVariant = colorResource(R.color.sb_tile),
        onSurfaceVariant = colorResource(R.color.sb_muted),
        surfaceContainerLowest = colorResource(R.color.sb_surface),
        surfaceContainerLow = colorResource(R.color.sb_surface_low),
        surfaceContainer = colorResource(R.color.sb_tile),
        surfaceContainerHigh = colorResource(R.color.sb_surface_high),
        surfaceContainerHighest = colorResource(R.color.sb_surface_highest),
        outline = colorResource(R.color.sb_outline),
        outlineVariant = colorResource(R.color.sb_line),
        inverseSurface = colorResource(R.color.sb_inverse_surface),
        inverseOnSurface = colorResource(R.color.sb_inverse_on_surface),
        inversePrimary = colorResource(R.color.sb_inverse_accent),
        error = colorResource(R.color.sb_error),
        onError = colorResource(R.color.sb_on_error),
    )
    val type = Typography()
    val heading = { s: TextStyle, w: FontWeight -> s.copy(fontFamily = Bricolage, fontWeight = w, letterSpacing = (-0.01).em) }
    MaterialTheme(
        colorScheme = colors,
        typography = type.copy(
            displaySmall = heading(type.displaySmall, FontWeight.ExtraBold),
            headlineLarge = heading(type.headlineLarge, FontWeight.ExtraBold),
            headlineMedium = heading(type.headlineMedium, FontWeight.Bold),
            headlineSmall = heading(type.headlineSmall, FontWeight.Bold),
            titleLarge = heading(type.titleLarge, FontWeight.Bold),
        ),
        content = { CompositionLocalProvider(LocalContentColor provides ink, content = content) },
    )
}

/**
 * The lowercase app name set like a die-cut sticker: accent fill over a thick rounded outline
 * with a soft drop shadow, the same treatment Look.DieCut renders for stickers.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 28.sp) {
    val text = stringResource(R.string.app_name)
    val style = TextStyle(
        fontFamily = Bricolage,
        fontWeight = FontWeight.ExtraBold,
        fontSize = fontSize,
        letterSpacing = (-0.02).em,
    )
    val stroke = with(LocalDensity.current) { (fontSize.toPx() * 0.26f) }
    Box(modifier) {
        Text(
            text,
            style = style.copy(
                color = colorResource(R.color.sb_wordmark_outline),
                drawStyle = Stroke(width = stroke, join = StrokeJoin.Round),
                shadow = Shadow(Color(0x55000000), Offset(0f, stroke * 0.25f), stroke * 0.35f),
            ),
        )
        Text(text, style = style.copy(color = colorResource(R.color.sb_wordmark_fill)))
    }
}
