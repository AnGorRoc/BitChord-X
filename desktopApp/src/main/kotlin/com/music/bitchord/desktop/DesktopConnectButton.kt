package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.music.bitchord.ui.player.ConnectPopover
import com.music.bitchord.ui.player.PartyAction
import com.music.bitchord.ui.player.PlayerPlatform

/**
 * Connect, from the top bar: the person's other devices and the party, one click from any page.
 *
 * On a phone Connect rises from the player because the player is where the sound is. A computer has
 * room to keep it in the bar permanently and to say, right there, where the sound is going: lit with
 * the device's name while another device is the speaker, lit with the people count in a party, plain
 * otherwise. The panel hangs under the button and closes on a click anywhere else.
 */
@Composable
internal fun DesktopConnectButton(modifier: Modifier = Modifier) {
    val host = PlayerPlatform.host
    val account by host.devices.collectAsState()
    val party by host.party.collectAsState()
    var open by remember { mutableStateOf(false) }

    val remote = account.controlled?.name
    val partyElsewhere = party.inParty && !party.ownsPlayer
    val lit = remote != null || party.inParty
    val label = when {
        remote != null -> remote
        party.inParty -> DesktopStrings.format("d_connect_party_people", party.members.size, fallback = "${party.members.size} in party")
        else -> null
    }
    val tint = if (lit) DesktopAccent else DesktopSecondary

    Box(modifier) {
        Row(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(50))
                .background(if (lit) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                .desktopHoverWash()
                .clickable { open = !open }
                .padding(horizontal = if (label != null) 10.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Rounded.Devices,
                DesktopStrings["d_connect", "Connect"],
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
            if (label != null) {
                Text(
                    label,
                    color = tint,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
        if (open) {
            Popup(
                popupPositionProvider = remember { UnderAnchor(gap = 12.dp) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                ConnectPopover(
                    onDismiss = { open = false },
                    onOpenParty = { open = false; DesktopPlayerHost.openListenTogether() },
                    onStartParty = {
                        open = false
                        DesktopPlayerHost.requestPartyAction(PartyAction.START)
                        DesktopPlayerHost.openListenTogether()
                    },
                    onJoinParty = {
                        open = false
                        DesktopPlayerHost.requestPartyAction(PartyAction.JOIN)
                        DesktopPlayerHost.openListenTogether()
                    },
                )
            }
        }
    }
}

/** Under the button, right edges aligned, kept inside the window. */
private class UnderAnchor(private val gap: Dp) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val g = gap.value.toInt()
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
        // Below the button when it fits (the bar at the top), above it otherwise (the bar at the bottom).
        val below = anchorBounds.bottom + g
        val y = if (below + popupContentSize.height + 8 <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - g - popupContentSize.height).coerceAtLeast(8)
        }
        return IntOffset(x, y)
    }
}

