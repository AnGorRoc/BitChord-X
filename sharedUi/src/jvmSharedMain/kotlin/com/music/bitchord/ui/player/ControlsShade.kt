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

// Shade starts at 53.8% of screen height (just above the title at 56.5%)
internal const val SHADE_TOP_FRACTION = 0.538f

// Vertical gradient: clear at top, 24% black at 46% of its way down, then held:
// Just enough for white text over the blurred cover without muddying album vibrancy.
private val ControlsShadeBrush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.46f to Color.Black.copy(alpha = 0.24f),
    1f to Color.Black.copy(alpha = 0.24f),
)

@Composable
fun ControlsShade(
    progressProvider: () -> Float,
    maxHeightPx: Float,
    topPxProvider: (() -> Float)? = null,
    modifier: Modifier = Modifier,
) {
    val shadeTopPx = topPxProvider?.invoke() ?: (maxHeightPx * SHADE_TOP_FRACTION)
    val shadeHeightPx = (maxHeightPx - shadeTopPx).coerceAtLeast(0f)

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { shadeHeightPx.toDp() })
            .offset { IntOffset(0, shadeTopPx.roundToInt()) }
            .drawBehind {
                val a = progressProvider().coerceIn(0f, 1f)
                if (a <= 0.004f) return@drawBehind
                drawRect(ControlsShadeBrush, alpha = a)
            }
    )
}
