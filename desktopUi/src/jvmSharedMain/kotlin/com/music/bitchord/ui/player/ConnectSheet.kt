package com.music.bitchord.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.listentogether.account.AccountConnection
import com.music.bitchord.data.listentogether.account.AccountDevice
import com.music.bitchord.data.listentogether.account.AccountState
import com.music.bitchord.data.listentogether.account.HandoffForce
import com.music.bitchord.data.listentogether.account.HandoffRules
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.BitChordIcons
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/**
 * Where the sound comes out, and with whom — one place for both.
 *
 * Opened by the party half of the player's pill whether or not there is a party. Top to bottom:
 * the device the sound is coming from right now, the person's other devices (tap one to send the
 * sound there, or to control it if it is already playing), and the party, if any.
 *
 * It does nothing by itself: what a tap means is [HandoffRules][com.music.bitchord.data.listentogether.account.HandoffRules]'
 * business, carried out by the host ([PlayerHost.pickDevice]). This sheet only reads the account
 * state and says what was tapped.
 */
@Composable
internal fun ConnectSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onOpenParty: () -> Unit,
    onStartParty: () -> Unit = onOpenParty,
    onJoinParty: () -> Unit = onOpenParty,
    modifier: Modifier = Modifier,
) {
    val host = PlayerPlatform.host
    val account by host.devices.collectAsStateWithLifecycle()
    val party by host.party.collectAsStateWithLifecycle()
    val connecting by host.connectingDevice.collectAsStateWithLifecycle()

    // The device just tapped. The sheet stays up while the hand-over is on its way, so the tap is
    // answered where it was made, and goes when it settles (a failure is told by the notice).
    var pending by remember { mutableStateOf<String?>(null) }
    var seenConnecting by remember { mutableStateOf(false) }
    LaunchedEffect(pending) {
        // A pick that never began connecting (nothing to send, say) must not leave the row spinning.
        if (pending != null) {
            delay(PICK_BEGIN_MS)
            if (!seenConnecting) pending = null
        }
    }
    LaunchedEffect(connecting) {
        val waiting = pending ?: return@LaunchedEffect
        if (connecting == waiting) {
            seenConnecting = true
        } else if (seenConnecting) {
            pending = null
            seenConnecting = false
            onDismiss()
        }
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.connect_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            account.soundingDevice?.let { SoundingCard(it, isSelf = it.deviceId == account.selfId) }

            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(Res.string.connect_your_devices))

            val thisDevice = account.self
            // The party sounds on another of the person's devices: this one is how it comes back.
            val partyElsewhere = party.inParty && !party.ownsPlayer
            if ((account.controlled != null || partyElsewhere) && thisDevice != null) {
                // Another device is the speaker: this one is how the person takes the sound back.
                DeviceRow(
                    device = thisDevice,
                    isSelf = true,
                    nowMs = 0L,
                    nameOf = { id -> account.devices.firstOrNull { it.deviceId == id }?.name.orEmpty() },
                    onClick = { host.comeBackToThisDevice(); onDismiss() },
                    onForce = null,
                )
            }
            val nowMs = remember { System.currentTimeMillis() }
            // A device the server still remembers from long ago is not one the person is looking for.
            val others = account.others.filter {
                it.deviceId != account.controlled?.deviceId && (it.connected || nowMs - it.lastSeenMs < FORGET_AWAY_MS) &&
                // Asked not to be offered, unless it is part of a link this device is in.
                (!it.hidden || it.controlledBy != null || it.controlling != null)
            }
            val nameOf = { id: String -> account.devices.firstOrNull { it.deviceId == id }?.name.orEmpty() }
            val blocked = { d: AccountDevice -> HandoffRules.blockedBy(d, party.code?.takeIf { party.inParty }) != null }
            others.forEach { device ->
                DeviceRow(
                    device = device,
                    isSelf = false,
                    nowMs = nowMs,
                    nameOf = nameOf,
                    // Stays open when the pick is refused: the reason is shown, and the person is still
                    // looking at the devices they were choosing from.
                    onClick = { host.pickDevice(device.deviceId, null); if (!blocked(device)) pending = device.deviceId },
                    onForce = { force -> host.pickDevice(device.deviceId, force); if (!blocked(device)) pending = device.deviceId },
                    connecting = connecting == device.deviceId,
                )
            }
            when {
                account.connection == AccountConnection.UNSUPPORTED ->
                    Hint(stringResource(Res.string.connect_unsupported))
                others.isEmpty() && account.controlled == null -> Hint(
                    // "No other devices" is only true once the channel is up; before that the
                    // honest answer is that it is still being reached, or cannot be.
                    when (account.connection) {
                        AccountConnection.CONNECTING -> stringResource(Res.string.connect_connecting)
                        AccountConnection.IDLE -> stringResource(Res.string.connect_signed_out)
                        else -> stringResource(Res.string.connect_no_devices)
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel(stringResource(Res.string.connect_party))
            if (party.inParty) {
                ActionRow(
                    title = stringResource(Res.string.connect_view_party),
                    subtitle = party.hostName?.let { stringResource(Res.string.connect_in_party_of, it) }
                        ?: party.code?.let { stringResource(Res.string.listen_together_in_party, it) },
                    onClick = { onDismiss(); onOpenParty() },
                )
            } else {
                // Starting a party is the way in, so it is the filled one; joining is for somebody who
                // already has a code, and says so in words instead of an unlabelled circle.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionPill(
                        text = stringResource(Res.string.connect_start_party),
                        icon = BitChordIcons.PartyStart,
                        filled = true,
                        modifier = Modifier.weight(1f),
                    ) { onDismiss(); onStartParty() }
                    ActionPill(
                        text = stringResource(Res.string.connect_join_with_code),
                        icon = BitChordIcons.PartyJoin,
                        filled = false,
                        modifier = Modifier.weight(1f),
                    ) { onDismiss(); onJoinParty() }
                }
            }
        }
    }
}

/** The device the sound is coming out of, lit, with what it is playing. */
@Composable
private fun SoundingCard(device: AccountDevice, isSelf: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val np = device.nowPlaying
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.35f), CARD_SHAPE)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(device.type), null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.connect_sounding_label).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = accent,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isSelf) stringResource(Res.string.connect_this_device) else device.name,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isSelf) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (np?.thumbnailUrl != null) {
                    AsyncImage(
                        model = np.thumbnailUrl,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = np?.title ?: stringResource(Res.string.connect_nothing_playing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = if (np != null) 0.95f else 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (np != null && np.artist.isNotBlank()) {
                    Text(
                        text = np.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: AccountDevice,
    isSelf: Boolean,
    nowMs: Long,
    nameOf: (String) -> String,
    onClick: () -> Unit,
    onForce: ((HandoffForce) -> Unit)?,
    connecting: Boolean = false,
) {
    val haptics = rememberHaptics()
    val enabled = device.connected || isSelf
    val np = device.nowPlaying
    val minutesAway = ((nowMs - device.lastSeenMs) / 60_000L).coerceAtLeast(0L).toInt()
    val subtitle = when {
        connecting -> stringResource(Res.string.connect_connecting_to)
        // When it was last seen, so a device that went to sleep a minute ago and one forgotten
        // for hours do not look the same.
        !device.connected && !isSelf -> when {
            minutesAway < 1 -> stringResource(Res.string.connect_away_now)
            minutesAway < 60 -> stringResource(Res.string.connect_away_minutes, minutesAway)
            else -> stringResource(Res.string.connect_away_hours, minutesAway / 60)
        }
        // Who, not just that: with three devices "controlling this device" says nothing.
        device.controlledBy != null -> stringResource(Res.string.connect_controlled_by, nameOf(device.controlledBy!!))
        device.controlling != null -> stringResource(Res.string.connect_controlling_device, nameOf(device.controlling!!))
        device.unreachable -> stringResource(Res.string.connect_not_reachable)
        device.noControl -> stringResource(Res.string.connect_no_control)
        np != null && device.isPlaying -> "${np.title} — ${np.artist}"
        // A device in a party plays the party, so what is on it matters less than that.
        device.partyCode != null -> stringResource(Res.string.connect_in_party)
        np != null -> stringResource(Res.string.connect_paused, np.title)
        else -> stringResource(Res.string.connect_idle)
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(Haptic.Select)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.4f
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(device.type), null, tint = Color.White.copy(alpha = 0.8f * alpha), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (isSelf) stringResource(Res.string.connect_this_device) else device.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.95f * alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (device.unreachable || device.noControl) {
                        Color(0xFFFFB454).copy(alpha = alpha)
                    } else if (device.controlledBy != null || device.controlling != null) {
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                    } else {
                        Color.White.copy(alpha = 0.6f * alpha)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (onForce != null && device.connected) {
            Box {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(Res.string.connect_more),
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp).clickable { menu = true },
                )
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = Color(0xFA1A1A1D),
                    border = BorderStroke(0.5.dp, Color(0x1FFFFFFF)),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.connect_send_mine), color = Color.White) },
                        onClick = { menu = false; onForce(HandoffForce.SEND) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.connect_bring_theirs), color = Color.White) },
                        onClick = { menu = false; onForce(HandoffForce.PULL) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String?, leading: ImageVector? = null, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptics.play(Haptic.Select)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) { Icon(leading, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(20.dp))
    }
}

/** A labelled pill under the party heading. Filled is the main way in; the outlined one is the shortcut. */
@Composable
private fun ActionPill(text: String, icon: ImageVector, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(if (filled) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.08f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptics.play(Haptic.Select)
                onClick()
            }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (filled) Color(0xFF111114) else Color.White.copy(alpha = 0.9f)
        Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/** A device that has been away longer than this is left out of the list, however long the server remembers it. */
private const val FORGET_AWAY_MS = 6L * 60 * 60 * 1000
private val CARD_SHAPE = RoundedCornerShape(20.dp)

private fun iconFor(type: String): ImageVector = when (type) {
    "computer" -> Icons.Rounded.Computer
    "tablet" -> Icons.Rounded.TabletAndroid
    "tv" -> Icons.Rounded.Tv
    "watch" -> Icons.Rounded.Watch
    "speaker" -> Icons.Rounded.Speaker
    else -> Icons.Rounded.PhoneAndroid
}

/** No hand-over in flight, for a platform with no account channel. */
internal val NoConnecting: kotlinx.coroutines.flow.StateFlow<String?> =
    kotlinx.coroutines.flow.MutableStateFlow(null)

/** How long a tapped device may take to start connecting before the sheet stops waiting for it. */
private const val PICK_BEGIN_MS = 1_500L

/** Absent, for a platform with no account channel: the sheet then shows only the party half. */
internal val NoDevices: kotlinx.coroutines.flow.StateFlow<AccountState> =
    kotlinx.coroutines.flow.MutableStateFlow(AccountState())
