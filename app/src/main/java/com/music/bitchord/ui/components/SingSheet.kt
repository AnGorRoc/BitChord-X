package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.SingMethod
import com.music.bitchord.ui.icons.SfIcons
import kotlin.math.roundToInt

/**
 * Apple Music Sing fader and acoustic DSP sheet for the currently playing track.
 *
 * Implements vocal isolation control via [com.music.bitchord.playback.VocalCutProcessor].
 * The fader directly models remaining vocal intensity from 100% (Full vocals / Original)
 * down through 50% (Duet mode) to 0% (Karaoke / Lead vocal removed).
 */
@Composable
fun SingSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val level by AppSettings.singLevel.collectAsStateWithLifecycle()
    val method by AppSettings.singMethod.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    val isSingActive = level > 0.001f
    val vocalVolume = (1f - level).coerceIn(0f, 1f)
    val vocalPercent = (vocalVolume * 100).roundToInt()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.sing_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
            ),
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.sing_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Spacer(Modifier.height(24.dp))

        // --- Liquid Pill Vocal Fader ---
        val modeText = when {
            level >= 0.98f -> stringResource(R.string.sing_mode_karaoke)
            level in 0.45f..0.55f -> stringResource(R.string.sing_mode_duet)
            level <= 0.01f -> stringResource(R.string.sing_mode_original)
            else -> stringResource(R.string.sing_level_percent, vocalPercent)
        }

        LiquidVocalFader(
            vocalVolume = vocalVolume,
            vocalPercent = vocalPercent,
            modeText = modeText,
            onVolumeChange = { newVol ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                AppSettings.setSingLevel(1f - newVol)
            },
        )

        Spacer(Modifier.height(6.dp))

        // Fader Range Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.sing_no_vocals),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Text(
                text = stringResource(R.string.sing_preset_duet),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Text(
                text = stringResource(R.string.sing_full_vocals),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(14.dp))

        // --- Quick Preset Pills ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresetButton(
                label = stringResource(R.string.sing_preset_karaoke),
                icon = SfIcons.Mic,
                isSelected = level >= 0.98f,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    AppSettings.setSingLevel(1.0f)
                },
                modifier = Modifier.weight(1f),
            )
            PresetButton(
                label = stringResource(R.string.sing_preset_duet),
                icon = SfIcons.Artist,
                isSelected = level in 0.45f..0.55f,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    AppSettings.setSingLevel(0.5f)
                },
                modifier = Modifier.weight(1f),
            )
            PresetButton(
                label = stringResource(R.string.sing_preset_original),
                icon = SfIcons.MusicNote,
                isSelected = level <= 0.02f,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    AppSettings.setSingLevel(0.0f)
                },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(20.dp))

        // --- Engine Selector & Acoustic Info Card ---
        Text(
            text = stringResource(R.string.sing_engine_title),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )

        Spacer(Modifier.height(6.dp))

        LiquidMethodSelector(
            selected = method,
            onSelect = { selectedMethod ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                AppSettings.setSingMethod(selectedMethod)
            },
        )

        Spacer(Modifier.height(10.dp))

        AcousticEngineCard(method = method)

        Spacer(Modifier.height(20.dp))

        // --- Bottom Actions ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSingActive) {
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        AppSettings.setSingLevel(0f)
                    },
                    shape = CircleShape,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.sing_turn_off),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onDismiss,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .weight(if (isSingActive) 1.2f else 1f)
                    .height(48.dp),
            ) {
                Text(
                    text = stringResource(R.string.done),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

/**
 * Apple Music Sing styled liquid capsule volume fader.
 */
@Composable
private fun LiquidVocalFader(
    vocalVolume: Float,
    vocalPercent: Int,
    modeText: String,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedVolume by animateFloatAsState(
        targetValue = vocalVolume,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy,
        ),
        label = "faderFill",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onVolumeChange(fraction)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        onVolumeChange(fraction)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onVolumeChange(fraction)
                }
            },
    ) {
        val totalWidth = maxWidth
        val fillWidth = totalWidth * animatedVolume

        // Active liquid fill track
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(fillWidth)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                            MaterialTheme.colorScheme.primary,
                        )
                    )
                ),
        )

        // Glass handle thumb line
        if (animatedVolume > 0.02f && animatedVolume < 0.98f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = (fillWidth - 4.dp).coerceAtLeast(0.dp))
                    .size(width = 4.dp, height = 30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f)),
            )
        }

        // Fader overlay content
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = SfIcons.Mic,
                    contentDescription = null,
                    tint = if (animatedVolume > 0.18f) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = modeText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (animatedVolume > 0.52f) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            Surface(
                shape = CircleShape,
                color = if (animatedVolume > 0.82f) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                },
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            ) {
                Text(
                    text = "$vocalPercent%",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = if (animatedVolume > 0.82f) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * Quick preset selection capsule button.
 */
@Composable
private fun PresetButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        label = "presetBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.White.copy(alpha = 0.08f)
        },
        label = "presetBorder",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "presetContent",
    )

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.height(40.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = contentColor,
            )
        }
    }
}

/**
 * Liquid glass segmented control for Sing DSP algorithms.
 */
@Composable
private fun LiquidMethodSelector(
    selected: SingMethod,
    onSelect: (SingMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SingMethod.entries.forEach { option ->
                val isSelected = option == selected
                val tabBg by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(200),
                    label = "tabBg",
                )
                val tabText by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "tabText",
                )
                val tabIcon = when (option) {
                    SingMethod.VOICE_GATE -> SfIcons.AutoAwesome
                    SingMethod.BAND_LIMITED -> SfIcons.Sliders
                    SingMethod.ORIGINAL -> SfIcons.MusicNote
                }
                val tabLabel = stringResource(
                    when (option) {
                        SingMethod.VOICE_GATE -> R.string.sing_method_gate
                        SingMethod.BAND_LIMITED -> R.string.sing_method_band
                        SingMethod.ORIGINAL -> R.string.sing_method_original
                    }
                )

                Surface(
                    onClick = { onSelect(option) },
                    shape = CircleShape,
                    color = tabBg,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = tabIcon,
                            contentDescription = null,
                            tint = tabText,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tabLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = tabText,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Detailed acoustic card showing DSP engine notes and duet hints.
 */
@Composable
private fun AcousticEngineCard(
    method: SingMethod,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when (method) {
                            SingMethod.VOICE_GATE -> SfIcons.AutoAwesome
                            SingMethod.BAND_LIMITED -> SfIcons.Sliders
                            SingMethod.ORIGINAL -> SfIcons.MusicNote
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }

                AnimatedContent(
                    targetState = method,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
                    label = "engineNote",
                    modifier = Modifier.weight(1f),
                ) { targetMethod ->
                    Text(
                        text = stringResource(
                            when (targetMethod) {
                                SingMethod.ORIGINAL -> R.string.sing_method_original_note
                                SingMethod.BAND_LIMITED -> R.string.sing_method_band_note
                                SingMethod.VOICE_GATE -> R.string.sing_method_gate_note
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = SfIcons.Artist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(R.string.sing_duet_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    lineHeight = 16.sp,
                )
            }
        }
    }
}
