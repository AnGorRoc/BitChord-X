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

        // La portada recorta su imagen para llenar su box; la reflexión recorta igual y hace zoom:
        val fit = max(w / bw, coverHeight / bh)
        val srcLeft = (bw - w / fit) / 2f
        val srcBottom = (bh + coverHeight / fit) / 2f
        val scale = fit * magnify

        for (row in 0..MESH_ROWS) {
            val v = bh * row / MESH_ROWS
            // flipped: la fila inferior de la portada esta arriba, y las filas superiores van hacia abajo
            val y = (srcBottom - v) * scale
            val colour = AndroidColor.argb((alpha * 255f).roundToInt().coerceIn(0, 255), 255, 255, 255)
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
}
