package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.music.bitchord.R
import dev.chrisbanes.haze.HazeState

/** The newest published release of this fork, as a page anyone can open. */
internal const val LATEST_RELEASE_URL = "https://github.com/AnGorRoc/BitChord-X/releases/latest"

/**
 * Asked when the listener sends the app to a friend: the APK installed on this
 * phone, or a link to the latest release.
 *
 * The choice matters because an APK copied off this phone carries this
 * phone's signature. A friend who installs a self-built or dev copy cannot
 * later move to an official release over it — Android refuses an update signed
 * by someone else — so the release link is offered beside it.
 *
 * In a window of its own, like [InfoAlert] with `inOwnWindow`: the Listen
 * Together page has no haze of its own to frost the card with.
 */
@Composable
fun ShareAppAlert(
    onSendApk: () -> Unit,
    onSendRelease: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        // The scaffold draws its own flat scrim; the platform's would double it.
        SideEffect { window?.setDimAmount(0f) }
        AlertScaffold(hazeState = remember { HazeState() }, onDismiss = onDismiss, frosted = false) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.share_app_choice_title),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.share_app_choice_message),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            AlertRule()
            ShareOption(
                label = stringResource(R.string.share_app_send_release),
                detail = stringResource(R.string.share_app_send_release_detail),
                onClick = onSendRelease,
            )
            AlertRule()
            ShareOption(
                label = stringResource(R.string.share_app_send_apk),
                detail = stringResource(R.string.share_app_send_apk_detail),
                onClick = onSendApk,
            )
            AlertRule()
            AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
        }
    }
}

/** An action row with a second line saying what it sends, centred like [AlertAction]. */
@Composable
private fun ShareOption(label: String, detail: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(indication = null, interactionSource = interactionSource, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
    }
}
