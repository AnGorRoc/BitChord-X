package com.music.bitchord.ui.components

import com.music.bitchord.R

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * Same UIAlertController shape as [UpdateAvailableDialog] — frosted card,
 * hairline rules, full-width stacked actions — but with a text field for the
 * one bit of input this alert needs.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ListenBrainzTokenAlert(
    hazeState: HazeState,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.listenbrainz_token),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = dialogInk(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.listenbrainz_token_description),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = dialogInk().copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = tokenInput,
                onValueChange = onTokenInputChange,
                placeholder = stringResource(R.string.api_token),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.save), emphasised = true, onClick = onSave)
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LastfmLoginAlert(
    hazeState: HazeState,
    usernameInput: String,
    onUsernameInputChange: (String) -> Unit,
    passwordInput: String,
    onPasswordInputChange: (String) -> Unit,
    error: String?,
    loading: Boolean,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.lastfm_login),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = dialogInk(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = error ?: stringResource(R.string.lastfm_login_description),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = if (error != null) MaterialTheme.colorScheme.error else dialogInk(),
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = usernameInput,
                onValueChange = onUsernameInputChange,
                placeholder = stringResource(R.string.username),
                enabled = !loading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(8.dp))
            PillTextField(
                value = passwordInput,
                onValueChange = onPasswordInputChange,
                placeholder = stringResource(R.string.password),
                enabled = !loading,
                isPassword = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (usernameInput.isNotBlank() && passwordInput.isNotBlank()) onSignIn() },
                ),
            )
        }
        AlertRule()
        AlertAction(
            label = if (loading) stringResource(R.string.signing_in) else stringResource(R.string.sign_in),
            emphasised = true,
            onClick = onSignIn,
            enabled = !loading && usernameInput.isNotBlank() && passwordInput.isNotBlank(),
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !loading,
        )
    }
}

/**
 * Manual token entry, for when the in-app login can't run — a WebView an OEM
 * has broken, or a token lifted from a desktop client.
 *
 * [error] carries back what the verification attempt said, because a token that
 * was mistyped or has expired is indistinguishable from one that works until
 * Discord is asked about it.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun DiscordTokenAlert(
    hazeState: HazeState,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    error: String?,
    loading: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.discord_token),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = dialogInk(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = error ?: stringResource(R.string.discord_token_description),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = if (error != null) MaterialTheme.colorScheme.error else dialogInk(),
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = tokenInput,
                onValueChange = onTokenInputChange,
                placeholder = stringResource(R.string.token),
                enabled = !loading,
                isPassword = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (tokenInput.isNotBlank()) onSave() }),
            )
        }
        AlertRule()
        AlertAction(
            label = if (loading) stringResource(R.string.checking) else stringResource(R.string.save),
            emphasised = true,
            onClick = onSave,
            enabled = !loading && tokenInput.isNotBlank(),
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !loading,
        )
    }
}

/**
 * One free-text presence field — an activity name, a button label.
 *
 * [message] is where the caller explains the field, including which `{...}`
 * variables it accepts, since that is the only place a user would find out.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun TextValueAlert(
    hazeState: HazeState,
    title: String,
    message: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** False greys Save out — for a field that isn't worth saving empty. */
    saveEnabled: Boolean = true,
    /** A third action between Save and Cancel, for a value that can be cleared. */
    onRemove: (() -> Unit)? = null,
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
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = dialogInk(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = dialogInk().copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.save),
            emphasised = true,
            onClick = onSave,
            enabled = saveEnabled,
        )
        if (onRemove != null) {
            AlertRule()
            AlertAction(label = stringResource(R.string.remove), emphasised = false, onClick = onRemove)
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/**
 * One field of a server editor: the value, its change handler, the hint, and
 * how the keyboard behaves on it. The last field always gets Done-to-save
 * while the rest advance with Next, which is what every editor below already
 * did by hand.
 */
data class EditorField(
    val value: String,
    val onChange: (String) -> Unit,
    val placeholder: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val isPassword: Boolean = false,
)

/**
 * The shape every server editor in this app shares: a centered title, the
 * last test result in place of the description, the fields, then Test above
 * Save above Cancel — plus an optional destructive row between Save and
 * Cancel for an entry that can also be removed.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ServerEditorAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    fields: List<EditorField>,
    /** What the last test said, or null before one has been run. */
    status: String?,
    statusIsGood: Boolean,
    testing: Boolean,
    /** Whether there is enough typed in to be worth testing or saving. */
    canSubmit: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
    removeLabel: String? = null,
    /** Offered only when there is something stored to remove. */
    onRemove: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = { if (!testing) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = dialogInk(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = status ?: description,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = when {
                    status == null -> dialogInk()
                    statusIsGood -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
                textAlign = TextAlign.Center,
            )
            fields.forEachIndexed { index, field ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                PillTextField(
                    value = field.value,
                    onValueChange = field.onChange,
                    placeholder = field.placeholder,
                    enabled = !testing,
                    isPassword = field.isPassword,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = field.keyboardType,
                        imeAction = if (index == fields.lastIndex) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSubmit && !testing) onSave() },
                    ),
                )
            }
        }
        AlertRule()
        // Above Save rather than beside it: an address is worth checking before
        // it is stored, and a row of three cramped buttons is what the Material
        // dialog did badly.
        AlertAction(
            label = if (testing) stringResource(R.string.testing) else stringResource(R.string.test),
            emphasised = false,
            onClick = onTest,
            enabled = canSubmit && !testing,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.save),
            emphasised = true,
            onClick = onSave,
            enabled = canSubmit && !testing,
        )
        if (onRemove != null && removeLabel != null) {
            AlertRule()
            AlertAction(
                label = removeLabel,
                emphasised = false,
                destructive = true,
                onClick = onRemove,
                enabled = !testing,
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !testing,
        )
    }
}

/**
 * One field of a hosted server editor: the saved value it opens with, the
 * hint, and how the keyboard behaves on it.
 */
data class FieldConfig(
    val initial: String,
    val placeholder: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val isPassword: Boolean = false,
)

/**
 * A server editor that owns its own state: field values, the last test
 * result and the testing flag. The caller hands over fixed field configs
 * plus what test and save mean for those values, and gets back the same
 * frosted card as [ServerEditorAlert] — without the dozen remembered states
 * each host otherwise repeats.
 *
 * The configs are fixed per call site, so remembering once per opening is
 * sound; the host leaves composition on dismiss, which is when the next
 * opening starts over from the saved values.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ServerEditorHost(
    hazeState: HazeState,
    title: String,
    description: String,
    fields: List<FieldConfig>,
    canSubmit: (List<String>) -> Boolean,
    testFailedRes: Int,
    onTest: suspend (List<String>) -> Result<Unit>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states = remember { fields.map { mutableStateOf(it.initial) } }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsGood by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val values = states.map { it.value }
    ServerEditorAlert(
        hazeState = hazeState,
        title = title,
        description = description,
        fields = fields.mapIndexed { index, field ->
            EditorField(
                value = states[index].value,
                onChange = { states[index].value = it; status = null },
                placeholder = field.placeholder,
                keyboardType = field.keyboardType,
                isPassword = field.isPassword,
            )
        },
        status = status,
        statusIsGood = statusIsGood,
        testing = testing,
        canSubmit = canSubmit(values),
        onTest = {
            testing = true
            status = null
            scope.launch {
                onTest(values).fold(
                    onSuccess = {
                        status = context.getString(R.string.connected)
                        statusIsGood = true
                    },
                    onFailure = {
                        status = context.getString(
                            testFailedRes,
                            it.message ?: context.getString(R.string.failed),
                        )
                        statusIsGood = false
                    },
                )
                testing = false
            }
        },
        onSave = { onSave(values) },
        onDismiss = onDismiss,
    )
}

/**
 * Add or edit a source that has an address — the addon editor.
 *
 * The same frosted card every other alert in this app uses, rather than the
 * Material `AlertDialog` this replaced. That one put a filled `OutlinedTextField`
 * and a row of cramped text buttons in the middle of a screen where nothing
 * else looks like that, and it read as a stock widget dropped into somebody
 * else's design.
 *
 * One field and up to four stacked actions. There is deliberately no name
 * field: an addon states its own name in its manifest, so asking the user to
 * invent one is asking for information the addon is about to supply anyway —
 * and a blank field would leave the row showing a bare hostname next to a
 * perfectly good published name.
 *
 * [status] is the one thing here that no other alert in the file needs:
 * testing an address has *three* outcomes rather than the usual two, and "it
 * answered, but not with something this app can use" is the one worth reading
 * — so a result replaces the description in place, coloured by [statusIsGood],
 * the way [LastfmLoginAlert] surfaces a failed sign-in.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AddonEditorAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    urlValue: String,
    onUrlChange: (String) -> Unit,
    urlPlaceholder: String,
    /** What the last test said, or null before one has been run. */
    status: String?,
    statusIsGood: Boolean,
    testing: Boolean,
    /** Whether there is enough typed in to be worth testing or saving. */
    canSubmit: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
    /** Offered only for a source already stored — there is nothing to remove otherwise. */
    onRemove: (() -> Unit)?,
    /**
     * What [onRemove] is called, for the callers that are not removing a source.
     *
     * The party server's address wears this same card — one field, an address
     * to test, four stacked actions — and "Remove source" would be the one line
     * on it still talking about addons.
     */
    removeLabel: String? = null,
    onDismiss: () -> Unit,
) {
    ServerEditorAlert(
        hazeState = hazeState,
        title = title,
        description = description,
        fields = listOf(
            EditorField(
                value = urlValue,
                onChange = onUrlChange,
                placeholder = urlPlaceholder,
                keyboardType = KeyboardType.Uri,
            ),
        ),
        status = status,
        statusIsGood = statusIsGood,
        testing = testing,
        canSubmit = canSubmit,
        onTest = onTest,
        onSave = onSave,
        removeLabel = removeLabel ?: stringResource(R.string.remove_source),
        onRemove = onRemove,
        onDismiss = onDismiss,
    )
}

/**
 * A name clash mid-upload: the server already holds a file under the name a
 * track would land as.
 *
 * Three stacked outcomes rather than a yes/no — overwriting destroys the
 * server copy, so the non-destructive answer leads as the emphasised action.
 * [showApplyToAll] is only true mid-batch, where one answer can carry every
 * clash still queued behind this one.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun WebDavConflictAlert(
    hazeState: HazeState,
    fileName: String,
    showApplyToAll: Boolean,
    applyToAll: Boolean,
    onApplyToAllChange: (Boolean) -> Unit,
    onOverwrite: () -> Unit,
    onKeepBoth: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.webdav_conflict_title, fileName),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = dialogInk(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.webdav_conflict_message),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = dialogInk().copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.webdav_keep_both),
            emphasised = true,
            onClick = onKeepBoth,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.webdav_overwrite),
            emphasised = false,
            onClick = onOverwrite,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.webdav_skip),
            emphasised = false,
            onClick = onSkip,
        )
        if (showApplyToAll) {
            AlertRule()
            ChoiceRow(
                label = stringResource(R.string.webdav_apply_to_all),
                detail = null,
                checked = applyToAll,
                onClick = { onApplyToAllChange(!applyToAll) },
            )
        }
    }
}

/**
 * A two-action confirmation using the same frosted UIAlertController shell as
 * the addon editor. Kept here so warnings opened from settings do not fall back
 * to a visually unrelated Material dialog.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ConfirmationAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
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
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = dialogInk(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = dialogInk().copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )
        }
        AlertRule()
        AlertAction(label = confirmLabel, emphasised = true, onClick = onConfirm)
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/**
 * [ConfirmationAlert] with nothing to confirm: a title, an explanation and
 * one OK. For telling the listener why something didn't happen, in the same
 * frosted shell as the rest, rather than in a system dialog that looks like
 * it belongs to another app.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun InfoAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    onDismiss: () -> Unit,
    /**
     * Opens the alert in a window of its own, above every other window.
     *
     * Needed whenever what prompted it can be on screen in another window: the
     * full player and the track menu are both bottom-sheet dialogs, and an
     * alert drawn in the activity's window opens *behind* them — found only
     * once the player was closed, long after the tap it was answering.
     */
    inOwnWindow: Boolean = false,
) {
    if (inOwnWindow) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            // The scaffold draws its own flat scrim; the platform's would double it.
            SideEffect { window?.setDimAmount(0f) }
            InfoAlertFrame(hazeState, title, description, onDismiss, frosted = false)
        }
    } else {
        InfoAlertFrame(hazeState, title, description, onDismiss, frosted = true)
    }
}

@Composable
private fun InfoAlertFrame(
    hazeState: HazeState,
    title: String,
    description: String,
    onDismiss: () -> Unit,
    frosted: Boolean,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss, frosted = frosted) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = dialogInk(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = dialogInk().copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )
        }
        AlertRule()
        AlertAction(label = stringResource(android.R.string.ok), emphasised = true, onClick = onDismiss)
    }
}

/**
 * Single-select list, ticked like [LyricsSourcesDialog] rather than with radio
 * buttons — same reasoning: a column of Material radios would be the one
 * Material thing left on an otherwise Apple-shaped alert.
 *
 * Picking commits immediately and closes, so there is no Save action to reach
 * for; Cancel is the only one, and it's the dismiss.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun <T> ChoiceAlert(
    hazeState: HazeState,
    title: String,
    message: String?,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
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
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = dialogInk(),
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = dialogInk().copy(alpha = 0.70f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        options.forEach { option ->
            AlertRule()
            ChoiceRow(
                label = label(option),
                detail = detail(option),
                checked = option == selected,
                onClick = { onSelect(option) },
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    detail: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(dialogInk().copy(alpha = if (checked) 0.14f else 0.05f))
            .border(0.5.dp, dialogInk().copy(alpha = if (checked) 0.40f else 0.08f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                color = dialogInk(),
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = dialogInk().copy(alpha = 0.65f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (checked) stringResource(R.string.selected) else null,
            tint = if (checked) dialogInk() else dialogInk().copy(alpha = 0.25f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The scrim and frosted card every floating dialog shares, in the shape the Spotify import uses: a 26dp
 * card of the dialog grey (or the now-playing card shade), its hairline edge and a pill-shaped main
 * button under the text.
 */
/**
 * Asks the system to blur everything behind this dialog's window, and says
 * whether it will. Android 12 and up, and only while the device allows
 * cross-window blur (battery saver and some ROMs turn it off) — anywhere else
 * this is false and the caller keeps its scrim. No dim either way: the blur
 * replaces it, so the page keeps its own brightness.
 */
@Composable
internal fun blurBehindDialogWindow(enabled: Boolean): Boolean {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return false
    if (!enabled || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return false
    val manager = view.context.getSystemService(android.view.WindowManager::class.java)
    if (manager?.isCrossWindowBlurEnabled != true) return false
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { DIALOG_BLUR_BEHIND.roundToPx() }
    SideEffect {
        window.setDimAmount(0f)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
    }
    return true
}

/** Enough to take the page out of focus, not so much it turns to a wash of colour. */
private val DIALOG_BLUR_BEHIND = 18.dp

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun AlertScaffold(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    /**
     * False for an alert in a window of its own, where [hazeState] has nothing
     * to blur — Haze reads only its own window. The card is then the plain
     * surface the reduced-blur setting uses; see [InfoAlert]'s `inOwnWindow`.
     */
    frosted: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fill = com.music.bitchord.ui.components.dialogCardFill()
    // In a window of its own the system can blur what is behind it: the page
    // goes soft at its own brightness instead of being dimmed. Only where it
    // can — otherwise, and under Reduce dynamic blur, the flat scrim as before.
    val blurredBehind = !frosted && blurBehindDialogWindow(enabled = !reduceDynamicBlur)
    val dim = Color.Black.copy(alpha = if (isDark) 0.55f else 0.28f)
    // Drawn in the app's own window, the page is blurred the way the card is,
    // through Haze — softened at its own brightness rather than dimmed, to
    // match the alerts that open in a window of their own.
    val pageBlur = frosted && !reduceDynamicBlur
    val pageColour = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                when {
                    blurredBehind -> Modifier
                    pageBlur -> Modifier.optimizedHazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = pageColour,
                            tints = emptyList(),
                            blurRadius = DIALOG_BLUR_BEHIND,
                            noiseFactor = 0f,
                            fallbackTint = HazeTint(dim),
                        ),
                    )
                    else -> Modifier.background(dim)
                },
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(min = 300.dp, max = 360.dp)
                .fillMaxWidth()
                .dialogFrostBackground(
                    hazeState = if (frosted) hazeState else null,
                    reduceDynamicBlur = reduceDynamicBlur,
                    shape = RoundedCornerShape(ALERT_CORNER),
                    fill = fill,
                    borderColor = if (isDark) DIALOG_EDGE_COLOR else DIALOG_LIGHT_EDGE_COLOR,
                    alpha = if (isDark) DIALOG_FROST_ALPHA else DIALOG_LIGHT_FROST_ALPHA,
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(vertical = 8.dp),
            content = content,
        )
    }
}
