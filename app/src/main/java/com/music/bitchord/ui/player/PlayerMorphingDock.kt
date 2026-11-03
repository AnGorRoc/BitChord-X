package com.music.bitchord.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.liquidGlass
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.launch

/**
 * Unified interactive morphing player dock & expanded menu card.
 *
 * Sits directly above the player's layer backdrop, continuously tracking finger gestures in real time:
 * - Resting state (progress = 0f): 56.dp continuous Liquid Glass pill with the 4 core controls:
 *   Shuffle, Repeat, Continuous (Infinity), and Automix.
 * - Real-time gesture morph: follows finger vertically 1:1, physically expanding its width, height,
 *   and corner radius, crossfading resting buttons into the complete expanded track menu card.
 * - Samples the player itself (livePlayerBackdrop), so the player's background and artwork blur through the glass.
 */
@Composable
fun PlayerMorphingDock(
    progress: Float,
    animatableProgress: Animatable<Float, AnimationVector1D>,
    song: Song,
    liked: Boolean,
    isDownloaded: Boolean,
    isAudioVersion: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    autoplayEnabled: Boolean,
    automixEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onToggleAutomix: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleDownload: () -> Unit,
    onOpenAlbum: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onStartRadio: () -> Unit,
    onShare: () -> Unit,
    onWatchMusicVideo: () -> Unit,
    onViewCredits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val coroutineScope = rememberCoroutineScope()

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    val liquidGlassEnabled by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useGlass = liquidGlassEnabled && isGlassSupported() && !reduceDynamicBlur

    val tint = LocalPlayerTint.current

    // Geometry interpolation
    val restingWidth = minOf(330.dp, screenWidth - 32.dp)
    val expandedWidth = minOf(520.dp, screenWidth - 28.dp)
    val currentWidth = lerp(restingWidth, expandedWidth, progress)

    val restingHeight = 56.dp
    val expandedHeight = 480.dp
    val currentHeight = lerp(restingHeight, expandedHeight, progress)

    val currentCornerRadius = lerp(28.dp, 24.dp, progress)
    val currentShape = RoundedCornerShape(currentCornerRadius)

    val surfaceModifier = if (useGlass) {
        Modifier.liquidGlass(
            shape = currentShape,
            surfaceTint = tint.copy(alpha = lerpFloat(0.20f, 0.16f, progress)),
        )
    } else {
        Modifier
            .background(Color(0xFF141624).copy(alpha = 0.90f), currentShape)
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), currentShape)
    }

    // Dismiss handling
    BackHandler(enabled = progress > 0f) {
        coroutineScope.launch {
            animatableProgress.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f))
        }
    }

    val density = LocalDensity.current
    val travelPx = with(density) { (expandedHeight - restingHeight).toPx() }

    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var lastDragAmount by remember { mutableFloatStateOf(0f) }

    val dragModifier = Modifier.pointerInput(travelPx) {
        detectVerticalDragGestures(
            onDragStart = {
                dragAccumulator = animatableProgress.value * travelPx
                lastDragAmount = 0f
            },
            onDragEnd = {
                coroutineScope.launch {
                    val current = animatableProgress.value
                    val target = when {
                        lastDragAmount < -6f -> 1f // Fast flick up
                        lastDragAmount > 6f -> 0f  // Fast flick down
                        current > 0.35f -> 1f
                        else -> 0f
                    }
                    animatableProgress.animateTo(
                        targetValue = target,
                        animationSpec = spring(
                            dampingRatio = if (target == 1f) 0.82f else 0.85f,
                            stiffness = 450f,
                        ),
                    )
                }
            },
            onDragCancel = {
                coroutineScope.launch {
                    val current = animatableProgress.value
                    val target = if (current > 0.35f) 1f else 0f
                    animatableProgress.animateTo(
                        targetValue = target,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f),
                    )
                }
            },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                lastDragAmount = dragAmount
                dragAccumulator = (dragAccumulator - dragAmount).coerceIn(0f, travelPx)
                val newProgress = dragAccumulator / travelPx
                coroutineScope.launch {
                    animatableProgress.snapTo(newProgress)
                }
            },
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Scrim over player when expanding
        if (progress > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.36f * progress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            coroutineScope.launch {
                                animatableProgress.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f))
                            }
                        },
                    ),
            )
        }

        // Morphing dock container + top handle
        Column(
            modifier = Modifier
                .padding(bottom = 14.dp)
                .navigationBarsPadding()
                .then(dragModifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle / pestañita
            Box(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            coroutineScope.launch {
                                val target = if (animatableProgress.value < 0.5f) 1f else 0f
                                animatableProgress.animateTo(
                                    targetValue = target,
                                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 450f),
                                )
                            }
                        },
                    ),
            )

            // Morphing Surface Box
            Box(
                modifier = Modifier
                    .width(currentWidth)
                    .height(currentHeight)
                    .then(surfaceModifier),
                contentAlignment = Alignment.TopCenter,
            ) {
                // 1. Resting Dock Content (The 4 Controls Pill)
                if (progress < 0.40f) {
                    val restingAlpha = (1f - progress * 2.8f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(restingHeight)
                            .graphicsLayer { alpha = restingAlpha },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 1. Aleatorio (Shuffle)
                            PillIconItem(
                                icon = BitChordIcons.Shuffle,
                                contentDescription = "Shuffle",
                                active = shuffleEnabled,
                                enabled = progress < 0.2f,
                                onClick = onToggleShuffle,
                            )

                            // 2. Repetir (Repeat)
                            PillIconItem(
                                icon = BitChordIcons.Repeat,
                                contentDescription = "Repeat",
                                active = repeatMode != Player.REPEAT_MODE_OFF,
                                badge = if (repeatMode == Player.REPEAT_MODE_ONE) "1" else null,
                                enabled = progress < 0.2f,
                                onClick = onCycleRepeat,
                            )

                            // 3. Continuo (Infinity / Autoplay)
                            PillIconItem(
                                icon = BitChordIcons.Infinity,
                                contentDescription = "Autoplay",
                                active = autoplayEnabled,
                                enabled = progress < 0.2f,
                                onClick = onToggleAutoplay,
                            )

                            // 4. Automix (BitChord Mix)
                            PillIconItem(
                                icon = Icons.Rounded.AutoAwesome,
                                contentDescription = "Automix",
                                active = automixEnabled,
                                enabled = progress < 0.2f,
                                onClick = onToggleAutomix,
                            )
                        }
                    }
                }

                // 2. Expanded Card Content
                if (progress > 0.15f) {
                    val expandedAlpha = ((progress - 0.20f) / 0.80f).coerceIn(0f, 1f)
                    val slideY = lerp(30.dp, 0.dp, progress)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .graphicsLayer {
                                alpha = expandedAlpha
                                translationY = with(density) { slideY.toPx() }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Header: Album cover, title & artist, download check, like star
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = song.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = song.artist,
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = onToggleDownload,
                                enabled = progress > 0.8f,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = if (isDownloaded) SfIcons.Downloaded else SfIcons.Download,
                                    contentDescription = stringResource(
                                        if (isDownloaded) R.string.saved_to_downloads else R.string.download,
                                    ),
                                    tint = if (isDownloaded) tint else Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = onToggleLike,
                                enabled = progress > 0.8f,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = if (liked) BitChordIcons.StarFilled else BitChordIcons.Star,
                                    contentDescription = stringResource(
                                        if (liked) R.string.remove_from_liked else R.string.like,
                                    ),
                                    tint = if (liked) Color(0xFFFFD700) else Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Row of the 4 playback controls formatted as squircle action cards
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 1. Repeat
                            SquircleControlButton(
                                icon = BitChordIcons.Repeat,
                                contentDescription = "Repeat",
                                active = repeatMode != Player.REPEAT_MODE_OFF,
                                badge = if (repeatMode == Player.REPEAT_MODE_ONE) "1" else null,
                                enabled = progress > 0.8f,
                                onClick = onCycleRepeat,
                                modifier = Modifier.weight(1f),
                            )

                            // 2. Shuffle
                            SquircleControlButton(
                                icon = BitChordIcons.Shuffle,
                                contentDescription = "Shuffle",
                                active = shuffleEnabled,
                                enabled = progress > 0.8f,
                                onClick = onToggleShuffle,
                                modifier = Modifier.weight(1f),
                            )

                            // 3. Continuous (Infinity)
                            SquircleControlButton(
                                icon = BitChordIcons.Infinity,
                                contentDescription = "Continuous",
                                active = autoplayEnabled,
                                enabled = progress > 0.8f,
                                onClick = onToggleAutoplay,
                                modifier = Modifier.weight(1f),
                            )

                            // 4. Automix
                            SquircleControlButton(
                                icon = Icons.Rounded.AutoAwesome,
                                contentDescription = "Automix",
                                active = automixEnabled,
                                enabled = progress > 0.8f,
                                onClick = onToggleAutomix,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        // Full-width pill: "Go to Album"
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(23.dp))
                                .background(tint.copy(alpha = 0.22f))
                                .border(0.5.dp, tint.copy(alpha = 0.38f), RoundedCornerShape(23.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = progress > 0.8f,
                                    onClick = {
                                        haptics.play(Haptic.Select)
                                        coroutineScope.launch {
                                            animatableProgress.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f))
                                        }
                                        onOpenAlbum()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = SfIcons.Album,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(19.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.open_album),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Vertical action pills
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 1. Add to Playlist
                            MorphingActionPill(
                                icon = SfIcons.AddToPlaylist,
                                label = stringResource(R.string.add_to_playlist),
                                enabled = progress > 0.8f,
                                onClick = {
                                    coroutineScope.launch { animatableProgress.animateTo(0f) }
                                    onAddToPlaylist()
                                },
                            )

                            // 2. Start Radio
                            MorphingActionPill(
                                icon = SfIcons.Radio,
                                label = stringResource(R.string.start_radio),
                                enabled = progress > 0.8f,
                                onClick = {
                                    coroutineScope.launch { animatableProgress.animateTo(0f) }
                                    onStartRadio()
                                },
                            )

                            // 3. Share
                            MorphingActionPill(
                                icon = SfIcons.Share,
                                label = stringResource(R.string.share),
                                enabled = progress > 0.8f,
                                onClick = {
                                    coroutineScope.launch { animatableProgress.animateTo(0f) }
                                    onShare()
                                },
                            )

                            // 4. Watch Music Video / Switch to Audio
                            MorphingActionPill(
                                icon = SfIcons.Video,
                                label = stringResource(
                                    if (isAudioVersion) R.string.convert_to_video else R.string.convert_to_audio,
                                ),
                                enabled = progress > 0.8f,
                                onClick = {
                                    coroutineScope.launch { animatableProgress.animateTo(0f) }
                                    onWatchMusicVideo()
                                },
                            )

                            // 5. View Credits
                            MorphingActionPill(
                                icon = SfIcons.Info,
                                label = stringResource(R.string.song_info),
                                enabled = progress > 0.8f,
                                onClick = {
                                    coroutineScope.launch { animatableProgress.animateTo(0f) }
                                    onViewCredits()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillIconItem(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val haptics = rememberHaptics()
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) Color.White.copy(alpha = 0.20f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = {
                    haptics.play(if (active) Haptic.ToggleOff else Haptic.ToggleOn)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Color.White else Color.White.copy(alpha = 0.65f),
            modifier = Modifier.size(22.dp),
        )
        if (badge != null) {
            Text(
                text = badge,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 5.dp),
            )
        }
    }
}

@Composable
private fun SquircleControlButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val haptics = rememberHaptics()
    val tint = LocalPlayerTint.current
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(if (active) tint.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
            .border(
                0.5.dp,
                if (active) tint.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f),
                shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = {
                    haptics.play(if (active) Haptic.ToggleOff else Haptic.ToggleOn)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) tint else Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(20.dp),
        )
        if (badge != null) {
            Text(
                text = badge,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) tint else Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp),
            )
        }
    }
}

@Composable
private fun MorphingActionPill(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = {
                    haptics.play(Haptic.Select)
                    onClick()
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.90f),
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.95f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
