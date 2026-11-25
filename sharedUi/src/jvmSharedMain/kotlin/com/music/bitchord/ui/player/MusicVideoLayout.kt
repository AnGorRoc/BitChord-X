package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.video_quality
import com.music.bitchord.sharedui.resources.video_quality_auto
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.theme.HideSystemBars
import kotlinx.coroutines.delay

/** How long the controls stay over a playing video untouched. */
internal const val VIDEO_CONTROLS_IDLE_MS = 3_500L

/**
 * Multi-gesture detector for video surfaces:
 * - Single tap: triggers [onTap]
 * - Double tap: triggers [onDoubleTap]
 * - Pinch outward (stretch): triggers [onStretch]
 * - Pinch inward: triggers [onPinchIn]
 * - Any touch interaction: reports [onInteraction] to reset inactivity timers
 */
internal suspend fun PointerInputScope.detectVideoGestures(
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onStretch: () -> Unit,
    onPinchIn: () -> Unit,
    onInteraction: () -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            onInteraction()

            var isMultiTouch = false
            var pinchTriggered = false
            var initialDistance = 0f
            var touchMovedTooMuch = false
            val startPos = down.position
            val touchSlop = viewConfiguration.touchSlop

            while (true) {
                val event = awaitPointerEvent()
                val activePointers = event.changes.filter { it.pressed }
                if (activePointers.isEmpty()) break

                onInteraction()

                if (activePointers.size >= 2) {
                    isMultiTouch = true
                    val p0 = activePointers[0].position
                    val p1 = activePointers[1].position
                    val dist = (p0 - p1).getDistance()

                    if (initialDistance <= 0f) {
                        initialDistance = dist.coerceAtLeast(10f)
                    } else if (!pinchTriggered) {
                        val ratio = dist / initialDistance
                        if (ratio >= 1.15f) {
                            pinchTriggered = true
                            onStretch()
                            activePointers.forEach { it.consume() }
                        } else if (ratio <= 0.85f) {
                            pinchTriggered = true
                            onPinchIn()
                            activePointers.forEach { it.consume() }
                        }
                    } else {
                        activePointers.forEach { it.consume() }
                    }
                } else if (!isMultiTouch) {
                    val currentPos = activePointers[0].position
                    if ((currentPos - startPos).getDistance() > touchSlop) {
                        touchMovedTooMuch = true
                    }
                }
            }

            if (isMultiTouch || touchMovedTooMuch) continue

            val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
            val secondDown = withTimeoutOrNull(doubleTapTimeout) {
                awaitFirstDown(requireUnconsumed = false)
            }

            if (secondDown == null) {
                onTap()
            } else {
                onInteraction()
                if ((secondDown.position - startPos).getDistance() > touchSlop * 2) {
                    onTap()
                } else {
                    var secondMoved = false
                    val secondStart = secondDown.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) break
                        if (active.size > 1 || (active[0].position - secondStart).getDistance() > touchSlop) {
                            secondMoved = true
                        }
                    }
                    if (!secondMoved) {
                        onDoubleTap()
                    } else {
                        onTap()
                    }
                }
            }
        }
    }
}

/**
 * A music video on a phone held sideways: the picture is the whole screen,
 * letterboxed in black, with the system bars away. A tap brings the controls
 * up over it — the title, the scrubber and the transport — and they stand
 * down again a few seconds after the last touch while it plays, as in every
 * video player. [onExitFullscreen] goes back to the ordinary landscape player.
 */
@Composable
internal fun FullscreenVideoPlayer(
    song: Song,
    isPlaying: Boolean,
    artRequest: ImageRequest,
    onRenderedChanged: (Boolean) -> Unit,
    rendered: Boolean,
    onExitFullscreen: () -> Unit,
    onOpenQuality: () -> Unit,
    scrubber: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    HideSystemBars(true)
    var controlsShown by remember { mutableStateOf(true) }
    // Bumped by every touch on the controls, so the idle timer starts over.
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsShown, isPlaying, touches) {
        if (controlsShown && isPlaying) {
            delay(VIDEO_CONTROLS_IDLE_MS)
            controlsShown = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVideoGestures(
                    onTap = { controlsShown = !controlsShown },
                    onDoubleTap = onExitFullscreen,
                    onStretch = {},
                    onPinchIn = {},
                    onInteraction = { touches++ },
                )
            },
    ) {
        // Track artwork with spinner until the picture's first frame lands.
        if (!rendered) VideoLoadingBackdrop(Modifier.fillMaxSize(), artRequest = artRequest)
        MusicVideoSurface(
            videoId = song.videoId,
            fit = true,
            onRenderedChanged = onRenderedChanged,
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.62f),
                            0.30f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    )
                    // Any touch on the controls starts the idle timer over.
                    // Watched, not taken: a tap on bare picture still reaches
                    // the box below and puts them away.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                touches++
                            }
                        }
                    }
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artist,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    VideoCornerControls(fullscreen = true, onToggleFullscreen = onExitFullscreen, onOpenQuality = onOpenQuality)
                }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 420.dp),
                ) {
                    transport()
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    scrubber()
                }
            }
        }
    }
}

/**
 * What stands in for a music video until its first frame lands: the track's still
 * cover artwork with a loading spinner, rather than just black, fading into the video.
 */
@Composable
internal fun VideoLoadingBackdrop(
    modifier: Modifier = Modifier,
    artRequest: ImageRequest? = null,
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (artRequest != null) {
            AsyncImage(
                model = artRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f)),
            )
        }
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 3.dp,
            modifier = Modifier.size(36.dp),
        )
    }
}

/**
 * The two glass boxes in a music video's top-right corner: the quality box —
 * the web player's VideoQuality, which reads the rendition on screen and opens
 * the quality drawer — and the full-screen button beside it.
 */
@Composable
internal fun VideoCornerControls(
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenQuality: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoQualityBox(onClick = onOpenQuality)
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.38f))
                .clickable(onClick = onToggleFullscreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What the quality box and drawer say is playing: the held height, else the one on screen. */
private fun MusicVideoQualityUi.selected(): Int? =
    if (held == null || held !in heights) activeHeight else held

/**
 * The playing video's quality: 360p, 480p, HD, FullHD, 2K, 4K — what is on
 * screen, which is not always what was picked (4K held, then a video shot in
 * 1080p). Nothing until the renditions are known and there is a choice to make.
 */
@Composable
private fun VideoQualityBox(onClick: () -> Unit) {
    val quality by PlayerPlatform.host.musicVideoQuality.collectAsStateWithLifecycle()
    if (quality.heights.size < 2) return
    val label = quality.selected()?.let(::videoQualityLabel) ?: "Auto"
    Box(
        modifier = Modifier
            .height(30.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
            label = "videoQualityLabel",
        ) { text ->
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * The quality list, in the same drawer as the audio output: Auto, then every
 * height this video offers, the one playing marked. Picking one holds it from
 * now on — for the next video too, where that video has it.
 */
@Composable
internal fun VideoQualitySheet(hazeState: HazeState, onDismiss: () -> Unit) {
    val quality by PlayerPlatform.host.musicVideoQuality.collectAsStateWithLifecycle()
    val selected = quality.selected()
    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.video_quality),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QualityRow(
                name = stringResource(Res.string.video_quality_auto),
                detail = if (quality.held == null) selected?.let(::videoQualityLabel) else null,
                active = quality.held == null,
            ) {
                PlayerPlatform.host.setMusicVideoQuality(null)
                onDismiss()
            }
            quality.heights.forEach { height ->
                QualityRow(
                    name = videoQualityLabel(height),
                    detail = "${height}p",
                    active = quality.held != null && selected == height,
                ) {
                    PlayerPlatform.host.setMusicVideoQuality(height)
                    onDismiss()
                }
            }
        }
    }
}

/** One row of [VideoQualitySheet], drawn as the audio output drawer draws an output. */
@Composable
private fun QualityRow(name: String, detail: String?, active: Boolean, onSelect: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (active) 0.10f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !active,
            ) {
                haptics.play(Haptic.Select)
                onSelect()
            }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = Color.White.copy(alpha = if (active) 1f else 0.85f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
