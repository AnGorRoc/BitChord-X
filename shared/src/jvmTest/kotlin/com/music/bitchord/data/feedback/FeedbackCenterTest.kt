package com.music.bitchord.data.feedback

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackCenterTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private var clock = 1_000_000L
    private var ids = 0

    private val token = "fbr_" + "a".repeat(43)

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("feedback").toFile()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun center(accountName: String? = "Ana López") = FeedbackCenter(
        api = FeedbackApi(server.url("/").toString(), OkHttpClient()),
        store = FeedbackStore(dir),
        platform = "android",
        appVersion = "2.5.0",
        platformLabel = "Android",
        locale = { "es-AR" },
        accountName = { accountName },
        now = { clock },
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
    )

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun take(): RecordedRequest = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS), "expected a request")

    private val ticketJson = """{"id":"fb_aaaaaaaaaaaaaaaa","kind":"bug","title":"Se corta","status":"new","platform":"android","appVersion":"2.5.0","createdAtMs":1,"updatedAtMs":1}"""
    private val createdJson = """{"ticket":$ticketJson,"message":{"id":7,"author":"user","kind":"message","body":"Detalle","createdAtMs":1,"attachments":[]},"reporterToken":"$token","limits":{"maxAttachmentsPerMessage":6,"maxTicketBytes":100,"maxUploadBytes":100}}"""

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)

    @Test
    fun reportReachesTheServerWithItsFiles() = runBlocking {
        val c = center()
        val id = c.submit(FeedbackDraft(FeedbackKind.Bug, " Se corta ", "Detalle", FeedbackDiagnostics(deviceModel = "Pixel"), listOf(PreparedFile(png, "image/png"))))
        assertEquals(1, c.state.value.outbox.size)
        assertEquals(1, dir.resolve("outbox").listFiles()!!.size)

        server.enqueue(json(201, createdJson))
        server.enqueue(json(201, """{"attachment":{"id":3,"clientId":"x","kind":"image","mime":"image/png","bytes":11}}"""))
        server.enqueue(json(200, """{"version":3,"tickets":[$ticketJson],"messages":[]}""").setHeader("ETag", "\"v3\""))
        val result = c.sync()

        val create = take()
        assertEquals("/api/feedback/tickets", create.path)
        assertNull(create.getHeader("Authorization"), "the first report has no token yet")
        assertNull(create.getHeader("Origin"))
        val body = Json.parseToJsonElement(create.body.readUtf8()).jsonObject
        assertEquals(id, body.getValue("clientTicketId").jsonPrimitive.content)
        assertEquals("Se corta", body.getValue("title").jsonPrimitive.content)
        assertEquals("android", body.getValue("platform").jsonPrimitive.content)
        assertEquals("Pixel", body.getValue("diagnostics").jsonObject.getValue("deviceModel").jsonPrimitive.content)

        val upload = take()
        assertEquals("PUT", upload.method)
        assertTrue(upload.path!!.startsWith("/api/feedback/tickets/fb_aaaaaaaaaaaaaaaa/messages/7/attachments/"))
        assertEquals("Bearer $token", upload.getHeader("Authorization"))
        assertEquals("image/png", upload.getHeader("Content-Type"))
        assertEquals(png.size.toLong(), upload.bodySize)

        val inbox = take()
        assertEquals("/api/feedback/inbox?since=0", inbox.path)

        assertEquals(1, result.sent)
        assertTrue(result.online)
        val s = c.state.value
        assertTrue(s.outbox.isEmpty())
        assertEquals("fb_aaaaaaaaaaaaaaaa", s.tickets.single().id)
        assertEquals("\"v3\"", s.etag)
        assertTrue(dir.resolve("outbox").listFiles().orEmpty().isEmpty(), "sent files are removed")
        assertEquals(token, FileFeedbackSecrets(dir.resolve("token")).token)

        // It all survives a restart.
        assertEquals("fb_aaaaaaaaaaaaaaaa", center().state.value.tickets.single().id)
    }

    @Test
    fun aDownServerIsRetriedLaterAndARefusalIsKept() = runBlocking {
        val c = center()
        c.submit(FeedbackDraft(FeedbackKind.Idea, "Idea buena", "Texto"))
        server.enqueue(json(503, """{"error":"busy"}"""))
        val first = c.sync()
        assertEquals(0, first.sent)
        val waiting = c.state.value.outbox.single()
        assertEquals(1, waiting.attempts)
        assertTrue(waiting.nextAttemptMs > clock)
        take()

        // Not due yet: nothing is sent.
        assertNull(c.syncIfDue(0)?.takeIf { it.sent > 0 })
        clock = waiting.nextAttemptMs
        server.enqueue(json(422, """{"error":"invalid_title","message":"title must be 3 to 140 characters."}"""))
        server.enqueue(json(304, "")) // there is no token, so no inbox call is made; spare response
        val second = c.sync()
        assertEquals("invalid_title", second.failed.single().failure)
        assertEquals("invalid_title", c.state.value.outbox.single().failure)
        assertFalse(c.shouldPollInBackground(), "a refused report is nothing to wait for")

        c.discard(second.failed.single().clientId)
        assertTrue(c.state.value.outbox.isEmpty())
    }

    @Test
    fun repliesAreFilledInAndNotifiedOnce() = runBlocking {
        FileFeedbackSecrets(dir.resolve("token")).token = token
        val c = center()
        val admin = """{"id":20,"author":"admin","kind":"message","body":"Hola {{nombre}}, ¿qué versión? Tienes la {{version}}.","createdAtMs":5,"ticketId":"fb_aaaaaaaaaaaaaaaa","attachments":[]}"""
        val status = """{"id":21,"author":"admin","kind":"status","body":"need_info","createdAtMs":6,"ticketId":"fb_aaaaaaaaaaaaaaaa","attachments":[]}"""
        server.enqueue(json(200, """{"version":9,"tickets":[${ticketJson.dropLast(1)},"unread":2}],"messages":[$admin,$status]}""").setHeader("ETag", "\"v9\""))
        val first = c.sync()
        assertEquals(listOf("Hola Ana, ¿qué versión? Tienes la 2.5.0.", "need_info"), first.notices.map { it.text })
        assertEquals("Se corta", first.notices.first().ticketTitle)
        assertEquals(2, c.state.value.unread)
        take()

        server.enqueue(MockResponse().setResponseCode(304))
        val second = c.sync()
        assertTrue(second.notices.isEmpty())
        val poll = take()
        assertEquals("/api/feedback/inbox?since=21", poll.path)
        assertEquals("\"v9\"", poll.getHeader("If-None-Match"))

        // Without an account name, the greeting closes up instead of leaving a gap.
        val anonymous = center(accountName = null)
        val msg = FeedbackMessage(id = 20, author = "admin", kind = "message", body = "Hola {{nombre}}, gracias")
        assertEquals("Hola, gracias", anonymous.render(msg, null))
        anonymous.setPreferredName("Leo")
        assertEquals("Hola Leo, gracias", anonymous.render(msg, null))
    }

    @Test
    fun openingAThreadMarksItRead() = runBlocking {
        FileFeedbackSecrets(dir.resolve("token")).token = token
        val c = center()
        server.enqueue(json(200, """{"ticket":$ticketJson,"messages":[
            {"id":7,"author":"user","kind":"message","body":"Detalle","attachments":[]},
            {"id":9,"author":"admin","kind":"message","body":"Hola","attachments":[]}]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val thread = c.openThread("fb_aaaaaaaaaaaaaaaa")
        assertEquals(2, thread.size)
        take()
        val read = take()
        assertEquals("/api/feedback/tickets/fb_aaaaaaaaaaaaaaaa/read", read.path)
        assertEquals("""{"upTo":9}""", read.body.readUtf8())

        // Offline, the cached thread is what there is.
        server.shutdown()
        assertEquals(2, c.openThread("fb_aaaaaaaaaaaaaaaa").size)
    }

    @Test
    fun aForgottenTokenIsDropped() = runBlocking {
        FileFeedbackSecrets(dir.resolve("token")).token = token
        val c = center()
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        c.sync()
        assertNull(FileFeedbackSecrets(dir.resolve("token")).token)
    }

    @Test
    fun ratingAndDeletionAreQueued() = runBlocking {
        FileFeedbackSecrets(dir.resolve("token")).token = token
        val c = center()
        c.rate("fb_aaaaaaaaaaaaaaaa", resolved = false, comment = "Sigue pasando")
        c.delete("fb_bbbbbbbbbbbbbbbb")
        server.enqueue(json(201, """{"message":{"id":30,"author":"user","kind":"unresolved","body":"Sigue pasando","attachments":[]}}"""))
        server.enqueue(json(404, """{"error":"not_found"}""")) // already gone: fine
        server.enqueue(MockResponse().setResponseCode(304))
        val r = c.sync()
        val rate = take()
        assertEquals("/api/feedback/tickets/fb_aaaaaaaaaaaaaaaa/rating", rate.path)
        assertTrue(rate.body.readUtf8().contains("\"resolved\":false"))
        assertEquals("DELETE", take().method)
        assertTrue(r.failed.isEmpty())
        assertTrue(c.state.value.outbox.isEmpty())
    }
}
