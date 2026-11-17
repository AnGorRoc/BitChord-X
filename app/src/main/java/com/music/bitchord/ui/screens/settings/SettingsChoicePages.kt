package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppFont
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.LosslessCacheLevel
import com.music.bitchord.ui.icons.SfIcons
import java.util.Locale

/** The main page's list of categories: a glyph and a name each, and a chevron into it. */
@Composable
internal fun SettingsCategoryList(onOpen: (SettingsCategory) -> Unit) {
    SettingsGroup {
        SettingsCategory.entries.forEachIndexed { index, category ->
            if (index > 0) RowDivider()
            SettingsRow(
                icon = category.icon,
                title = stringResource(category.title),
                onClick = { onOpen(category) },
            )
        }
    }
}

/**
 * One of [SettingsChoiceKind]'s settings as a page: its options in a card,
 * the one in force ticked, and what the choice is about in the footer. A pick
 * is saved at once and [onChosen] then leaves the page, as the drawer this
 * replaces closed on one.
 */
@Composable
fun SettingsChoicePage(
    choice: SettingsChoiceKind,
    onChosen: () -> Unit,
    /** Asks before a bit-perfect mode is turned on — see [SettingsScreen]. */
    onConfirmBitPerfect: (BitPerfectMode) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(choice.title))
        when (choice) {
            SettingsChoiceKind.AUDIO_QUALITY_WIFI, SettingsChoiceKind.AUDIO_QUALITY_CELLULAR -> {
                val wifi = choice == SettingsChoiceKind.AUDIO_QUALITY_WIFI
                val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
                val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = AudioQuality.entries.reversed(),
                    selected = if (wifi) wifiQuality else cellularQuality,
                    label = { it.localizedLabel() },
                    detail = { stringResource(R.string.quality_hourly, it.detail, it.hourly) },
                    footer = stringResource(
                        R.string.audio_quality_connection,
                        stringResource(if (wifi) R.string.wifi else R.string.mobile_data).lowercase(Locale.getDefault()),
                    ),
                ) { quality ->
                    if (wifi) AppSettings.setAudioQualityWifi(quality) else AppSettings.setAudioQualityCellular(quality)
                    onChosen()
                }
            }

            SettingsChoiceKind.DOWNLOAD_QUALITY -> {
                val selected by AppSettings.downloadQuality.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = DownloadQuality.entries.reversed(),
                    selected = selected,
                    label = { it.localizedLabel() },
                    detail = { stringResource(R.string.quality_per_track, it.detail, it.perTrack) },
                    footer = stringResource(R.string.download_quality_dialog_subtitle),
                ) { quality ->
                    AppSettings.setDownloadQuality(quality)
                    onChosen()
                }
            }

            SettingsChoiceKind.AUTOMIX_PERFORMANCE -> {
                val selected by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = AutomixPerformanceMode.entries,
                    selected = selected,
                    label = { it.localizedLabel() },
                    detail = {
                        stringResource(
                            when (it) {
                                AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient_subtitle
                                AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced_subtitle
                                AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance_subtitle
                            },
                        )
                    },
                    footer = stringResource(R.string.automix_performance_warning),
                ) { mode ->
                    AppSettings.setAutomixPerformanceMode(mode)
                    onChosen()
                }
            }

            SettingsChoiceKind.BIT_PERFECT -> {
                val selected by AppSettings.bitPerfectMode.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = BitPerfectMode.entries,
                    selected = selected,
                    label = { it.localizedLabel() },
                    detail = { it.description() },
                    footer = stringResource(R.string.bit_perfect_sheet_warning),
                    enabled = { it.isSupported },
                ) { mode ->
                    onChosen()
                    if (mode == BitPerfectMode.OFF) AppSettings.setBitPerfectMode(mode) else onConfirmBitPerfect(mode)
                }
            }

            SettingsChoiceKind.LOSSLESS_CACHE -> {
                val selected by AppSettings.losslessCacheLevel.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = LosslessCacheLevel.entries,
                    selected = selected,
                    label = { it.localizedLabel() },
                    detail = { it.localizedDetail() },
                    footer = stringResource(R.string.lossless_cache_subtitle),
                ) { level ->
                    AppSettings.setLosslessCacheLevel(level)
                    onChosen()
                }
            }

            SettingsChoiceKind.FONT -> {
                val selected by AppSettings.appFont.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = AppFont.entries,
                    selected = selected,
                    label = { it.localizedLabel() },
                    detail = { it.description() },
                    footer = stringResource(R.string.font_footer),
                ) { font ->
                    AppSettings.setAppFont(font)
                    onChosen()
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * A card of options, one of them ticked: the body of every choice page. A
 * label and a dimmer line under it per option; [enabled] greys out the ones
 * this device can't take.
 */
@Composable
internal fun <T> ChoiceGroup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    footer: String?,
    detail: (@Composable (T) -> String)? = null,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    SettingsGroup(footer = footer, topSpacing = 0.dp) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = ROW_INSET),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val available = enabled(option)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = available) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(option)
                    }
                    .alpha(if (available) 1f else 0.45f)
                    .heightIn(min = 52.dp)
                    .padding(horizontal = ROW_INSET, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    detail?.let {
                        Text(
                            text = it(option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (option == selected) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        SfIcons.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
