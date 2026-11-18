package com.music.bitchord.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils

/**
 * The colour the page under a row is painted, when it is not the theme's
 * background — the phone washes its pages in whatever is playing. Null leaves
 * the theme's own.
 *
 * Rows that paint an opaque fill over their swipe reveal read this, so the fill
 * matches the page rather than dragging a band of black or white across a
 * tinted one. Not static: it fades from one record to the next.
 */
val LocalPageBackground = compositionLocalOf<Color?> { null }

/** [LocalPageBackground], or the theme's background where nothing set one. */
@Composable
fun pageBackground(): Color = LocalPageBackground.current ?: MaterialTheme.colorScheme.background

/**
 * The fill for grouped cards (Settings' rows) sitting on a washed page: the
 * page's own hue, one step further from the text — deeper in dark mode, paler
 * in light — so every card is the same colour and reads as part of the page
 * rather than as a black or grey slab dropped on it. Null leaves the theme's.
 */
val LocalCardSurface = compositionLocalOf<Color?> { null }

/** [LocalCardSurface], or the theme's surfaceVariant where nothing set one. */
@Composable
fun cardSurface(): Color = LocalCardSurface.current ?: MaterialTheme.colorScheme.surfaceVariant

/** [page] shifted to the card shade: same hue and saturation, lightness moved away from the text. */
fun cardShadeOf(page: Color, dark: Boolean): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(page.toArgb(), it) }
    hsl[2] = if (dark) hsl[2] * CARD_DARK_FACTOR else hsl[2] + (1f - hsl[2]) * CARD_LIGHT_LIFT
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Dark page 0.13 -> card ~0.07: clearly deeper, still the record's colour. */
private const val CARD_DARK_FACTOR = 0.55f

/** Light page 0.91 -> card ~0.96: a raised pale card, as iOS groups sit on their page. */
private const val CARD_LIGHT_LIFT = 0.55f
