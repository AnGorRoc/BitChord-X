package com.music.bitchord.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

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
