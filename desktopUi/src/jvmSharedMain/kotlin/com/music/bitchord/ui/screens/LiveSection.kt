package com.music.bitchord.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.LiveStation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.live_badge
import com.music.bitchord.sharedui.resources.live_on_air
import com.music.bitchord.sharedui.resources.live_station_chill
import com.music.bitchord.sharedui.resources.live_station_jazz
import com.music.bitchord.sharedui.resources.live_station_lofi
import com.music.bitchord.sharedui.resources.live_station_mix
import com.music.bitchord.sharedui.resources.live_station_phonk
import com.music.bitchord.sharedui.resources.live_subtitle
import com.music.bitchord.sharedui.resources.live_title
import com.music.bitchord.sharedui.resources.retry
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ShimmerBox
import org.jetbrains.compose.resources.stringResource

/*
 * Explore's live section. Broadcasts are the one kind of item that is not a
 * track, and they are drawn so they never read as one: a "studio" palette of
 * near-black and signal red instead of the page's own colours, 16:9 frames
 * with a pulsing on-air lamp, and a tuner instead of a shelf title — the
 * stations are switched between, not browsed.
 */

/** The on-air red. Fixed rather than themed: it is a signal, not a brand colour. */
internal val OnAirRed = Color(0xFFFF2D46)
private val StudioBlack = Color(0xFF0B0B10)

@Composable
private fun LiveStation.label(): String = stringResource(
    when (this) {
        LiveStation.MIX -> Res.string.live_station_mix
        LiveStation.LOFI -> Res.string.live_station_lofi
        LiveStation.JAZZ -> Res.string.live_station_jazz
        LiveStation.CHILL -> Res.string.live_station_chill
        LiveStation.PHONK -> Res.string.live_station_phonk
    },
)

/** Header, tuner and the row of broadcasts, as LazyColumn content. */
internal fun androidx.compose.foundation.lazy.LazyListScope.liveSection(
    state: UiState<List<Song>>,
    station: LiveStation,
    onStationChange: (LiveStation) -> Unit,
    onBroadcastClick: (Song) -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "live-header") { LiveHeader() }
    item(key = "live-tuner") { LiveTuner(station, onStationChange) }
    item(key = "live-row") {
        when (state) {
            UiState.Loading -> LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                userScrollEnabled = false,
            ) {
                items(3) {
                    ShimmerBox(
                        Modifier
                            .width(LIVE_CARD_WIDTH)
                            .aspectRatio(16f / 9f),
                        RoundedCornerShape(20.dp),
                    )
                }
            }
            is UiState.Error -> MessageState(
                message = state.message,
                actionLabel = stringResource(Res.string.retry),
                onAction = onRetry,
            )
            is UiState.Success -> LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.data, key = { it.videoId }) { broadcast ->
                    BroadcastCard(broadcast, onClick = { onBroadcastClick(broadcast) })
                }
            }
        }
    }
}

private val LIVE_CARD_WIDTH = 264.dp

@Composable
private fun LiveHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OnAirLamp(size = 14.dp)
        Column {
            Text(
                text = stringResource(Res.string.live_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.live_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The station switch, drawn as a radio dial: a strip of frequencies under a
 * hairline, the tuned one lit in on-air red with its needle.
 */
@Composable
private fun LiveTuner(selected: LiveStation, onSelect: (LiveStation) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        items(LiveStation.entries, key = { it.id }) { station ->
            val tuned = station == selected
            val ink by animateColorAsState(
                targetValue = if (tuned) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "tunerInk",
            )
            val plate by animateColorAsState(
                targetValue = if (tuned) OnAirRed else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "tunerPlate",
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(plate)
                        .clickable { onSelect(station) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = station.label().uppercase(),
                        color = ink,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // The needle: only under the tuned station.
                Box(
                    Modifier
                        .size(width = 18.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(if (tuned) OnAirRed else Color.Transparent),
                )
            }
        }
    }
}

/** A 16:9 frame of the broadcast with the studio dressing. */
@Composable
private fun BroadcastCard(broadcast: Song, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier = Modifier.width(LIVE_CARD_WIDTH)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(StudioBlack)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(listOf(OnAirRed, OnAirRed.copy(alpha = 0.15f), OnAirRed.copy(alpha = 0.6f))),
                    shape = shape,
                )
                .clickable(onClick = onClick),
        ) {
            broadcast.thumbnailUrl?.let { art ->
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.88f },
                )
            }
            Scanlines(Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.35f to Color.Transparent,
                            0.6f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
            )
            LiveBadge(Modifier.align(Alignment.TopStart).padding(10.dp))
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.live_on_air).uppercase(),
                    color = Color.White.copy(alpha = 0.75f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.weight(1f),
                )
                SignalBars(Modifier.size(width = 22.dp, height = 14.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = broadcast.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = broadcast.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The red "● LIVE" plate, shared with the player. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(OnAirRed)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        OnAirLamp(size = 6.dp, color = Color.White)
        Text(
            text = stringResource(Res.string.live_badge),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}

/** A lamp that breathes, with a ring that radiates from it. */
@Composable
fun OnAirLamp(size: androidx.compose.ui.unit.Dp, color: Color = OnAirRed) {
    val transition = rememberInfiniteTransition(label = "onAir")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "onAirPulse",
    )
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(color.copy(alpha = (1f - pulse) * 0.5f), radius = r * (1f + pulse * 0.9f))
        drawCircle(color, radius = r * 0.72f)
    }
}

/** Three bars rising and falling out of phase — the station is transmitting. */
@Composable
private fun SignalBars(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "signal")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "signalPhase",
    )
    Canvas(modifier) {
        val bars = 3
        val gap = size.width * 0.18f
        val w = (size.width - gap * (bars - 1)) / bars
        repeat(bars) { i ->
            val level = 0.35f + 0.65f * ((kotlin.math.sin(phase + i * 1.9f) + 1f) / 2f)
            val h = size.height * level
            drawRoundRect(
                color = if (i == bars - 1) OnAirRed else Color.White,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/** Faint horizontal lines over the frame: the monitor-wall look. */
@Composable
private fun Scanlines(modifier: Modifier) {
    Canvas(modifier) {
        val step = 3.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawRect(Color.Black.copy(alpha = 0.12f), topLeft = Offset(0f, y), size = Size(size.width, 1f))
            y += step
        }
    }
}
