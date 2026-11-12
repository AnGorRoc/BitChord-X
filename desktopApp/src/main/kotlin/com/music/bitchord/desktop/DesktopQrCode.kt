package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A cached, theme-independent invite QR for a nearby phone to scan. */
@Composable
internal fun DesktopQrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    clickableToEnlarge: Boolean = true,
    codeText: String? = null,
) {
    var enlarged by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val quietZone = 10.dp
    val sidePx = with(density) { (size - quietZone * 2).roundToPx() }
    val image by produceState<ImageBitmap?>(null, content, sidePx) {
        value = withContext(Dispatchers.Default) { renderDesktopQr(content, sidePx, density) }
    }
    val clickModifier = if (clickableToEnlarge && content.isNotBlank()) {
        Modifier.clickable { enlarged = true }
    } else Modifier

    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .then(clickModifier)
            .padding(quietZone),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = "Party invite QR code", modifier = Modifier.fillMaxSize()) }
    }

    if (enlarged && clickableToEnlarge && content.isNotBlank()) {
        DesktopEnlargedQrDialog(
            content = content,
            codeText = codeText,
            onDismiss = { enlarged = false },
        )
    }
}

@Composable
private fun DesktopEnlargedQrDialog(
    content: String,
    codeText: String?,
    onDismiss: () -> Unit,
) {
    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 380, popup = true) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = panelInset(22.dp), vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    DesktopStrings["listen_together_scan_qr", "Scan QR Code"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = DesktopSecondary)
                }
            }
            Spacer(Modifier.height(18.dp))
            DesktopQrCode(
                content = content,
                size = 250.dp,
                clickableToEnlarge = false,
            )
            codeText?.takeIf { it.isNotBlank() }?.let { code ->
                Spacer(Modifier.height(18.dp))
                Text(
                    "PARTY CODE",
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    code,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                DesktopStrings["listen_together_scan_from_phone", "Point a phone's camera at this screen to join"],
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

private fun renderDesktopQr(content: String, sidePx: Int, density: Density): ImageBitmap? {
    if (content.isBlank() || sidePx <= 0) return null
    val matrix = runCatching {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            256,
            256,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }.getOrNull() ?: return null
    val image = ImageBitmap(sidePx, sidePx)
    val side = sidePx.toFloat()
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(side, side)) {
        val cell = side / matrix.width
        val dot = cell * 0.86f
        val inset = (cell - dot) / 2f
        val radius = CornerRadius(dot * 0.3f, dot * 0.3f)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) {
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(x * cell + inset, y * cell + inset),
                    size = Size(dot, dot),
                    cornerRadius = radius,
                )
            }
        }
    }
    return image
}
