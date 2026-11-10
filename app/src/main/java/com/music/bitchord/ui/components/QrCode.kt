package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.music.bitchord.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A party invite as a square somebody else can point a camera at.
 *
 * Drawn cleanly and compactly into an [ImageBitmap] off the main thread.
 * ZXing provides the raw QR module matrix, and we render contiguous, solid
 * blocks without artificial pixel gaps for maximum readability and instant scanning.
 *
 * Tapping the QR code opens a fullscreen enlarged dialog so friends can scan it
 * comfortably across a room.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    foreground: Color = Color.Black,
    background: Color = Color.White,
    quietZone: Dp = 8.dp,
    clickableToEnlarge: Boolean = true,
    codeText: String? = null,
) {
    var isEnlarged by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val sidePx = with(density) { (size - quietZone * 2).roundToPx() }
    val renderTargetPx = maxOf(sidePx, 512)

    val image by produceState<ImageBitmap?>(null, content, renderTargetPx, foreground) {
        value = withContext(Dispatchers.Default) {
            renderQr(content, renderTargetPx, foreground, density)
        }
    }

    val clickModifier = if (clickableToEnlarge && content.isNotBlank()) {
        Modifier.clickable(
            role = Role.Button,
            onClickLabel = stringResource(R.string.listen_together_scan_qr),
        ) {
            isEnlarged = true
        }
    } else Modifier

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .then(clickModifier)
            .padding(quietZone),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = image != null, enter = fadeIn()) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = stringResource(R.string.listen_together_scan_qr),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (isEnlarged && clickableToEnlarge && content.isNotBlank()) {
        EnlargedQrDialog(
            content = content,
            codeText = codeText,
            foreground = foreground,
            background = background,
            onDismiss = { isEnlarged = false },
        )
    }
}

@Composable
private fun EnlargedQrDialog(
    content: String,
    codeText: String?,
    foreground: Color,
    background: Color,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QrCode(
                    content = content,
                    size = 260.dp,
                    foreground = foreground,
                    background = background,
                    quietZone = 10.dp,
                    clickableToEnlarge = false,
                )

                if (!codeText.isNullOrBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = codeText,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 6.sp,
                        ),
                        color = foreground,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.listen_together_scan_qr_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = foreground.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Encodes and paints [content], entirely off the main thread.
 *
 * Requests an unscaled matrix (0x0 with 0 margin) from ZXing so we obtain
 * the true raw QR modules without artificial letterboxing or margin inflation.
 * Each module is then drawn solid and contiguous to prevent pixel gaps.
 */
private fun renderQr(
    content: String,
    sidePx: Int,
    foreground: Color,
    density: Density,
): ImageBitmap? {
    if (sidePx <= 0 || content.isBlank()) return null
    val matrix = runCatching {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }.getOrNull() ?: return null

    val modules = matrix.width
    if (modules <= 0) return null

    val image = ImageBitmap(sidePx, sidePx)
    val side = sidePx.toFloat()
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(side, side)) {
        val cellW = side / modules
        val cellH = side / modules
        for (y in 0 until modules) {
            val top = y * cellH
            val bottom = (y + 1) * cellH
            val h = bottom - top + 0.3f
            for (x in 0 until modules) {
                if (!matrix.get(x, y)) continue
                val left = x * cellW
                val right = (x + 1) * cellW
                val w = right - left + 0.3f
                drawRect(
                    color = foreground,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                )
            }
        }
    }
    return image
}
