package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.telemetry.TelemetryLevel
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow

/** Which telemetry dialog is up, if any. One at a time: each one leads to the next. */
sealed interface TelemetryPrompt {
    /** The first time the app is opened: what is sent, and where to change it. */
    data object Notice : TelemetryPrompt

    /** The three levels, to pick one. */
    data object Picker : TelemetryPrompt

    /** Asked before sharing less, because the reports are how crashes get fixed. */
    data class Plea(val target: TelemetryLevel) : TelemetryPrompt
}

object TelemetryPrompts {
    val current = MutableStateFlow<TelemetryPrompt?>(null)

    fun openPicker() {
        current.value = TelemetryPrompt.Picker
    }

    /**
     * Shows the notice once, to anyone who has not seen it yet.
     *
     * It counts as seen the moment it is shown, not when a button closes it:
     * leaving the app with it open (Back, Home, an update installed over it)
     * used to leave the flag unset, and the notice came back on every launch.
     */
    fun showNoticeIfNew() {
        if (!AppSettings.telemetryNoticeSeen.value && current.value == null) {
            AppSettings.markTelemetryNoticeSeen()
            current.value = TelemetryPrompt.Notice
        }
    }
}

@Composable
fun telemetryLevelLabel(level: TelemetryLevel): String = stringResource(
    when (level) {
        TelemetryLevel.FULL -> R.string.telemetry_level_full
        TelemetryLevel.MINIMAL -> R.string.telemetry_level_minimal
        TelemetryLevel.OFF -> R.string.telemetry_level_off
    },
)

@Composable
private fun telemetryLevelDetail(level: TelemetryLevel): String = stringResource(
    when (level) {
        TelemetryLevel.FULL -> R.string.telemetry_level_full_desc
        TelemetryLevel.MINIMAL -> R.string.telemetry_level_minimal_desc
        TelemetryLevel.OFF -> R.string.telemetry_level_off_desc
    },
)

private fun telemetryLevelIcon(level: TelemetryLevel): ImageVector = when (level) {
    TelemetryLevel.FULL -> Icons.Rounded.Insights
    TelemetryLevel.MINIMAL -> Icons.Rounded.Tune
    TelemetryLevel.OFF -> Icons.Rounded.Block
}

/** The telemetry dialogs, in the card the Spotify import uses. */
@Composable
fun TelemetryDialogHost(hazeState: HazeState) {
    val prompt by TelemetryPrompts.current.collectAsStateWithLifecycle()
    val level by AppSettings.telemetryLevel.collectAsStateWithLifecycle()
    val close = { TelemetryPrompts.current.value = null }
    // Back closes the dialog instead of leaving the app underneath it.
    androidx.activity.compose.BackHandler(enabled = prompt != null, onBack = close)

    when (val p = prompt) {
        null -> Unit

        TelemetryPrompt.Notice -> {
            val seen = { AppSettings.markTelemetryNoticeSeen() }
            AlertScaffold(hazeState = hazeState, onDismiss = { seen(); close() }) {
                DialogHeading(
                    icon = Icons.Rounded.Security,
                    title = stringResource(R.string.telemetry_notice_title),
                    description = stringResource(R.string.telemetry_notice_body),
                )
                AlertAction(label = stringResource(R.string.telemetry_notice_ok), emphasised = true, onClick = { seen(); close() })
                AlertAction(
                    label = stringResource(R.string.telemetry_notice_choose),
                    emphasised = false,
                    onClick = { seen(); TelemetryPrompts.openPicker() },
                )
            }
        }

        TelemetryPrompt.Picker -> {
            AlertScaffold(hazeState = hazeState, onDismiss = close) {
                DialogHeading(
                    icon = Icons.Rounded.Security,
                    title = stringResource(R.string.telemetry_diagnostics),
                    description = stringResource(R.string.telemetry_picker_message),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TelemetryLevel.values().forEach { option ->
                        DestinationOptionCard(
                            title = telemetryLevelLabel(option),
                            subtitle = telemetryLevelDetail(option),
                            icon = telemetryLevelIcon(option),
                            selected = option == level,
                            enabled = true,
                            subtitleMaxLines = 4,
                            onClick = {
                                when {
                                    option == level -> close()
                                    // Sharing less: say why it matters first. Sharing more needs no persuading.
                                    level.isMoreThan(option) -> TelemetryPrompts.current.value = TelemetryPrompt.Plea(option)
                                    else -> {
                                        AppSettings.setTelemetryLevel(option)
                                        close()
                                    }
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = close)
            }
        }

        is TelemetryPrompt.Plea -> AlertScaffold(hazeState = hazeState, onDismiss = close) {
            DialogHeading(
                icon = Icons.Rounded.Favorite,
                title = stringResource(R.string.telemetry_plea_title),
                description = stringResource(R.string.telemetry_plea_body),
            )
            AlertAction(label = stringResource(R.string.telemetry_plea_keep), emphasised = true, onClick = close)
            if (p.target == TelemetryLevel.OFF && level == TelemetryLevel.FULL) {
                AlertAction(
                    label = stringResource(R.string.telemetry_plea_minimal),
                    emphasised = false,
                    onClick = {
                        AppSettings.setTelemetryLevel(TelemetryLevel.MINIMAL)
                        close()
                    },
                )
            }
            AlertAction(
                label = stringResource(R.string.telemetry_plea_confirm),
                emphasised = false,
                onClick = {
                    AppSettings.setTelemetryLevel(p.target)
                    close()
                },
            )
        }
    }
}

/** An icon, a title and a paragraph, centred: the head of every dialog in this style. */
@Composable
internal fun DialogHeading(icon: ImageVector?, title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = dialogInk(),
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
            color = dialogInk(),
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = dialogInk().copy(alpha = 0.70f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
