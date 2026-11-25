package com.music.bitchord.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppFont

// Apple Music's signature red. No longer the primary accent, but kept for the
// spots (Replay's rank badge) that want that specific red regardless of theme.
val AccentRed = Color(0xFFFA2D48)

private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0D0D0F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E),
)

/**
 * The easter egg's palette: a neon night. Deep violet where the dark theme is
 * black, the cards a step up into dusk, hot pink where the app would be white
 * for emphasis, and cyan beside it — the colours of a sunset bay after dark.
 * Always a dark scheme: the neon only glows against night.
 */
val ViceBackground = Color(0xFF241763)

/**
 * The easter egg's page, top to bottom, as Discord's own neon theme lays it:
 * indigo night at the top, violet and magenta through the middle, and the
 * warm rust of a sun gone down at the foot.
 */
val ViceGradient = listOf(
    Color(0xFF241763),
    Color(0xFF3E1A6E),
    Color(0xFF5A1F66),
    Color(0xFF6E2A4A),
    Color(0xFF7A3A2C),
)

/** A card over [ViceGradient]: a frosted pane the neon shows through. */
val ViceCard = Color.White.copy(alpha = 0.08f)

/**
 * The easter egg by day, for the Light theme: the same bay in the morning —
 * a pale aqua sky at the top, through lavender and pink, to the warm peach of
 * the sand at the foot.
 */
val ViceDayBackground = Color(0xFFD3F2FA)

val ViceDayGradient = listOf(
    Color(0xFFD3F2FA),
    Color(0xFFE4DCFB),
    Color(0xFFF6D9EE),
    Color(0xFFFFE0D2),
    Color(0xFFFFEBD6),
)

/** A card over [ViceDayGradient]: frosted white, the pastel showing through. */
val ViceDayCard = Color.White.copy(alpha = 0.5f)

/** The easter egg's page colour, by night ([dark]) or by day. */
fun viceBackground(dark: Boolean): Color = if (dark) ViceBackground else ViceDayBackground

/** The easter egg's page wash, by night ([dark]) or by day. */
fun viceGradient(dark: Boolean): List<Color> = if (dark) ViceGradient else ViceDayGradient

/** The easter egg's card, by night ([dark]) or by day. */
fun viceCard(dark: Boolean): Color = if (dark) ViceCard else ViceDayCard

/**
 * The page's wash: the record's colour (or the theme's page) — or, under the
 * easter egg, its [gradient] from the top of the area to its foot.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPageWash(color: Color, gradient: List<Color>?) {
    if (gradient != null) {
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(gradient))
    } else {
        drawRect(color)
    }
}
private val ViceColors = darkColorScheme(
    primary = Color(0xFFFF5DB8),
    onPrimary = Color(0xFF14002A),
    secondary = Color(0xFF35E0F0),
    onSecondary = Color(0xFF00262B),
    tertiary = Color(0xFFFF9A5A),
    background = ViceBackground,
    onBackground = Color.White,
    surface = Color(0xFF1C0F3D),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A1652),
    onSurfaceVariant = Color(0xFFCDB8F0),
    outline = Color(0xFF3D2470),
)

/**
 * The easter egg by day: deep violet ink on the morning pastels, a hot pink
 * deep enough to read on them, and a sea teal beside it.
 */
private val ViceDayColors = lightColorScheme(
    primary = Color(0xFFD6287F),
    onPrimary = Color.White,
    secondary = Color(0xFF0E8FA3),
    onSecondary = Color.White,
    tertiary = Color(0xFFE0703A),
    background = ViceDayBackground,
    onBackground = Color(0xFF2A1050),
    surface = Color(0xFFF7F0FF),
    onSurface = Color(0xFF2A1050),
    surfaceVariant = Color(0xFFEBDDF7),
    onSurfaceVariant = Color(0xFF6B4F8C),
    outline = Color(0xFFD9C6EE),
)

private val LightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF7F7F9),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFE5E5EA),
)

/**
 * SF Pro Display, the face Apple Music itself is set in, drawn one weight
 * lighter than it is asked for.
 *
 * Measured against Apple Music's own screens, every weight here came out a
 * cut heavier than the same text there: its titles are Semibold where these
 * asked for Bold, its metadata Medium where these asked for Semibold. The
 * shift is made once, in the family, so it reaches the type scale and every
 * weight set in place alike and keeps the steps between them: Heavy draws
 * Bold, Bold draws Semibold, Semibold draws Medium, Medium draws Regular.
 * Regular stays Regular — no lighter cut is bundled, and Apple's body text is
 * Regular too. A FontWeight in this app therefore names the step it sits at in
 * the scale, not the cut it draws.
 *
 * Compose synthesises nothing, so a weight outside these falls back to the
 * nearest one listed.
 */
val SFProDisplay = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_regular, FontWeight.W500),
    Font(R.font.sf_pro_display_medium, FontWeight.W600),
    Font(R.font.sf_pro_display_semibold, FontWeight.W700),
    Font(R.font.sf_pro_display_bold, FontWeight.W800),
)

/*
 * Letter spacing measured off Apple Music's own screens: the spacing the
 * bundled Display cut needs, at the weight it draws, to set a line to the
 * width Apple sets it. Apple sets text under 20pt in SF Pro Text, which is
 * spaced looser than Display, so the small sizes open up — about +0.25 to
 * +0.4 at 12 to 14 — and the spacing closes as the size grows, to just under
 * nothing for semibold titles at 18 to 24. The large titles had nothing to
 * measure against and follow the same trend.
 */
private val BitChordTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.25).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = 0.05.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp, letterSpacing = 0.25.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp, letterSpacing = 0.3.sp),
).withFamily(SFProDisplay)

/**
 * SF Pro Display as the app first shipped it, before [SFProDisplay] moved every
 * weight a cut lighter: each weight draws its own cut, Heavy included. Kept for
 * [AppFont.LEGACY].
 */
val SFProDisplayLegacy = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_medium, FontWeight.W500),
    Font(R.font.sf_pro_display_semibold, FontWeight.W600),
    Font(R.font.sf_pro_display_bold, FontWeight.W700),
    Font(R.font.sf_pro_display_heavy, FontWeight.W800),
)

// Heavy, tight typography — the original scale [AppFont.LEGACY] restores.
private val LegacyTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp),
).withFamily(SFProDisplayLegacy)

/** Applies [family] to every style in the scale, so nothing is left on Roboto. */
private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

@Composable
fun BitChordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    font: AppFont = AppFont.DEFAULT,
    /** The easter egg's colours in place of the plain scheme: its night when dark, its day when light. */
    vice: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = when {
            vice -> if (darkTheme) ViceColors else ViceDayColors
            darkTheme -> DarkColors
            else -> LightColors
        },
        typography = when (font) {
            AppFont.DEFAULT -> BitChordTypography
            AppFont.LEGACY -> LegacyTypography
        },
        content = content,
    )
}

/**
 * Draws the status and navigation bar glyphs dark or light.
 *
 * `enableEdgeToEdge()` decides this from the *system* dark-mode setting, which
 * is the wrong input the moment the in-app theme disagrees with it: Light theme
 * on a phone in dark mode left white icons on a white bar, invisible. The bars
 * have to follow the content the app is actually painting. The player supplies
 * its own stable light-icon value and paints contrast behind it; every other
 * surface follows the theme. Hence a parameter rather than reading it here.
 */
@Composable
fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = findWindow(view) ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}

