package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.TelemetryPrompts
import com.music.bitchord.ui.components.telemetryLevelLabel
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

/**
 * How this device takes part in Listen Together and Connect: whether the other
 * devices see it, whether they may drive it, and whether the player offers the
 * party button. Shown on the Listen Together page, and in a Settings search.
 */
@Composable
internal fun SettingsListenTogetherDeviceSection(search: SettingsSearch) {
    val listenTogetherButton by AppSettings.showListenTogetherButton.collectAsStateWithLifecycle()
    val deviceVisible by AppSettings.deviceVisible.collectAsStateWithLifecycle()
    val deviceAcceptsControl by AppSettings.deviceAcceptsControl.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.listen_together_this_device)) {
        val deviceVisibleTitle = stringResource(R.string.device_visible)
        row(deviceVisibleTitle, "listen together", "connect", "devices", "visible", "hidden", "party") {
            SettingsRow(
                icon = SfIcons.Groups,
                title = deviceVisibleTitle,
                subtitle = stringResource(R.string.device_visible_subtitle),
                trailing = {
                    IosSwitch(checked = deviceVisible, onCheckedChange = AppSettings::setDeviceVisible)
                },
                onClick = { AppSettings.setDeviceVisible(!deviceVisible) },
            )
        }
        val deviceControlTitle = stringResource(R.string.device_accepts_control)
        row(deviceControlTitle, "listen together", "connect", "devices", "control", "remote", "allow") {
            SettingsRow(
                icon = SfIcons.Groups,
                title = deviceControlTitle,
                subtitle = stringResource(R.string.device_accepts_control_subtitle),
                trailing = {
                    IosSwitch(checked = deviceAcceptsControl, onCheckedChange = AppSettings::setDeviceAcceptsControl)
                },
                onClick = { AppSettings.setDeviceAcceptsControl(!deviceAcceptsControl) },
            )
        }
        val listenTogetherButtonTitle = stringResource(R.string.show_listen_together_button)
        row(listenTogetherButtonTitle, "listen together", "party", "jam", "output", "headphones", "player") {
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
    }
}

/** The telemetry level, as a row for the privacy section. */
@Composable
internal fun TelemetryRow(title: String) {
    val telemetryLevel by AppSettings.telemetryLevel.collectAsStateWithLifecycle()
    SettingsRow(
        icon = SfIcons.Info,
        title = title,
        value = telemetryLevelLabel(telemetryLevel),
        onClick = TelemetryPrompts::openPicker,
    )
}

/** The launcher icon choices, each previewed, the one in use highlighted. */
@Composable
internal fun AppIconPicker(title: String) {
    val context = LocalContext.current
    var selectedIcon by remember { mutableStateOf(AppIcon.current(context)) }
    SettingsRow(icon = SfIcons.Apps, title = title)
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
