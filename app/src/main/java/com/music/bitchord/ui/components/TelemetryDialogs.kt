package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    /** Shows the notice once, to anyone who has not seen it yet. */
    fun showNoticeIfNew() {
        if (!AppSettings.telemetryNoticeSeen.value && current.value == null) {
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

/** The telemetry dialogs, in the frosted alert shell every other dialog here uses. */
@Composable
fun TelemetryDialogHost(hazeState: HazeState) {
    val prompt by TelemetryPrompts.current.collectAsStateWithLifecycle()
    val level by AppSettings.telemetryLevel.collectAsStateWithLifecycle()
    val close = { TelemetryPrompts.current.value = null }

    when (val p = prompt) {
        null -> Unit

        TelemetryPrompt.Notice -> {
            val seen = { AppSettings.markTelemetryNoticeSeen() }
            TelemetryAlert(
                hazeState = hazeState,
                title = stringResource(R.string.telemetry_notice_title),
                description = stringResource(R.string.telemetry_notice_body),
                onDismiss = { seen(); close() },
                actions = listOf(
                    TelemetryAction(stringResource(R.string.telemetry_notice_ok), emphasised = true) {
                        seen(); close()
                    },
                    TelemetryAction(stringResource(R.string.telemetry_notice_choose), emphasised = false) {
                        seen(); TelemetryPrompts.openPicker()
                    },
                ),
            )
        }

        TelemetryPrompt.Picker -> {
            val labels = TelemetryLevel.values().associateWith { telemetryLevelLabel(it) }
            val details = TelemetryLevel.values().associateWith { telemetryLevelDetail(it) }
            ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.telemetry_diagnostics),
            message = stringResource(R.string.telemetry_picker_message),
            options = TelemetryLevel.values().toList(),
            selected = level,
            label = { labels.getValue(it) },
            detail = { details.getValue(it) },
            onSelect = { chosen ->
                when {
                    chosen == level -> close()
                    // Sharing less: ask first. Sharing more needs no persuading.
                    level.isMoreThan(chosen) -> TelemetryPrompts.current.value = TelemetryPrompt.Plea(chosen)
                    else -> {
                        AppSettings.setTelemetryLevel(chosen)
                        close()
                    }
                }
            },
            onDismiss = close,
            )
        }

        is TelemetryPrompt.Plea -> TelemetryAlert(
            hazeState = hazeState,
            title = stringResource(R.string.telemetry_plea_title),
            description = stringResource(R.string.telemetry_plea_body),
            onDismiss = close,
            actions = buildList {
                add(TelemetryAction(stringResource(R.string.telemetry_plea_keep), emphasised = true, onClick = close))
                if (p.target == TelemetryLevel.OFF && level == TelemetryLevel.FULL) {
                    add(
                        TelemetryAction(stringResource(R.string.telemetry_plea_minimal), emphasised = false) {
                            AppSettings.setTelemetryLevel(TelemetryLevel.MINIMAL)
                            close()
                        },
                    )
                }
                add(
                    TelemetryAction(stringResource(R.string.telemetry_plea_confirm), emphasised = false) {
                        AppSettings.setTelemetryLevel(p.target)
                        close()
                    },
                )
            },
        )
    }
}

private class TelemetryAction(val label: String, val emphasised: Boolean, val onClick: () -> Unit)

/** Title, text and stacked full-width actions: the shape of [ConfirmationAlert] with any number of actions. */
@Composable
private fun TelemetryAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    onDismiss: () -> Unit,
    actions: List<TelemetryAction>,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        actions.forEach { action ->
            AlertRule()
            AlertAction(label = action.label, emphasised = action.emphasised, onClick = action.onClick)
        }
    }
}
