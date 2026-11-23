package com.music.bitchord.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.account.AccountDevice

/**
 * A dialog explaining that the party being joined is already active on one of the person's own devices,
 * offering a direct action to connect to it via Connect, and a cancel action.
 */
@Composable
fun OwnDevicePartyAlert(
    device: AccountDevice,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    FrostAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.listen_together_own_device_dialog_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                stringResource(R.string.listen_together_own_device_dialog_message, device.name),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConnect) {
                Text(
                    stringResource(R.string.listen_together_connect_action),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
