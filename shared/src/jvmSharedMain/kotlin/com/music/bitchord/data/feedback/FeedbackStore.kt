package com.music.bitchord.data.feedback

import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * Everything the app remembers about its feedback, kept in one JSON file. Tickets and their
 * threads are a cache of the server; the outbox is what has not reached it yet and must survive
 * a restart, a lost connection or a killed process.
 */
@Serializable
data class FeedbackState(
    val tickets: List<FeedbackTicket> = emptyList(),
    /** Threads the person has opened, by ticket ID. */
    val threads: Map<String, List<FeedbackMessage>> = emptyMap(),
    val outbox: List<OutboxItem> = emptyList(),
    /** The inbox ETag and the newest developer message already fetched. */
    val etag: String? = null,
    val since: Long = 0,
    /** The newest developer message a notification was raised for. */
    val notifiedUpTo: Long = 0,
    val lastSyncMs: Long = 0,
    /** Unsent text, by ticket ID, or [FeedbackCenter.NEW_DRAFT] for a new report. */
    val drafts: Map<String, String> = emptyMap(),
    /** How the person asked to be called in replies; empty means their account name. Never sent. */
    val preferredName: String = "",
    val limits: FeedbackLimits = FeedbackLimits(),
) {
    val unread get() = tickets.sumOf { it.unread }
}

/** Something to send: a new report, a reply, a rating or a deletion. */
@Serializable
data class OutboxItem(
    /** The client ID the server dedupes on (clientTicketId / clientMessageId). */
    val clientId: String,
    val type: String,
    /** The server's ticket. For a new report, set once the server has made it. */
    val ticketId: String? = null,
    /** The server's message the files belong to, once it exists. */
    val messageId: Long? = null,
    val kind: String = "",
    val title: String = "",
    val body: String = "",
    val locale: String = "",
    val diagnostics: FeedbackDiagnostics? = null,
    val resolved: Boolean = false,
    val files: List<OutboxFile> = emptyList(),
    val createdAtMs: Long = 0,
    val attempts: Int = 0,
    val nextAttemptMs: Long = 0,
    /** Why the server refused it for good; the person can discard it. */
    val failure: String? = null,
) {
    companion object {
        const val TICKET = "ticket"
        const val MESSAGE = "message"
        const val RATING = "rating"
        const val DELETE = "delete"
    }
}

@Serializable
data class OutboxFile(
    val clientId: String,
    /** The prepared file's name in the outbox folder. */
    val name: String,
    val mime: String,
    val bytes: Long,
    val done: Boolean = false,
    val failure: String? = null,
)

/** Where the reporter token is kept. The default is a file next to the state, in app-private storage. */
interface FeedbackSecrets {
    var token: String?
}

class FileFeedbackSecrets(private val file: File) : FeedbackSecrets {
    override var token: String?
        get() = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        set(value) {
            if (value == null) file.delete() else writeAtomically(file, value.toByteArray())
        }
}

internal class FeedbackStore(
    private val dir: File,
    val secrets: FeedbackSecrets = FileFeedbackSecrets(File(dir, "token")),
) {
    private val stateFile = File(dir, "feedback.json")
    private val outboxDir = File(dir, "outbox")

    fun load(): FeedbackState = runCatching {
        FeedbackJson.decodeFromString(FeedbackState.serializer(), stateFile.readText())
    }.getOrElse { FeedbackState() }

    fun save(state: FeedbackState) {
        writeAtomically(stateFile, FeedbackJson.encodeToString(FeedbackState.serializer(), state).toByteArray())
    }

    /** Keeps prepared bytes for the outbox and returns the file's name. */
    fun adopt(bytes: ByteArray, mime: String): String {
        val ext = when {
            mime == "image/png" -> "png"
            mime == "image/jpeg" -> "jpg"
            mime == "image/webp" -> "webp"
            else -> "txt"
        }
        val name = "${UUID.randomUUID()}.$ext"
        writeAtomically(File(outboxDir, name), bytes)
        return name
    }

    fun file(name: String) = File(outboxDir, name)

    fun delete(name: String) {
        File(outboxDir, name).delete()
    }

    /** Removes outbox files no item refers to (left by a crash between steps). */
    fun sweep(state: FeedbackState) {
        val live = state.outbox.flatMap { it.files }.filter { !it.done }.map { it.name }.toSet()
        outboxDir.listFiles()?.forEach { if (it.name !in live) it.delete() }
    }
}

internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        // Windows will not rename over an existing file.
        file.delete()
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }
}
