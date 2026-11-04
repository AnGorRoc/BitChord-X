package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * Immersive styled controls shade gradient.
 * Oscurece sutilmente la zona inferior garantizando que el título, créditos y controles
 * sean 100% legibles sobre portadas claras o blancas.
 */
@Composable
fun ControlsShade(
    progressProvider: () -> Float,
    maxHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val shadeTopPx = maxHeightPx * 0.538f
    val shadeHeightPx = maxHeightPx * (1f - 0.538f)
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { shadeHeightPx.toDp() })
            .offset { IntOffset(0, shadeTopPx.roundToInt()) }
            .drawBehind {
                val a = progressProvider().coerceIn(0f, 1f)
                if (a <= 0.004f) return@drawBehind
                val shade = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.46f to Color.Black.copy(alpha = 0.28f),
                    1f to Color.Black.copy(alpha = 0.28f),
                )
                drawRect(shade, alpha = a)
            }
    )
}
