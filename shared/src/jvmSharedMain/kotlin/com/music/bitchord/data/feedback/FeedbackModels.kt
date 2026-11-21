package com.music.bitchord.data.feedback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the server's feedback API sends and takes (backend/feedback). Field names are its JSON. */

enum class FeedbackKind(val wire: String) {
    Bug("bug"), Idea("idea"), Question("question"), Other("other");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: Other
    }
}

/** Ticket statuses, as the server names them. */
object FeedbackStatus {
    const val NEW = "new"
    const val REVIEWED = "reviewed"
    const val NEED_INFO = "need_info"
    const val IN_PROGRESS = "in_progress"
    const val DONE = "done"
    const val WONTFIX = "wontfix"
    const val DUPLICATE = "duplicate"

    fun isClosed(status: String) = status == DONE || status == WONTFIX || status == DUPLICATE
}

/** Message kinds. A status message's body is the new status. */
object FeedbackMessageKind {
    const val MESSAGE = "message"
    const val THANKS = "thanks"
    const val STATUS = "status"
    const val RESOLVED = "resolved"
    const val UNRESOLVED = "unresolved"
}

@Serializable
data class FeedbackDiagnostics(
    val deviceModel: String = "",
    val osVersion: String = "",
    val abi: String = "",
    val screen: String = "",
    val telemetryLevel: String = "",
    /** Sent only when the telemetry level is "full"; the server drops it otherwise. */
    val installId: String = "",
)

@Serializable
data class FeedbackTicket(
    val id: String,
    val kind: String,
    val title: String,
    val status: String,
    val platform: String = "",
    val appVersion: String = "",
    val satisfaction: Int = 0,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
    val closedAtMs: Long = 0,
    /** The developer's messages this person has not read yet (inbox only). */
    val unread: Int = 0,
)

@Serializable
data class FeedbackAttachment(
    val id: Long = 0,
    val clientId: String = "",
    val kind: String = "",
    val mime: String = "",
    val bytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class FeedbackMessage(
    val id: Long = 0,
    val author: String,
    val kind: String,
    val body: String = "",
    val createdAtMs: Long = 0,
    val readAtMs: Long = 0,
    val attachments: List<FeedbackAttachment> = emptyList(),
    /** Set on the inbox's messages: which ticket they belong to. */
    val ticketId: String = "",
) {
    val fromDeveloper get() = author == "admin"
}

@Serializable
data class FeedbackLimits(
    val maxAttachmentsPerMessage: Int = 6,
    val maxTicketBytes: Long = 25L shl 20,
    val maxUploadBytes: Long = 8L shl 20,
)

@Serializable
data class FeedbackInbox(
    val version: Long = 0,
    val tickets: List<FeedbackTicket> = emptyList(),
    val messages: List<FeedbackMessage> = emptyList(),
)

@Serializable
internal data class CreateTicketRequest(
    val clientTicketId: String,
    val kind: String,
    val title: String,
    val body: String,
    val platform: String,
    val appVersion: String,
    val locale: String = "",
    val diagnostics: FeedbackDiagnostics? = null,
)

@Serializable
data class CreatedTicket(
    val ticket: FeedbackTicket,
    val message: FeedbackMessage,
    val reporterToken: String? = null,
    val limits: FeedbackLimits = FeedbackLimits(),
)

@Serializable
data class FeedbackThread(
    val ticket: FeedbackTicket,
    val messages: List<FeedbackMessage>,
)

@Serializable
internal data class MessageRequest(val clientMessageId: String, val body: String)

@Serializable
internal data class RatingRequest(val clientMessageId: String, val resolved: Boolean, val comment: String = "")

@Serializable
internal data class ReadRequest(val upTo: Long)

@Serializable
internal data class MessageResponse(val message: FeedbackMessage)

@Serializable
internal data class AttachmentResponse(val attachment: FeedbackAttachment)

@Serializable
internal data class ErrorResponse(
    val error: String = "",
    val message: String = "",
    @SerialName("problems") val problems: List<String> = emptyList(),
)

internal val FeedbackJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
