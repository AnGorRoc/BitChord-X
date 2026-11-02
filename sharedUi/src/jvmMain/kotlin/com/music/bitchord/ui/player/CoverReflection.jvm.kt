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
            0.00f to Color.Black.copy(alpha = 0.65f * alpha),
            0.15f to Color.Black.copy(alpha = 0.45f * alpha),
            0.40f to Color.Black.copy(alpha = 0.22f * alpha),
            0.70f to Color.Black.copy(alpha = 0.07f * alpha),
            1.00f to Color.Transparent,
        )
        drawRect(
            brush = gradientBrush,
            blendMode = BlendMode.DstIn,
        )
    }
}
