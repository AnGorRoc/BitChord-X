package com.music.bitchord.desktop

import com.music.bitchord.ui.theme.Ink
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A drop-in for Material's `AlertDialog`, drawn in the panel the Spotify import and every other desktop
 * dialog use: a centred title and message, then the actions at the right. A call site only changes its name.
 */
@Composable
internal fun DesktopAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    DesktopDialogPanel(onDismiss = onDismissRequest, maxWidth = 400, popup = true) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title != null) {
                CompositionLocalProvider(LocalContentColor provides Ink) {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) { title() }
                }
            }
            if (text != null) {
                if (title != null) Spacer(Modifier.height(5.dp))
                CompositionLocalProvider(LocalContentColor provides DesktopSecondary) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall) { text() }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dismissButton?.invoke()
            confirmButton()
        }
    }
}
