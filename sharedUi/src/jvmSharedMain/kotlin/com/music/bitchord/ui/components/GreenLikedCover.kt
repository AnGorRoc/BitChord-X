package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The cover of a copy of Spotify's Liked Songs that is not the account's own
 * Liked Music — the one kept on the device, or the separate playlist on
 * YouTube Music. It is YouTube Music's liked cover (a four-corner wash behind
 * a white thumb) in greens, so it reads as "liked" and as "not that list" at
 * once.
 */
@Composable
fun GreenLikedCover(modifier: Modifier = Modifier, iconSize: Dp = 40.dp) {
    Box(
        modifier = modifier.drawBehind {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.linearGradient(
                    colors = listOf(LIME, MINT, TEAL, DEEP),
                    start = Offset(0f, 0f),
                    end = Offset(w, h),
                ),
            )
            // Light from the top corners, as on the original; the foot stays dark.
            drawRect(Brush.radialGradient(listOf(LIME.copy(alpha = 0.85f), Color.Transparent), Offset(0f, 0f), w * 0.75f))
            drawRect(Brush.radialGradient(listOf(MINT.copy(alpha = 0.6f), Color.Transparent), Offset(w * 0.45f, 0f), w * 0.6f))
            drawRect(Brush.radialGradient(listOf(TEAL.copy(alpha = 0.7f), Color.Transparent), Offset(w, 0f), w * 0.7f))
            drawRect(Brush.radialGradient(listOf(DEEP, Color.Transparent), Offset(w * 0.8f, h), w * 0.8f))
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.ThumbUp,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

private val LIME = Color(0xFFB7E978)
private val MINT = Color(0xFF6FD3A4)
private val TEAL = Color(0xFF1E7F74)
private val DEEP = Color(0xFF062A1D)
