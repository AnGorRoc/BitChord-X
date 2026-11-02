package com.music.bitchord.ui.player

import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.max
import kotlin.math.roundToInt

private const val MESH_ROWS = 32
private const val REFLECTION_STRENGTH = 0.92f
private const val REFLECTION_FADE_END = 0.9f

internal actual class PlatformReflectionMesh actual constructor() {
    private val verts = FloatArray((MESH_ROWS + 1) * 2 * 2)
    private val colors = IntArray((MESH_ROWS + 1) * 2)
    private val paint = AndroidPaint().apply { isFilterBitmap = true }

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

        val bitmap = image.asAndroidBitmap()
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        // Recorte proporcional idéntico al ContentScale.Crop de la portada
        val fit = max(w / bw, coverHeight / bh)
        val srcLeft = (bw - w / fit) / 2f
        val srcBottom = (bh + coverHeight / fit) / 2f
        val scale = fit * magnify

        for (row in 0..MESH_ROWS) {
            val v = bh * row / MESH_ROWS
            // Invierte la coordenada: la fila inferior de la portada queda en la costura superior (y=0)
            val y = (srcBottom - v) * scale
            val fade = fadeAt(y / h) * alpha
            val colour = AndroidColor.argb((fade * 255f).roundToInt().coerceIn(0, 255), 255, 255, 255)
            for (col in 0..1) {
                val u = bw * col
                val x = w / 2f + ((u - srcLeft) * fit - w / 2f) * magnify
                val i = row * 2 + col
                verts[i * 2] = x
                verts[i * 2 + 1] = y
                colors[i] = colour
            }
        }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val saved = native.save()
            native.clipRect(0f, 0f, w, h)
            native.drawBitmapMesh(bitmap, 1, MESH_ROWS, verts, 0, colors, 0, paint)
            native.restoreToCount(saved)
        }
    }

    // Caída cúbica Hermite (smoothstep) que disipa suavemente el reflejo
    private fun fadeAt(t: Float): Float {
        val x = (t.coerceIn(0f, 1f) / REFLECTION_FADE_END).coerceIn(0f, 1f)
        val eased = x * x * (3f - 2f * x)
        return REFLECTION_STRENGTH * (1f - eased)
    }
}
