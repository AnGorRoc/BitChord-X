package com.music.bitchord.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

private const val FADE_CURVE_STOPS = 16
private fun smootherStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

internal actual class PlatformReflectionMesh actual constructor() {
    actual fun draw(
        scope: DrawScope,
        image: ImageBitmap,
        coverHeight: Float,
        magnify: Float,
        alpha: Float,
    ): Unit = with(scope) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || coverHeight <= 0f || alpha <= 0.004f) return

        withTransform({
            scale(scaleX = magnify, scaleY = -magnify, pivot = Offset(w / 2f, h / 2f))
        }) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }

        val gradientBrush = Brush.verticalGradient(
            colorStops = Array(FADE_CURVE_STOPS + 1) { i ->
                val t = i / FADE_CURVE_STOPS.toFloat()
                t to Color.Black.copy(alpha = 0.85f * alpha * (1f - smootherStep(t)))
            },
            startY = 0f,
            endY = h,
        )
        drawRect(
            brush = gradientBrush,
            blendMode = BlendMode.DstIn,
        )
    }
}
