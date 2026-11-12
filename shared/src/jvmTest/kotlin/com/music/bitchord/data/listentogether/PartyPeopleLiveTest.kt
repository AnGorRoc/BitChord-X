package com.music.bitchord.data.listentogether

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shared request and response types against the real server: devices of one account must come
 * back as one person, and the queue limit must be the server's.
 *
 * Skipped unless `BITCHORD_TEST_SERVER` names a running server.
 */
class PartyPeopleLiveTest {

    private val server: String? = System.getenv("BITCHORD_TEST_SERVER")?.takeIf { it.isNotBlank() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun fresh() = (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString())
        .replace("-", "").take(64)

    private suspend fun HttpClient.send(url: String, body: JoinRequest): PartyMembership {
        val text = post(url) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JoinRequest.serializer(), body))
        }.bodyAsText()
        return json.decodeFromString(PartyMembership.serializer(), text)
    }

    @Test
    fun `two devices of one account are one person in a real party`() {
        val base = server ?: return
        runBlocking {
            val http = HttpClient(OkHttp)
            try {
                val proof = fresh()
                val created = http.send(
                    "$base/api/parties",
                    JoinRequest("ana", "phone-${proof.take(6)}", "Ana", null, 2, accountProof = proof, deviceName = "Pixel"),
                )
                val second = http.send(
                    "$base/api/parties/${created.code}/join",
                    JoinRequest("ana", "pc-${proof.take(6)}", "ignored name", null, accountProof = proof, deviceName = "IOP"),
                )
                assertEquals(created.you.person, second.you.person, "one account, one person")
                assertEquals("Ana", second.you.displayName, "the person's name is kept")
                assertTrue(second.you.isHost, "the host's other device is the host")
                assertEquals("IOP", second.you.deviceName)
                assertEquals(1, second.party.members.byPerson().size)
                assertEquals(2, second.party.members.size)

                // Somebody else still fits: the two devices took one seat of two.
                val beto = http.send(
                    "$base/api/parties/${created.code}/join",
                    JoinRequest("beto", "b-${proof.take(6)}", "Beto", null, accountProof = fresh()),
                )
                assertNotEquals(created.you.person, beto.you.person)
                assertEquals(2, beto.party.members.byPerson().size)
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun `the server announces its queue limit`() {
        val base = server ?: return
        runBlocking {
            val http = HttpClient(OkHttp)
            try {
                assertEquals(25, PartyServers.parseMaxUpcoming("<html>not a server</html>"))
                val real = http.get("$base/api/info").bodyAsText()
                assertTrue(PartyServers.parseMaxUpcoming(real) >= 25)
                assertTrue(PartyServers.FEATURE_PEOPLE in PartyServers.parseFeatures(real))
            } finally {
                http.close()
            }
        }
    }
}
