package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.JamInviteLink
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyPreview
import com.music.bitchord.data.listentogether.PartyServers
import com.music.bitchord.data.listentogether.spotify.SpotifyJamBridge
import kotlinx.coroutines.launch

/**
 * A party invite from outside the app — a tapped link or a scanned square — calls in here.
 *
 * The join sheet is the whole response. It sits over whatever page was already showing (the home
 * tab if the app was opened from cold), because following an invite is a question, not a
 * destination: sending somebody to Settings › Listen Together first would move the app out from
 * under the party they were about to join, and leave them there if they backed out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PartyInviteHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val incoming by JamInviteLink.pending.collectAsStateWithLifecycle()

    // Saveable so a rotation mid-question does not lose the invite; the
    // preview is refetched from the code.
    var inviteCode by rememberSaveable { mutableStateOf<String?>(null) }
    var inviteServer by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingServerConsent by remember { mutableStateOf<Pair<String, String>?>(null) }
    var preview by remember { mutableStateOf<PartyPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var playHere by remember { mutableStateOf(true) }

    fun dismiss() {
        inviteCode = null
        inviteServer = null
        pendingServerConsent = null
        preview = null
        failure = null
        busy = false
    }

    LaunchedEffect(incoming) {
        val invite = incoming ?: return@LaunchedEffect

        // A Spotify Jam invite has nothing to confirm: the token is the whole
        // answer, so it is joined silently and the page stays where it is.
        val jamToken = invite.spotifyJamToken
        if (jamToken != null) {
            JamInviteLink.handled()
            scope.launch {
                SpotifyJamBridge.joinJam(jamToken).onFailure { problem ->
                    Toast.makeText(context, problem.message, Toast.LENGTH_LONG).show()
                }
            }
            return@LaunchedEffect
        }

        val targetServer = invite.serverUrl?.trim()?.trimEnd('/')
        val activeServer = (ListenTogether.activePartyServerBase()
            ?: ListenTogether.effectiveIdleServerBase()).trim().trimEnd('/')
        // Already in this party: there is nothing to ask and no slot to take.
        if (ListenTogether.state.value.code.equals(invite.code, ignoreCase = true)) {
            JamInviteLink.handled()
            return@LaunchedEffect
        }
        // Only a server this build does not already know needs consent to be
        // talked to at all; the built-in ones go straight to the sheet.
        val knownServer = targetServer.isNullOrBlank() ||
            targetServer.equals(activeServer, ignoreCase = true) ||
            targetServer.equals(ListenTogether.defaultServer, ignoreCase = true) ||
            PartyServers.isOriginalOfficial(targetServer)

        preview = null
        failure = null
        if (!knownServer) {
            pendingServerConsent = invite.code to targetServer.orEmpty()
        } else {
            inviteCode = invite.code
            inviteServer = targetServer
        }
        JamInviteLink.handled()
    }

    LaunchedEffect(inviteCode, inviteServer) {
        val code = inviteCode ?: return@LaunchedEffect
        if (preview?.code == code) return@LaunchedEffect
        busy = true
        failure = null
        ListenTogether.previewParty(code, inviteServer)
            .onSuccess { preview = it }
            .onFailure { failure = it.message }
        busy = false
    }

    pendingServerConsent?.let { (codeToJoin, serverToSet) ->
        val isSwitchToOfficial = serverToSet.isBlank() || PartyServers.isOriginalOfficial(serverToSet)
        AlertDialog(
            onDismissRequest = { pendingServerConsent = null },
            title = {
                Text(
                    stringResource(
                        if (isSwitchToOfficial) {
                            R.string.listen_together_official_server_dialog_title
                        } else {
                            R.string.listen_together_custom_server_dialog_title
                        }
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    if (isSwitchToOfficial) {
                        stringResource(R.string.listen_together_official_server_dialog_message, codeToJoin)
                    } else {
                        stringResource(
                            R.string.listen_together_custom_server_dialog_message,
                            serverToSet,
                            codeToJoin,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Same next step as any other invite: the confirm sheet, not an
                        // immediate join. This dialog is only consent to talk to a
                        // different server at all.
                        val server = serverToSet.ifBlank { null }
                        pendingServerConsent = null
                        preview = null
                        failure = null
                        inviteServer = server
                        inviteCode = codeToJoin
                    },
                ) {
                    Text(
                        stringResource(R.string.listen_together_custom_server_switch_and_join),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingServerConsent = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (inviteCode == null) return

    ModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        val current = preview
        when {
            current != null -> JoinConfirmSheet(
                preview = current,
                playHere = playHere,
                onPlayHereChange = { playHere = it },
                busy = busy,
                error = failure,
                onDismiss = { dismiss() },
                onJoin = {
                    busy = true
                    failure = null
                    scope.launch {
                        // Set before joining so the welcome that follows already
                        // knows how this device listens; put back if the party
                        // turns it away.
                        ListenTogether.setListenHere(playHere)
                        val inParty = ListenTogether.state.value.inParty
                        val problem = if (inviteServer != null || inParty) {
                            val target = ListenTogether.resolveSwitchTarget(
                                inviteServer,
                                ListenTogether.customServerUrl.value,
                            )
                            when (
                                val switched = ListenTogether.switchPartyWithRecovery(
                                    target,
                                    current.code,
                                    ListenTogether.nickname(),
                                )
                            ) {
                                is ListenTogether.SwitchPartyResult.Success -> null
                                is ListenTogether.SwitchPartyResult.TargetFailedRecovered ->
                                    switched.targetError
                                is ListenTogether.SwitchPartyResult.TargetFailedNoParty ->
                                    switched.targetError
                            }
                        } else {
                            ListenTogether.joinParty(current.code, ListenTogether.nickname())
                                .exceptionOrNull()?.message
                        }
                        failure = problem
                        if (problem != null) ListenTogether.setListenHere(true)
                        busy = false
                        if (problem == null) dismiss()
                    }
                },
            )

            failure != null -> InviteMessageSheet(
                message = failure.orEmpty(),
                onDismiss = { dismiss() },
            )

            else -> InviteLoadingSheet()
        }
    }
}

/** A beat while the party is looked up, on the same fully open drawer the answer arrives in. */
@Composable
private fun InviteLoadingSheet() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

/** A link that could not be looked up: said where the invitation would have been. */
@Composable
private fun InviteMessageSheet(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(
                text = stringResource(R.string.listen_together_not_now),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
