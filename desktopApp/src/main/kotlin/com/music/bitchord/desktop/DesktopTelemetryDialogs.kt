package com.music.bitchord.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.telemetry.TelemetryLevel
import com.music.bitchord.ui.icons.BitChordIcons

/** Which telemetry dialog is up, if any. One at a time: each one leads to the next. */
internal sealed interface DesktopTelemetryPrompt {
    data object Notice : DesktopTelemetryPrompt
    data object Picker : DesktopTelemetryPrompt
    data class Plea(val target: TelemetryLevel) : DesktopTelemetryPrompt
}

internal fun telemetryLevelLabel(level: TelemetryLevel): String = when (level) {
    TelemetryLevel.FULL -> DesktopStrings["telemetry_level_full", "Full"]
    TelemetryLevel.MINIMAL -> DesktopStrings["telemetry_level_minimal", "Minimal"]
    TelemetryLevel.OFF -> DesktopStrings["telemetry_level_off", "Off"]
}

private fun telemetryLevelDetail(level: TelemetryLevel): String = when (level) {
    TelemetryLevel.FULL -> DesktopStrings[
        "telemetry_level_full_desc",
        "Anonymous usage and error reports with details, device model and system version. The most useful for fixing bugs.",
    ]
    TelemetryLevel.MINIMAL -> DesktopStrings[
        "telemetry_level_minimal_desc",
        "Only that the app is open, with its version and platform, under an ID that changes every day. Errors are counted by type, with no details.",
    ]
    TelemetryLevel.OFF -> DesktopStrings["telemetry_level_off_desc", "Nothing is sent."]
}

private class TelemetryAction(val label: String, val emphasised: Boolean, val onClick: () -> Unit)

/** The telemetry dialogs, in the same panel as every other dialog on the PC. */
@Composable
internal fun DesktopTelemetryDialogHost() {
    val prompt by DesktopPresence.prompt.collectAsState()
    val level by DesktopPresence.telemetryLevel.collectAsState()
    val close = { DesktopPresence.prompt.value = null }

    when (val p = prompt) {
        null -> Unit

        DesktopTelemetryPrompt.Notice -> {
            val seen = { DesktopPresence.markNoticeSeen() }
            TelemetryAlert(
                title = DesktopStrings["telemetry_notice_title", "Help us fix what we can't see"],
                description = DesktopStrings[
                    "telemetry_notice_body",
                    "BitChord-X is a small project and we can't test every phone and system. When something fails, an anonymous report tells us what and where: for example, “player error, Pixel 7, Android 15, version 1.10.1”. That is all it holds. Never your account, IP address, listening history or playlists. You can change it any time in Settings.",
                ],
                onDismiss = { seen(); close() },
                actions = listOf(
                    TelemetryAction(DesktopStrings["telemetry_notice_ok", "Got it"], emphasised = true) { seen(); close() },
                    TelemetryAction(DesktopStrings["telemetry_notice_choose", "Choose what is sent"], emphasised = false) {
                        seen(); DesktopPresence.prompt.value = DesktopTelemetryPrompt.Picker
                    },
                ),
            )
        }

        DesktopTelemetryPrompt.Picker -> DesktopDialogPanel(onDismiss = close, maxWidth = 400, popup = true) {
            DialogHeading(
                title = DesktopStrings["telemetry_diagnostics", "Anonymous telemetry and error reports"],
                message = DesktopStrings[
                    "telemetry_picker_message",
                    "Never your account, IP address, listening history or playlists. Changes apply right away.",
                ],
            )
            TelemetryLevel.values().forEach { option ->
                DesktopCardRule()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            when {
                                option == level -> close()
                                // Sharing less: ask first. Sharing more needs no persuading.
                                level.isMoreThan(option) -> DesktopPresence.prompt.value = DesktopTelemetryPrompt.Plea(option)
                                else -> {
                                    DesktopPresence.setTelemetryLevel(option)
                                    close()
                                }
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(telemetryLevelLabel(option), color = Color.White)
                        Text(telemetryLevelDetail(option), style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
                    }
                    if (option == level) {
                        Icon(
                            BitChordIcons.Check,
                            DesktopStrings["selected", "Selected"],
                            tint = DesktopAccent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            DesktopCardRule()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.TextButton(onClick = close) {
                    Text(DesktopStrings["cancel", "Cancel"], color = DesktopSecondary)
                }
            }
        }

        is DesktopTelemetryPrompt.Plea -> TelemetryAlert(
            title = DesktopStrings["telemetry_plea_title", "Before you turn this off"],
            description = DesktopStrings[
                "telemetry_plea_body",
                "Most crashes happen on phones and systems we don't own, and a report is often the only way we find out. Each one holds an error and a device model, nothing about you, and a single report can fix a bug for everybody on the same device. If full reports are too much, Minimal only counts that something failed. It is your choice either way.",
            ],
            onDismiss = close,
            actions = buildList {
                add(TelemetryAction(DesktopStrings["telemetry_plea_keep", "Keep it on"], emphasised = true, onClick = close))
                if (p.target == TelemetryLevel.OFF && level == TelemetryLevel.FULL) {
                    add(
                        TelemetryAction(DesktopStrings["telemetry_plea_minimal", "Use minimal instead"], emphasised = false) {
                            DesktopPresence.setTelemetryLevel(TelemetryLevel.MINIMAL)
                            close()
                        },
                    )
                }
                add(
                    TelemetryAction(DesktopStrings["telemetry_plea_confirm", "Change anyway"], emphasised = false) {
                        DesktopPresence.setTelemetryLevel(p.target)
                        close()
                    },
                )
            },
        )
    }
}

/** The shared heading, then the actions at the right, as the Spotify import's dialog has them. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TelemetryAlert(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    actions: List<TelemetryAction>,
) {
    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 400, popup = true) {
        DialogHeading(title = title, message = description)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.Center,
        ) {
            // The main action is last, at the right edge, in the accent colour.
            actions.sortedBy { it.emphasised }.forEach { action ->
                androidx.compose.material3.TextButton(onClick = action.onClick) {
                    Text(
                        action.label,
                        color = if (action.emphasised) DesktopAccent else DesktopSecondary,
                        fontWeight = if (action.emphasised) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
