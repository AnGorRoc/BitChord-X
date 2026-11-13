package com.music.bitchord.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.AppIcon
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.components.languageDisplayNameRes
import com.music.bitchord.ui.icons.SfIcons
import java.util.Locale

@Composable
internal fun SettingsMiscellaneousSection(search: SettingsSearch) {
    val swipeRightAction by AppSettings.swipeRightAction.collectAsStateWithLifecycle()
    val swipeLeftAction by AppSettings.swipeLeftAction.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val listenTogetherButton by AppSettings.showListenTogetherButton.collectAsStateWithLifecycle()
    val hideStatusBarInPlayer by AppSettings.hideStatusBarInPlayer.collectAsStateWithLifecycle()
    val showQueueHistory by AppSettings.showQueueHistory.collectAsStateWithLifecycle()
    val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.miscellaneous)) {
        val swipeRightTitle = stringResource(R.string.swipe_right_action)
        val swipeHoldHint = stringResource(R.string.swipe_hold_playlist_hint)
        row(swipeRightTitle, "swipe", "queue", "playlist", "right", stringResource(R.string.play_next_on_swipe)) {
            val rightLabel = swipeRightAction.label()
            SettingsRow(
                icon = SfIcons.PlaylistPlay,
                title = swipeRightTitle,
                subtitle = "$rightLabel · $swipeHoldHint",
                trailing = {
                    SwipeActionPill(action = swipeRightAction, label = rightLabel)
                },
                onClick = { AppSettings.setSwipeRightAction(swipeRightAction.next()) },
            )
        }
        val swipeLeftTitle = stringResource(R.string.swipe_left_action)
        row(swipeLeftTitle, "swipe", "queue", "playlist", "left", stringResource(R.string.play_next_on_swipe)) {
            val leftLabel = swipeLeftAction.label()
            SettingsRow(
                icon = SfIcons.PlaylistAdd,
                title = swipeLeftTitle,
                subtitle = "$leftLabel · $swipeHoldHint",
                trailing = {
                    SwipeActionPill(action = swipeLeftAction, label = leftLabel)
                },
                onClick = { AppSettings.setSwipeLeftAction(swipeLeftAction.next()) },
            )
        }
        val dontRepeatSongsTitle = stringResource(R.string.dont_repeat_songs)
        row(dontRepeatSongsTitle, "radio", "suggestions") {
            SettingsRow(
                icon = SfIcons.History,
                title = dontRepeatSongsTitle,
                subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                trailing = {
                    IosSwitch(
                        checked = dontRepeatSuggestions,
                        onCheckedChange = AppSettings::setDontRepeatSuggestions,
                    )
                },
                onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
            )
        }
        val stopMusicOnCloseTitle = stringResource(R.string.stop_music_on_close)
        row(stopMusicOnCloseTitle, "notification", "background") {
            SettingsRow(
                icon = SfIcons.MusicOff,
                title = stopMusicOnCloseTitle,
                subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                trailing = {
                    IosSwitch(
                        checked = stopOnTaskRemoved,
                        onCheckedChange = AppSettings::setStopOnTaskRemoved,
                    )
                },
                onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
            )
        }
        val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
        row(hideVolumeBarTitle, "volume", "player") {
            SettingsRow(
                icon = SfIcons.VolumeOff,
                title = hideVolumeBarTitle,
                subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideVolumeBar,
                        onCheckedChange = AppSettings::setHideVolumeBar,
                    )
                },
                onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
            )
        }
        val listenTogetherButtonTitle = stringResource(R.string.show_listen_together_button)
        row(listenTogetherButtonTitle, "party", "jam", "output", "headphones", "player") {
            SettingsRow(
                icon = SfIcons.Groups,
                title = listenTogetherButtonTitle,
                subtitle = stringResource(R.string.show_listen_together_button_subtitle),
                trailing = {
                    IosSwitch(
                        checked = listenTogetherButton,
                        onCheckedChange = AppSettings::setShowListenTogetherButton,
                    )
                },
                onClick = { AppSettings.setShowListenTogetherButton(!listenTogetherButton) },
            )
        }
        val hideStatusBarTitle = stringResource(R.string.hide_status_bar_in_player)
        row(hideStatusBarTitle, "status bar", "immersive", "fullscreen", "player") {
            SettingsRow(
                icon = SfIcons.WebAssetOff,
                title = hideStatusBarTitle,
                subtitle = stringResource(R.string.hide_status_bar_in_player_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideStatusBarInPlayer,
                        onCheckedChange = AppSettings::setHideStatusBarInPlayer,
                    )
                },
                onClick = { AppSettings.setHideStatusBarInPlayer(!hideStatusBarInPlayer) },
            )
        }
        val showQueueHistoryTitle = stringResource(R.string.show_queue_history)
        row(showQueueHistoryTitle, "player", "queue", "history", "previously played") {
            SettingsRow(
                icon = SfIcons.History,
                title = showQueueHistoryTitle,
                subtitle = stringResource(R.string.show_queue_history_subtitle),
                trailing = {
                    IosSwitch(
                        checked = showQueueHistory,
                        onCheckedChange = AppSettings::setShowQueueHistory,
                    )
                },
                onClick = { AppSettings.setShowQueueHistory(!showQueueHistory) },
            )
        }
        val hideSongStatusTitle = stringResource(R.string.hide_song_status)
        row(hideSongStatusTitle, "player", "playing from", "played by") {
            SettingsRow(
                icon = SfIcons.VisibilityOff,
                title = hideSongStatusTitle,
                subtitle = stringResource(R.string.hide_song_status_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideSongStatus,
                        onCheckedChange = AppSettings::setHideSongStatus,
                    )
                },
                onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
            )
        }
    }
}

@Composable
internal fun SettingsLanguageSection(
    search: SettingsSearch,
    onAppLanguage: () -> Unit,
) {
    SearchableSettingsGroup(search, header = stringResource(R.string.language)) {
        val appLanguageTitle = stringResource(R.string.app_language)
        row(appLanguageTitle, "locale", "translate") {
            val selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
                ?: Locale.getDefault().toLanguageTag()
            SettingsRow(
                icon = SfIcons.Language,
                title = appLanguageTitle,
                subtitle = stringResource(languageDisplayNameRes(selectedLanguage)),
                onClick = onAppLanguage,
            )
        }
    }
}

@Composable
internal fun SettingsAdvancedSection(search: SettingsSearch) {
    val context = LocalContext.current
    val smartVersionAlignment by AppSettings.smartVersionAlignment.collectAsStateWithLifecycle()
    val smartSourceAlignment by AppSettings.smartSourceAlignment.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.advanced_options)) {
        val smartAlignmentTitle = stringResource(R.string.smart_version_alignment)
        row(smartAlignmentTitle, "alignment", "sync", "waveform", "video", "audio", "skit", "intro") {
            SettingsRow(
                icon = SfIcons.GraphicEq,
                title = smartAlignmentTitle,
                subtitle = stringResource(R.string.smart_version_alignment_subtitle),
                trailing = {
                    IosSwitch(
                        checked = smartVersionAlignment,
                        onCheckedChange = AppSettings::setSmartVersionAlignment,
                    )
                },
                onClick = { AppSettings.setSmartVersionAlignment(!smartVersionAlignment) },
            )
        }
        val smartSourceAlignmentTitle = stringResource(R.string.smart_source_alignment)
        row(smartSourceAlignmentTitle, "alignment", "sync", "waveform", "upgrade", "source", "lossless", divided = false) {
            SettingsSubRow(
                title = smartSourceAlignmentTitle,
                subtitle = stringResource(R.string.smart_source_alignment_subtitle),
                checked = smartVersionAlignment && smartSourceAlignment,
                onCheckedChange = AppSettings::setSmartSourceAlignment,
                enabled = smartVersionAlignment,
            )
        }
        val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
        row(showNerdStatsTitle, "debug", "bitrate", "codec") {
            SettingsRow(
                icon = SfIcons.BarChart,
                title = showNerdStatsTitle,
                subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                trailing = {
                    IosSwitch(
                        checked = nerdStats,
                        onCheckedChange = AppSettings::setShowNerdStats,
                    )
                },
                onClick = { AppSettings.setShowNerdStats(!nerdStats) },
            )
        }
        val appIconTitle = stringResource(R.string.app_icon)
        row(appIconTitle, "icon", "launcher", "logo") {
            var selectedIcon by remember { mutableStateOf(AppIcon.current(context)) }
            SettingsRow(icon = SfIcons.Apps, title = appIconTitle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon.entries.forEach { option ->
                    val selected = option == selectedIcon
                    Column(
                        modifier = Modifier
                            .width(84.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                selectedIcon = option
                                AppIcon.apply(context, option)
                            }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val preview = option.previewRes
                        if (preview == null) {
                            AndroidView(
                                factory = { ctx ->
                                    android.widget.ImageView(ctx).apply { setImageResource(option.iconRes) }
                                },
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(22.dp)),
                            )
                        } else {
                            Image(
                                painter = painterResource(preview),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(68.dp)
                                    .scale(1.5f),
                            )
                        }
                        Text(
                            text = stringResource(option.labelRes),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
