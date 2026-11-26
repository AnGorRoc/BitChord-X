package com.music.bitchord.desktop

import com.music.bitchord.ui.theme.Ink
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KeyCapBackground: Color
    @Composable get() = com.music.bitchord.ui.theme.themed(Color(0xFF242426), Color(0xFFE5E5EA))
private val KeyCapBorder: Color
    @Composable get() = Ink.copy(alpha = 0.15f)

@Composable
internal fun DesktopShortcutsDialog(onDismiss: () -> Unit) {
    val isMac = DesktopPlatform.isMac
    val cmdOrCtrl = if (isMac) "Cmd" else "Ctrl"

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 560, solid = true) {
        Column(
            Modifier
                .padding(horizontal = panelInset(22.dp), vertical = 20.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        DesktopStrings["keyboard_shortcuts", "Keyboard shortcuts"],
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        DesktopStrings["d_keyboard_shortcuts_subtitle", "Quick actions for playback, navigation and controls"],
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = DesktopStrings["close", "Close"],
                        tint = DesktopSecondary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = DesktopDivider)
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Playback
                ShortcutSection(
                    title = DesktopStrings["d_shortcuts_playback", "Playback"],
                    items = listOf(
                        DesktopStrings["d_shortcut_play_pause", "Play / Pause"] to listOf("Space"),
                        DesktopStrings["d_shortcut_next_track", "Next track"] to listOf(cmdOrCtrl, "→"),
                        DesktopStrings["d_shortcut_prev_track", "Previous track"] to listOf(cmdOrCtrl, "←"),
                        DesktopStrings["d_shortcut_seek_5s", "Seek 5 seconds"] to listOf("←", "→"),
                        DesktopStrings["d_shortcut_seek_15s", "Seek 15 seconds"] to listOf("Shift", "←", "→"),
                    )
                )

                // Volume & Modes
                ShortcutSection(
                    title = DesktopStrings["d_shortcuts_volume", "Volume & Modes"],
                    items = listOf(
                        DesktopStrings["d_shortcut_volume_up_down", "Volume up / down"] to listOf(cmdOrCtrl, "↑", "↓"),
                        DesktopStrings["d_shortcut_mute", "Mute / Unmute"] to (if (isMac) listOf("Cmd", "Shift", "M") else listOf("Ctrl", "M")),
                        DesktopStrings["d_shortcut_shuffle", "Toggle shuffle"] to listOf(cmdOrCtrl, "S"),
                        DesktopStrings["d_shortcut_repeat", "Cycle repeat mode"] to listOf(cmdOrCtrl, "R"),
                    )
                )

                // Views & Panels
                ShortcutSection(
                    title = DesktopStrings["d_shortcuts_modes_views", "Views & Panels"],
                    items = listOf(
                        DesktopStrings["d_shortcut_lyrics", "Toggle lyrics panel"] to listOf(cmdOrCtrl, "L"),
                        DesktopStrings["d_shortcut_queue", "Toggle queue panel"] to (if (isMac) listOf("Cmd", "J") else listOf("Ctrl", "Q")),
                        DesktopStrings["d_shortcut_now_playing", "Toggle now playing"] to listOf(cmdOrCtrl, "N"),
                        DesktopStrings["d_shortcut_like", "Like / Unlike song"] to listOf(cmdOrCtrl, "D"),
                        DesktopStrings["d_shortcut_audio_output", "Audio output device"] to listOf(cmdOrCtrl, "O"),
                        DesktopStrings["d_shortcut_search", "Search"] to listOf(cmdOrCtrl, "F"),
                        DesktopStrings["d_shortcut_settings", "Settings"] to listOf(cmdOrCtrl, ","),
                    )
                )

                // Navigation & Window
                ShortcutSection(
                    title = DesktopStrings["d_shortcuts_navigation", "Navigation & Window"],
                    items = listOf(
                        DesktopStrings["d_shortcut_back", "Go back"] to listOf("Alt", "←"),
                        DesktopStrings["d_shortcut_fullscreen", "Toggle fullscreen"] to listOf("F11"),
                        DesktopStrings["d_shortcut_close_layer", "Close panel / sheet"] to listOf("Esc"),
                        DesktopStrings["d_shortcut_close_window", "Close active view"] to listOf(cmdOrCtrl, "W"),
                        DesktopStrings["d_shortcut_help", "Show keyboard shortcuts"] to listOf("F1"),
                    )
                )

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ShortcutSection(
    title: String,
    items: List<Pair<String, List<String>>>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = DesktopAccent.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        items.forEach { (description, keys) ->
            ShortcutRow(description = description, keys = keys)
        }
    }
}

@Composable
private fun ShortcutRow(
    description: String,
    keys: List<String>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.copy(alpha = 0.9f),
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            keys.forEachIndexed { index, key ->
                if (index > 0 && key != "→" && key != "↓") {
                    Text(
                        text = "+",
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                KeyBadge(text = key)
            }
        }
    }
}

@Composable
private fun KeyBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(KeyCapBackground)
            .border(1.dp, KeyCapBorder, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
