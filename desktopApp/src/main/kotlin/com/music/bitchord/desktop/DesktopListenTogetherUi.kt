package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.rounded.Logout
import kotlinx.coroutines.delay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.listentogether.PartyInvites
import com.music.bitchord.data.listentogether.byPerson
import com.music.bitchord.data.listentogether.PartyActivity
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.listentogether.PartyPreview
import com.music.bitchord.data.listentogether.account.AccountDevice
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Desktop counterpart of Android's complete Listen Together page. */
@Composable
internal fun DesktopListenTogetherDialog(autoplayEnabled: Boolean, onDismiss: () -> Unit) {
    val state by DesktopListenTogether.state.collectAsState()
    val activity by DesktopListenTogether.activity.collectAsState()
    val server by DesktopListenTogether.customServerUrl.collectAsState()
    val serverStatus by DesktopListenTogether.serverStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    // The server a pasted link named; null for a typed code, which uses the idle server.
    var codeServer by remember { mutableStateOf<String?>(null) }
    var previewServer by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf(DesktopListenTogether.nickname()) }
    // A setting for the host once the party exists, not a decision at the door.
    val maxMembers = DEFAULT_PARTY_SIZE
    var joinPlayHere by remember { mutableStateOf(false) }
    var serverDraft by remember { mutableStateOf(server) }
    var preview by remember { mutableStateOf<PartyPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var ownDeviceAlert by remember { mutableStateOf<AccountDevice?>(null) }

    LaunchedEffect(server) {
        if (serverDraft == server || serverDraft.isBlank()) serverDraft = server
    }

    val codeFocus = remember { FocusRequester() }
    var codeFocused by remember { mutableStateOf(false) }
    val startParty: () -> Unit = {
        if (!busy) {
            busy = true
            localError = null
            scope.launch {
                DesktopListenTogether.createParty(nickname, maxMembers, autoplayEnabled)
                    .onSuccess { DesktopPlayerHost.showMessage(tr("d_msg_created", "Party started · code %1\$s", DesktopListenTogether.state.value.code.orEmpty())) }
                    .onFailure { localError = it.message }
                busy = false
            }
        }
    }
    // Picked up from the Connect sheet: start a party straight away, or put the cursor where a
    // code goes. Taken once, so reopening this page later does not repeat it.
    // A collector rather than an effect keyed on the request: clearing the request would restart a keyed
    // effect and cancel the retry below before it got to run.
    LaunchedEffect(Unit) {
        DesktopPlayerHost.pendingPartyAction.filterNotNull().collect { action ->
            DesktopPlayerHost.pendingPartyAction.value = null
            if (DesktopListenTogether.state.value.inParty) return@collect
            when (action) {
                com.music.bitchord.ui.player.PartyAction.START ->
                    if (DesktopListenTogether.canJoin() && DesktopListenTogether.hasServer) startParty()
                com.music.bitchord.ui.player.PartyAction.JOIN -> {
                    // The page is only just composing when this arrives, and a field that is not on screen
                    // yet cannot take focus: try again for a moment rather than once.
                    repeat(30) {
                        delay(120)
                        if (codeFocused) return@collect
                        runCatching { codeFocus.requestFocus() }
                    }
                }
            }
        }
    }

    // Solid and unblurred: this panel is forms and long grey explanations, which glass over a
    // blurred page made hard to read. Only a party's room needs the height; the landing and the
    // join prompt are as tall as what they hold.
    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 860, solid = true) {
        Column(desktopPanelBody(cardMax = 720.dp, cardMin = if (state.inParty) 600.dp else 0.dp, fill = true)) {
            Row(
                Modifier.fillMaxWidth().padding(start = panelInset(24.dp), end = panelInset(14.dp), top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        DesktopStrings["listen_together", "Listen together"],
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.inParty) tr("d_lt_sub_in", "Everyone hears the same song at the same moment")
                        else tr("d_lt_sub_out", "Start a room or join friends with a six-character code"),
                        color = PartySecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // A page is left by the window's back, as every other page is.
                if (!LocalDesktopPanelIsPage.current) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, tr("d_close", "Close")) }
                }
            }
            if (!LocalDesktopPanelIsPage.current) HorizontalDivider(color = DesktopCardEdge)

            when {
                state.inParty -> PartyRoom(
                    state = state,
                    activity = activity,
                    busy = busy,
                    onLeave = {
                        busy = true
                        scope.launch {
                            DesktopListenTogether.leaveParty()
                            DesktopPlayerHost.showMessage(tr("d_msg_left", "You left the party"))
                            busy = false
                        }
                    },
                )
                preview != null -> JoinConfirmation(
                    preview = preview!!,
                    foreignServerHost = previewServer
                        ?.takeUnless(DesktopListenTogether::isKnownServer)
                        ?.let(DesktopListenTogether::serverHost),
                    playHere = joinPlayHere,
                    onPlayHereChange = { joinPlayHere = it },
                    busy = busy,
                    error = localError ?: state.error,
                    onBack = { preview = null; localError = null; joinPlayHere = false },
                    onJoin = {
                        val ownDevice = DesktopListenTogether.ownDeviceInParty(preview!!.code)
                        if (ownDevice != null) {
                            preview = null
                            localError = null
                            ownDeviceAlert = ownDevice
                            return@JoinConfirmation
                        }
                        busy = true
                        localError = null
                        scope.launch {
                            // Set before joining so the welcome that follows already knows how this
                            // computer listens; put back if the party turns it away.
                            DesktopListenTogether.setListenHere(joinPlayHere)
                            DesktopListenTogether.joinParty(preview!!.code, nickname, previewServer)
                                .onSuccess {
                                    val host = preview?.hostName?.trim().orEmpty()
                                    DesktopPlayerHost.showMessage(if (host.isEmpty()) tr("d_msg_joined", "You joined the party") else tr("d_msg_joined_host", "You joined %1\$s's party", host))
                                }
                                .onFailure { problem ->
                                    val own = (problem as? DesktopListenTogether.AlreadyOnOwnDeviceException)?.device
                                        ?: DesktopListenTogether.ownDeviceInParty(preview!!.code)
                                    if (own != null) {
                                        preview = null
                                        localError = null
                                        ownDeviceAlert = own
                                    } else {
                                        localError = problem.message
                                        DesktopListenTogether.setListenHere(true)
                                    }
                                }
                            busy = false
                        }
                    },
                )
                else -> PartyLanding(
                    signedIn = DesktopListenTogether.canJoin(),
                    enabled = DesktopListenTogether.canJoin() && DesktopListenTogether.hasServer,
                    avatarUrl = DesktopListenTogether.myAvatarUrl(),
                    nickname = nickname,
                    onNicknameChange = {
                        nickname = it.take(80)
                        DesktopListenTogether.setNickname(nickname)
                    },
                    code = code,
                    onCodeChange = {
                        // A pasted link of any shape carries its server; a typed code does not.
                        val pasted = PartyInvites.parse(it)?.takeUnless { invite -> invite.isSpotifyJam }
                        code = pasted?.code
                            ?: it.filter(Char::isLetterOrDigit).uppercase().take(DesktopListenTogether.CODE_LENGTH)
                        codeServer = pasted?.serverUrl
                        localError = null
                    },
                    server = serverDraft,
                    serverStatus = serverStatus,
                    onServerChange = { serverDraft = it },
                    onSaveServer = {
                        localError = null
                        DesktopListenTogether.normalizeServerUrl(serverDraft)
                            .onSuccess { normalized ->
                                DesktopListenTogether.setCustomServerUrl(normalized)
                                    .onSuccess { serverDraft = normalized }
                                    .onFailure { localError = it.message }
                            }
                            .onFailure { localError = it.message }
                    },
                    onRefreshServer = DesktopListenTogether::refreshServerHealth,
                    busy = busy,
                    error = localError ?: state.error,
                    codeFocus = codeFocus,
                    onCodeFocusChange = { codeFocused = it },
                    onCreate = startParty,
                    onPreview = {
                        val ownDevice = DesktopListenTogether.ownDeviceInParty(code)
                        if (ownDevice != null) {
                            localError = null
                            ownDeviceAlert = ownDevice
                            return@PartyLanding
                        }
                        busy = true
                        localError = null
                        scope.launch {
                            DesktopListenTogether.previewParty(code, codeServer)
                                .onSuccess {
                                    preview = it
                                    previewServer = codeServer
                                }
                                .onFailure { problem ->
                                    val own = (problem as? DesktopListenTogether.AlreadyOnOwnDeviceException)?.device
                                        ?: DesktopListenTogether.ownDeviceInParty(code)
                                    if (own != null) {
                                        localError = null
                                        ownDeviceAlert = own
                                    } else {
                                        localError = problem.message
                                    }
                                }
                            busy = false
                        }
                    },
                )
            }
        }
    }

    ownDeviceAlert?.let { device ->
        DesktopAlertDialog(
            onDismissRequest = { ownDeviceAlert = null },
            title = { Text(tr("d_own_device_party_title", "Party on your device")) },
            text = {
                Text(
                    tr(
                        "d_own_device_party_msg",
                        "Your device “%1\$s” is already in this party. Connect to it via Connect to control playback or listen together.",
                        device.name,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val devId = device.deviceId
                        ownDeviceAlert = null
                        if (state.inParty) {
                            scope.launch {
                                DesktopListenTogether.leaveParty()
                                DesktopPlayerHost.pickDevice(devId, null)
                            }
                        } else {
                            DesktopPlayerHost.pickDevice(devId, null)
                        }
                    }
                ) {
                    Text(
                        tr("d_connect_action", "Connect"),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { ownDeviceAlert = null }) {
                    Text(tr("d_cancel", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun PartyLanding(
    signedIn: Boolean,
    enabled: Boolean,
    avatarUrl: String?,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    server: String,
    serverStatus: DesktopListenTogether.ServerStatus,
    onServerChange: (String) -> Unit,
    onSaveServer: () -> Unit,
    onRefreshServer: () -> Unit,
    busy: Boolean,
    error: String?,
    codeFocus: FocusRequester,
    onCodeFocusChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    onPreview: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = panelInset(24.dp), vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            // Same height side by side, so the two buttons sit on one line.
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PartyChoiceCard(
                    title = tr("d_start_title", "Start a party"),
                    subtitle = tr("d_start_sub", "Choose how friends see you. Anyone with the link or code can join."),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PartyAvatar(avatarUrl, 40)
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = onNicknameChange,
                            label = { Text(tr("d_nickname", "Nickname")) },
                            singleLine = true,
                            colors = fieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
                    PrimaryButton(tr("d_start_btn", "Start party"), busy, enabled, onCreate)
                }

                PartyChoiceCard(
                    title = tr("d_join_title", "Join a party"),
                    subtitle = tr("d_join_sub", "Paste an invite link or type the code. You’ll see who is inside before joining."),
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCodeChange,
                        label = { Text(tr("d_invite_field", "Invite link or code")) },
                        placeholder = { Text("ABC123") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 4.sp),
                        colors = fieldColors(),
                        keyboardActions = KeyboardActions(onDone = { if (enabled && code.length == DesktopListenTogether.CODE_LENGTH) onPreview() }),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().focusRequester(codeFocus).onFocusChanged { onCodeFocusChange(it.isFocused) },
                    )
                    Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
                    PrimaryButton(
                        label = tr("d_lookup", "Look up party"),
                        busy = busy,
                        enabled = enabled && code.length == DesktopListenTogether.CODE_LENGTH,
                        onClick = onPreview,
                    )
                }
            }
        }
        error?.takeIf(String::isNotBlank)?.let { item { Notice(it, true) } }
        item {
            // Most people never change the server, so it is one line saying whether it is up; the
            // address and its buttons open from it, and are open already when something needs fixing.
            val offline = serverStatus.health == DesktopListenTogether.Health.OFFLINE
            var serverOpen by remember { mutableStateOf(server.isNotBlank() || offline) }
            LaunchedEffect(offline) { if (offline) serverOpen = true }
            Column(Modifier.desktopCardInset(RoundedCornerShape(14.dp))) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { serverOpen = !serverOpen }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            when (serverStatus.health) {
                                DesktopListenTogether.Health.ONLINE -> Color(0xFF34C759)
                                DesktopListenTogether.Health.CHECKING -> PartySecondary
                                DesktopListenTogether.Health.OFFLINE -> DesktopDestructive
                            },
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(tr("d_server_title", "Party server"), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        serverStatusLine(serverStatus),
                        color = if (offline) DesktopDestructive else PartySecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (serverOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null,
                        tint = PartySecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                AnimatedVisibility(visible = serverOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                        Text(tr("d_server_sub", "Leave blank to use the server included with this build."), color = PartySecondary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                DesktopSearchField(
                                    query = server,
                                    onQueryChange = onServerChange,
                                    onSearch = {},
                                    placeholder = tr("d_server_field", "Custom server address"),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onRefreshServer) { Text(tr("d_test", "Test")) }
                            Button(
                                onClick = onSaveServer,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White),
                            ) { Text(if (server.isBlank()) tr("d_use_builtin", "Use built-in") else tr("d_save", "Save")) }
                        }
                    }
                }
            }
        }
    }
}

/** Field outlines bright enough to find the field by; the theme's own are close to invisible here. */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
    focusedBorderColor = DesktopAccent,
)

@Composable
private fun PartyChoiceCard(title: String, subtitle: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().desktopCardInset(RoundedCornerShape(16.dp)).padding(18.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = PartySecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun JoinConfirmation(
    preview: PartyPreview,
    /** Set when the invite points at a server this build does not already use. */
    foreignServerHost: String?,
    playHere: Boolean,
    onPlayHereChange: (Boolean) -> Unit,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onJoin: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = panelInset(32.dp), vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Groups, null, tint = DesktopAccent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (preview.hostName.isBlank()) tr("d_join_q", "Join this party?") else tr("d_join_q_host", "Join %1\$s’s party?", preview.hostName),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(tr("d_join_count", "%1\$d of %2\$d people · %3\$s", preview.memberCount, preview.maxMembers, preview.code), color = PartySecondary)
        foreignServerHost?.let { host ->
            Spacer(Modifier.height(6.dp))
            Text(
                tr("d_join_foreign", "Hosted on %1\$s. Your name and avatar will be shared with that server.", host),
                color = PartySecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            preview.members.take(5).forEach { PartyAvatar(it.avatarUrl, 42, it.displayName) }
        }
        Spacer(Modifier.height(20.dp))
        PlayHereChoice(playHere, onPlayHereChange, Modifier.width(360.dp), enabled = !busy)
        error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = DesktopDestructive) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack, enabled = !busy) { Text(tr("d_back", "Back")) }
            PrimaryButton(tr("d_join_btn", "Join party"), busy, !preview.isFull, onJoin, Modifier.width(190.dp))
        }
    }
}

@Composable
private fun PartyRoom(state: DesktopListenTogether.State, activity: List<PartyActivity>, busy: Boolean, onLeave: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = panelInset(20.dp), vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LazyColumn(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { RoomHero(state) }
            item { NowPlayingCard(state) }
            item { PlayHereCard() }
            item { QueueCard(state) }
        }
        LazyColumn(Modifier.weight(.85f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { MembersCard(state) }
            if (state.you?.isHost == true) item { HostControls(state) }
            if (activity.isNotEmpty()) item { ActivityCard(activity) }
            state.error?.let { item { Notice(it, true) } }
            item {
                OutlinedButton(
                    onClick = onLeave,
                    enabled = !busy,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, DesktopDestructive.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Icon(Icons.Rounded.Logout, null, tint = DesktopDestructive, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("d_leave", "Leave party"), color = DesktopDestructive, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    tr("d_leave_sub", "The others carry on listening"),
                    color = PartySecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RoomHero(state: DesktopListenTogether.State) {
    val invite = DesktopListenTogether.inviteUrl(state.code.orEmpty())
    var showQr by remember { mutableStateOf(true) }
    Column(
        Modifier
            .fillMaxWidth()
            .desktopCardInset(RoundedCornerShape(16.dp))
            .padding(vertical = 20.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                state.members.take(4).forEach { PartyAvatar(it.avatarUrl, 30, it.displayName) }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (state.members.size == 1) tr("d_people_one", "1 person listening") else tr("d_people_many", "%1\$d people listening", state.members.size),
                color = PartySecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            tr("d_party_code", "Party code").uppercase(),
            color = PartySecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            state.code.orEmpty(),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 6.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            tr("d_code_hint", "Tell people this code, or share the link"),
            color = PartySecondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        AnimatedVisibility(
            visible = showQr,
            enter = fadeIn(tween(200)) +
                scaleIn(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                    initialScale = 0.7f,
                    transformOrigin = TransformOrigin.Center,
                ) +
                expandVertically(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                    expandFrom = Alignment.CenterVertically,
                ),
            exit = fadeOut(tween(160)) +
                scaleOut(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                    targetScale = 0.7f,
                    transformOrigin = TransformOrigin.Center,
                ) +
                shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                    shrinkTowards = Alignment.CenterVertically,
                ),
        ) {
            Column(Modifier.padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DesktopQrCode(invite, size = 140.dp, clickableToEnlarge = true, codeText = state.code)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { DesktopExternalLinks.copy(invite); DesktopPlayerHost.showMessage(tr("d_link_copied", "Invite link copied")) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = DesktopAccent, contentColor = Color.Black),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("d_copy_link", "Copy link"), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { DesktopExternalLinks.copy(state.code.orEmpty()); DesktopPlayerHost.showMessage(tr("d_code_copied", "Code copied")) },
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("d_copy_code", "Copy code"))
            }
        }
        TextButton(onClick = { showQr = !showQr }, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (showQr) tr("d_hide_qr", "Hide QR code") else tr("d_show_qr", "Show QR code"), color = PartySecondary)
        }
    }
}

@Composable
private fun NowPlayingCard(state: DesktopListenTogether.State) {
    val track = state.playback.track
    var positionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(state.playback.seq, state.playback.isPlaying) {
        positionMs = DesktopListenTogether.partyPositionMs() ?: 0L
        if (state.playback.isPlaying) {
            while (true) {
                delay(250)
                positionMs = DesktopListenTogether.partyPositionMs() ?: 0L
            }
        }
    }
    val duration = track?.durationMs?.takeIf { it > 0 }
    val healthy = state.connection == DesktopListenTogether.Connection.LIVE && state.clockSynced
    fun clock(ms: Long) = (ms / 1000).coerceAtLeast(0).let { "%d:%02d".format(it / 60, it % 60) }
    Column(Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(16.dp)).padding(16.dp)) {
        CardTitle(tr("d_now_playing", "Now playing"))
        if (track == null) {
            Text(tr("d_nothing_yet", "Nothing playing yet"), color = PartySecondary)
            if (state.you?.isHost == true) Text(tr("d_nothing_hint", "Play a song and it will appear for everyone."), color = PartySecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DesktopArtwork(track.thumbnailUrl, Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)), px = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = PartySecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    state.playback.startedByName?.let { Text(tr("d_started_by", "Started by %1\$s", it), color = PartySecondary, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (duration != null) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = DesktopAccent,
                    trackColor = Color.White.copy(alpha = 0.12f),
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(clock(positionMs), color = PartySecondary, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(clock(duration), color = PartySecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (healthy) Color(0xFF34C759) else Color(0xFFFF9F0A)))
            Spacer(Modifier.width(8.dp))
            Text(connectionLine(state), color = PartySecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The heading over a card: the same weight as on the phone, not a tiny caps label. */
@Composable
private fun CardTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = PartySecondary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) Text(trailing, color = PartySecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** A small filled tag next to a name: who is you, who is the host. */
@Composable
private fun Tag(text: String) {
    Text(
        text,
        color = PartySecondary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun QueueCard(state: DesktopListenTogether.State) {
    Column(Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(16.dp)).padding(16.dp)) {
        CardTitle(tr("d_up_next", "Up next"))
        val upcoming = state.queue.items.drop((state.queue.index + 1).coerceAtLeast(0)).take(5)
        if (upcoming.isEmpty()) Text(tr("d_queue_empty", "The shared queue is empty"), color = PartySecondary)
        upcoming.forEachIndexed { index, track ->
            if (index > 0 && !LocalDesktopPanelIsPage.current) HorizontalDivider(color = DesktopCardEdge)
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", color = PartySecondary, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = PartySecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MembersCard(state: DesktopListenTogether.State) {
    val people = state.members.byPerson()
    Column(Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(16.dp)).padding(16.dp)) {
        CardTitle(tr("d_people", "People"), "${people.size} / ${state.maxMembers}")
        people.forEachIndexed { index, person ->
            if (index > 0 && !LocalDesktopPanelIsPage.current) HorizontalDivider(color = DesktopCardEdge)
            MemberRow(
                member = person.asMember,
                devices = person.devices,
                isYou = person.includes(state.you?.memberId),
                canKick = state.you?.isHost == true && !person.isHost,
            ) { DesktopListenTogether.kick(person.lead.memberId) }
        }
    }
}

/** Where the sound comes out; see [DesktopListenTogether.listenHere]. */
@Composable
private fun PlayHereRow(playHere: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (playHere) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, null, tint = DesktopAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("d_listen_together_play_on_pc", "Play on this computer"), fontWeight = FontWeight.SemiBold)
            Text(
                if (playHere) tr("d_play_here_on", "You hear it here, and everyone else on theirs.")
                else tr("d_play_here_off", "Sound comes only from the host. You still see and control the party."),
                color = PartySecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(playHere, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PlayHereCard() {
    val playHere by DesktopListenTogether.listenHere.collectAsState()
    Column(Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(16.dp)).padding(16.dp)) {
        PlayHereRow(playHere, DesktopListenTogether::setListenHere)
    }
}

/** What a new party holds. Small parties are a setting for the host, not a decision at the door. */
private const val DEFAULT_PARTY_SIZE = 10

@Composable
private fun HostControls(state: DesktopListenTogether.State) {
    Column(Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(16.dp)).padding(16.dp)) {
        CardTitle(tr("d_room_settings", "Room settings"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (state.hostOnlyControl) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = DesktopAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("d_host_only", "Only I can control playback"), fontWeight = FontWeight.SemiBold)
                Text(tr("d_host_only_sub", "Guests can still pause on their own device."), color = PartySecondary, style = MaterialTheme.typography.bodySmall)
            }
            Switch(state.hostOnlyControl, onCheckedChange = DesktopListenTogether::setHostOnlyControl)
        }
        if (LocalDesktopPanelIsPage.current) {
            Spacer(Modifier.height(20.dp))
        } else {
            HorizontalDivider(color = DesktopCardEdge, modifier = Modifier.padding(vertical = 10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("d_party_size", "Party size"), fontWeight = FontWeight.SemiBold)
            }
            Stepper("−", state.maxMembers > maxOf(2, state.members.size)) { DesktopListenTogether.setMaxMembers(state.maxMembers - 1) }
            Text("${state.maxMembers}", modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
            Stepper("+", state.maxMembers < 10) { DesktopListenTogether.setMaxMembers(state.maxMembers + 1) }
        }
    }
}

@Composable
private fun ActivityCard(entries: List<PartyActivity>) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(16.dp)).padding(16.dp)) {
        CardTitle(tr("d_activity", "Activity"))
        // The same thing said twice in a row (two reports of one song change) is one line.
        entries.distinctBy { "${it.by}|${it.detail.ifBlank { it.action }}|${it.atMs / 60_000}" }.take(8).forEach { entry ->
            Text(
                "${clock.format(Date(entry.atMs))}  ${entry.by}: ${entry.detail.ifBlank { entry.action }}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun MemberRow(member: PartyMember, devices: List<PartyMember>, isYou: Boolean, canKick: Boolean, onKick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        PartyAvatar(member.avatarUrl, 34, member.displayName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    member.displayName.ifBlank { tr("d_someone", "Someone") },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isYou) { Spacer(Modifier.width(8.dp)); Tag(tr("d_you", "You")) }
                if (!member.playsAudio) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.VolumeOff, tr("d_not_playing_here", "Not playing on this device"), tint = PartySecondary, modifier = Modifier.size(16.dp))
                }
            }
            // "Away" rather than offline: the seat is still theirs while a connection comes back.
            val line = when {
                !member.connected -> tr("d_away", "Away")
                devices.size > 1 -> devices.joinToString(" · ") { it.deviceName.ifBlank { it.displayName } }
                else -> null
            }
            if (line != null) {
                Text(line, color = PartySecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (member.isHost) {
            Tag(tr("d_host", "Host"))
        } else if (canKick) {
            TextButton(onClick = onKick) { Text(tr("d_remove", "Remove"), color = DesktopDestructive) }
        }
    }
}

@Composable
private fun PartyAvatar(url: String?, size: Int, fallback: String = "") {
    if (url != null) {
        DesktopArtwork(url, Modifier.size(size.dp).clip(CircleShape), px = size * 3)
    } else {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(DesktopCardInsetFill).border(0.5.dp, DesktopCardEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (fallback.isNotBlank()) Text(fallback.take(1).uppercase(), fontWeight = FontWeight.Bold)
            else Icon(Icons.Rounded.Person, null, tint = PartySecondary, modifier = Modifier.size((size * .55f).dp))
        }
    }
}

@Composable
private fun Stepper(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(DesktopCardInsetFill).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Color.White else PartySecondary) }
}

@Composable
private fun PrimaryButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = DesktopAccent, contentColor = Color.Black),
        modifier = modifier.height(46.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
        else Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Notice(text: String, destructive: Boolean) {
    Text(
        text,
        color = if (destructive) DesktopDestructive else PartySecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(12.dp)).padding(14.dp),
    )
}

@Composable
private fun connectionLine(state: DesktopListenTogether.State): String = when {
    state.connection == DesktopListenTogether.Connection.CONNECTING -> tr("d_reconnecting", "Reconnecting…")
    state.connection == DesktopListenTogether.Connection.OFFLINE -> tr("d_offline", "Offline")
    !state.clockSynced -> tr("d_syncing", "Syncing clocks…")
    else -> tr("d_in_sync", "In sync · %1\$d ms", state.roundTripMs)
}

private fun serverStatusLine(status: DesktopListenTogether.ServerStatus): String = when {
    status.health == DesktopListenTogether.Health.CHECKING -> tr("d_srv_checking", "Checking the party server…")
    status.health == DesktopListenTogether.Health.OFFLINE -> tr("d_srv_offline", "Party server is offline")
    status.isFallback -> tr("d_srv_fallback", "Custom server is offline · built-in server online · %1\$d ms", status.latencyMs.toInt())
    else -> tr("d_srv_online", "Party server online · %1\$d ms", status.latencyMs.toInt())
}

/**
 * Where the sound comes out, as two options stacked at the same width. The one chosen is filled.
 * Confirming is the join button under them.
 */
@Composable
private fun PlayHereChoice(playHere: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayHereOption(!playHere, tr("d_play_other", "Only on the other device"), tr("d_play_here_off", "Sound comes only from the host. You still see and control the party."), enabled) { onChange(false) }
        PlayHereOption(playHere, tr("d_play_here", "Play on this computer"), tr("d_play_here_join_on", "You hear it here and everyone else hears it on theirs."), enabled) { onChange(true) }
    }
}

@Composable
private fun PlayHereOption(selected: Boolean, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) DesktopAccent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = if (selected) DesktopAccent else MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PartySecondary)
    }
}

/**
 * Secondary text on this panel's solid card: a step lighter than the app's grey, which reads well on
 * a glass bar but is faint on the inset blocks this panel is made of.
 */
private val PartySecondary = Color(0xFFAEAEB4)

/** The desktop's own words in the language in force; a key with no translation reads as the English given. */
private fun tr(key: String, fallback: String, vararg args: Any?): String =
    if (args.isEmpty()) DesktopStrings[key, fallback] else DesktopStrings.format(key, *args, fallback = fallback)
