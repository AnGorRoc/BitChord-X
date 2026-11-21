package com.music.bitchord.ui.screens

import android.media.AudioFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.settings.SongSwipeAction
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.components.SwipeToQueueBox
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

@Composable
internal fun SettingsAccountAndPartyGroup(
    search: SettingsSearch,
    signedIn: Boolean,
    account: Account?,
    onAccountScrobbling: () -> Unit,
    onListenTogether: () -> Unit,
) {
    val party by ListenTogether.state.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search) {
        val accountTitle = stringResource(R.string.account_integrations)
        row(accountTitle, "scrobbling", "last.fm", "listenbrainz") {
            val avatarUrl = account?.thumbnailUrl?.takeIf { signedIn }
            SettingsRow(
                icon = SfIcons.Person,
                iconContent = avatarUrl?.let { url ->
                    {
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(ICON_SIZE).clip(CircleShape),
                        )
                    }
                },
                title = accountTitle,
                subtitle = account?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                onClick = onAccountScrobbling,
            )
        }
        val listenTogetherTitle = stringResource(R.string.listen_together)
        row(listenTogetherTitle, "jam", "party", "sync", "friends") {
            SettingsRow(
                icon = SfIcons.Groups,
                title = listenTogetherTitle,
                subtitle = party.code?.let {
                    stringResource(R.string.listen_together_in_party, it)
                } ?: stringResource(R.string.listen_together_subtitle),
                badge = party.members.size.takeIf { party.inParty && it > 1 }?.toString(),
                onClick = onListenTogether,
            )
        }
    }
}

@Composable
internal fun SettingsAudioQualityGroup(
    search: SettingsSearch,
    onSources: () -> Unit,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
) {
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val offlineOn by AppSettings.offlineMode.collectAsStateWithLifecycle()
    val detectedOffline by AppSettings.effectiveOffline.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val dataSaver by AppSettings.dataSaver.collectAsStateWithLifecycle()
    val losslessDowngrade by AppSettings.losslessDowngrade.collectAsStateWithLifecycle()
    val losslessFromStart by AppSettings.losslessFromStart.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val dolbyAtmos by AppSettings.dolbyAtmos.collectAsStateWithLifecycle()
    val directSourceStream by AppSettings.directSourceStream.collectAsStateWithLifecycle()
    val dolbyAtmosSupported = DeviceCodecs.playsDolbyAtmos
    val sourceConfigs by com.music.bitchord.data.sources.SourceRegistry.configs.collectAsStateWithLifecycle()
    val atmosSource = com.music.bitchord.data.sources.SourceRegistry.mayServeDolbyAtmos(sourceConfigs)
    val bitPerfectReady by BitPerfectOutput.ready.collectAsStateWithLifecycle()
    val inParty by remember {
        ListenTogether.state.map { it.inParty }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = ListenTogether.state.value.inParty)

    SearchableSettingsGroup(search, header = stringResource(R.string.audio_quality)) {
        val offlineTitle = stringResource(R.string.offline_mode)
        row(offlineTitle, stringResource(R.string.offline_mode_subtitle), "local", "downloads") {
            SettingsRow(
                icon = SfIcons.CloudOff,
                title = offlineTitle,
                subtitle = stringResource(R.string.offline_mode_subtitle),
                badge = when {
                    offlineOn -> stringResource(R.string.in_use)
                    detectedOffline -> stringResource(R.string.offline_mode_detected)
                    else -> null
                },
                trailing = {
                    IosSwitch(
                        checked = offlineOn,
                        onCheckedChange = AppSettings::setOfflineMode,
                    )
                },
                onClick = { AppSettings.setOfflineMode(!offlineOn) },
            )
        }
        val sourceTitle = stringResource(R.string.source)
        val sourceSubtitle = stringResource(R.string.sources_subtitle)
        row(sourceTitle, sourceSubtitle, "addon", "lossless") {
            SettingsRow(
                icon = SfIcons.Extension,
                title = sourceTitle,
                subtitle = sourceSubtitle,
                onClick = onSources,
            )
        }
        val directSourceTitle = stringResource(R.string.direct_source_stream)
        row(directSourceTitle, "addon", "direct", "prefetch", "read-ahead", divided = false) {
            SettingsSubRow(
                title = directSourceTitle,
                checked = directSourceStream && !inParty,
                onCheckedChange = AppSettings::setDirectSourceStream,
                subtitle = stringResource(R.string.direct_source_stream_in_party).takeIf { inParty },
                enabled = !inParty,
            )
        }
        val onWifiTitle = stringResource(R.string.on_wifi)
        row(onWifiTitle, "wi-fi", "streaming quality") {
            SettingsRow(
                icon = SfIcons.Wifi,
                title = onWifiTitle,
                badge = stringResource(R.string.in_use).takeIf { metered == false },
                value = wifiQuality.localizedLabel(),
                onClick = { onOpenChoice(SettingsChoiceKind.AUDIO_QUALITY_WIFI) },
            )
        }
        val dataSaverTitle = stringResource(R.string.data_saver)
        row(dataSaverTitle, "cellular", "mobile data", "save", "streaming quality") {
            SettingsRow(
                icon = SfIcons.NetworkCheck,
                title = dataSaverTitle,
                subtitle = stringResource(R.string.data_saver_subtitle),
                trailing = {
                    IosSwitch(checked = dataSaver, onCheckedChange = AppSettings::setDataSaver)
                },
                onClick = { AppSettings.setDataSaver(!dataSaver) },
            )
        }
        val onMobileDataTitle = stringResource(R.string.on_mobile_data)
        row(onMobileDataTitle, "cellular", "streaming quality") {
            SettingsRow(
                icon = SfIcons.SignalCellularAlt,
                title = onMobileDataTitle,
                badge = stringResource(R.string.in_use).takeIf { metered == true },
                value = cellularQuality.localizedLabel(),
                onClick = { onOpenChoice(SettingsChoiceKind.AUDIO_QUALITY_CELLULAR) },
            )
        }
        val losslessDowngradeTitle = stringResource(R.string.lossless_downgrade)
        row(losslessDowngradeTitle, "lossless", "buffering", "streaming quality") {
            SettingsRow(
                icon = SfIcons.NetworkCheck,
                title = losslessDowngradeTitle,
                subtitle = stringResource(R.string.lossless_downgrade_subtitle),
                trailing = {
                    IosSwitch(
                        checked = losslessDowngrade,
                        onCheckedChange = AppSettings::setLosslessDowngrade,
                    )
                },
                onClick = { AppSettings.setLosslessDowngrade(!losslessDowngrade) },
            )
        }
        val losslessFromStartTitle = stringResource(R.string.lossless_from_start)
        row(losslessFromStartTitle, "lossless", "flac", "wait", "streaming quality") {
            SettingsRow(
                icon = SfIcons.HourglassTop,
                title = losslessFromStartTitle,
                subtitle = stringResource(R.string.lossless_from_start_subtitle),
                trailing = {
                    IosSwitch(
                        checked = losslessFromStart,
                        onCheckedChange = AppSettings::setLosslessFromStart,
                    )
                },
                onClick = { AppSettings.setLosslessFromStart(!losslessFromStart) },
            )
        }
        val dolbyAtmosTitle = stringResource(R.string.dolby_atmos)
        row(dolbyAtmosTitle, "surround", "e-ac-3") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    iconPainter = painterResource(R.drawable.ic_dolby_atmos),
                    title = dolbyAtmosTitle,
                    subtitle = stringResource(
                        when {
                            !dolbyAtmosSupported -> R.string.dolby_atmos_unavailable
                            // On, and nothing to ask: say so, rather than a switch that does nothing.
                            !atmosSource -> R.string.dolby_atmos_no_source
                            else -> R.string.dolby_atmos_subtitle
                        },
                    ),
                    badge = stringResource(R.string.dolby_atmos_needs_source)
                        .takeIf { dolbyAtmosSupported && !atmosSource && dolbyAtmos },
                    enabled = dolbyAtmosSupported,
                    trailing = {
                        IosSwitch(
                            checked = dolbyAtmos && dolbyAtmosSupported,
                            onCheckedChange = AppSettings::setDolbyAtmos,
                            enabled = dolbyAtmosSupported,
                        )
                    },
                    onClick = { AppSettings.setDolbyAtmos(!dolbyAtmos) },
                )
            }
        }
    }
}

@Composable
internal fun SettingsDownloadsSection(
    search: SettingsSearch,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
) {
    val downloadQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val wifiOnlyDownloads by AppSettings.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val autoDownloadPlaylists by AppSettings.autoDownloadPlaylists.collectAsStateWithLifecycle()
    val downloadLikedSongs by AppSettings.downloadLikedSongs.collectAsStateWithLifecycle()
    val exportDownloads by AppSettings.exportDownloads.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.downloads)) {
        val downloadQualityTitle = stringResource(R.string.download_quality)
        row(downloadQualityTitle, "offline", "lossless") {
            SettingsRow(
                icon = SfIcons.Download,
                title = downloadQualityTitle,
                subtitle = stringResource(R.string.download_quality_subtitle, downloadQuality.perTrack),
                value = downloadQuality.localizedLabel(),
                onClick = { onOpenChoice(SettingsChoiceKind.DOWNLOAD_QUALITY) },
            )
        }
        val downloadWifiOnlyTitle = stringResource(R.string.download_wifi_only)
        row(downloadWifiOnlyTitle, "wi-fi", "cellular", divided = false) {
            SettingsSubRow(
                title = downloadWifiOnlyTitle,
                checked = wifiOnlyDownloads,
                onCheckedChange = AppSettings::setWifiOnlyDownloads,
                badge = stringResource(R.string.blocking).takeIf { wifiOnlyDownloads && metered == true },
            )
        }
        val autoDownloadTitle = stringResource(R.string.auto_download_playlists)
        row(autoDownloadTitle, "playlist", "new songs", divided = false) {
            SettingsSubRow(
                title = autoDownloadTitle,
                checked = autoDownloadPlaylists,
                onCheckedChange = AppSettings::setAutoDownloadPlaylists,
            )
        }
        val downloadLikedTitle = stringResource(R.string.download_liked_songs)
        row(downloadLikedTitle, "liked", "favorites", "offline", "automatic") {
            SettingsSubRow(
                title = downloadLikedTitle,
                checked = downloadLikedSongs,
                onCheckedChange = AppSettings::setDownloadLikedSongs,
                subtitle = stringResource(R.string.download_liked_songs_subtitle),
            )
        }
        val exportDownloadsTitle = stringResource(R.string.export_compatible_downloads)
        row(exportDownloadsTitle, "music folder") {
            SettingsSubRow(
                title = exportDownloadsTitle,
                checked = exportDownloads,
                onCheckedChange = AppSettings::setExportDownloads,
                subtitle = "Music/BitChord".takeIf { exportDownloads },
            )
        }
    }
}

/** What plays next and how one track hands over to the next. */
@Composable
internal fun SettingsPlaybackSection(
    search: SettingsSearch,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
) {
    val context = LocalContext.current
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val bitPerfectReady by BitPerfectOutput.ready.collectAsStateWithLifecycle()
    val smartFade by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val automixPerformance by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val swipeRightAction by AppSettings.swipeRightAction.collectAsStateWithLifecycle()
    val swipeLeftAction by AppSettings.swipeLeftAction.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()

    SearchableSettingsGroup(
        search,
        header = stringResource(R.string.playback),
        footer = stringResource(R.string.bit_perfect_bypassed_footer).takeIf { bitPerfectReady },
    ) {
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
        val crossfadeTitle = stringResource(R.string.crossfade)
        val crossfadeDisabled = bitPerfectReady || smartFade
        row(crossfadeTitle, "fade", "gapless") {
            BypassedByBitPerfect(crossfadeDisabled) {
                SliderRow(
                    icon = SfIcons.Waves,
                    title = crossfadeTitle,
                    subtitle = stringResource(R.string.crossfade_subtitle),
                    value = if (crossfade == 0) stringResource(R.string.off) else "${crossfade}s",
                    sliderValue = crossfade.toFloat(),
                    onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                    valueRange = 0f..12f,
                    steps = 11,
                    enabled = !crossfadeDisabled,
                )
            }
        }
        val automixTitle = stringResource(R.string.automix)
        row(automixTitle, "crossfade", "smart fade", "mix") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.AutoAwesome,
                    title = automixTitle,
                    subtitle = if (smartFade) {
                        stringResource(R.string.automix_enabled_subtitle)
                    } else {
                        stringResource(R.string.automix_disabled_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = smartFade,
                            onCheckedChange = AppSettings::setSmartFadeEnabled,
                        )
                    },
                    onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
                )
            }
        }
        // Only means anything with Automix on — see [SettingsChoiceKind.AUTOMIX_PERFORMANCE].
        val automixPerformanceTitle = stringResource(R.string.automix_performance)
        row(
            automixPerformanceTitle, "cpu", "battery",
            divided = false,
            visible = smartFade,
        ) {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.Tune,
                    title = automixPerformanceTitle,
                    subtitle = stringResource(R.string.automix_performance_subtitle),
                    value = automixPerformance.localizedLabel(),
                    onClick = { onOpenChoice(SettingsChoiceKind.AUTOMIX_PERFORMANCE) },
                )
            }
        }
        val skipSilenceTitle = stringResource(R.string.skip_silence)
        row(skipSilenceTitle, "silence") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.VolumeOff,
                    title = skipSilenceTitle,
                    subtitle = if (outputStatus.actualEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        stringResource(R.string.skip_silence_float_subtitle)
                    } else {
                        stringResource(R.string.skip_silence_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = skipSilence,
                            onCheckedChange = AppSettings::setSkipSilence,
                        )
                    },
                    onClick = { AppSettings.setSkipSilence(!skipSilence) },
                )
            }
        }
        val swipeRightTitle = stringResource(R.string.swipe_right_action)
        val swipeLeftTitle = stringResource(R.string.swipe_left_action)
        val swipeHoldHint = stringResource(R.string.swipe_hold_playlist_hint)
        val swipeOptions = remember {
            listOf(SongSwipeAction.PLAY_NEXT, SongSwipeAction.ADD_TO_QUEUE, SongSwipeAction.DISABLED)
        }
        row(stringResource(R.string.preview), "swipe", "preview", "test", "queue", "gesture") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ROW_INSET, vertical = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                ) {
                    SwipeToQueueBox(
                        onSwipeAction = { action ->
                            val msg = when (action) {
                                SongSwipeAction.PLAY_NEXT -> context.getString(R.string.play_next)
                                SongSwipeAction.ADD_TO_QUEUE -> context.getString(R.string.add_to_queue)
                                SongSwipeAction.DISABLED -> context.getString(R.string.swipe_action_disabled)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        onSwipeHoldPlaylist = {
                            Toast.makeText(context, swipeHoldHint, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(com.music.bitchord.ui.theme.cardSurface())
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                Color(0xFF5856D6).copy(alpha = 0.8f),
                                                Color(0xFFFF9500).copy(alpha = 0.8f),
                                            ),
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = SfIcons.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.preview),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.swipe_plays_next),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        row(swipeRightTitle, "swipe", "queue", "playlist", "right", stringResource(R.string.play_next_on_swipe)) {
            val rightLabel = swipeRightAction.label()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                title = swipeRightTitle,
                subtitle = rightLabel,
                trailing = {
                    SwipeActionPill(action = swipeRightAction, label = rightLabel)
                },
                onClick = { AppSettings.setSwipeRightAction(swipeRightAction.next()) },
            )
            SegmentedControl(
                options = swipeOptions.map { it.label() },
                selectedIndex = swipeOptions.indexOf(swipeRightAction),
                onSelect = { AppSettings.setSwipeRightAction(swipeOptions[it]) },
                modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 12.dp),
            )
        }
        row(swipeLeftTitle, "swipe", "queue", "playlist", "left", stringResource(R.string.play_next_on_swipe)) {
            val leftLabel = swipeLeftAction.label()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                title = swipeLeftTitle,
                subtitle = leftLabel,
                trailing = {
                    SwipeActionPill(action = swipeLeftAction, label = leftLabel)
                },
                onClick = { AppSettings.setSwipeLeftAction(swipeLeftAction.next()) },
            )
            SegmentedControl(
                options = swipeOptions.map { it.label() },
                selectedIndex = swipeOptions.indexOf(swipeLeftAction),
                onSelect = { AppSettings.setSwipeLeftAction(swipeOptions[it]) },
                modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 12.dp),
            )
        }
        row(swipeHoldHint, "swipe", "playlist", "hold") {
            SettingsRow(
                icon = SfIcons.AddToPlaylist,
                title = swipeHoldHint,
            )
        }
    }
}

/** What is done to the sound itself on its way out. */
@Composable
internal fun SettingsSoundSection(
    search: SettingsSearch,
    onEqualizer: () -> Unit,
) {
    val bitPerfectReady by BitPerfectOutput.ready.collectAsStateWithLifecycle()
    val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val loudnessOffOnSpeaker by AppSettings.loudnessOffOnSpeaker.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val playingDolbyAtmos by remember {
        NerdStats.current.map { it?.isDolbyAtmos == true }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = NerdStats.current.value?.isDolbyAtmos == true)

    SearchableSettingsGroup(
        search,
        header = stringResource(R.string.settings_sound),
        footer = stringResource(R.string.bit_perfect_bypassed_footer).takeIf { bitPerfectReady },
    ) {
        val equalizerTitle = stringResource(R.string.equalizer)
        row(equalizerTitle, "eq", "bass", "treble") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.Tune,
                    title = equalizerTitle,
                    subtitle = stringResource(R.string.equalizer_subtitle),
                    onClick = onEqualizer,
                )
            }
        }
        val loudnessTitle = stringResource(R.string.loudness_normalization)
        row(loudnessTitle, "loudness", "volume", "normalize", "replaygain", "lufs") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.VolumeUp,
                    title = loudnessTitle,
                    subtitle = stringResource(R.string.loudness_normalization_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = loudnessNormalization,
                            onCheckedChange = AppSettings::setLoudnessNormalization,
                        )
                    },
                    onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
                )
            }
        }
        // Only means anything with normalization on.
        val loudnessSpeakerTitle = stringResource(R.string.loudness_off_on_speaker)
        row(
            loudnessSpeakerTitle, "loudness", "speaker", "normalize",
            divided = false,
            visible = loudnessNormalization,
        ) {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsSubRow(
                    title = loudnessSpeakerTitle,
                    checked = loudnessOffOnSpeaker,
                    onCheckedChange = AppSettings::setLoudnessOffOnSpeaker,
                )
            }
        }
        val spatialAudioTitle = stringResource(R.string.spatial_audio)
        row(spatialAudioTitle, "surround", "3d") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.SurroundSound,
                    title = spatialAudioTitle,
                    subtitle = if (playingDolbyAtmos) {
                        stringResource(R.string.spatial_audio_atmos_subtitle)
                    } else {
                        stringResource(R.string.spatial_audio_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = spatialAudio,
                            onCheckedChange = AppSettings::setSpatialAudio,
                        )
                    },
                    onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
                )
            }
        }
    }
}

/** Where the sound leaves the phone: the output path, and USB DACs. */
@Composable
internal fun SettingsOutputSection(
    search: SettingsSearch,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
    onConfirmFixedVolume: () -> Unit,
) {
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val bitPerfectMode by AppSettings.bitPerfectMode.collectAsStateWithLifecycle()
    val bitPerfectStatus by BitPerfectOutput.status.collectAsStateWithLifecycle()
    val dacVolume by BitPerfectOutput.hardwareVolume.collectAsStateWithLifecycle()
    val usbAllowFixedVolume by AppSettings.usbAllowFixedVolume.collectAsStateWithLifecycle()
    val convertForLimitedDac by AppSettings.convertForLimitedDac.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.settings_output_dac)) {
        val outputPrecisionTitle = stringResource(R.string.output_precision)
        row(outputPrecisionTitle, "pcm", "bit depth", "sample rate", "dac") {
            SettingsRow(
                icon = SfIcons.GraphicEq,
                title = outputPrecisionTitle,
                subtitle = buildString {
                    append(outputStatus.sink)
                    append(" · ")
                    append(outputStatus.deviceName)
                    (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                        ?.let { append(" · ${it / 1000.0} kHz") }
                    append(" · ")
                    append(AudioOutputStatus.encodingLabel(outputStatus))
                },
            )
            SegmentedControl(
                options = OutputPcmMode.entries.map(OutputPcmMode::label),
                selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val preferUsbDacTitle = stringResource(R.string.prefer_usb_dac)
        row(preferUsbDacTitle, "headphone", "output") {
            SettingsSubRow(
                title = preferUsbDacTitle,
                checked = preferUsbDac,
                onCheckedChange = AppSettings::setPreferUsbDac,
                badge = stringResource(R.string.connected).takeIf { outputStatus.isUsb },
            )
        }
        val bitPerfectTitle = stringResource(R.string.bit_perfect_output)
        row(bitPerfectTitle, "usb", "dac", "exclusive", "hi-res", "bitperfect") {
            SettingsRow(
                icon = SfIcons.Usb,
                title = bitPerfectTitle,
                subtitleContent = {
                    Column {
                        Text(
                            text = bitPerfectSubtitle(bitPerfectMode, bitPerfectStatus),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = bitPerfectMode.localizedLabel(),
                            modifier = Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = { onOpenChoice(SettingsChoiceKind.BIT_PERFECT) },
            )
        }
        dacVolume?.let { volume ->
            val dacVolumeTitle = stringResource(R.string.dac_volume)
            row(dacVolumeTitle, "volume", "usb", "dac", divided = false) {
                SliderRow(
                    icon = SfIcons.VolumeUp,
                    title = dacVolumeTitle,
                    subtitle = stringResource(R.string.dac_volume_subtitle),
                    value = "%.1f dB".format(volume.currentDb),
                    sliderValue = volume.currentDb.toFloat(),
                    onSliderValue = { BitPerfectOutput.setHardwareVolume(it.toDouble()) },
                    valueRange = volume.minDb.toFloat()..volume.maxDb.toFloat(),
                    steps = 0,
                )
            }
        }
        val fixedVolumeTitle = stringResource(R.string.usb_allow_fixed_volume)
        row(
            fixedVolumeTitle, "volume", "usb", "dac",
            divided = false,
            visible = bitPerfectMode == BitPerfectMode.USB_DIRECT,
        ) {
            SettingsSubRow(
                title = fixedVolumeTitle,
                subtitle = stringResource(R.string.usb_allow_fixed_volume_subtitle),
                checked = usbAllowFixedVolume,
                onCheckedChange = { on ->
                    if (on) onConfirmFixedVolume() else AppSettings.setUsbAllowFixedVolume(false)
                },
            )
        }
        val convertTitle = stringResource(R.string.convert_for_limited_dac)
        row(
            convertTitle, "convert", "resample", "usb", "dac", "bitperfect",
            divided = false,
            visible = bitPerfectMode != BitPerfectMode.OFF && bitPerfectMode.isSupported,
        ) {
            SettingsSubRow(
                title = convertTitle,
                subtitle = stringResource(R.string.convert_for_limited_dac_subtitle),
                checked = convertForLimitedDac,
                onCheckedChange = AppSettings::setConvertForLimitedDac,
            )
        }
    }
}
