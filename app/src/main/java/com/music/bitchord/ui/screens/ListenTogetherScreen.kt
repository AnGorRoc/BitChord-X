package com.music.bitchord.ui.screens

import com.music.bitchord.ui.icons.SfIcons
import android.content.Intent
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.foundation.clickable
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.components.QrCode
import com.music.bitchord.ui.components.QrScannerDialog
import com.music.bitchord.data.listentogether.spotify.SpotifyJamBridge
import com.music.bitchord.data.listentogether.spotify.SpotifyJamApi
import com.music.bitchord.data.listentogether.spotify.SpotifyAccountTier
import com.music.bitchord.data.listentogether.spotify.SpotifyJamMember
import com.music.bitchord.data.listentogether.spotify.SpotifyJamSession
import com.music.bitchord.data.canvas.SpotifyToken
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.ui.copyText
import com.music.bitchord.data.listentogether.JamInviteLink
import com.music.bitchord.data.listentogether.PartyInvites
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyActivity
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.listentogether.byPerson
import com.music.bitchord.data.listentogether.ServerConnectionState
import com.music.bitchord.data.listentogether.ServerUrlError
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ServerUrlError.toMessageRes(): Int = when (this) {
    ServerUrlError.Whitespace -> R.string.listen_together_err_whitespace
    ServerUrlError.InvalidScheme -> R.string.listen_together_invalid_server_url
    ServerUrlError.InvalidHost -> R.string.listen_together_invalid_server_url
    ServerUrlError.InvalidPort -> R.string.listen_together_err_invalid_port
    ServerUrlError.InvalidPath -> R.string.listen_together_err_invalid_path
    ServerUrlError.HasPath -> R.string.listen_together_err_no_path
    ServerUrlError.HasQuery -> R.string.listen_together_err_no_query
    ServerUrlError.HasFragment -> R.string.listen_together_err_no_fragment
    ServerUrlError.Malformed -> R.string.listen_together_invalid_server_url
}

/**
 * Listen together: one party, one code, up to five signed-in devices.
 *
 * A deliberately plain screen — the designed one comes later. What it is for
 * right now is the two things the feature cannot be built without: proving that
 * a code created on one phone admits another, and that both then agree, to the
 * millisecond, on where the music is. The party position under **Now playing**
 * is that second proof, and it is why this screen ticks: two devices side by
 * side should show the same number.
 *
 * There is no player binding yet. Controls sent from here move the party's
 * state and every other device sees them; nothing starts playing. That join is
 * the next piece of work and is the reason
 * [ListenTogether.partyPositionMs][com.music.bitchord.data.listentogether.ListenTogether.partyPositionMs]
 * exists.
 */
/** Which of the two names the settings dialog is editing. */
private enum class NameEdit { Person, Device }

/** A one-line text prompt, for a name. Saves only a non-blank value; blank is "keep the default". */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    hint: String,
    maxLength: Int,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(maxLength) },
                singleLine = true,
                placeholder = { Text(hint) },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherScreen(
    signedIn: Boolean,
    onSignIn: () -> Unit,
    contentPadding: PaddingValues,
    /**
     * Opens the server editor, which is mounted at the root rather than here.
     *
     * @see PartyServerEditor for why it cannot live on this screen.
     */
    onEditServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val state by ListenTogether.state.collectAsStateWithLifecycle()
    val customServer by ListenTogether.customServerUrl.collectAsStateWithLifecycle()
    val connectionState by ListenTogether.serverConnectionState.collectAsStateWithLifecycle()
    val activity by ListenTogether.activity.collectAsStateWithLifecycle()
    val spotifySession by SpotifyJamBridge.session.collectAsStateWithLifecycle()

    var codeInput by remember { mutableStateOf("") }
    // The server a pasted link named, kept beside the code it came with.
    var codeInputServer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf(ListenTogether.nickname()) }
    // Offered to the host as a setting once the party exists, not asked up front: people almost
    // never want fewer than everyone they invite.
    val maxMembers = DEFAULT_PARTY_SIZE
    var joinPlayHere by remember { mutableStateOf(true) }
    var sharingApk by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf<NameEdit?>(null) }
    var deviceName by remember { mutableStateOf(ListenTogether.deviceName()) }
    var sheet by remember { mutableStateOf<PartySheet?>(null) }
    var showQrScanner by remember { mutableStateOf(false) }

    /**
     * Starts a party with what the screen already knows - the name from
     * settings, the room size nobody has asked to change - and leaves the
     * party's own page in view once it exists. There is no preparatory sheet:
     * the name is configured elsewhere and the size is a host setting.
     */
    val createPartyNow: () -> Unit = {
        if (!busy) {
            busy = true
            failure = null
            scope.launch {
                val result = ListenTogether.createParty(nickname, maxMembers)
                if (result.isSuccess) {
                    // The old create sheet defaulted this on when the account could host a Jam.
                    val tier = runCatching { SpotifyToken.accountTier() }
                        .getOrDefault(SpotifyAccountTier.UNKNOWN)
                    if (tier.canHostJam) {
                        SpotifyJamBridge.createJam().onFailure { err ->
                            Log.w("ListenTogether", "Spotify Jam creation failed: ${err.message}")
                        }
                    }
                }
                failure = result.exceptionOrNull()?.message
                busy = false
                if (result.isSuccess) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.listen_together_msg_created, ListenTogether.state.value.code.orEmpty()),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // Always fully open: a half-height drawer hides the choices this sheet
    // exists to show.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Picked up from the Connect sheet: start a party straight away, or open
    // the code sheet to join somebody else's.
    val launch by PartyLaunch.pending.collectAsStateWithLifecycle()
    LaunchedEffect(launch, state.inParty) {
        val action = launch ?: return@LaunchedEffect
        PartyLaunch.pending.value = null
        if (!state.inParty) {
            failure = null
            when (action) {
                PartyLaunch.Action.START -> createPartyNow()
                PartyLaunch.Action.JOIN -> sheet = PartySheet.JoinCode
            }
        }
    }

    editingName?.let { which ->
        val isPerson = which == NameEdit.Person
        NameDialog(
            title = stringResource(if (isPerson) R.string.listen_together_your_name else R.string.listen_together_device_name),
            initial = if (isPerson) nickname else deviceName,
            hint = stringResource(R.string.listen_together_name_hint),
            maxLength = if (isPerson) 80 else 60,
            onSave = { value ->
                if (isPerson) {
                    nickname = value
                    ListenTogether.setNickname(value)
                } else {
                    ListenTogether.setDeviceName(value)
                    deviceName = ListenTogether.deviceName()
                }
                editingName = null
            },
            onDismiss = { editingName = null },
        )
    }

    /** The invite URL for a code, pointing at whichever server holds the party. */
    val inviteLinkFor: (String) -> String = { partyCode ->
        JamInviteLink.url(
            partyCode,
            ListenTogether.activePartyServerBase() ?: ListenTogether.effectiveIdleServerBase(),
        )
    }

    /** The system chooser, with the link and the spoken-aloud code together. */
    val shareInvite: () -> Unit = {
        state.code?.let { partyCode ->
            val message = inviteLinkFor(partyCode) +
                System.lineSeparator() + System.lineSeparator() +
                context.getString(R.string.listen_together_share_text, partyCode)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, message)
                    },
                    null,
                ),
            )
        }
    }

    /**
     * Looks a party up and puts its faces on screen, rather than joining it.
     *
     * Both doors in — a tapped link and six characters typed — come through
     * here, so what the listener agrees to is the same picture either way.
     */
    val openPartyPreview: (String, String?) -> Unit = { previewCode, previewServer ->
        busy = true
        failure = null
        scope.launch {
            ListenTogether.previewParty(previewCode, previewServer)
                .onSuccess { sheet = PartySheet.Confirm(it, previewServer) }
                .onFailure { failure = it.message }
            busy = false
        }
    }

    // A membership outlives the process; the socket does not. Opening it when
    // the screen is looked at — rather than on every cold start — is what keeps
    // a feature nobody is currently using off the radio.
    LaunchedEffect(Unit) { ListenTogether.ensureConnected() }
    DisposableEffect(Unit) {
        ListenTogether.setScreenActive(true)
        onDispose {
            ListenTogether.setScreenActive(false)
        }
    }

    sheet?.let { open ->
        ModalBottomSheet(
            onDismissRequest = {
                sheet = null
                // Closed with the message unread is still read: leaving it set
                // would surface it on the page underneath, as a line about a
                // sheet that is no longer there.
                failure = null
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            when (open) {
                PartySheet.JoinCode -> JoinPartySheet(
                    code = codeInput,
                    onCodeChange = { typed ->
                        // A pasted link carries its server; a typed code does not.
                        val pasted = PartyInvites.parse(typed)?.takeUnless { it.isSpotifyJam }
                        codeInput = pasted?.code ?: typed.filter(Char::isLetterOrDigit)
                            .uppercase()
                            .take(ListenTogether.CODE_LENGTH)
                        codeInputServer = pasted?.serverUrl
                        // Typing is the retry. Keeping "no party with that
                        // code" on screen while the code is being corrected
                        // makes it read as a verdict on what is there now.
                        failure = null
                    },
                    busy = busy,
                    enabled = ListenTogether.hasServer,
                    error = failure,
                    onScanQr = { showQrScanner = true },
                    onSubmit = { openPartyPreview(codeInput, codeInputServer) },
                )

                is PartySheet.Confirm -> JoinConfirmSheet(
                    preview = open.preview,
                    playHere = joinPlayHere,
                    onPlayHereChange = { joinPlayHere = it },
                    busy = busy,
                    error = failure,
                    onDismiss = {
                        sheet = null
                        failure = null
                    },
                    onJoin = {
                        busy = true
                        failure = null
                        scope.launch {
                            // Set before joining so the welcome that follows already knows how
                            // this device listens; put back if the party turns it away.
                            ListenTogether.setListenHere(joinPlayHere)
                            // A server named on the invite itself always wins,
                            // whether this device is idle or already live: it
                            // is the one place the target the preview came
                            // from is actually known. Otherwise, already being
                            // in a party means a switch, which keeps this
                            // device where it is if the new party turns it
                            // away.
                            val problem = if (open.server != null || state.inParty) {
                                val target = ListenTogether.resolveSwitchTarget(open.server, customServer)
                                when (
                                    val switched = ListenTogether.switchPartyWithRecovery(
                                        target,
                                        open.preview.code,
                                        nickname,
                                    )
                                ) {
                                    is ListenTogether.SwitchPartyResult.Success -> null
                                    is ListenTogether.SwitchPartyResult.TargetFailedRecovered ->
                                        switched.targetError
                                    is ListenTogether.SwitchPartyResult.TargetFailedNoParty ->
                                        switched.targetError
                                }
                            } else {
                                ListenTogether.joinParty(open.preview.code, nickname)
                                    .exceptionOrNull()?.message
                            }
                            failure = problem
                            if (problem != null) ListenTogether.setListenHere(true)
                            busy = false
                            if (problem == null) {
                                codeInput = ""
                                sheet = null
                                val host = open.preview.hostName.trim()
                                Toast.makeText(
                                    context,
                                    if (host.isEmpty()) context.getString(R.string.listen_together_msg_joined)
                                    else context.getString(R.string.listen_together_msg_joined_host, host),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
        }
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onScanned = { scannedText ->
                showQrScanner = false
                scope.launch {
                    val resolvedJam = SpotifyJamApi.resolveInvite(scannedText)
                    if (resolvedJam != null) {
                        busy = true
                        failure = null
                        SpotifyJamBridge.joinJam(resolvedJam.sessionToken)
                            .onFailure { failure = it.message }
                        busy = false
                    } else {
                        val parsed = JamInviteLink.parseInvite(scannedText)
                        if (parsed != null) {
                            val jamToken = parsed.spotifyJamToken
                            if (parsed.isSpotifyJam && jamToken != null) {
                                busy = true
                                failure = null
                                SpotifyJamBridge.joinJam(jamToken)
                                    .onFailure { failure = it.message }
                                busy = false
                            } else {
                                openPartyPreview(parsed.code, parsed.serverUrl)
                            }
                        } else {
                            val code = scannedText.filter(Char::isLetterOrDigit).uppercase().take(ListenTogether.CODE_LENGTH)
                            if (code.length == ListenTogether.CODE_LENGTH) {
                                openPartyPreview(code, null)
                            } else {
                                failure = context.getString(R.string.listen_together_invalid_qr)
                            }
                        }
                    }
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.listen_together))

        if (!state.inParty) {
            PartyLanding(
                avatarUrl = ListenTogether.myAvatarUrl(),
                enabled = ListenTogether.hasServer && !busy,
                // The last attempt's message does not belong on the next one.
                onCreate = createPartyNow,
                onJoin = {
                    failure = null
                    sheet = PartySheet.JoinCode
                },
            )
        } else {
            InAParty(
                state = state,
                inviteLink = inviteLinkFor(state.code.orEmpty()),
                spotifySession = spotifySession,
                onCopy = { scope.launch { clipboard.copyText(state.code.orEmpty()) } },
                onShareLink = shareInvite,
                onShareSpotifyJam = {
                    spotifySession?.joinSessionUrl?.let { jamUrl ->
                        val message = jamUrl + System.lineSeparator() + System.lineSeparator() +
                            context.getString(R.string.listen_together_share_spotify_jam_text)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, message)
                                },
                                null,
                            ),
                        )
                    }
                },
                onCopySpotifyJam = {
                    spotifySession?.joinSessionUrl?.let { jamUrl ->
                        scope.launch { clipboard.copyText(jamUrl) }
                        Toast.makeText(
                            context,
                            context.getString(R.string.listen_together_share_spotify_jam),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onLeave = {
                    scope.launch {
                        SpotifyJamBridge.leaveJam()
                        ListenTogether.leaveParty()
                        Toast.makeText(context, context.getString(R.string.listen_together_msg_left), Toast.LENGTH_SHORT).show()
                    }
                },
                onSetCapacity = ListenTogether::setMaxMembers,
                onSetHostOnlyControl = ListenTogether::setHostOnlyControl,
                onKick = ListenTogether::kick,
            )
            // Inside the party branch on purpose. A log of who skipped what is
            // a thing to look back over while a party is running; on the page
            // that offers to start one it is a list of somebody else's evening,
            // under a button that has not been pressed yet.
            PartyActivityList(activity)
        }

        // Only when nothing is covering it. A sheet is a drawer over the bottom
        // of this page, and every failure it can produce is already shown
        // inside it — printing the same line down here as well means printing
        // it where it cannot be read.
        if (sheet == null) {
            (failure ?: state.error)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = GROUP_INSET + 4.dp, end = GROUP_INSET + 4.dp, top = 12.dp),
                )
            }
        }

        // Last, and empty by default. Nobody setting up a party needs to think
        // about an address — there is one built in — so this is where somebody
        // running their own server comes looking, rather than the first thing
        // everybody else has to read past.
        //
        // A row and a dialog rather than a box sitting open on the page. An
        // address is typed once and then never again, and a field left on
        // screen has to explain itself continuously: what the Save beside it
        // applies to, why it greys out, what becomes of a half-typed address
        // when the page is scrolled away from. Behind a row there is nothing
        // half-typed to explain — the same shape as the ListenBrainz token.
        // How this person appears: their name in parties, and what this device is called in the Connect
        // sheet. Both live here and nowhere else now; creating a party never asks.
        SettingsGroup(
            header = stringResource(R.string.listen_together_you_header),
            footer = stringResource(R.string.listen_together_you_footer),
        ) {
            SettingsRow(
                icon = SfIcons.Person,
                title = stringResource(R.string.listen_together_your_name),
                subtitle = nickname.ifBlank { stringResource(R.string.listen_together_name_default) },
                enabled = !state.inParty,
                onClick = { editingName = NameEdit.Person },
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.VolumeUp,
                title = stringResource(R.string.listen_together_device_name),
                subtitle = deviceName,
                onClick = { editingName = NameEdit.Device },
            )
        }

        SettingsGroup(
            header = stringResource(R.string.listen_together_custom_server),
            footer = stringResource(R.string.listen_together_custom_server_footer),
        ) {
            SettingsRow(
                icon = SfIcons.Dns,
                title = stringResource(R.string.listen_together_custom_server),
                subtitleContent = {
                    Text(
                        text = customServer.ifBlank {
                            stringResource(R.string.listen_together_using_default)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (customServer.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        ServerConnectionLine(connectionState)
                    }
                },
                // Locked while in a party: changing the address under a live
                // membership would leave this device holding a token for a
                // server it no longer talks to, and the party unable to say
                // why it went quiet.
                enabled = !state.inParty && !busy,
                onClick = onEditServer,
                trailing = if (busy) ({ Spinner() }) else null,
            )
        }

        SettingsGroup(
            header = stringResource(R.string.listen_together_share_app_header),
            footer = stringResource(R.string.listen_together_share_app_footer),
        ) {
            SettingsRow(
                icon = SfIcons.Share,
                title = stringResource(R.string.listen_together_share_apk),
                subtitle = if (sharingApk) {
                    stringResource(R.string.listen_together_sharing_apk)
                } else {
                    stringResource(R.string.listen_together_share_apk_subtitle)
                },
                enabled = !sharingApk,
                trailing = if (sharingApk) ({ Spinner() }) else null,
                onClick = {
                    if (sharingApk) return@SettingsRow
                    sharingApk = true
                    scope.launch {
                        val apkUri = extractApkForSharing(context)
                        sharingApk = false
                        if (apkUri != null) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/vnd.android.package-archive"
                                putExtra(Intent.EXTRA_STREAM, apkUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    shareIntent,
                                    context.getString(R.string.listen_together_share_apk),
                                ),
                            )
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.listen_together_share_apk_error),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Whether the party server is answering, before anything else on the screen.
 *
 * Every other failure in this feature looks the same from the outside — a code
 * that will not create, a join that sits there — and most of the time the
 * answer is simply that the server is asleep or unreachable. Saying so up front
 * is the difference between a feature that looks broken and one that is waiting.
 *
 * Deliberately says nothing about *where* the server is. The address this build
 * uses is not shown here, on the row below, or in any log — see
 * [ListenTogether.customServerUrl].
 */
@Composable
private fun ServerHealthRow(
    status: ListenTogether.ServerStatus,
    onRecheck: () -> Unit,
) {
    SettingsGroup {
        SettingsRow(
            icon = when (status.health) {
                ListenTogether.Health.ONLINE -> SfIcons.CloudDone
                ListenTogether.Health.OFFLINE -> SfIcons.CloudOff
                else -> SfIcons.Cloud
            },
            title = stringResource(R.string.listen_together_server),
            subtitle = when (status.health) {
                ListenTogether.Health.ONLINE ->
                    if (status.isFallback) {
                        stringResource(R.string.listen_together_server_online_fallback, status.latencyMs)
                    } else {
                        stringResource(R.string.listen_together_server_online, status.latencyMs)
                    }
                ListenTogether.Health.OFFLINE ->
                    stringResource(R.string.listen_together_server_offline)
                ListenTogether.Health.CHECKING ->
                    stringResource(R.string.listen_together_server_checking)
                ListenTogether.Health.UNKNOWN ->
                    stringResource(R.string.listen_together_server_unknown)
            },
            trailing = if (status.health == ListenTogether.Health.CHECKING) ({ Spinner() }) else null,
            onClick = onRecheck,
        )
    }
}

/**
 * Whether the address that was typed is actually answering.
 *
 * Under the address rather than in a toast, because the interesting case is the
 * one nobody is watching for: a server that answered when it was saved and has
 * since gone quiet. A toast for that would have to fire at a moment nobody
 * asked anything, so it does not fire at all — this line simply reads
 * differently the next time the page is opened.
 */
@Composable
private fun ServerConnectionLine(connection: ServerConnectionState) {
    val (icon, tint, label) = when (connection) {
        ServerConnectionState.Checking -> Triple(
            null,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.listen_together_server_checking),
        )
        is ServerConnectionState.CustomOnline -> Triple(
            SfIcons.CloudDone,
            MaterialTheme.colorScheme.primary,
            stringResource(R.string.listen_together_status_custom_online, connection.latencyMs),
        )
        is ServerConnectionState.CustomFallback -> Triple(
            SfIcons.CloudOff,
            MaterialTheme.colorScheme.error,
            stringResource(R.string.listen_together_status_custom_fallback, connection.latencyMs),
        )
        ServerConnectionState.Offline -> Triple(
            SfIcons.CloudOff,
            MaterialTheme.colorScheme.error,
            stringResource(R.string.listen_together_status_all_offline),
        )
        // Nothing to say: the row above already reads "using the built-in one".
        is ServerConnectionState.DefaultOnline -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon == null) {
            Spinner(modifier = Modifier.size(14.dp))
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What a new party holds. Small parties are a setting for the host, not a decision at the door. */
private const val DEFAULT_PARTY_SIZE = 10

/** The six cells of [PartyCodeField], and the Join button under them. */
internal val CODE_CELL_SHAPE = RoundedCornerShape(12.dp)

/**
 * The party code, entered as six cells rather than one box.
 *
 * A code that gets read out loud and typed in by somebody else is a sequence of
 * characters, not a word. Six cells say so without a hint line: they show how
 * many are wanted, which one is being typed, and how far in the reading has
 * got — see [listen_together_code_footer][R.string.listen_together_code_footer]
 * for why the alphabet avoids O and I.
 *
 * One [BasicTextField] underneath, not six. Six fields means six focus targets
 * to hand along on every keystroke and back again on every backspace, and a
 * pasted code that lands entirely in the first one. So the real field is
 * invisible, laid over the cells at [Modifier.matchParentSize], and the cells
 * are only ever a picture of what it holds.
 */
@Composable
internal fun PartyCodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Rebuilt on every change so the selection is pinned back to the end. The
    // field spans the whole row and shows nothing, so without this a tap
    // anywhere along it would drop the caret into the middle of the code and
    // the next character would appear in a cell that isn't the lit one.
    val field = remember(code) { TextFieldValue(code, TextRange(code.length)) }

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(ListenTogether.CODE_LENGTH) { index ->
                CodeCell(
                    char = code.getOrNull(index),
                    // Only ever one cell, and only while the keyboard is up: a
                    // ring left lit on a field nobody is typing into reads as
                    // something being wrong with it.
                    active = focused && index == code.length.coerceAtMost(ListenTogether.CODE_LENGTH - 1),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        BasicTextField(
            value = field,
            onValueChange = { onCodeChange(it.text) },
            enabled = enabled,
            singleLine = true,
            // Both invisible: the cells are where the typing shows up, and a
            // second caret drifting along behind them would be the giveaway
            // that this is one field wearing a costume.
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (code.length == ListenTogether.CODE_LENGTH) onSubmit() },
            ),
            modifier = Modifier
                .matchParentSize()
                .onFocusChanged { focused = it.isFocused },
        )
    }
}

/** One character's worth of [PartyCodeField]. */
@Composable
private fun CodeCell(
    char: Char?,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Animated because the ring moves cell to cell as the code is typed, and a
    // border that simply appears one box to the right on each keystroke reads
    // as flicker rather than as travel.
    //
    // Every cell keeps a ring; only its colour changes. Lighting one and
    // leaving the other five with nothing at all is what turned six boxes into
    // a single floating square — there has to be a row of somewhere-to-type
    // first, or the lit one is not "the cell you are on", it is the only cell.
    val ring by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.outline
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        },
        label = "party code cell ring",
    )
    Box(
        modifier = modifier
            .height(52.dp)
            // surfaceVariant, because these now live in a sheet whose container
            // is the page background: a cell painted in that colour is a cell
            // nobody can see.
            .background(MaterialTheme.colorScheme.surfaceVariant, CODE_CELL_SHAPE)
            .border(1.5.dp, ring, CODE_CELL_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        if (char != null) {
            Text(
                text = char.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else if (active) {
            // A caret, and only in the empty cell being typed into — once there
            // is a character to show, the character already answers "where am I".
            val blink = rememberInfiniteTransition(label = "party code caret")
            val alpha by blink.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "party code caret alpha",
            )
            Box(
                Modifier
                    .size(width = 2.dp, height = 22.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

private val CARD_SHAPE_LG = RoundedCornerShape(24.dp)

/** One soft card: the container every block of the party page sits in, so the page reads as a few clear pieces. */
@Composable
private fun PartyCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET, vertical = 6.dp)
            .clip(CARD_SHAPE_LG)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun PartySectionTitle(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = GROUP_INSET + 10.dp, end = GROUP_INSET + 10.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InAParty(
    state: ListenTogether.State,
    inviteLink: String,
    spotifySession: SpotifyJamSession? = null,
    onCopy: () -> Unit,
    onShareLink: () -> Unit,
    onShareSpotifyJam: () -> Unit = {},
    onCopySpotifyJam: () -> Unit = {},
    onLeave: () -> Unit,
    onSetCapacity: (Int) -> Unit,
    onSetHostOnlyControl: (Boolean) -> Unit,
    onKick: (String) -> Unit,
) {
    PartyHero(
        state = state,
        inviteLink = inviteLink,
        spotifySession = spotifySession,
        onCopy = onCopy,
        onShareLink = onShareLink,
        onShareSpotifyJam = onShareSpotifyJam,
        onCopySpotifyJam = onCopySpotifyJam,
    )

    NowPlayingInTheParty(state)

    val playHere by ListenTogether.listenHere.collectAsStateWithLifecycle()
    Spacer(Modifier.height(8.dp))
    PartyCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { ListenTogether.setListenHere(!playHere) }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (playHere) SfIcons.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.listen_together_play_here),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(
                        if (playHere) R.string.listen_together_play_here_on else R.string.listen_together_play_here_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            IosSwitch(checked = playHere, onCheckedChange = { ListenTogether.setListenHere(it) })
        }
    }

    val people = state.members.byPerson()
    val spotifyCount = spotifySession?.members?.size ?: 0
    PartySectionTitle(
        text = stringResource(R.string.listen_together_people),
        trailing = "${people.size + spotifyCount} / ${state.maxMembers + spotifyCount}",
    )
    PartyCard {
        // People, not devices: one row per person, with their devices under the name.
        people.forEachIndexed { index, person ->
            if (index > 0) RowDivider()
            MemberRow(
                member = person.asMember,
                isYou = person.includes(state.you?.memberId),
                canKick = state.you?.isHost == true && !person.isHost,
                onKick = { onKick(person.lead.memberId) },
                devices = person.devices,
            )
        }
        spotifySession?.members?.forEach { spotifyMember ->
            RowDivider()
            SpotifyMemberRow(member = spotifyMember)
        }
    }

    if (state.you?.isHost == true) {
        PartySectionTitle(text = stringResource(R.string.listen_together_room_settings))
        PartyCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetHostOnlyControl(!state.hostOnlyControl) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SfIcons.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(ICON_SIZE))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.listen_together_host_only),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        stringResource(R.string.listen_together_host_only_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                IosSwitch(checked = state.hostOnlyControl, onCheckedChange = onSetHostOnlyControl)
            }
            RowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.listen_together_party_size),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                SizeStepButton("−", enabled = state.maxMembers > maxOf(2, state.members.size)) { onSetCapacity(state.maxMembers - 1) }
                Text(
                    "${state.maxMembers}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.Center,
                )
                SizeStepButton("+", enabled = state.maxMembers < 10) { onSetCapacity(state.maxMembers + 1) }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    // Leaving is the one thing on this page that is set apart, drawn as what it is instead of
    // looking like one more setting.
    OutlinedButton(
        onClick = onLeave,
        shape = RoundedCornerShape(percent = 50),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = GROUP_INSET + 4.dp).height(52.dp),
    ) {
        Icon(SfIcons.Logout, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.listen_together_leave),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        text = stringResource(R.string.listen_together_leave_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = GROUP_INSET + 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun SizeStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.3f),
        )
    }
}

/**
 * The first thing on the page and the thing people come here for: who is in, the code, and the ways
 * to hand it to somebody. The QR is behind a button because most of the time the link is enough.
 */
@Composable
private fun PartyHero(
    state: ListenTogether.State,
    inviteLink: String,
    spotifySession: SpotifyJamSession?,
    onCopy: () -> Unit,
    onShareLink: () -> Unit,
    onShareSpotifyJam: () -> Unit,
    onCopySpotifyJam: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var showQr by remember { mutableStateOf(false) }
    val hasJam = spotifySession != null && spotifySession.joinSessionUrl.isNotBlank()
    val onJam = hasJam && tab == 1
    val people = state.members.byPerson()
    val jamGreen = Color(0xFF1DB954)

    PartyCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hasJam) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                ) {
                    listOf("BitChord", "Spotify Jam").forEachIndexed { index, label ->
                        Surface(
                            modifier = Modifier.clickable { tab = index },
                            shape = RoundedCornerShape(8.dp),
                            color = if (tab == index) MaterialTheme.colorScheme.surface else Color.Transparent,
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (tab == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (!onJam) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MemberAvatarStack(
                        members = people.map { it.asMember.displayName to it.asMember.avatarUrl },
                        total = people.size,
                        faceSize = 30.dp,
                        shown = 4,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = pluralStringResource(R.plurals.listen_together_people_count, people.size, people.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            Text(
                text = stringResource(R.string.listen_together_code).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (onJam) "SPOTIFY JAM" else state.code.orEmpty(),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = if (onJam) 3.sp else 8.sp,
                ),
                color = if (onJam) jamGreen else MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    if (onJam) R.string.listen_together_scan_spotify_jam_subtitle else R.string.listen_together_code_hint_short,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )

            androidx.compose.animation.AnimatedVisibility(visible = showQr) {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val content = if (onJam) spotifySession?.joinSessionUrl.orEmpty() else inviteLink
                    if (content.isNotBlank()) {
                        QrCode(
                            content = content,
                            size = 168.dp,
                            codeText = if (onJam) "Spotify Jam" else state.code,
                            modifier = Modifier.border(
                                1.dp,
                                if (onJam) jamGreen.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.08f),
                                RoundedCornerShape(16.dp),
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = if (onJam) onShareSpotifyJam else onShareLink,
                    shape = RoundedCornerShape(percent = 50),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(SfIcons.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share), fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = if (onJam) onCopySpotifyJam else onCopy,
                    shape = RoundedCornerShape(percent = 50),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(SfIcons.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (onJam) R.string.listen_together_copy_link else R.string.copy_code), maxLines = 1)
                }
            }
            TextButton(onClick = { showQr = !showQr }, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    stringResource(if (showQr) R.string.listen_together_hide_qr else R.string.listen_together_show_qr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingInTheParty(state: ListenTogether.State) {
    val track = state.playback.track
    var positionMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.playback.seq, state.playback.isPlaying) {
        positionMs = ListenTogether.partyPositionMs() ?: 0L
        if (state.playback.isPlaying) {
            while (true) {
                delay(250)
                positionMs = ListenTogether.partyPositionMs() ?: 0L
            }
        }
    }

    val connection = connectionLine(state)
    val healthy = state.connection == ListenTogether.Connection.LIVE && state.clockSynced
    val duration = track?.durationMs?.takeIf { it > 0 }

    PartySectionTitle(text = stringResource(R.string.listen_together_now_playing))
    PartyCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (track?.thumbnailUrl != null) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp),
                        )
                    } else {
                        Icon(SfIcons.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.listen_together_nothing_playing),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (track != null && track.artist.isNotBlank()) {
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (track != null && duration != null) {
                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(elapsed(positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(elapsed(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (healthy) Color(0xFF34C759) else Color(0xFFFF9F0A)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = connection,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One line saying whether this device is actually keeping up, and how well. */
@Composable
private fun connectionLine(state: ListenTogether.State): String = when {
    state.connection != ListenTogether.Connection.LIVE ->
        stringResource(R.string.listen_together_reconnecting)
    // Live, but the clock has not been measured yet — so the position above is
    // the server's last word on it rather than this device's own reckoning, and
    // saying so is more use than a spinner.
    !state.clockSynced -> stringResource(R.string.listen_together_syncing_clock)
    else -> stringResource(R.string.listen_together_in_sync, state.roundTripMs)
}

/**
 * This device's own party picture, drawn in the slot the rows around it give
 * their glyphs — so the field beside it starts on the same line as every other
 * field on the screen.
 */
@Composable
private fun PartyAvatar(url: String?, size: Dp = ICON_SIZE) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Icon(
            SfIcons.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun MemberRow(
    member: PartyMember,
    isYou: Boolean,
    canKick: Boolean = false,
    onKick: () -> Unit = {},
    /** Every device of this person; shown under the name when there is more than one. */
    devices: List<PartyMember> = emptyList(),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (member.avatarUrl != null) {
                AsyncImage(
                    model = member.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(CircleShape),
                )
            } else {
                Text(
                    text = member.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isYou) {
                    Spacer(Modifier.width(8.dp))
                    Badge(stringResource(R.string.listen_together_you))
                }
                if (!member.playsAudio) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = stringResource(R.string.listen_together_silent_member),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // "Away" rather than "offline": the slot is still theirs, and the
            // server holds it through a grace period precisely so a tunnel or a
            // locked screen does not read to everyone else as leaving.
            if (!member.connected) {
                Text(
                    text = stringResource(R.string.listen_together_away),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (devices.size > 1) {
                Text(
                    text = devices.joinToString(" · ") { it.deviceName.ifBlank { it.displayName } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (member.isHost) {
            Spacer(Modifier.width(8.dp))
            Badge(stringResource(R.string.listen_together_host))
        } else if (canKick) {
            TextButton(onClick = onKick) { Text(stringResource(R.string.listen_together_remove)) }
        }
    }
}

@Composable
private fun SpotifyMemberRow(member: SpotifyJamMember) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF1DB954).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            if (member.avatarUrl != null) {
                AsyncImage(
                    model = member.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(CircleShape),
                )
            } else {
                Text(
                    text = (member.displayName.ifBlank { member.username }).take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF1DB954),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName.ifBlank { member.username },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Badge("Spotify")
            }
        }
        if (member.isHost) {
            Spacer(Modifier.width(8.dp))
            Badge(stringResource(R.string.listen_together_host))
        }
    }
}

@Composable
private fun PartyActivityList(entries: List<PartyActivity>) {
    if (entries.isEmpty()) return
    SettingsGroup(
        header = stringResource(R.string.listen_together_activity),
        footer = stringResource(R.string.listen_together_activity_footer),
    ) {
        val logScroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(logScroll)
                .padding(horizontal = ROW_INSET, vertical = 8.dp),
        ) {
            entries.forEach { entry ->
                val timestamp = DateFormat.format("HH:mm:ss", entry.atMs)
                Text(
                    text = "[$timestamp] ${entry.by}: ${entry.detail.ifBlank { entry.action }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier = Modifier.size(18.dp)) {
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

private fun elapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}

private suspend fun extractApkForSharing(context: android.content.Context): android.net.Uri? = withContext(Dispatchers.IO) {
    runCatching {
        val sourceApk = File(context.applicationInfo.sourceDir)
        if (!sourceApk.exists()) return@withContext null
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val targetApk = File(sharedDir, "BitChord-v${BuildConfig.VERSION_NAME}.apk")
        sourceApk.inputStream().use { input ->
            targetApk.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetApk)
    }.getOrNull()
}
