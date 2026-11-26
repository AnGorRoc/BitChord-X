package com.music.bitchord.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Whether the PC's pages are drawn dark or light, as the theme setting and the
 * system's own mode decide it. The app's root hands it to [LocalDesktopDark].
 */
object DesktopUiTheme {
    var dark by mutableStateOf(true)
}

/**
 * Whether what is being drawn is dark. The root provides [DesktopUiTheme.dark];
 * the player provides true whatever it says, since it is drawn over the
 * artwork, as on the phone — and so does everything opened from it.
 */
val LocalDesktopDark = compositionLocalOf { true }

/** Text and icons on a page: white when dark, black when light. */
val Ink: Color
    @Composable @ReadOnlyComposable get() = if (LocalDesktopDark.current) Color.White else Color.Black

/** What sits on [Ink] when it is used as a fill, as on a chosen chip. */
val InkInverse: Color
    @Composable @ReadOnlyComposable get() = if (LocalDesktopDark.current) Color.Black else Color.White

/** Picks [dark] or [light] for what is being drawn. */
@Composable
@ReadOnlyComposable
fun themed(dark: Color, light: Color): Color = if (LocalDesktopDark.current) dark else light
