package com.music.bitchord.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.OutlinedButton
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.data.listentogether.spotify.SpotifyAccountTier
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyPreview
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.components.QrCode
import androidx.compose.runtime.getValue

/**
 * Which of the party sheets is up, if any.
 *
 * One nullable value rather than a boolean each: they are alternatives — a
 * create sheet and a join sheet can never both be open — and a set of flags
 * that must not both be true is a bug waiting for the one path that sets the
 * second without clearing the first.
 */
internal sealed interface PartySheet {
    /** Name and size, then create. */
    data object Create : PartySheet

    /** Six cells, to look a party up. */
    data object JoinCode : PartySheet

    /**
     * Who is in the party this device is about to commit a slot to.
     *
     * [server] rides along because an invite may point at somebody's own
     * server, and the join that follows has to go to the same place the
     * preview came from.
     */
    data class Confirm(val preview: PartyPreview, val server: String?) : PartySheet

    /** A link and a square, for getting somebody else in. */
    data object Invite : PartySheet
}

/**
 * This device's own face, lit from behind.
 *
 * The glow is the whole of the decoration on the landing screen, and it is one
 * blurred circle rather than a pattern: the listener's picture is the subject,
 * and a field of shapes around it — the obvious thing to reach for — competes
 * with the one element that is actually about them.
 *
 * It breathes on a long, offset pair of animations so the rise and the fade do
 * not turn over together, which is what a single looping scale reads as: a
 * pulse rather than something lit.
 */
@Composable
internal fun GlowingAvatar(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    glow: Color = MaterialTheme.colorScheme.primary,
) {
    val breathe = rememberInfiniteTransition(label = "partyGlow")
    val spread by breathe.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "partyGlowSpread",
    )
    val intensity by breathe.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            // Deliberately not the spread's period: equal periods make the
            // brightest frame always the widest one, which reads as one object
            // throbbing instead of light moving.
            animation = tween(3_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "partyGlowIntensity",
    )

    Box(
        modifier = modifier.size(size * 2.1f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 1.75f)
                .scale(spread)
                // Blurred as well as faded to transparent: a radial gradient
                // alone bands visibly on the dark background at this size.
                //
                // Unbounded, which is the whole point: the default edge
                // treatment clips the blur to the layout rectangle, so a glow
                // that grows past its own box gets a straight edge sliced
                // across it — a round light inside a visible square, breathing
                // in and out of its own corners.
                .blur(28.dp, BlurredEdgeTreatment.Unbounded)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glow.copy(alpha = intensity),
                            glow.copy(alpha = intensity * 0.35f),
                            Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                ),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.45f),
                )
            }
        }
    }
}

/**
 * What the screen is when there is no party yet.
 *
 * Two doors rather than one, and they are not equals: creating is the common
 * case and gets the filled button; joining is what somebody does who already
 * has a code, and a text button is enough for a person who arrived looking for
 * it.
 */
@Composable
internal fun PartyLanding(
    avatarUrl: String?,
    enabled: Boolean,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        GlowingAvatar(url = avatarUrl)
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.listen_together_landing_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.listen_together_landing_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onCreate,
            enabled = enabled,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_create_action),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onJoin,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_join_existing),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The shared head of every sheet here: a title, and an optional line under it. */
@Composable
private fun SheetHeader(title: String, subtitle: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (subtitle != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Why the last attempt from this sheet did not work, said inside the sheet.
 *
 * On the page behind it there is nothing to read: a drawer covers the bottom
 * half of the screen and a line of red underneath it is a line nobody sees,
 * which is how "no party with that code" turned into a button that appeared to
 * do nothing at all. The message belongs next to the control that produced it.
 */
@Composable
private fun SheetError(message: String?) {
    if (message.isNullOrBlank()) return
    Spacer(Modifier.height(14.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Name and size, asked once, before a party exists.
 *
 * Both are changeable afterwards from the party's own page — this is not the
 * only chance to set them — but they are the two things somebody starting a
 * party tends to have an opinion about, and asking here costs one sheet rather
 * than a trip into settings after the fact.
 */
@Composable
internal fun CreatePartySheet(
    avatarUrl: String?,
    /** The name already configured; creating a party never asks for one. */
    nickname: String,
    busy: Boolean,
    enabled: Boolean,
    error: String?,
    spotifyTier: SpotifyAccountTier = SpotifyAccountTier.UNKNOWN,
    spotifyJamEnabled: Boolean = false,
    onSpotifyJamEnabledChange: (Boolean) -> Unit = {},
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHeader(
            title = stringResource(R.string.listen_together_create),
            subtitle = stringResource(R.string.listen_together_create_sheet_subtitle),
        )
        Spacer(Modifier.height(22.dp))
        // Who they will see. The name is the one already set (Settings), or the account's own.
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartySheetAvatar(url = avatarUrl)
            Spacer(Modifier.width(ICON_GAP))
            Text(
                text = nickname.ifBlank { stringResource(R.string.listen_together_you) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        // Spotify Jam compatibility switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.listen_together_spotify_jam_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1DB954).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Spotify",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF1DB954),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (spotifyTier) {
                        SpotifyAccountTier.PREMIUM -> stringResource(R.string.listen_together_spotify_jam_premium_subtitle)
                        SpotifyAccountTier.FREE -> stringResource(R.string.listen_together_spotify_jam_free_subtitle)
                        SpotifyAccountTier.UNKNOWN -> stringResource(R.string.listen_together_spotify_jam_disconnected_subtitle)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            IosSwitch(
                checked = spotifyJamEnabled && spotifyTier.canHostJam,
                onCheckedChange = { if (spotifyTier.canHostJam) onSpotifyJamEnabledChange(it) },
                enabled = enabled && spotifyTier.canHostJam,
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCreate,
            enabled = enabled && !busy,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(
                    if (busy) R.string.listen_together_creating else R.string.listen_together_create_action,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SheetError(error)
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Where the sound comes out. On, this device plays the party like everybody else's does. Off, it
 * follows and controls the party while the music plays from the host, which is what a room full of
 * people who are already hearing it from one speaker wants.
 */
@Composable
internal fun PlayHereRow(playHere: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.listen_together_play_here),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(
                    if (playHere) R.string.listen_together_play_here_on else R.string.listen_together_play_here_off,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        IosSwitch(checked = playHere, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Where the sound comes out, as two options stacked at the same width. The one chosen is filled.
 * Confirming is the join button under them.
 */
@Composable
internal fun PlayHereChoice(playHere: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayHereOption(
            selected = playHere,
            title = stringResource(R.string.listen_together_play_here),
            subtitle = stringResource(R.string.listen_together_play_here_on),
            enabled = enabled,
            onClick = { onChange(true) },
        )
        PlayHereOption(
            selected = !playHere,
            title = stringResource(R.string.listen_together_play_remote),
            subtitle = stringResource(R.string.listen_together_play_here_off),
            enabled = enabled,
            onClick = { onChange(false) },
        )
    }
}

@Composable
private fun PlayHereOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The avatar as the sheets draw it — larger than a settings row's glyph. */
@Composable
private fun PartySheetAvatar(url: String?, size: Dp = 40.dp) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/**
 * Six characters, and the button that looks them up.
 *
 * Looking up rather than joining: what this sheet hands back is a party to
 * *consider*, and [JoinConfirmSheet] is where the slot is actually taken.
 */
@Composable
internal fun JoinPartySheet(
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    enabled: Boolean,
    error: String?,
    onScanQr: () -> Unit = {},
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHeader(
            title = stringResource(R.string.listen_together_join),
            subtitle = stringResource(R.string.listen_together_code_hint),
        )
        Spacer(Modifier.height(22.dp))
        PartyCodeField(
            code = code,
            onCodeChange = onCodeChange,
            enabled = enabled && !busy,
            onSubmit = onSubmit,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSubmit,
            enabled = enabled && !busy && code.length == ListenTogether.CODE_LENGTH,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(
                    if (busy) R.string.listen_together_joining else R.string.listen_together_join_action,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onScanQr,
            enabled = enabled && !busy,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(
                Icons.Rounded.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.listen_together_scan_qr_action),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        SheetError(error)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The faces already in a party, overlapped, with a count for the rest.
 *
 * Three and a number rather than all of them: the row has to stay the same
 * width at two members and at ten, and past three faces nobody is identifying
 * anyone — they are reading "a few people", which the number says better.
 */
@Composable
internal fun MemberAvatarStack(
    members: List<Pair<String, String?>>,
    total: Int,
    modifier: Modifier = Modifier,
    faceSize: Dp = 52.dp,
    shown: Int = 3,
) {
    val faces = members.take(shown)
    val extra = (total - faces.size).coerceAtLeast(0)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy((-12).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        faces.forEach { (name, avatar) ->
            Box(
                modifier = Modifier
                    .size(faceSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (avatar != null) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = null,
                        modifier = Modifier.size(faceSize).clip(CircleShape),
                    )
                } else {
                    Text(
                        text = name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (extra > 0) {
            Box(
                modifier = Modifier
                    .size(faceSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$extra",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * The last step before a device slot is committed: who is in there, and a way
 * out that is not the back button.
 */
@Composable
internal fun JoinConfirmSheet(
    preview: PartyPreview,
    playHere: Boolean,
    onPlayHereChange: (Boolean) -> Unit,
    busy: Boolean,
    error: String?,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MemberAvatarStack(
            members = preview.members.map { it.displayName to it.avatarUrl },
            total = preview.memberCount,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = preview.hostName.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.listen_together_join_question, it) }
                ?: stringResource(R.string.listen_together_join_question_unnamed),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Only when the server actually told us. A party looked up on a server
        // with no preview endpoint comes back as a code and nothing else, and
        // "0 of 5 listening" would be this screen inventing a fact about a
        // party it cannot see into. See ListenTogether.previewParty.
        if (preview.memberCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        R.string.listen_together_listening,
                        preview.memberCount,
                        preview.maxMembers,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        PlayHereChoice(playHere = playHere, onChange = onPlayHereChange, enabled = !busy)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onJoin,
            enabled = !busy && !preview.isFull,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        preview.isFull -> R.string.listen_together_full
                        busy -> R.string.listen_together_joining
                        else -> R.string.listen_together_join_action
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SheetError(error)
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_not_now),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.listen_together_preview_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * How somebody else gets in: a link to send, or a square to point a camera at.
 *
 * No tap-to-join over NFC, which the obvious reference for this sheet has.
 * Android's side of that is a foreground-dispatch dance that needs both phones
 * awake, unlocked, and in the app, which is a lot of explaining for something
 * slower than reading six characters aloud.
 */
@Composable
internal fun InviteSheet(
    code: String,
    link: String,
    spotifyJamUrl: String? = null,
    onShareLink: () -> Unit,
    onShareSpotifyJam: () -> Unit = {},
    onCopyCode: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = BitChord, 1 = Spotify Jam

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHeader(title = stringResource(R.string.listen_together_invite_title))

        if (!spotifyJamUrl.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (selectedTab == 0) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { selectedTab = 0 }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "BitChord",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedTab == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (selectedTab == 1) Color(0xFF1DB954).copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { selectedTab = 1 }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Spotify Jam",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 1) Color(0xFF1DB954) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = if (selectedTab == 0 || spotifyJamUrl.isNullOrBlank()) onShareLink else onShareSpotifyJam,
            shape = RoundedCornerShape(percent = 50),
            colors = if (selectedTab == 1 && !spotifyJamUrl.isNullOrBlank()) {
                ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.White)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (selectedTab == 0 || spotifyJamUrl.isNullOrBlank()) {
                        R.string.listen_together_share_link
                    } else {
                        R.string.listen_together_share_spotify_jam
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val qrContent = if (selectedTab == 0 || spotifyJamUrl.isNullOrBlank()) link else spotifyJamUrl
            val qrForeground = if (selectedTab == 1 && !spotifyJamUrl.isNullOrBlank()) Color(0xFF1DB954) else Color.Black

            QrCode(
                content = qrContent,
                size = 160.dp,
                codeText = if (selectedTab == 0) code else null,
                foreground = qrForeground,
                modifier = Modifier.border(
                    1.dp,
                    if (selectedTab == 1 && !spotifyJamUrl.isNullOrBlank()) Color(0xFF1DB954).copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.08f),
                    RoundedCornerShape(16.dp),
                ),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(
                    if (selectedTab == 0 || spotifyJamUrl.isNullOrBlank()) {
                        R.string.listen_together_scan_qr
                    } else {
                        R.string.listen_together_scan_spotify_jam_qr
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (selectedTab == 0 || spotifyJamUrl.isNullOrBlank()) {
                        R.string.listen_together_scan_qr_subtitle
                    } else {
                        R.string.listen_together_scan_spotify_jam_subtitle
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (selectedTab == 0 || spotifyJamUrl.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                // The code under the square, because reading six
                // characters out is still the fastest way into a party in the same room.
                TextButton(
                    onClick = onCopyCode,
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                ) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 6.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

