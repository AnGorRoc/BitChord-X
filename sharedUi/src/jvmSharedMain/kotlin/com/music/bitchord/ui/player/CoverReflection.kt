package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.graphics.argbPixels
import com.music.bitchord.ui.graphics.forPixelAccess
import com.music.bitchord.ui.graphics.imageBitmapOf
import com.music.bitchord.ui.graphics.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val REFLECTION_PX = 96
private const val REFLECTION_BLUR_PX = 6

internal expect class PlatformReflectionMesh() {
    fun draw(
        scope: DrawScope,
        image: ImageBitmap,
        coverHeight: Float,
        magnify: Float,
        alpha: Float,
    )
}

/**
 * Caches and shares the 96px 6px-blurred thumbnail across CoverPicture back layer and CoverReflection.
 */
@Composable
fun rememberBlurredCover(imageUrl: String?): State<ImageBitmap?> {
    val context = LocalPlatformContext.current
    return produceState<ImageBitmap?>(null, imageUrl) {
        if (imageUrl == null) {
            value = null
            return@produceState
        }
        val request = ImageRequest.Builder(context)
            .data(imageUrl.artworkAt(120))
            .size(REFLECTION_PX, REFLECTION_PX)
            .forPixelAccess()
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val raw = (result as? SuccessResult)?.image?.toImageBitmap()
        value = if (raw != null) {
            withContext(Dispatchers.Default) {
                raw.blurred(REFLECTION_BLUR_PX)
            }
        } else {
            null
        }
    }
}

/**
 * Inverted diffused reflection for album artwork in immersive mode.
 * Proyecta la portada invertida y difuminada hacia abajo con desvanecimiento analítico
 * suave usando [PlatformReflectionMesh].
 */
@Composable
fun CoverReflection(
    bitmap: ImageBitmap?,
    coverHeightPx: () -> Float,
    magnify: () -> Float = { 1f },
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (bitmap == null) return
    val mesh = remember { PlatformReflectionMesh() }

    Spacer(
        modifier = modifier.drawBehind {
            val a = alpha().coerceIn(0f, 1f)
            if (a <= 0.004f) return@drawBehind
            mesh.draw(this, bitmap, coverHeightPx(), magnify(), a)
        }
    )
}

@Composable
fun CoverReflection(
    imageUrl: String?,
    coverHeightPx: () -> Float,
    magnify: () -> Float = { 1f },
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val bitmapState = rememberBlurredCover(imageUrl)
    CoverReflection(
        bitmap = bitmapState.value,
        coverHeightPx = coverHeightPx,
        magnify = magnify,
        alpha = alpha,
        modifier = modifier,
    )
}

/**
 * 3-pass box blur on small reflection thumbnail bitmap on Dispatchers.Default,
 * equivalent to Gaussian blur at sub-millisecond cost.
 */
private fun ImageBitmap.blurred(radius: Int): ImageBitmap {
    val width = width
    val height = height
    if (width < 2 || height < 2 || radius <= 0) return this
    var current = argbPixels()
    val buffer = IntArray(current.size)
    val r = radius.coerceAtLeast(1)

    repeat(3) {
        boxBlurHorizontal(current, buffer, width, height, r)
        boxBlurVertical(buffer, current, width, height, r)
    }

    return imageBitmapOf(current, width, height)
}

private fun boxBlurHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, r: Int) {
    val window = r * 2 + 1
    for (y in 0 until height) {
        var rAcc = 0
        var gAcc = 0
        var bAcc = 0
        val rowOffset = y * width
        for (offset in -r..r) {
            val px = src[rowOffset + offset.coerceIn(0, width - 1)]
            rAcc += (px shr 16) and 0xFF
            gAcc += (px shr 8) and 0xFF
            bAcc += px and 0xFF
        }
        for (x in 0 until width) {
            dst[rowOffset + x] = (0xFF shl 24) or
                ((rAcc / window) shl 16) or
                ((gAcc / window) shl 8) or
                (bAcc / window)
            val leaving = src[rowOffset + (x - r).coerceIn(0, width - 1)]
            val entering = src[rowOffset + (x + r + 1).coerceIn(0, width - 1)]
            rAcc += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            gAcc += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            bAcc += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

private fun boxBlurVertical(src: IntArray, dst: IntArray, width: Int, height: Int, r: Int) {
    val window = r * 2 + 1
    for (x in 0 until width) {
        var rAcc = 0
        var gAcc = 0
        var bAcc = 0
        for (offset in -r..r) {
            val px = src[offset.coerceIn(0, height - 1) * width + x]
            rAcc += (px shr 16) and 0xFF
            gAcc += (px shr 8) and 0xFF
            bAcc += px and 0xFF
        }
        for (y in 0 until height) {
            dst[y * width + x] = (0xFF shl 24) or
                ((rAcc / window) shl 16) or
                ((gAcc / window) shl 8) or
                (bAcc / window)
            val leaving = src[(y - r).coerceIn(0, height - 1) * width + x]
            val entering = src[(y + r + 1).coerceIn(0, height - 1) * width + x]
            rAcc += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            gAcc += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            bAcc += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
