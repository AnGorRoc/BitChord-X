package com.music.bitchord.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Lets what follows — a `clickable`, a ripple's bounds — reach past the box it
 * is laid out in, by [start] and [end] across and [vertical] above and below,
 * while the box still lays out at its own size. Nothing around it moves; only
 * the area that answers a tap grows into the dead space beside it.
 *
 * For a touch target, not a glyph: it goes on a layer of its own that fills the
 * glyph's slot (`matchParentSize()` first, so the constraints are fixed), with
 * the glyph drawn beside it rather than inside it.
 *
 * Touches are only taken where the bleed is not clipped by an ancestor, and a
 * later sibling still wins where the two overlap — so a negative [vertical]
 * is the way to give back height that a tighter layout hands to a neighbour.
 * [vertical] is read while laying out, so an animated amount re-places the
 * target each frame without recomposing anything.
 */
fun Modifier.touchBleed(
    start: Dp = 0.dp,
    end: Dp = 0.dp,
    vertical: () -> Dp = { 0.dp },
): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val startPx = start.roundToPx()
    val endPx = end.roundToPx()
    val left = if (layoutDirection == LayoutDirection.Ltr) startPx else endPx
    val right = if (layoutDirection == LayoutDirection.Ltr) endPx else startPx
    val bleedY = vertical().roundToPx().coerceAtLeast(-height / 2)
    val placeable = measurable.measure(
        Constraints.fixed(
            width = (width + left + right).coerceAtLeast(0),
            height = (height + bleedY * 2).coerceAtLeast(0),
        ),
    )
    // place, not placeRelative: left and right are already the physical sides.
    layout(width, height) { placeable.place(-left, -bleedY) }
}
