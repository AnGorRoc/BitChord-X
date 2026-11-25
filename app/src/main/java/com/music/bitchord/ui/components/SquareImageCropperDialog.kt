package com.music.bitchord.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An interactive full-screen square image cropper dialog.
 * Allows smooth pinch-to-zoom and slide/pan gestures to precisely frame a playlist cover.
 */
@Composable
fun SquareImageCropperDialog(
    imageUri: Uri,
    onDismiss: () -> Unit,
    onCropConfirmed: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isCropping by remember { mutableStateOf(false) }

    LaunchedEffect(imageUri) {
        isLoading = true
        sourceBitmap = withContext(Dispatchers.IO) {
            decodeSampledBitmapFromUri(context, imageUri, reqWidth = 2048, reqHeight = 2048)
        }
        isLoading = false
    }

    Dialog(
        onDismissRequest = { if (!isCropping) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isCropping,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF101012),
        ) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else if (sourceBitmap == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No se pudo cargar la imagen",
                            color = Color.White,
                            fontSize = 16.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onDismiss) {
                            Text("Cerrar")
                        }
                    }
                }
            } else {
                val bitmap = sourceBitmap!!
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                var initialized by remember { mutableStateOf(false) }
                var currentCropBoxSize by remember { mutableFloatStateOf(0f) }

                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Top header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            enabled = !isCropping,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Cancelar",
                                tint = Color.White,
                            )
                        }
                        Text(
                            text = "Ajustar portada",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Box(Modifier.size(48.dp))
                    }

                    // Main interactive viewport
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val viewWidth = constraints.maxWidth.toFloat()
                        val viewHeight = constraints.maxHeight.toFloat()
                        val cropBoxSize = min(viewWidth, viewHeight) * 0.82f
                        currentCropBoxSize = cropBoxSize
                        val cropLeft = (viewWidth - cropBoxSize) / 2f
                        val cropTop = (viewHeight - cropBoxSize) / 2f
                        val cropRect = remember(cropLeft, cropTop, cropBoxSize) {
                            Rect(cropLeft, cropTop, cropLeft + cropBoxSize, cropTop + cropBoxSize)
                        }

                        val minScale = remember(bitmap, cropBoxSize) {
                            max(cropBoxSize / bitmap.width.toFloat(), cropBoxSize / bitmap.height.toFloat())
                        }
                        val maxScale = minScale * 4f

                        if (!initialized) {
                            scale = minScale
                            offset = Offset.Zero
                            initialized = true
                        }

                        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(minScale, maxScale, cropBoxSize) {
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        if (isCropping) return@detectTransformGestures
                                        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                                        scale = newScale

                                        // Constrain pan within crop viewport boundaries
                                        val curDisplayW = bitmap.width * newScale
                                        val curDisplayH = bitmap.height * newScale

                                        val maxPanX = max(0f, (curDisplayW - cropBoxSize) / 2f)
                                        val maxPanY = max(0f, (curDisplayH - cropBoxSize) / 2f)

                                        val newOffsetX = (offset.x + pan.x).coerceIn(-maxPanX, maxPanX)
                                        val newOffsetY = (offset.y + pan.y).coerceIn(-maxPanY, maxPanY)

                                        offset = Offset(newOffsetX, newOffsetY)
                                    }
                                },
                        ) {
                            // 1. Draw transformed image centered relative to the crop box
                            val imgDisplayW = bitmap.width * scale
                            val imgDisplayH = bitmap.height * scale
                            val imgLeft = (viewWidth - imgDisplayW) / 2f + offset.x
                            val imgTop = (viewHeight - imgDisplayH) / 2f + offset.y

                            drawImage(
                                image = imageBitmap,
                                dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
                                dstSize = IntSize(imgDisplayW.roundToInt(), imgDisplayH.roundToInt()),
                            )

                            // 2. Draw darkened scrim outside the square crop window
                            val scrimPath = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(Rect(0f, 0f, viewWidth, viewHeight))
                                addRect(cropRect)
                            }
                            drawPath(
                                path = scrimPath,
                                color = Color.Black.copy(alpha = 0.65f),
                            )

                            // 3. Draw crop square border
                            drawRect(
                                color = Color.White.copy(alpha = 0.85f),
                                topLeft = Offset(cropLeft, cropTop),
                                size = Size(cropBoxSize, cropBoxSize),
                                style = Stroke(width = 2.dp.toPx()),
                            )

                            // 4. Subtle 3x3 rule-of-thirds grid lines
                            val oneThird = cropBoxSize / 3f
                            val twoThirds = oneThird * 2f
                            val gridColor = Color.White.copy(alpha = 0.25f)
                            val gridStroke = 1.dp.toPx()

                            // Vertical grid lines
                            drawLine(gridColor, Offset(cropLeft + oneThird, cropTop), Offset(cropLeft + oneThird, cropTop + cropBoxSize), gridStroke)
                            drawLine(gridColor, Offset(cropLeft + twoThirds, cropTop), Offset(cropLeft + twoThirds, cropTop + cropBoxSize), gridStroke)
                            // Horizontal grid lines
                            drawLine(gridColor, Offset(cropLeft, cropTop + oneThird), Offset(cropLeft + cropBoxSize, cropTop + oneThird), gridStroke)
                            drawLine(gridColor, Offset(cropLeft, cropTop + twoThirds), Offset(cropLeft + cropBoxSize, cropTop + twoThirds), gridStroke)
                        }
                    }

                    // Bottom controls & guidance
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Pellizca para zoom, desliza para mover",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                enabled = !isCropping,
                            ) {
                                Text("Cancelar", color = Color.White.copy(alpha = 0.8f))
                            }

                            Button(
                                onClick = {
                                    if (isCropping) return@Button
                                    isCropping = true
                                    scope.launch {
                                        val cropped = withContext(Dispatchers.IO) {
                                            cropBitmapSquare(bitmap, currentCropBoxSize, scale, offset)
                                        }
                                        if (cropped != null) {
                                            onCropConfirmed(cropped)
                                        } else {
                                            isCropping = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black,
                                ),
                                enabled = !isCropping,
                            ) {
                                if (isCropping) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.Black,
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Crop,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = "Aplicar",
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun decodeSampledBitmapFromUri(
    context: Context,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int,
): Bitmap? {
    return runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        var inSampleSize = 1
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()
}

private fun cropBitmapSquare(
    source: Bitmap,
    cropBoxSize: Float,
    scale: Float,
    offset: Offset,
): Bitmap? {
    return runCatching {
        val sourceW = source.width.toFloat()
        val sourceH = source.height.toFloat()

        val cropSideInBitmap = (cropBoxSize / scale).coerceIn(1f, min(sourceW, sourceH))
        val bitmapCenterX = sourceW / 2f - (offset.x / scale)
        val bitmapCenterY = sourceH / 2f - (offset.y / scale)

        val left = (bitmapCenterX - cropSideInBitmap / 2f).coerceIn(0f, sourceW - cropSideInBitmap)
        val top = (bitmapCenterY - cropSideInBitmap / 2f).coerceIn(0f, sourceH - cropSideInBitmap)

        val cropped = Bitmap.createBitmap(
            source,
            left.roundToInt(),
            top.roundToInt(),
            cropSideInBitmap.roundToInt(),
            cropSideInBitmap.roundToInt(),
        )

        val targetSize = 800
        if (cropped.width > targetSize) {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        } else {
            cropped
        }
    }.getOrNull()
}
