package com.music.bitchord.data.feedback

import com.music.bitchord.data.Http
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The server's refusal of a request. [retryable] says whether the same request may work later
 * (the server was busy, down or rate limiting); anything else will fail the same way again.
 */
class FeedbackHttpException(
    val status: Int,
    val code: String,
    message: String,
    val problems: List<String> = emptyList(),
) : IOException("HTTP $status $code: $message") {
    val retryable get() = status == 408 || status == 429 || status >= 500
}

/**
 * Blocking calls to the feedback API of [baseUrl] (`https://server`, no trailing path). Callers
 * run them off the main thread; [FeedbackCenter] does. Network failures are [IOException]s,
 * which are always worth retrying; refusals are [FeedbackHttpException].
 *
 * Requests never carry an Origin: the server refuses app platforms that send one.
 */
internal class FeedbackApi(
    baseUrl: String,
    private val client: OkHttpClient = Http.client,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val jsonType = "application/json".toMediaType()

    sealed interface InboxResult {
        data object NotModified : InboxResult
        data class Changed(val inbox: FeedbackInbox, val etag: String?) : InboxResult
    }

    fun createTicket(token: String?, request: CreateTicketRequest): CreatedTicket =
        call(post("/api/feedback/tickets", request), token)

    fun upload(token: String, ticketId: String, messageId: Long, clientId: String, file: File, mime: String): FeedbackAttachment {
        val body = file.asRequestBody(mime.toMediaType())
        val path = "/api/feedback/tickets/${seg(ticketId)}/messages/$messageId/attachments/${seg(clientId)}"
        val request = Request.Builder().url(base + path).put(body).build()
        return call<AttachmentResponse>(request, token, timeoutSec = 120).attachment
    }

    fun postMessage(token: String, ticketId: String, clientMessageId: String, body: String): FeedbackMessage =
        call<MessageResponse>(post("/api/feedback/tickets/${seg(ticketId)}/messages", MessageRequest(clientMessageId, body)), token).message

    fun rate(token: String, ticketId: String, clientMessageId: String, resolved: Boolean, comment: String): FeedbackMessage =
        call<MessageResponse>(post("/api/feedback/tickets/${seg(ticketId)}/rating", RatingRequest(clientMessageId, resolved, comment)), token).message

    fun markRead(token: String, ticketId: String, upTo: Long) {
        callUnit(post("/api/feedback/tickets/${seg(ticketId)}/read", ReadRequest(upTo)), token)
    }

    fun thread(token: String, ticketId: String): FeedbackThread =
        call(Request.Builder().url("$base/api/feedback/tickets/${seg(ticketId)}").get().build(), token)

    fun delete(token: String, ticketId: String) {
        callUnit(Request.Builder().url("$base/api/feedback/tickets/${seg(ticketId)}").delete().build(), token)
    }

    /** The inbox, or [InboxResult.NotModified] when [etag] is still current (the common case). */
    fun inbox(token: String, since: Long, etag: String?): InboxResult {
        val builder = Request.Builder().url("$base/api/feedback/inbox?since=$since").get()
        if (!etag.isNullOrEmpty()) builder.header("If-None-Match", etag)
        execute(builder.build(), token).use { res ->
            if (res.code == 304) return InboxResult.NotModified
            val text = checked(res)
            return InboxResult.Changed(FeedbackJson.decodeFromString(FeedbackInbox.serializer(), text), res.header("ETag"))
        }
    }

    private fun seg(s: String) = URLEncoder.encode(s, "UTF-8")

    private inline fun <reified T> post(path: String, body: T): Request =
        Request.Builder().url(base + path)
            .post(FeedbackJson.encodeToString(serializer<T>(), body).toRequestBody(jsonType))
            .build()

    private fun execute(request: Request, token: String?, timeoutSec: Long = 20): Response {
        val req = if (token.isNullOrEmpty()) request else request.newBuilder().header("Authorization", "Bearer $token").build()
        val call = client.newCall(req)
        call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
        return call.execute()
    }

    /** The body of a successful response, or the server's refusal as an exception. */
    private fun checked(res: Response): String {
        val text = res.body.string()
        if (res.isSuccessful) return text
        val err = runCatching { FeedbackJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
        throw FeedbackHttpException(res.code, err?.error.orEmpty(), err?.message.orEmpty(), err?.problems.orEmpty())
    }

    private inline fun <reified T> call(request: Request, token: String?, timeoutSec: Long = 20): T =
        execute(request, token, timeoutSec).use { res ->
            FeedbackJson.decodeFromString(serializer<T>() as KSerializer<T>, checked(res))
        }

    private fun callUnit(request: Request, token: String?) {
        execute(request, token).use { checked(it) }
    }
}
