package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Dominant color veil overlay for cover reflection in immersive mode.
 * Desvanece el color predominante de transparente a 70% de opacidad hacia el fondo.
 */
@Composable
fun CoverVeil(
    color: Color,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier = modifier.drawBehind {
            val a = alpha().coerceIn(0f, 1f)
            if (a <= 0.004f) return@drawBehind
            val brush = Brush.verticalGradient(
                0f to color.copy(alpha = 0f),
                0.30f to color.copy(alpha = 0f),
                1f to color.copy(alpha = 0.70f),
            )
            drawRect(brush, alpha = a)
        }
    )
}
