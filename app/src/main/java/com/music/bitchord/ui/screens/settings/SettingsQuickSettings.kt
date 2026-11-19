package com.music.bitchord.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.ui.components.FrostAlertDialog
import com.music.bitchord.ui.components.LocalLiquidGlassEnabled
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.liquidGlassRim
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.SfIcons
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * A setting that can be pinned to the top of Settings. A switch flips in
 * place; anything with more than two answers opens a short list to pick from.
 * Each knows the page it lives on, which a long press opens.
 */
internal enum class QuickSetting(@param:StringRes val title: Int, val page: SettingsCategory) {
    OFFLINE_MODE(R.string.offline_mode, SettingsCategory.AUDIO_QUALITY),
    DATA_SAVER(R.string.data_saver, SettingsCategory.AUDIO_QUALITY),
    MOBILE_DATA_QUALITY(R.string.on_mobile_data, SettingsCategory.AUDIO_QUALITY),
    WIFI_QUALITY(R.string.on_wifi, SettingsCategory.AUDIO_QUALITY),
    LIQUID_GLASS(R.string.liquid_glass, SettingsCategory.EFFECTS_PERFORMANCE),
    ANIMATED_COVER(R.string.animated_cover_art, SettingsCategory.PLAYER),
    THEME(R.string.theme, SettingsCategory.APPEARANCE),
    SYNCED_LYRICS(R.string.synced_lyrics, SettingsCategory.LYRICS),
    TINT(R.string.tint_with_now_playing, SettingsCategory.APPEARANCE),
    PLAYER_THEME(R.string.player_theme, SettingsCategory.PLAYER),
    LOUDNESS(R.string.loudness_normalization, SettingsCategory.SOUND),
    AUTOMIX(R.string.automix, SettingsCategory.PLAYBACK),
    SKIP_SILENCE(R.string.skip_silence, SettingsCategory.PLAYBACK),
    SPATIAL_AUDIO(R.string.spatial_audio, SettingsCategory.SOUND),
    DOLBY_ATMOS(R.string.dolby_atmos, SettingsCategory.AUDIO_QUALITY),
    LOSSLESS_FROM_START(R.string.lossless_from_start, SettingsCategory.AUDIO_QUALITY),
    DOWNLOAD_QUALITY(R.string.download_quality, SettingsCategory.DOWNLOADS),
    DOWNLOAD_WIFI_ONLY(R.string.download_wifi_only, SettingsCategory.DOWNLOADS),
    REDUCE_ANIMATION(R.string.reduce_animation, SettingsCategory.EFFECTS_PERFORMANCE),
    REDUCE_BLUR(R.string.reduce_dynamic_blur, SettingsCategory.EFFECTS_PERFORMANCE),
    HIDE_STATUS_BAR(R.string.hide_status_bar_in_player, SettingsCategory.PLAYER),
    PREFER_MUSIC_ONLY(R.string.prefer_music_only, SettingsCategory.CONTENT_PREFERENCES),
    NERD_STATS(R.string.show_nerd_stats, SettingsCategory.ADVANCED),
    ;

    /** The glyph in lists, where there is no state to show: the setting at work. */
    val icon: ImageVector get() = iconFor(on = true)

    /**
     * The glyph for a switch in each state, drawn as what the state *means* —
     * a plain cloud while streaming, the cloud struck through once offline —
     * rather than one glyph in two colours. On is filled, off is outlined, so
     * the state reads in the shape as well as in the tile behind it. A choice
     * keeps one glyph here; [QuickChoice.icon] follows its answer instead.
     */
    fun iconFor(on: Boolean): ImageVector = when (this) {
        OFFLINE_MODE -> if (on) Icons.Rounded.CloudOff else Icons.Outlined.Cloud
        DATA_SAVER -> if (on) Icons.Rounded.DataSaverOn else Icons.Outlined.DataSaverOff
        MOBILE_DATA_QUALITY -> Icons.Rounded.SignalCellularAlt
        WIFI_QUALITY -> Icons.Rounded.Wifi
        LIQUID_GLASS -> if (on) Icons.Rounded.AutoAwesome else Icons.Outlined.AutoAwesome
        ANIMATED_COVER -> if (on) Icons.Rounded.MotionPhotosOn else Icons.Outlined.MotionPhotosPaused
        THEME -> Icons.Rounded.Brightness4
        SYNCED_LYRICS -> if (on) Icons.Rounded.Lyrics else Icons.Outlined.Lyrics
        TINT -> if (on) Icons.Rounded.Palette else Icons.Outlined.Palette
        PLAYER_THEME -> SfIcons.Gradient
        LOUDNESS -> if (on) Icons.Rounded.GraphicEq else Icons.Outlined.Equalizer
        AUTOMIX -> if (on) Icons.Rounded.AutoMode else Icons.Outlined.AutoMode
        SKIP_SILENCE -> if (on) Icons.Rounded.FastForward else Icons.Outlined.FastForward
        SPATIAL_AUDIO -> if (on) Icons.Rounded.SpatialAudio else Icons.Outlined.SpatialAudioOff
        DOLBY_ATMOS -> if (on) Icons.Rounded.SurroundSound else Icons.Outlined.SurroundSound
        LOSSLESS_FROM_START -> if (on) Icons.Rounded.HourglassFull else Icons.Outlined.HourglassEmpty
        DOWNLOAD_QUALITY -> Icons.Rounded.Download
        DOWNLOAD_WIFI_ONLY -> if (on) Icons.Rounded.Wifi else Icons.Outlined.SignalCellularAlt
        REDUCE_ANIMATION -> if (on) Icons.Rounded.MotionPhotosOff else Icons.Outlined.MotionPhotosOn
        REDUCE_BLUR -> if (on) Icons.Rounded.BlurOff else Icons.Outlined.BlurOn
        HIDE_STATUS_BAR -> if (on) Icons.Rounded.Fullscreen else Icons.Outlined.FullscreenExit
        PREFER_MUSIC_ONLY -> if (on) Icons.Rounded.MusicNote else Icons.Outlined.MusicVideo
        NERD_STATS -> if (on) Icons.Rounded.Insights else Icons.Outlined.Insights
    }

    /** The switch behind it, or null when it is a choice of more than two. */
    val toggle: Pair<StateFlow<Boolean>, (Boolean) -> Unit>?
        get() = when (this) {
            OFFLINE_MODE -> AppSettings.offlineMode to AppSettings::setOfflineMode
            DATA_SAVER -> AppSettings.dataSaver to AppSettings::setDataSaver
            LIQUID_GLASS -> AppSettings.liquidGlass to AppSettings::setLiquidGlass
            ANIMATED_COVER -> AppSettings.animatedCanvas to AppSettings::setAnimatedCanvas
            SYNCED_LYRICS -> AppSettings.syncedLyrics to AppSettings::setSyncedLyrics
            TINT -> AppSettings.tintWithNowPlaying to AppSettings::setTintWithNowPlaying
            LOUDNESS -> AppSettings.loudnessNormalization to AppSettings::setLoudnessNormalization
            AUTOMIX -> AppSettings.smartFadeEnabled to AppSettings::setSmartFadeEnabled
            SKIP_SILENCE -> AppSettings.skipSilence to AppSettings::setSkipSilence
            SPATIAL_AUDIO -> AppSettings.spatialAudio to AppSettings::setSpatialAudio
            DOLBY_ATMOS -> AppSettings.dolbyAtmos to AppSettings::setDolbyAtmos
            LOSSLESS_FROM_START -> AppSettings.losslessFromStart to AppSettings::setLosslessFromStart
            DOWNLOAD_WIFI_ONLY -> AppSettings.wifiOnlyDownloads to AppSettings::setWifiOnlyDownloads
            REDUCE_ANIMATION -> AppSettings.reduceAnimation to AppSettings::setReduceAnimation
            REDUCE_BLUR -> AppSettings.reduceDynamicBlur to AppSettings::setReduceDynamicBlur
            HIDE_STATUS_BAR -> AppSettings.hideStatusBarInPlayer to AppSettings::setHideStatusBarInPlayer
            PREFER_MUSIC_ONLY -> AppSettings.preferMusicOnly to AppSettings::setPreferMusicOnly
            NERD_STATS -> AppSettings.showNerdStats to AppSettings::setShowNerdStats
            MOBILE_DATA_QUALITY, WIFI_QUALITY, THEME, PLAYER_THEME, DOWNLOAD_QUALITY -> null
        }
}

/** One answer to a choice: its name, and what it costs or means, when there is something to say. */
private class QuickOption(val label: String, val detail: String?)

/** A choice of several: what is in force, and every option with what picking it does. */
private class QuickChoice(
    val icon: ImageVector,
    val value: String,
    val options: List<QuickOption>,
    val selected: Int,
    val onPick: (Int) -> Unit,
)

@Composable
private fun QuickSetting.choice(): QuickChoice? = when (this) {
    QuickSetting.MOBILE_DATA_QUALITY, QuickSetting.WIFI_QUALITY -> {
        val wifi = this == QuickSetting.WIFI_QUALITY
        val current by (if (wifi) AppSettings.audioQualityWifi else AppSettings.audioQualityCellular)
            .collectAsStateWithLifecycle()
        val entries = AudioQuality.entries.reversed()
        QuickChoice(
            icon = when (current) {
                AudioQuality.LOW -> if (wifi) Icons.Rounded.Wifi1Bar else Icons.Rounded.SignalCellularAlt1Bar
                AudioQuality.MEDIUM -> if (wifi) Icons.Rounded.Wifi2Bar else Icons.Rounded.SignalCellularAlt2Bar
                else -> if (wifi) Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt
            },
            value = current.localizedLabel(),
            options = entries.map {
                QuickOption(it.localizedLabel(), stringResource(R.string.quality_hourly, it.detail, it.hourly))
            },
            selected = entries.indexOf(current),
            onPick = {
                if (wifi) AppSettings.setAudioQualityWifi(entries[it]) else AppSettings.setAudioQualityCellular(entries[it])
            },
        )
    }
    QuickSetting.THEME -> {
        val current by AppSettings.themeMode.collectAsStateWithLifecycle()
        QuickChoice(
            icon = when (current) {
                ThemeMode.LIGHT -> Icons.Rounded.LightMode
                ThemeMode.DARK -> Icons.Rounded.DarkMode
                else -> Icons.Rounded.BrightnessAuto
            },
            value = current.localizedLabel(),
            options = ThemeMode.entries.map { QuickOption(it.localizedLabel(), null) },
            selected = ThemeMode.entries.indexOf(current),
            onPick = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
        )
    }
    QuickSetting.PLAYER_THEME -> {
        val current by AppSettings.playerTheme.collectAsStateWithLifecycle()
        QuickChoice(
            icon = SfIcons.Gradient,
            value = current.localizedLabel(),
            options = PlayerTheme.entries.map { QuickOption(it.localizedLabel(), it.description()) },
            selected = PlayerTheme.entries.indexOf(current),
            onPick = { AppSettings.setPlayerTheme(PlayerTheme.entries[it]) },
        )
    }
    QuickSetting.DOWNLOAD_QUALITY -> {
        val current by AppSettings.downloadQuality.collectAsStateWithLifecycle()
        val entries = DownloadQuality.entries.reversed()
        QuickChoice(
            icon = Icons.Rounded.Download,
            value = current.localizedLabel(),
            options = entries.map {
                QuickOption(it.localizedLabel(), stringResource(R.string.quality_per_track, it.detail, it.perTrack))
            },
            selected = entries.indexOf(current),
            onPick = { AppSettings.setDownloadQuality(entries[it]) },
        )
    }
    else -> null
}

/** The ones this device can use: no glass switch where glass can't draw, no Atmos without a decoder. */
@Composable
private fun availableQuickSettings(): List<QuickSetting> {
    val glass = isGlassSupported()
    return QuickSetting.entries.filter {
        when (it) {
            QuickSetting.LIQUID_GLASS -> glass
            QuickSetting.DOLBY_ATMOS -> DeviceCodecs.playsDolbyAtmos
            else -> true
        }
    }
}

private val TileShape = RoundedCornerShape(20.dp)

/** Every tile the same height, whether its name takes one line or two. */
private val TILE_HEIGHT = 76.dp

/**
 * The pinned settings, two to a row at the top of Settings, and the way to
 * change which ones they are. A tap changes one; a long press opens the page
 * it lives on.
 */
@Composable
internal fun SettingsQuickSettingsGroup(onOpenSection: (SettingsCategory) -> Unit) {
    val names by AppSettings.quickSettings.collectAsStateWithLifecycle()
    val available = availableQuickSettings()
    val pinned = names.mapNotNull { name -> available.firstOrNull { it.name == name } }
    var editing by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<QuickSetting?>(null) }
    val haptics = rememberHaptics()

    // The header carries the Edit pill, so it is drawn here rather than by the group.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GROUP_INSET + 4.dp, end = GROUP_INSET, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.quick_settings).uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(if (pinned.isEmpty()) R.string.quick_settings_add else R.string.edit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
                .clickable { editing = true }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
    SettingsGroup(topSpacing = 0.dp) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pinned.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { setting ->
                        QuickSettingTile(
                            setting = setting,
                            onChoose = { choosing = setting },
                            onOpenPage = {
                                haptics.play(Haptic.Select)
                                onOpenSection(setting.page)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (pinned.isEmpty()) {
                Text(
                    text = stringResource(R.string.quick_settings_pick_hint, AppSettings.MAX_QUICK_SETTINGS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
    }

    choosing?.let { setting ->
        val choice = setting.choice()
        if (choice != null) {
            FrostAlertDialog(
                onDismissRequest = { choosing = null },
                title = { Text(stringResource(setting.title)) },
                text = {
                    Column {
                        choice.options.forEachIndexed { index, option ->
                            PickRow(
                                label = option.label,
                                detail = option.detail,
                                checked = index == choice.selected,
                                onClick = {
                                    haptics.play(Haptic.Select)
                                    choice.onPick(index)
                                    choosing = null
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        } else {
            choosing = null
        }
    }

    if (editing) {
        QuickSettingsEditor(
            pinned = pinned,
            available = available,
            onDone = {
                AppSettings.setQuickSettings(it.map(QuickSetting::name))
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/**
 * Which settings are pinned and in what order: the pinned ones first, each
 * movable up or down and removable, then the rest, any of which a tap adds
 * while there is room.
 */
@Composable
private fun QuickSettingsEditor(
    pinned: List<QuickSetting>,
    available: List<QuickSetting>,
    onDone: (List<QuickSetting>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(pinned) }
    val haptics = rememberHaptics()
    val full = draft.size >= AppSettings.MAX_QUICK_SETTINGS
    FrostAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_settings)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.quick_settings_pick_hint, AppSettings.MAX_QUICK_SETTINGS) +
                        " " + stringResource(R.string.quick_settings_hold_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    EditorHeader(stringResource(R.string.quick_settings_pinned) + " · ${draft.size}/${AppSettings.MAX_QUICK_SETTINGS}")
                    draft.forEachIndexed { index, setting ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {
                                haptics.play(Haptic.Tick)
                                draft = draft - setting
                            }) {
                                Icon(
                                    Icons.Rounded.RemoveCircle,
                                    contentDescription = stringResource(R.string.remove),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            Icon(setting.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(setting.title),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    haptics.play(Haptic.Tick)
                                    draft = draft.toMutableList().apply { add(index - 1, removeAt(index)) }
                                },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                            }
                            IconButton(
                                onClick = {
                                    haptics.play(Haptic.Tick)
                                    draft = draft.toMutableList().apply { add(index + 1, removeAt(index)) }
                                },
                                enabled = index < draft.lastIndex,
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                            }
                        }
                    }
                    EditorHeader(stringResource(R.string.quick_settings_more))
                    available.filterNot { it in draft }.forEach { setting ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !full) {
                                    haptics.play(Haptic.Tick)
                                    draft = draft + setting
                                }
                                .alpha(if (full) 0.4f else 1f)
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = Color(0xFF34C759),
                                )
                            }
                            Icon(setting.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(setting.title),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(draft) }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun EditorHeader(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
    )
}

/**
 * One pinned setting. On, the whole tile takes the colour of what is
 * playing — the page's tint, deepened enough to stand off the card — and
 * neutral when nothing tints the page; off, it lies flat in the card. With
 * Liquid Glass on, an active tile also catches the light along its rim. The
 * glyph itself changes with the state (see [QuickSetting.iconFor]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickSettingTile(
    setting: QuickSetting,
    onChoose: () -> Unit,
    onOpenPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val toggle = setting.toggle
    val on = toggle?.first?.collectAsStateWithLifecycle()?.value
    val choice = if (toggle == null) setting.choice() else null
    val active = on == true
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val glass = LocalLiquidGlassEnabled.current && isGlassSupported()
    // A reader, read where the tile is drawn, so the tint's fade between
    // records repaints the tile without recomposing it.
    val pageTint = com.music.bitchord.ui.theme.LocalPageBackground.current
    val flat = scheme.onBackground.copy(alpha = 0.05f)
    val activeness by animateFloatAsState(if (active) 1f else 0f, label = "quickTileActive")
    Row(
        modifier = modifier
            .height(TILE_HEIGHT)
            .clip(TileShape)
            .drawBehind {
                val tinted = activeTileColour(pageTint(), dark)
                drawRect(lerp(flat, tinted, activeness))
            }
            .then(if (active && glass) Modifier.liquidGlassRim(TileShape) else Modifier)
            .combinedClickable(
                onClick = {
                    if (toggle != null) {
                        haptics.play(if (active) Haptic.ToggleOff else Haptic.ToggleOn)
                        toggle.second(!active)
                    } else {
                        haptics.play(Haptic.Tick)
                        onChoose()
                    }
                },
                onLongClick = onOpenPage,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(
            targetState = choice?.icon ?: setting.iconFor(active),
            label = "quickTileIcon",
        ) { glyph ->
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = scheme.onBackground,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(setting.title),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = choice?.value ?: stringResource(if (active) R.string.on else R.string.off),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A choice opens a list: say so, so it isn't mistaken for a switch.
        if (choice != null) {
            Icon(
                Icons.Rounded.UnfoldMore,
                contentDescription = null,
                tint = scheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The fill of an active tile: the page's tint at a depth the card's own shade
 * can't be mistaken for — saturated enough to read as the record's colour,
 * kept on the text's far side so the black or white label stays legible. With
 * nothing tinting the page, a neutral step off the card instead.
 */
private fun activeTileColour(page: Color?, dark: Boolean): Color {
    if (page == null) return if (dark) Color(0xFF3A3A3E) else Color(0xFFDCDCE2)
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(page.toArgb(), it) }
    hsl[1] = (hsl[1] * 1.6f).coerceIn(0.22f, 0.62f)
    hsl[2] = if (dark) 0.30f else 0.80f
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
private fun PickRow(
    label: String,
    detail: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (checked) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
