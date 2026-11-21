package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.feedback.DesktopFeedbackImageShrinker
import com.music.bitchord.data.feedback.FeedbackCenter
import com.music.bitchord.data.feedback.FeedbackDraft
import com.music.bitchord.data.feedback.FeedbackKind
import com.music.bitchord.data.feedback.FeedbackMessage
import com.music.bitchord.data.feedback.FeedbackMessageKind
import com.music.bitchord.data.feedback.FeedbackStatus
import com.music.bitchord.data.feedback.FeedbackTicket
import com.music.bitchord.data.feedback.OutboxItem
import com.music.bitchord.data.feedback.PreparedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/*
 * Feedback on the desktop, as one Settings panel with three views: the person's reports, a new
 * report, and one report's conversation with the developer. The data and the syncing are the
 * shared FeedbackCenter's, as on the phone.
 */

private const val MAX_IMAGES = 6
private const val TITLE_DRAFT = FeedbackCenter.NEW_DRAFT + ":title"

private sealed interface FeedbackView {
    data object List : FeedbackView
    data object New : FeedbackView
    data class Thread(val ticketId: String) : FeedbackView
}

/** Text on the accent: the accent is white in some themes, so white text would vanish on it. */
private fun onAccent(): Color = if (DesktopAccent.luminance() > 0.6f) Color.Black else Color.White

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(DesktopAccent).padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), color = onAccent(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun DesktopFeedbackPanel(onDismiss: () -> Unit) {
    var view by remember { mutableStateOf<FeedbackView>(FeedbackView.List) }
    LaunchedEffect(Unit) { DesktopFeedback.syncSoon() }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 560) {
        Row(
            Modifier.fillMaxWidth().padding(start = panelInset(12.dp), end = panelInset(20.dp), top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (view != FeedbackView.List) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable { view = FeedbackView.List },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = DesktopStrings["back", "Back"], tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when (view) {
                    FeedbackView.New -> DesktopStrings["feedback_new", "New report"]
                    else -> DesktopStrings["feedback_page_title", "Feedback"]
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        Column(desktopPanelBody(cardMax = 640.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            when (val v = view) {
                FeedbackView.List -> FeedbackList(onNew = { view = FeedbackView.New }, onOpen = { view = FeedbackView.Thread(it) })
                FeedbackView.New -> FeedbackCompose(onSent = { view = FeedbackView.List })
                is FeedbackView.Thread -> FeedbackThreadView(v.ticketId, onDeleted = { view = FeedbackView.List })
            }
        }
        DesktopCardRule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = panelInset(16.dp), vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text(DesktopStrings["done", "Done"], color = DesktopAccent) }
        }
    }
}

// ---- building blocks ----

@Composable
private fun FbGroup(header: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = panelInset(16.dp))) {
        header?.let {
            Text(
                it.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = DesktopSecondary,
                modifier = Modifier.padding(start = panelInset(6.dp), top = 14.dp, bottom = 6.dp),
            )
        }
        Column(Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(12.dp))) { content() }
        footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
                modifier = Modifier.padding(start = panelInset(6.dp), end = panelInset(6.dp), top = 6.dp),
            )
        }
    }
}

@Composable
private fun FbRow(title: String, subtitle: String? = null, unread: Int = 0, destructive: Boolean = false, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.desktopRowClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (destructive) DesktopDestructive else Color.White,
                fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary) }
        }
        if (unread > 0) {
            Spacer(Modifier.width(10.dp))
            UnreadBadge(unread)
        }
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            it()
        }
    }
}

@Composable
private fun FbField(value: String, onValueChange: (String) -> Unit, placeholder: String, minHeight: Int = 40, singleLine: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = DesktopSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            cursorBrush = SolidColor(DesktopAccent),
            // The whole box is the field, so a click anywhere in it puts the cursor there. The
            // window's shortcuts act on key-up (space plays or pauses, arrows seek), which a field
            // does not consume; consuming it here keeps typing from driving the player.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = (minHeight - 20).coerceAtLeast(20).dp)
                .onKeyEvent { it.type == KeyEventType.KeyUp },
        )
    }
}

@Composable
private fun FbButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DesktopAccent.copy(alpha = if (enabled) 1f else 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = onAccent(), fontWeight = FontWeight.SemiBold)
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    FeedbackKind.Bug.wire -> DesktopStrings["feedback_kind_bug", "Bug"]
    FeedbackKind.Idea.wire -> DesktopStrings["feedback_kind_idea", "Idea"]
    FeedbackKind.Question.wire -> DesktopStrings["feedback_kind_question", "Question"]
    else -> DesktopStrings["feedback_kind_other", "Other"]
}

private fun ago(ms: Long): String {
    if (ms <= 0) return ""
    val minutes = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> DesktopStrings["feedback_just_now", "just now"]
        minutes < 60 -> DesktopStrings["feedback_minutes_ago", "%d min ago"].format(minutes)
        minutes < 48 * 60 -> DesktopStrings["feedback_hours_ago", "%d h ago"].format(minutes / 60)
        else -> DesktopStrings["feedback_days_ago", "%d d ago"].format(minutes / (24 * 60))
    }
}

private fun outboxStatus(item: OutboxItem): String = when {
    item.failure != null -> DesktopStrings["feedback_failed", "Couldn't be sent (%s)"].format(item.failure)
    item.attempts > 0 -> DesktopStrings["feedback_waiting", "Waiting for a connection"]
    else -> DesktopStrings["feedback_sending", "Sending…"]
}

// ---- pictures ----

/** A picture shrunk for sending, with a copy to show. */
private class PickedImage(val file: PreparedFile, val preview: ImageBitmap?)

private fun picked(bytes: ByteArray): PickedImage? {
    val shrunk = DesktopFeedbackImageShrinker.shrink(bytes) ?: return null
    val preview = runCatching { org.jetbrains.skia.Image.makeFromEncoded(shrunk.bytes).toComposeImageBitmap() }.getOrNull()
    return PickedImage(shrunk, preview)
}

/** Asks for image files with the system's own dialog. */
private suspend fun chooseImages(): List<PickedImage?> {
    val files = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, DesktopStrings["feedback_attach", "Add screenshots"], FileDialog.LOAD).apply {
            isMultipleMode = true
            setFilenameFilter { _, name -> name.lowercase(Locale.ROOT).let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") } }
            isVisible = true
        }
        dialog.files.orEmpty().toList()
    }
    return withContext(Dispatchers.IO) {
        files.map { f -> runCatching { if (f.length() > 40L shl 20) null else picked(f.readBytes()) }.getOrNull() }
    }
}

/** The picture on the clipboard (a screenshot just taken), or null. */
private suspend fun pasteImage(): PickedImage? = withContext(Dispatchers.IO) {
    runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val image = when {
            clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> clipboard.getData(DataFlavor.imageFlavor) as java.awt.Image
            clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
                @Suppress("UNCHECKED_CAST")
                val file = (clipboard.getData(DataFlavor.javaFileListFlavor) as List<File>).firstOrNull() ?: return@runCatching null
                return@runCatching picked(file.readBytes())
            }
            else -> return@runCatching null
        }
        val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        val png = ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
        picked(png)
    }.getOrNull()
}

@Composable
private fun ImagePicker(images: MutableList<PickedImage>, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val unreadable = DesktopStrings["feedback_image_unreadable", "That file isn't an image the app can read."]
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = images.size < MAX_IMAGES, onClick = {
                scope.launch {
                    val got = chooseImages()
                    if (got.any { it == null }) onError(unreadable)
                    images.addAll(got.filterNotNull().take(MAX_IMAGES - images.size))
                }
            }) { Text(DesktopStrings["feedback_attach_file", "Choose images…"], color = DesktopAccent) }
            TextButton(enabled = images.size < MAX_IMAGES, onClick = {
                scope.launch {
                    val got = pasteImage()
                    if (got == null) onError(DesktopStrings["feedback_paste_none", "There is no image on the clipboard."]) else images.add(got)
                }
            }) { Text(DesktopStrings["feedback_paste", "Paste screenshot"], color = DesktopAccent) }
            Spacer(Modifier.weight(1f))
            if (images.isNotEmpty()) Text("${images.size}/$MAX_IMAGES", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (images.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                images.forEachIndexed { i, img ->
                    Box(Modifier.size(76.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f))) {
                        img.preview?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(76.dp)) }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f)).clickable { images.removeAt(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = DesktopStrings["feedback_remove_attachment", "Remove"], tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---- the list ----

@Composable
private fun FeedbackList(onNew: () -> Unit, onOpen: (String) -> Unit) {
    val center = DesktopFeedback.center
    val state by center.state.collectAsState()
    val scope = rememberCoroutineScope()
    val pending = state.outbox.filter { (it.type == OutboxItem.TICKET && it.ticketId == null) || it.failure != null }

    Text(
        DesktopStrings["feedback_intro", "Found a bug, or have an idea? Tell the developer. Screenshots help a lot."],
        color = DesktopSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = panelInset(22.dp), vertical = 4.dp),
    )
    FbGroup {
        FbRow(title = DesktopStrings["feedback_new", "New report"], onClick = onNew)
    }
    if (pending.isNotEmpty()) {
        FbGroup(header = DesktopStrings["feedback_pending", "Not sent yet"]) {
            pending.forEachIndexed { i, item ->
                if (i > 0) DesktopCardRule()
                FbRow(
                    title = item.title.ifEmpty { item.body.take(60) },
                    subtitle = outboxStatus(item),
                    trailing = if (item.failure != null) {
                        { TextButton(onClick = { scope.launch { center.discard(item.clientId) } }) { Text(DesktopStrings["feedback_discard", "Discard"], color = DesktopAccent) } }
                    } else null,
                )
            }
        }
    }
    if (state.tickets.isEmpty()) {
        if (pending.isEmpty()) {
            Text(
                DesktopStrings["feedback_empty", "Nothing sent yet. Your reports and their answers show up here."],
                color = DesktopSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    } else {
        FbGroup(header = DesktopStrings["feedback_your_reports", "Your reports"]) {
            state.tickets.forEachIndexed { i, t ->
                if (i > 0) DesktopCardRule()
                FbRow(
                    title = t.title,
                    subtitle = "${kindLabel(t.kind)} · ${DesktopFeedback.statusLabel(t.status)} · ${ago(t.updatedAtMs)}",
                    unread = t.unread,
                    onClick = { onOpen(t.id) },
                )
            }
        }
    }
}

// ---- a new report ----

@Composable
private fun FeedbackCompose(onSent: () -> Unit) {
    val center = DesktopFeedback.center
    val state by center.state.collectAsState()
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(FeedbackKind.Bug) }
    var title by remember { mutableStateOf(state.drafts[TITLE_DRAFT].orEmpty()) }
    var body by remember { mutableStateOf(state.drafts[FeedbackCenter.NEW_DRAFT].orEmpty()) }
    var withDiagnostics by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(state.preferredName) }
    var message by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    val images = remember { mutableStateListOf<PickedImage>() }
    val diagnostics = remember { DesktopFeedback.diagnostics() }

    DisposableEffect(Unit) {
        onDispose {
            DesktopFeedback.scope.launch {
                center.setDraft(FeedbackCenter.NEW_DRAFT, if (sent) "" else body)
                center.setDraft(TITLE_DRAFT, if (sent) "" else title)
            }
        }
    }

    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = panelInset(16.dp), vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FeedbackKind.entries.forEach { k ->
            val selected = k == kind
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) DesktopAccent else Color.White.copy(alpha = 0.08f))
                    .clickable { kind = k }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) { Text(kindLabel(k.wire), color = if (selected) onAccent() else Color.White) }
        }
    }
    FbGroup {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FbField(
                value = title,
                onValueChange = { title = it.take(140); message = null },
                placeholder = if (kind == FeedbackKind.Bug) DesktopStrings["feedback_title_hint_bug", "Music stops when the screen locks"] else DesktopStrings["feedback_title_hint_other", "A few words about it"],
                singleLine = true,
            )
            FbField(
                value = body,
                onValueChange = { body = it.take(8000); message = null },
                placeholder = when (kind) {
                    FeedbackKind.Bug -> DesktopStrings["feedback_body_hint_bug", "What happened, what you expected, and the steps that make it happen."]
                    FeedbackKind.Idea -> DesktopStrings["feedback_body_hint_idea", "What you would like, and what it would let you do."]
                    FeedbackKind.Question -> DesktopStrings["feedback_body_hint_question", "What you would like to know."]
                    FeedbackKind.Other -> DesktopStrings["feedback_body_hint_other", "Anything you want to tell the developer."]
                },
                minHeight = 120,
            )
        }
        DesktopCardRule()
        ImagePicker(images) { message = it }
    }
    FbGroup(
        footer = DesktopStrings["feedback_diagnostics_detail", "Sends: %s"].format(
            listOf(diagnostics.deviceModel, diagnostics.osVersion, "BitChord ${DesktopFeedback.version}", diagnostics.screen)
                .filter { it.isNotBlank() }.joinToString(" · "),
        ),
    ) {
        FbRow(
            title = DesktopStrings["feedback_diagnostics", "Attach technical info"],
            onClick = { withDiagnostics = !withDiagnostics },
            trailing = { Switch(checked = withDiagnostics, onCheckedChange = { withDiagnostics = it }, colors = desktopSwitchColors()) },
        )
    }
    FbGroup(footer = DesktopStrings["feedback_name_hint_pc", "Replies greet you by this name. It stays on this computer and is never sent. Empty uses your account's first name."]) {
        Column(Modifier.padding(12.dp)) {
            Text(DesktopStrings["feedback_name_label", "Name for replies"], color = Color.White)
            Spacer(Modifier.padding(top = 6.dp))
            FbField(value = name, onValueChange = { name = it.take(40) }, placeholder = "", singleLine = true)
        }
    }
    message?.let {
        Text(it, color = DesktopDestructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = panelInset(22.dp), vertical = 6.dp))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = panelInset(16.dp), vertical = 12.dp), horizontalArrangement = Arrangement.End) {
        FbButton(DesktopStrings["feedback_send", "Send"], enabled = title.isNotBlank() && body.isNotBlank() && !sent) {
            if (title.trim().length < 3) {
                message = DesktopStrings["feedback_title_too_short", "Write a summary of at least 3 characters."]
                return@FbButton
            }
            sent = true
            scope.launch {
                center.setPreferredName(name)
                center.submit(FeedbackDraft(kind, title, body, diagnostics.takeIf { withDiagnostics }, images.map { it.file }))
                DesktopFeedback.syncSoon()
                onSent()
            }
        }
    }
}

// ---- one report ----

@Composable
private fun Bubble(text: String, mine: Boolean, meta: String, thanks: Boolean = false, attachments: Int = 0) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = panelInset(16.dp), vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = if (mine) 14.dp else 4.dp, bottomEnd = if (mine) 4.dp else 14.dp))
                .background(
                    when {
                        thanks -> Color(0xFF2DD4BF).copy(alpha = 0.18f)
                        mine -> DesktopAccent.copy(alpha = 0.24f)
                        else -> Color.White.copy(alpha = 0.08f)
                    },
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (text.isNotEmpty()) Text(text, color = Color.White)
            if (attachments > 0) {
                Text(
                    "📎 " + DesktopStrings["feedback_attachments", "%d attached"].format(attachments),
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (meta.isNotEmpty()) Text(meta, color = DesktopSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp))
    }
}

@Composable
private fun SystemLine(text: String) {
    Text(text, color = DesktopSecondary, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
}

@Composable
private fun MessageRow(m: FeedbackMessage, ticket: FeedbackTicket?, center: FeedbackCenter) {
    val whenText = ago(m.createdAtMs)
    when (m.kind) {
        FeedbackMessageKind.STATUS -> SystemLine(DesktopStrings["feedback_status_changed", "Status: %s"].format(DesktopFeedback.statusLabel(m.body)) + " · " + whenText)
        FeedbackMessageKind.RESOLVED, FeedbackMessageKind.UNRESOLVED -> {
            SystemLine(
                if (m.kind == FeedbackMessageKind.RESOLVED) DesktopStrings["feedback_resolved_said_yes", "You said it was solved"]
                else DesktopStrings["feedback_resolved_said_no", "You said it still happens"],
            )
            if (m.body.isNotBlank()) Bubble(m.body, mine = true, meta = whenText)
        }
        else -> {
            val mine = !m.fromDeveloper
            Bubble(
                text = center.render(m, ticket),
                mine = mine,
                meta = (if (mine) DesktopStrings["feedback_you", "You"] else DesktopStrings["feedback_developer", "Developer"]) + " · " + whenText,
                thanks = m.kind == FeedbackMessageKind.THANKS,
                attachments = m.attachments.size,
            )
        }
    }
}

@Composable
private fun FeedbackThreadView(ticketId: String, onDeleted: () -> Unit) {
    val center = DesktopFeedback.center
    val state by center.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ticket = state.tickets.firstOrNull { it.id == ticketId }
    val messages = state.threads[ticketId].orEmpty()
    val outgoing = state.outbox.filter { it.ticketId == ticketId && (it.type == OutboxItem.MESSAGE || it.type == OutboxItem.RATING) }
    LaunchedEffect(ticketId, messages.size) { withContext(Dispatchers.IO) { center.openThread(ticketId) } }

    var reply by remember(ticketId) { mutableStateOf(state.drafts[ticketId].orEmpty()) }
    val images = remember { mutableStateListOf<PickedImage>() }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    DisposableEffect(ticketId) {
        onDispose { DesktopFeedback.scope.launch { center.setDraft(ticketId, reply) } }
    }

    Text(ticket?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = panelInset(22.dp)))
    if (ticket != null) {
        Text(DesktopFeedback.statusLabel(ticket.status), color = DesktopAccent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = panelInset(22.dp), top = 2.dp, bottom = 8.dp))
    }
    messages.forEach { MessageRow(it, ticket, center) }
    outgoing.forEach { item ->
        if (item.type == OutboxItem.MESSAGE && item.messageId == null) Bubble(item.body, mine = true, meta = outboxStatus(item), attachments = item.files.size)
    }

    val rated = ticket != null && (ticket.satisfaction != 0 || outgoing.any { it.type == OutboxItem.RATING })
    if (ticket != null && FeedbackStatus.isClosed(ticket.status) && !rated) {
        FbGroup(header = DesktopStrings["feedback_resolved_question", "Is it solved?"]) {
            FbRow(title = DesktopStrings["feedback_resolved_yes", "Solved"], onClick = {
                scope.launch { center.rate(ticketId, resolved = true); DesktopFeedback.syncSoon() }
            })
            DesktopCardRule()
            FbRow(title = DesktopStrings["feedback_resolved_no", "Still happening"], onClick = {
                scope.launch { center.rate(ticketId, resolved = false, comment = reply); reply = ""; DesktopFeedback.syncSoon() }
            })
        }
    }

    FbGroup {
        Column(Modifier.padding(12.dp)) {
            FbField(value = reply, onValueChange = { reply = it.take(8000) }, placeholder = DesktopStrings["feedback_reply_hint", "Write a reply"], minHeight = 64)
        }
        ImagePicker(images) { error = it }
        error?.let { Text(it, color = DesktopDestructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp)) }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
            FbButton(DesktopStrings["feedback_send", "Send"], enabled = reply.isNotBlank()) {
                val text = reply
                val files = images.map { it.file }
                reply = ""
                images.clear()
                scope.launch {
                    center.reply(ticketId, text, files)
                    DesktopFeedback.syncSoon()
                }
            }
        }
    }
    FbGroup {
        if (!confirmDelete) {
            FbRow(title = DesktopStrings["feedback_delete", "Delete report"], destructive = true, onClick = { confirmDelete = true })
        } else {
            FbRow(
                title = DesktopStrings["feedback_delete_confirm", "Delete this report and its messages? This can't be undone."],
                trailing = {
                    Row {
                        TextButton(onClick = { confirmDelete = false }) { Text(DesktopStrings["feedback_cancel", "Cancel"], color = DesktopSecondary) }
                        TextButton(onClick = {
                            scope.launch {
                                center.delete(ticketId)
                                DesktopFeedback.syncSoon()
                                onDeleted()
                            }
                        }) { Text(DesktopStrings["feedback_delete_confirm_button", "Delete"], color = DesktopDestructive) }
                    }
                },
            )
        }
    }
}
