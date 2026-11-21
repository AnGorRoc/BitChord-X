package com.music.bitchord.data.feedback

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole round trip against a real server (backend/, run locally): set FEEDBACK_SERVER_URL to
 * its public port and FEEDBACK_ADMIN_URL to its admin port (no ADMIN_SECRET). Skipped otherwise.
 */
class FeedbackServerIntegrationTest {
    @Test
    fun roundTrip() = runBlocking {
        val base = System.getenv("FEEDBACK_SERVER_URL") ?: return@runBlocking
        val admin = System.getenv("FEEDBACK_ADMIN_URL") ?: return@runBlocking
        val dir = Files.createTempDirectory("feedback-it").toFile()
        try {
            val c = FeedbackCenter.create(base, dir, "pc", "9.9.9", "PC", { "es-AR" }, { "María José Pérez" })
            val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3000, 1200, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            val shrunk = DesktopFeedbackImageShrinker.shrink(png)!!
            c.submit(FeedbackDraft(FeedbackKind.Bug, "Prueba de integración", "Desde el cliente Kotlin", FeedbackDiagnostics(deviceModel = "Test"), listOf(shrunk)))
            val sent = c.sync()
            assertEquals(1, sent.sent, "report should reach the server")
            val ticket = c.state.value.tickets.single()

            // The developer answers from the dashboard's API.
            val http = OkHttpClient()
            http.newCall(
                Request.Builder().url("$admin/api/admin/feedback/tickets/${ticket.id}/messages")
                    .post("""{"body":"Hola {{nombre}}, ¿sigue pasando en la {{version}}?","status":"need_info"}""".toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { assertEquals(201, it.code) }

            val synced = c.sync()
            assertEquals("Hola María, ¿sigue pasando en la 9.9.9?", synced.notices.first().text)
            assertEquals(FeedbackStatus.NEED_INFO, synced.notices.last().text)
            assertTrue(c.state.value.unread >= 1)

            val thread = c.openThread(ticket.id)
            assertEquals(1, thread.first().attachments.size, "the screenshot is attached to the description")
            c.reply(ticket.id, "Sí, sigue")
            assertEquals(1, c.sync().sent)
            c.rate(ticket.id, resolved = true)
            c.sync()
            c.delete(ticket.id)
            c.sync()
            assertTrue(c.state.value.tickets.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
