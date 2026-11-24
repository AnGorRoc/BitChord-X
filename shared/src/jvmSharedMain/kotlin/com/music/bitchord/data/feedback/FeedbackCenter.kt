package com.music.bitchord.data.feedback

import com.music.bitchord.data.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** A file ready to send: already shrunk (see [FeedbackImageShrinker]) or a text log. */
class PreparedFile(val bytes: ByteArray, val mime: String)

/** A new report as the person wrote it. */
data class FeedbackDraft(
    val kind: FeedbackKind,
    val title: String,
    val body: String,
    /** Null when the person did not tick "attach technical info". */
    val diagnostics: FeedbackDiagnostics? = null,
    val files: List<PreparedFile> = emptyList(),
)

/** A developer message to tell the person about, with its text already filled in. */
data class FeedbackNotice(
    val ticketId: String,
    val ticketTitle: String,
    val messageId: Long,
    val kind: String,
    /** The filled-in text; for a status change, the new status (see [FeedbackStatus]). */
    val text: String,
)

data class FeedbackSyncResult(
    val notices: List<FeedbackNotice> = emptyList(),
    /** Reports and replies that reached the server in this sync. */
    val sent: Int = 0,
    /** Items the server refused for good in this sync. */
    val failed: List<OutboxItem> = emptyList(),
    /** False when the server could not be reached. */
    val online: Boolean = true,
)

/**
 * The app's side of feedback, shared by the phone and the desktop: it queues what the person
 * sends, delivers it when it can, polls for the developer's answers and fills their variables in.
 *
 * Nothing here talks to the network on its own; the platform calls [sync] when the app comes to
 * the foreground, after the person sends something, and from its background job while
 * [shouldPollInBackground] says there is anything to wait for. Every call is safe to repeat:
 * the server recognises a retried report, reply or upload by its client ID.
 */
class FeedbackCenter internal constructor(
    private val api: FeedbackApi,
    private val store: FeedbackStore,
    private val platform: String,
    private val appVersion: String,
    private val platformLabel: String,
    private val locale: () -> String,
    private val accountName: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    companion object {
        const val NEW_DRAFT = "new"
        private const val TAG = "Feedback"
        private const val KEEP_POLLING_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_BACKOFF_MS = 30L * 60 * 1000

        /**
         * @param baseUrl the server, which must list "feedback" in its /api/info.
         * @param dir app-private folder for the state, the token and files waiting to upload.
         * @param platform "android" or "pc", as the server names them.
         * @param platformLabel what {{plataforma}} reads as, in the app's language.
         * @param accountName the signed-in account's name, for {{nombre}}; never sent.
         */
        fun create(
            baseUrl: String,
            dir: File,
            platform: String,
            appVersion: String,
            platformLabel: String,
            locale: () -> String,
            accountName: () -> String?,
            secrets: FeedbackSecrets? = null,
        ): FeedbackCenter {
            val store = if (secrets != null) FeedbackStore(dir, secrets) else FeedbackStore(dir)
            return FeedbackCenter(FeedbackApi(baseUrl), store, platform, appVersion, platformLabel, locale, accountName)
        }
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow(store.load().also(store::sweep))

    /** Tickets, threads, the outbox and drafts; the UI draws from this. */
    val state: StateFlow<FeedbackState> = _state.asStateFlow()

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun update(change: (FeedbackState) -> FeedbackState) {
        val next = change(_state.value)
        _state.value = next
        store.save(next)
    }

    // ---- what the person does ----

    /** Queues a new report and returns its client ID. Call [sync] to send it. */
    suspend fun submit(draft: FeedbackDraft): String = locked {
        val title = draft.title.trim()
        val body = draft.body.trim()
        require(title.length in 3..140) { "title must be 3 to 140 characters" }
        require(body.isNotEmpty() && body.length <= 8000) { "body must be 1 to 8000 characters" }
        val id = newId()
        val item = OutboxItem(
            clientId = id, type = OutboxItem.TICKET, kind = draft.kind.wire, title = title, body = body,
            locale = locale(), diagnostics = draft.diagnostics, files = adopt(draft.files), createdAtMs = now(),
        )
        update { it.copy(outbox = it.outbox + item, drafts = it.drafts - NEW_DRAFT) }
        id
    }

    /** Queues a reply to the developer, with optional files. */
    suspend fun reply(ticketId: String, text: String, files: List<PreparedFile> = emptyList()): String = locked {
        val body = text.trim()
        require(body.isNotEmpty() && body.length <= 8000) { "reply must be 1 to 8000 characters" }
        val item = OutboxItem(
            clientId = newId(), type = OutboxItem.MESSAGE, ticketId = ticketId, body = body,
            files = adopt(files), createdAtMs = now(),
        )
        update { it.copy(outbox = it.outbox + item, drafts = it.drafts - ticketId) }
        item.clientId
    }

    /** Queues the person's verdict on a closed ticket ("solved" or "still happening"). */
    suspend fun rate(ticketId: String, resolved: Boolean, comment: String = ""): String = locked {
        val item = OutboxItem(
            clientId = newId(), type = OutboxItem.RATING, ticketId = ticketId, resolved = resolved,
            body = comment.trim().take(1000), createdAtMs = now(),
        )
        update { it.copy(outbox = it.outbox + item) }
        item.clientId
    }

    /** Deletes a ticket here at once and on the server as soon as it can. */
    suspend fun delete(ticketId: String) = locked {
        val item = OutboxItem(clientId = newId(), type = OutboxItem.DELETE, ticketId = ticketId, createdAtMs = now())
        // Whatever was still on its way to that ticket goes with it.
        val dropped = _state.value.outbox.filter { it.ticketId == ticketId }
        dropped.flatMap { it.files }.forEach { store.delete(it.name) }
        update { s ->
            s.copy(
                tickets = s.tickets.filterNot { it.id == ticketId },
                threads = s.threads - ticketId,
                drafts = s.drafts - ticketId,
                outbox = s.outbox - dropped.toSet() + item,
            )
        }
    }

    /** Drops an item the server refused, with its files. */
    suspend fun discard(clientId: String) = locked {
        _state.value.outbox.firstOrNull { it.clientId == clientId }?.files?.forEach { store.delete(it.name) }
        update { s -> s.copy(outbox = s.outbox.filterNot { it.clientId == clientId }) }
    }

    suspend fun setDraft(key: String, text: String) = locked {
        update { s -> s.copy(drafts = if (text.isBlank()) s.drafts - key else s.drafts + (key to text)) }
    }

    suspend fun setPreferredName(name: String) = locked {
        update { it.copy(preferredName = name.trim().take(40)) }
    }

    private fun adopt(files: List<PreparedFile>): List<OutboxFile> = files.map {
        OutboxFile(clientId = newId(), name = store.adopt(it.bytes, it.mime), mime = it.mime, bytes = it.bytes.size.toLong())
    }

    // ---- reading ----

    /** The variables a developer message is filled with for [ticket]. */
    fun variablesFor(ticket: FeedbackTicket?): Map<String, String> {
        val name = _state.value.preferredName.ifBlank {
            // A greeting reads better with the first name than with "Juan Pérez García".
            accountName().orEmpty().trim().substringBefore(' ')
        }
        return mapOf(
            "nombre" to name,
            "version" to appVersion,
            "plataforma" to platformLabel,
            "ticket" to (ticket?.id ?: ""),
            "titulo" to (ticket?.title ?: ""),
        )
    }

    /** A developer message as the person reads it. */
    fun render(message: FeedbackMessage, ticket: FeedbackTicket?): String =
        if (message.fromDeveloper) FeedbackTemplate.render(message.body, variablesFor(ticket)) else message.body

    /**
     * Fetches [ticketId]'s whole thread, caches it, and marks the developer's messages read. Off
     * line, it returns what is cached.
     */
    suspend fun openThread(ticketId: String): List<FeedbackMessage> = locked {
        val token = store.secrets.token
        if (token == null) return@locked _state.value.threads[ticketId].orEmpty()
        try {
            val thread = api.thread(token, ticketId)
            val lastAdmin = thread.messages.filter { it.fromDeveloper }.maxOfOrNull { it.id } ?: 0
            val unread = thread.messages.any { it.fromDeveloper && it.readAtMs == 0L }
            if (unread) api.markRead(token, ticketId, lastAdmin)
            update { s ->
                s.copy(
                    threads = s.threads + (ticketId to thread.messages),
                    tickets = s.tickets.map { if (it.id == ticketId) thread.ticket.copy(unread = 0) else it },
                    notifiedUpTo = maxOf(s.notifiedUpTo, lastAdmin),
                )
            }
            thread.messages
        } catch (e: FeedbackHttpException) {
            if (e.status == 404) update { s -> s.copy(tickets = s.tickets.filterNot { it.id == ticketId }, threads = s.threads - ticketId) }
            _state.value.threads[ticketId].orEmpty()
        } catch (e: IOException) {
            _state.value.threads[ticketId].orEmpty()
        }
    }

    /**
     * Whether a background job should keep checking: something is waiting to be sent, or a
     * ticket is open or closed recently enough that an answer may still come.
     */
    fun shouldPollInBackground(): Boolean {
        val s = _state.value
        if (s.outbox.any { it.failure == null }) return true
        val cutoff = now() - KEEP_POLLING_MS
        return s.tickets.any { !FeedbackStatus.isClosed(it.status) || it.closedAtMs > cutoff }
    }

    /** [sync], unless the last one was less than [minIntervalMs] ago and nothing waits to be sent. */
    suspend fun syncIfDue(minIntervalMs: Long): FeedbackSyncResult? {
        val s = _state.value
        val pending = s.outbox.any { it.failure == null && it.nextAttemptMs <= now() }
        if (!pending && now() - s.lastSyncMs < minIntervalMs) return null
        return sync()
    }

    // ---- talking to the server ----

    /** Sends what is queued, then checks for the developer's answers. */
    suspend fun sync(): FeedbackSyncResult = locked {
        var sent = 0
        val failed = mutableListOf<OutboxItem>()
        var online = true
        for (original in _state.value.outbox) {
            if (original.failure != null || original.nextAttemptMs > now()) continue
            when (val outcome = deliver(original)) {
                is Outcome.Done -> sent += if (original.type == OutboxItem.TICKET || original.type == OutboxItem.MESSAGE) 1 else 0
                is Outcome.Refused -> failed += outcome.item
                is Outcome.Later -> {
                    // A network failure will fail the rest the same way; try them all next time.
                    if (!outcome.serverBusy) {
                        online = false
                        break
                    }
                }
            }
        }
        val notices = if (online) poll() else null
        update { it.copy(lastSyncMs = now()) }
        FeedbackSyncResult(notices = notices.orEmpty(), sent = sent, failed = failed, online = online && notices != null)
    }

    private sealed interface Outcome {
        data object Done : Outcome
        data class Refused(val item: OutboxItem) : Outcome
        data class Later(val serverBusy: Boolean) : Outcome
    }

    private fun replaceItem(item: OutboxItem) = update { s -> s.copy(outbox = s.outbox.map { if (it.clientId == item.clientId) item else it }) }

    private fun removeItem(item: OutboxItem) = update { s -> s.copy(outbox = s.outbox.filterNot { it.clientId == item.clientId }) }

    private fun deliver(start: OutboxItem): Outcome {
        var item = start
        try {
            when (item.type) {
                OutboxItem.TICKET -> if (item.ticketId == null) {
                    val created = api.createTicket(
                        store.secrets.token,
                        CreateTicketRequest(
                            clientTicketId = item.clientId, kind = item.kind, title = item.title, body = item.body,
                            platform = platform, appVersion = appVersion, locale = item.locale, diagnostics = item.diagnostics,
                        ),
                    )
                    created.reporterToken?.let { store.secrets.token = it }
                    item = item.copy(ticketId = created.ticket.id, messageId = created.message.id)
                    replaceItem(item)
                    update { s ->
                        s.copy(
                            tickets = listOf(created.ticket) + s.tickets.filterNot { it.id == created.ticket.id },
                            threads = s.threads + (created.ticket.id to listOf(created.message)),
                            limits = created.limits,
                        )
                    }
                }
                OutboxItem.MESSAGE -> if (item.messageId == null) {
                    val m = api.postMessage(token(), item.ticketId!!, item.clientId, item.body)
                    item = item.copy(messageId = m.id)
                    replaceItem(item)
                    appendToThread(item.ticketId!!, m)
                }
                OutboxItem.RATING -> {
                    val m = api.rate(token(), item.ticketId!!, item.clientId, item.resolved, item.body)
                    appendToThread(item.ticketId, m)
                    update { s ->
                        s.copy(tickets = s.tickets.map {
                            if (it.id != item.ticketId) it
                            else it.copy(
                                satisfaction = if (item.resolved) 1 else -1,
                                // Saying it is not solved reopens it on the server too.
                                status = if (!item.resolved && FeedbackStatus.isClosed(it.status)) FeedbackStatus.REVIEWED else it.status,
                            )
                        })
                    }
                }
                OutboxItem.DELETE -> try {
                    api.delete(token(), item.ticketId!!)
                } catch (e: FeedbackHttpException) {
                    if (e.status != 404) throw e
                }
            }
            item = uploadFiles(item)
            removeItem(item)
            return Outcome.Done
        } catch (e: FeedbackHttpException) {
            if (e.retryable) return retryLater(item, serverBusy = true)
            DebugLog.w(TAG, "feedback ${item.type} refused: ${e.message}")
            item.files.forEach { store.delete(it.name) }
            val refused = item.copy(failure = e.code.ifEmpty { "http_${e.status}" })
            replaceItem(refused)
            return Outcome.Refused(refused)
        } catch (e: IOException) {
            return retryLater(item, serverBusy = false)
        }
    }

    private fun retryLater(item: OutboxItem, serverBusy: Boolean): Outcome {
        val attempts = item.attempts + 1
        val backoff = minOf(MAX_BACKOFF_MS, 15_000L shl minOf(attempts - 1, 7))
        replaceItem(item.copy(attempts = attempts, nextAttemptMs = now() + backoff))
        return Outcome.Later(serverBusy)
    }

    /**
     * Uploads the item's files that are not up yet. A file the server refuses for good (wrong
     * type, too large) is dropped and noted; the report itself already went through.
     */
    private fun uploadFiles(start: OutboxItem): OutboxItem {
        var item = start
        val ticketId = item.ticketId ?: return item
        val messageId = item.messageId ?: return item
        for (f in item.files) {
            if (f.done || f.failure != null) continue
            val updated = try {
                api.upload(token(), ticketId, messageId, f.clientId, store.file(f.name), f.mime)
                f.copy(done = true)
            } catch (e: FeedbackHttpException) {
                if (e.retryable) throw e
                f.copy(failure = e.code.ifEmpty { "http_${e.status}" })
            }
            store.delete(f.name)
            item = item.copy(files = item.files.map { if (it.clientId == f.clientId) updated else it })
            replaceItem(item)
        }
        return item
    }

    private fun token(): String = store.secrets.token ?: throw FeedbackHttpException(401, "no_token", "No reporter token yet")

    private fun appendToThread(ticketId: String, m: FeedbackMessage) = update { s ->
        val cached = s.threads[ticketId] ?: return@update s
        if (cached.any { it.id == m.id }) s else s.copy(threads = s.threads + (ticketId to cached + m))
    }

    /** Checks the inbox. Returns the notices to raise, or null when the server was unreachable. */
    private fun poll(): List<FeedbackNotice>? {
        val token = store.secrets.token ?: return emptyList()
        val s = _state.value
        val result = try {
            api.inbox(token, s.since, s.etag)
        } catch (e: FeedbackHttpException) {
            if (e.status == 401) {
                // The server no longer knows this token (its database was reset). The next report
                // gets a new one; what was cached here cannot be reached any more.
                store.secrets.token = null
                update { it.copy(tickets = emptyList(), threads = emptyMap(), etag = null, since = 0, notifiedUpTo = 0) }
                return emptyList()
            }
            return if (e.retryable) null else emptyList()
        } catch (e: IOException) {
            return null
        }
        if (result !is FeedbackApi.InboxResult.Changed) return emptyList()
        val inbox = result.inbox
        val tickets = inbox.tickets.associateBy { it.id }
        val notices = inbox.messages
            .filter { it.fromDeveloper && it.id > s.notifiedUpTo && it.readAtMs == 0L }
            .map { m ->
                val ticket = tickets[m.ticketId]
                FeedbackNotice(
                    ticketId = m.ticketId, ticketTitle = ticket?.title.orEmpty(), messageId = m.id, kind = m.kind,
                    text = if (m.kind == FeedbackMessageKind.STATUS) m.body else FeedbackTemplate.render(m.body, variablesFor(ticket)),
                )
            }
        val newest = inbox.messages.maxOfOrNull { it.id } ?: 0
        update { st ->
            var threads = st.threads
            for (m in inbox.messages) {
                val cached = threads[m.ticketId] ?: continue
                if (cached.none { it.id == m.id }) threads = threads + (m.ticketId to cached + m)
            }
            st.copy(
                tickets = inbox.tickets,
                threads = threads.filterKeys { it in tickets },
                etag = result.etag,
                since = maxOf(st.since, newest),
                notifiedUpTo = maxOf(st.notifiedUpTo, newest),
            )
        }
        return notices
    }
}
