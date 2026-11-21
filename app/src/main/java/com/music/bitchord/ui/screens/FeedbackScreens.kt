package com.music.bitchord.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.feedback.AndroidFeedback
import com.music.bitchord.data.feedback.AndroidFeedbackImageShrinker
import com.music.bitchord.data.feedback.FeedbackCenter
import com.music.bitchord.data.feedback.FeedbackDraft
import com.music.bitchord.data.feedback.FeedbackKind
import com.music.bitchord.data.feedback.FeedbackMessage
import com.music.bitchord.data.feedback.FeedbackMessageKind
import com.music.bitchord.data.feedback.FeedbackNotifications
import com.music.bitchord.data.feedback.FeedbackStatus
import com.music.bitchord.data.feedback.FeedbackTicket
import com.music.bitchord.data.feedback.OutboxItem
import com.music.bitchord.data.feedback.PreparedFile
import com.music.bitchord.ui.components.FrostAlertDialog
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.theme.cardSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Feedback on the phone: the row in Settings, the list of the person's reports, a new report,
 * and one report's conversation with the developer. The data and its syncing live in
 * FeedbackCenter (shared with the desktop); these only draw it.
 */

private const val MAX_IMAGES = 6

/** The new report's summary is kept as a draft of its own, next to its body. */
private const val TITLE_DRAFT = FeedbackCenter.NEW_DRAFT + ":title"

/** Settings' row into feedback, with how many answers are unread. Hidden where the server takes none. */
@Composable
internal fun FeedbackSettingsGroup(accountName: String?, onClick: () -> Unit) {
    val available by AndroidFeedback.available.collectAsStateWithLifecycle()
    if (!available) return
    LaunchedEffect(accountName) { AndroidFeedback.rememberAccountName(accountName) }
    val state by AndroidFeedback.center.state.collectAsStateWithLifecycle()
    SettingsGroup(footer = stringResource(R.string.feedback_settings_footer)) {
        SettingsRow(
            icon = SfIcons.Feedback,
            title = stringResource(R.string.feedback_row),
            badge = state.unread.takeIf { it > 0 }?.let { stringResource(R.string.feedback_row_unread, it) },
            onClick = onClick,
        )
    }
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    FeedbackKind.Bug.wire -> SfIcons.Bug
    FeedbackKind.Idea.wire -> SfIcons.Idea
    FeedbackKind.Question.wire -> SfIcons.Question
    else -> SfIcons.Feedback
}

@Composable
private fun kindLabel(kind: FeedbackKind): String = stringResource(
    when (kind) {
        FeedbackKind.Bug -> R.string.feedback_kind_bug
        FeedbackKind.Idea -> R.string.feedback_kind_idea
        FeedbackKind.Question -> R.string.feedback_kind_question
        FeedbackKind.Other -> R.string.feedback_kind_other
    },
)

private fun ago(ms: Long): String {
    if (ms <= 0) return ""
    val now = System.currentTimeMillis()
    // The server's clock may run a little ahead of the phone's: never "in 0 minutes".
    return DateUtils.getRelativeTimeSpanString(minOf(ms, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** What a queued item is doing, in words. */
@Composable
private fun outboxStatus(item: OutboxItem): String = when {
    item.failure != null -> stringResource(R.string.feedback_failed, item.failure.orEmpty())
    item.attempts > 0 -> stringResource(R.string.feedback_waiting)
    else -> stringResource(R.string.feedback_sending)
}

// ---- the list ----

@Composable
fun FeedbackScreen(
    accountName: String?,
    contentPadding: PaddingValues,
    onNew: () -> Unit,
    onOpenTicket: (String) -> Unit,
) {
    val center = AndroidFeedback.center
    val state by center.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(accountName) { AndroidFeedback.rememberAccountName(accountName) }
    LaunchedEffect(Unit) { AndroidFeedback.syncSoon() }

    // New reports the server has not made yet, and anything it refused.
    val pending = state.outbox.filter { (it.type == OutboxItem.TICKET && it.ticketId == null) || it.failure != null }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) })
            .padding(contentPadding)
            // With no bars over it, the keyboard is all that can hide a field.
            .imePadding(),
    ) {
        SettingsPageTitle(stringResource(R.string.feedback_page_title))
        Text(
            stringResource(R.string.feedback_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        SettingsGroup(topSpacing = 16.dp) {
            SettingsRow(icon = SfIcons.Add, title = stringResource(R.string.feedback_new), onClick = onNew)
        }

        if (pending.isNotEmpty()) {
            SettingsGroup(header = stringResource(R.string.feedback_pending)) {
                pending.forEachIndexed { i, item ->
                    if (i > 0) RowDivider()
                    SettingsRow(
                        icon = kindIcon(item.kind),
                        title = item.title.ifEmpty { item.body.take(60) },
                        subtitle = outboxStatus(item),
                        trailing = if (item.failure != null) {
                            { TextButton(onClick = { scope.launch { center.discard(item.clientId) } }) { Text(stringResource(R.string.feedback_discard)) } }
                        } else null,
                    )
                }
            }
        }

        if (state.tickets.isEmpty()) {
            if (pending.isEmpty()) {
                Text(
                    stringResource(R.string.feedback_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
                )
            }
        } else {
            val context = LocalContext.current
            SettingsGroup(header = stringResource(R.string.feedback_your_reports)) {
                state.tickets.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    SettingsRow(
                        icon = kindIcon(t.kind),
                        title = t.title,
                        subtitle = FeedbackNotifications.statusLabel(context, t.status) + " · " + ago(t.updatedAtMs),
                        badge = t.unread.takeIf { it > 0 }?.toString(),
                        onClick = { onOpenTicket(t.id) },
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ---- a new report ----

/** A picked image, shrunk for sending, with a small copy to show. */
private class PickedImage(val file: PreparedFile, val preview: ImageBitmap?)

/** Reads and shrinks picked images off the main thread. Null entries could not be read. */
private suspend fun pickImages(context: Context, uris: List<Uri>): List<PickedImage?> = withContext(Dispatchers.IO) {
    uris.map { uri ->
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                // A 50 MP photo is about 20 MB; anything bigger is not a screenshot.
                val limit = 40 * 1024 * 1024
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (out.size() <= limit) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                if (out.size() > limit) null else out.toByteArray()
            } ?: return@runCatching null
            val shrunk = AndroidFeedbackImageShrinker.shrink(bytes) ?: return@runCatching null
            val preview = BitmapFactory.decodeByteArray(
                shrunk.bytes, 0, shrunk.bytes.size,
                BitmapFactory.Options().apply { inSampleSize = 8 },
            )?.asImageBitmap()
            PickedImage(shrunk, preview)
        }.getOrNull()
    }
}

@Composable
private fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): (remaining: Int) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = pickImages(context, uris)
            if (picked.any { it == null }) {
                Toast.makeText(context, context.getString(R.string.feedback_image_unreadable), Toast.LENGTH_SHORT).show()
            }
            onPicked(picked.filterNotNull())
        }
    }
    return { remaining ->
        if (remaining > 0) launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

@Composable
private fun ImageStrip(images: List<PickedImage>, onRemove: (Int) -> Unit) {
    if (images.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEachIndexed { i, img ->
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                img.preview?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp)) }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                        .clickable { onRemove(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(SfIcons.Close, contentDescription = stringResource(R.string.feedback_remove_attachment), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/** A multi-line box in the style of [PillTextField]. */
@Composable
private fun NoteField(value: String, onValueChange: (String) -> Unit, placeholder: String, minHeight: Int = 120, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FeedbackComposeScreen(
    accountName: String?,
    contentPadding: PaddingValues,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val center = AndroidFeedback.center
    val state by center.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var kind by rememberSaveable { mutableStateOf(FeedbackKind.Bug) }
    var title by rememberSaveable { mutableStateOf(state.drafts[TITLE_DRAFT].orEmpty()) }
    var body by rememberSaveable { mutableStateOf(state.drafts[FeedbackCenter.NEW_DRAFT].orEmpty()) }
    var withDiagnostics by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf(state.preferredName) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    val images = remember { mutableStateListOf<PickedImage>() }
    val pick = rememberImagePicker { picked -> images.addAll(picked.take(MAX_IMAGES - images.size)) }
    val diagnostics = remember { AndroidFeedback.diagnostics(context) }

    // What was typed survives leaving the page without sending.
    DisposableEffect(Unit) {
        onDispose {
            AndroidFeedback.scope.launch {
                center.setDraft(FeedbackCenter.NEW_DRAFT, if (sent) "" else body)
                center.setDraft(TITLE_DRAFT, if (sent) "" else title)
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            // With no bars over it, the keyboard is all that can hide a field.
            .imePadding(),
    ) {
        SettingsPageTitle(stringResource(R.string.feedback_new))

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeedbackKind.entries.forEach { k ->
                val selected = k == kind
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.primary else cardSurface())
                        .clickable { kind = k }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground
                    Icon(kindIcon(k.wire), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(kindLabel(k), color = tint, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        SettingsGroup(topSpacing = 16.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillTextField(
                    value = title,
                    onValueChange = { title = it.take(140); error = null },
                    placeholder = stringResource(if (kind == FeedbackKind.Bug) R.string.feedback_title_hint_bug else R.string.feedback_title_hint_other),
                    container = MaterialTheme.colorScheme.background,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                NoteField(
                    value = body,
                    onValueChange = { body = it.take(8000); error = null },
                    placeholder = stringResource(
                        when (kind) {
                            FeedbackKind.Bug -> R.string.feedback_body_hint_bug
                            FeedbackKind.Idea -> R.string.feedback_body_hint_idea
                            FeedbackKind.Question -> R.string.feedback_body_hint_question
                            FeedbackKind.Other -> R.string.feedback_body_hint_other
                        },
                    ),
                )
            }
            RowDivider()
            SettingsRow(
                icon = SfIcons.Image,
                title = stringResource(R.string.feedback_attach),
                value = if (images.isEmpty()) null else stringResource(R.string.feedback_attach_count, images.size, MAX_IMAGES),
                enabled = images.size < MAX_IMAGES,
                onClick = { pick(MAX_IMAGES - images.size) },
            )
            ImageStrip(images) { images.removeAt(it) }
        }

        SettingsGroup(
            footer = stringResource(
                R.string.feedback_diagnostics_detail,
                listOf(diagnostics.deviceModel, diagnostics.osVersion, "BitChord ${BuildConfig.VERSION_NAME}", diagnostics.screen)
                    .filter { it.isNotBlank() }.joinToString(" · "),
            ),
        ) {
            SettingsRow(
                icon = SfIcons.Info,
                title = stringResource(R.string.feedback_diagnostics),
                trailing = { IosSwitch(checked = withDiagnostics, onCheckedChange = { withDiagnostics = it }) },
                onClick = { withDiagnostics = !withDiagnostics },
            )
        }

        SettingsGroup(footer = stringResource(R.string.feedback_name_hint)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.feedback_name_label), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                PillTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    placeholder = accountName?.trim()?.substringBefore(' ').orEmpty(),
                    container = MaterialTheme.colorScheme.background,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
        PrimaryAction(
            text = stringResource(R.string.feedback_send),
            enabled = title.isNotBlank() && body.isNotBlank() && !sent,
            onClick = {
                if (title.trim().length < 3) {
                    error = context.getString(R.string.feedback_title_too_short)
                    return@PrimaryAction
                }
                sent = true
                scope.launch {
                    center.setPreferredName(name)
                    center.submit(
                        FeedbackDraft(
                            kind = kind,
                            title = title,
                            body = body,
                            diagnostics = diagnostics.takeIf { withDiagnostics },
                            files = images.map { it.file },
                        ),
                    )
                    AndroidFeedback.syncSoon()
                    Toast.makeText(context, context.getString(R.string.feedback_sent), Toast.LENGTH_LONG).show()
                    onDone()
                }
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ---- one report ----

@Composable
private fun Bubble(text: String, mine: Boolean, meta: String, accent: Boolean = false, attachments: Int = 0) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
                    ),
                )
                .background(
                    when {
                        accent -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
                        mine -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else -> cardSurface()
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onBackground)
                if (attachments > 0) {
                    Text(
                        "📎 " + stringResource(R.string.feedback_attachments, attachments),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (text.isEmpty()) 0.dp else 6.dp),
                    )
                }
            }
        }
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp))
        }
    }
}

@Composable
private fun SystemLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun MessageRow(m: FeedbackMessage, ticket: FeedbackTicket?, center: FeedbackCenter) {
    val context = LocalContext.current
    val when_ = ago(m.createdAtMs)
    when (m.kind) {
        FeedbackMessageKind.STATUS -> SystemLine(context.getString(R.string.feedback_status_changed, FeedbackNotifications.statusLabel(context, m.body)) + " · " + when_)
        FeedbackMessageKind.RESOLVED, FeedbackMessageKind.UNRESOLVED -> {
            SystemLine(stringResource(if (m.kind == FeedbackMessageKind.RESOLVED) R.string.feedback_resolved_said_yes else R.string.feedback_resolved_said_no))
            if (m.body.isNotBlank()) Bubble(m.body, mine = true, meta = when_)
        }
        else -> {
            val mine = !m.fromDeveloper
            Bubble(
                text = center.render(m, ticket),
                mine = mine,
                meta = (if (mine) stringResource(R.string.feedback_you) else stringResource(R.string.feedback_developer)) + " · " + when_,
                accent = m.kind == FeedbackMessageKind.THANKS,
                attachments = m.attachments.size,
            )
        }
    }
}

@Composable
fun FeedbackThreadScreen(
    ticketId: String,
    contentPadding: PaddingValues,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val center = AndroidFeedback.center
    val state by center.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ticket = state.tickets.firstOrNull { it.id == ticketId }
    val messages = state.threads[ticketId].orEmpty()
    val outgoing = state.outbox.filter { it.ticketId == ticketId && (it.type == OutboxItem.MESSAGE || it.type == OutboxItem.RATING) }

    // Opening reads it: the developer's messages are marked read and its notification goes.
    LaunchedEffect(ticketId, messages.size) { center.openThread(ticketId) }

    var reply by rememberSaveable(ticketId) { mutableStateOf(state.drafts[ticketId].orEmpty()) }
    val images = remember { mutableStateListOf<PickedImage>() }
    val pick = rememberImagePicker { picked -> images.addAll(picked.take(MAX_IMAGES - images.size)) }
    var confirmDelete by remember { mutableStateOf(false) }
    DisposableEffect(ticketId) {
        onDispose { AndroidFeedback.scope.launch { center.setDraft(ticketId, reply) } }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size, outgoing.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(contentPadding).imePadding()) {
        Text(
            ticket?.title.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
        if (ticket != null) {
            Text(
                FeedbackNotifications.statusLabel(context, ticket.status),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 12.dp),
            )
        }

        messages.forEach { MessageRow(it, ticket, center) }
        outgoing.forEach { item ->
            if (item.type == OutboxItem.MESSAGE && item.messageId == null) {
                Bubble(item.body, mine = true, meta = outboxStatus(item), attachments = item.files.size)
            }
        }

        val rated = ticket != null && (ticket.satisfaction != 0 || outgoing.any { it.type == OutboxItem.RATING })
        if (ticket != null && FeedbackStatus.isClosed(ticket.status) && !rated) {
            SettingsGroup(header = stringResource(R.string.feedback_resolved_question)) {
                SettingsRow(icon = SfIcons.ThumbsUp, title = stringResource(R.string.feedback_resolved_yes), onClick = {
                    scope.launch { center.rate(ticketId, resolved = true); AndroidFeedback.syncSoon() }
                })
                RowDivider()
                SettingsRow(icon = SfIcons.ThumbsDown, title = stringResource(R.string.feedback_resolved_no), onClick = {
                    scope.launch { center.rate(ticketId, resolved = false, comment = reply); reply = ""; AndroidFeedback.syncSoon() }
                })
            }
        }

        SettingsGroup {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteField(value = reply, onValueChange = { reply = it.take(8000) }, placeholder = stringResource(R.string.feedback_reply_hint), minHeight = 64)
                ImageStrip(images) { images.removeAt(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pick(MAX_IMAGES - images.size) }, enabled = images.size < MAX_IMAGES) {
                        Icon(SfIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.feedback_attach))
                    }
                    Spacer(Modifier.weight(1f))
                    PrimaryAction(
                        text = stringResource(R.string.feedback_send),
                        enabled = reply.isNotBlank(),
                        onClick = {
                            val text = reply
                            val files = images.map { it.file }
                            reply = ""
                            images.clear()
                            scope.launch {
                                center.reply(ticketId, text, files)
                                AndroidFeedback.syncSoon()
                            }
                        },
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        }

        SettingsGroup {
            DestructiveRow(label = stringResource(R.string.feedback_delete), onClick = { confirmDelete = true })
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmDelete) {
        FrostAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.feedback_delete)) },
            text = { Text(stringResource(R.string.feedback_delete_confirm), textAlign = TextAlign.Center) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.feedback_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        center.delete(ticketId)
                        AndroidFeedback.syncSoon()
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.feedback_delete_confirm_button), color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}
