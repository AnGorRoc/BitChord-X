package com.music.bitchord.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.music.bitchord.ui.graphics.ColorUtils

/**
 * The colour the page under a row is painted, when it is not the theme's
 * background — the phone washes its pages in whatever is playing. Null leaves
 * the theme's own.
 *
 * Rows that paint an opaque fill over their swipe reveal read this, so the fill
 * matches the page rather than dragging a band of black or white across a
 * tinted one.
 *
 * Provided as a reader, not a colour: the wash fades from one record to the
 * next, and a colour provided at the root would recompose the whole app on
 * every frame of that fade. A reader is the same object throughout, so only
 * what calls it — what actually paints the wash — follows the fade.
 */
val LocalPageBackground = staticCompositionLocalOf<() -> Color?> { { null } }

/**
 * The page's wash when it is a gradient, top of the window to its foot, rather
 * than one colour — the Vice theme's. Null for a flat page.
 *
 * What paints "the page" over something ([pageBackdrop]) paints its own slice
 * of this, so the slice lines up with the page around it instead of laying a
 * flat block across the gradient.
 */
val LocalPageGradient = staticCompositionLocalOf<List<Color>?> { null }

/** [LocalPageBackground], or the theme's background where nothing set one. */
@Composable
fun pageBackground(): Color = LocalPageBackground.current() ?: MaterialTheme.colorScheme.background

/**
 * Fills this element with the page it sits on: [pageBackground] on a flat
 * page, or, on a [LocalPageGradient] page, the part of the gradient that is
 * behind it in the window, so the fill disappears into the page.
 *
 * [fade], when given, is an alpha mask over the element (opaque where it is
 * opaque): a fade into the page, rather than a block of it.
 */
@Composable
fun Modifier.pageBackdrop(fade: Brush? = null): Modifier {
    val gradient = LocalPageGradient.current
    val flat = LocalPageBackground.current
    val fallback = MaterialTheme.colorScheme.background
    if (gradient == null) {
        return if (fade == null) {
            then(Modifier.drawBehind { drawRect(flat() ?: fallback) })
        } else {
            then(
                Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawRect(flat() ?: fallback)
                        drawRect(fade, blendMode = BlendMode.DstIn)
                        drawContent()
                    },
            )
        }
    }
    // Where the element sits in the window, read at draw time so scrolling
    // only redraws it.
    val top = remember { mutableFloatStateOf(0f) }
    val windowHeight = remember { mutableFloatStateOf(0f) }
    return then(
        Modifier
            .onGloballyPositioned {
                top.floatValue = it.positionInRoot().y
                windowHeight.floatValue = it.findRootCoordinates().size.height.toFloat()
            }
            .then(if (fade != null) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier)
            .drawWithContent {
                val height = windowHeight.floatValue.takeIf { it > 0f } ?: size.height
                drawRect(
                    Brush.verticalGradient(
                        gradient,
                        startY = -top.floatValue,
                        endY = height - top.floatValue,
                    ),
                )
                if (fade != null) drawRect(fade, blendMode = BlendMode.DstIn)
                drawContent()
            },
    )
}

/**
 * The fill for grouped cards (Settings' rows) sitting on a washed page: the
 * page's own hue, one step further from the text — deeper in dark mode, paler
 * in light — so every card is the same colour and reads as part of the page
 * rather than as a black or grey slab dropped on it. Null leaves the theme's.
 * A reader for the same reason as [LocalPageBackground].
 */
val LocalCardSurface = staticCompositionLocalOf<() -> Color?> { { null } }

/** [LocalCardSurface], or null where nothing set one. */
@Composable
fun cardSurfaceOrNull(): Color? = LocalCardSurface.current()

/** [LocalCardSurface], or the theme's surfaceVariant where nothing set one. */
@Composable
fun cardSurface(): Color = cardSurfaceOrNull() ?: MaterialTheme.colorScheme.surfaceVariant

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
