package com.music.bitchord.data.listentogether.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the real client against a real server, to prove they agree on the wire.
 *
 * Skipped unless `BITCHORD_TEST_SERVER` names a running server (for example
 * `http://127.0.0.1:18089`), so an ordinary test run needs nothing else:
 *
 *     BITCHORD_TEST_SERVER=http://127.0.0.1:18089 ./gradlew :shared:jvmTest --tests '*AccountDevicesLiveTest'
 */
class AccountDevicesLiveTest {

    private val server: String? = System.getenv("BITCHORD_TEST_SERVER")?.takeIf { it.isNotBlank() }

    private fun client() = HttpClient(OkHttp) {
        install(WebSockets)
        install(ContentNegotiation) { json() }
    }

    private fun device(scope: CoroutineScope, http: HttpClient, id: String, name: String, type: String, proof: String) =
        AccountDevices(
            http = http,
            scope = scope,
            serverBase = { server },
            proof = { proof },
            self = { DeviceSelf(id, name, type) },
        )

    private suspend fun AccountDevices.waitFor(what: String, ok: (AccountState) -> Boolean): AccountState =
        withTimeout(8_000) {
            try {
                state.first(ok)
            } catch (e: Exception) {
                throw AssertionError("timed out waiting for $what; last state: ${state.value}", e)
            }
        }

    @Test
    fun `two devices of one account see each other, hand over and control`() {
        if (server == null) return
        // A fresh account every run, so leftovers on the server cannot interfere.
        val proof = (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()).replace("-", "").take(64)
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val http = client()
            val phone = device(scope, http, "phone-live", "Pixel", "phone", proof)
            val pc = device(scope, http, "pc-live", "IOP", "computer", proof)
            try {
                // Collected from the start: a command that arrives before somebody is listening is gone.
                val inbox = java.util.Collections.synchronizedList(mutableListOf<DeviceCommand>())
                val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
                scope.launch { pc.commands.collect { inbox += it } }
                scope.launch { phone.errors.collect { errors += it } }
                suspend fun commandOf(action: String): DeviceCommand = withTimeout(8_000) {
                    var found: DeviceCommand? = null
                    while (found == null) {
                        found = synchronized(inbox) { inbox.firstOrNull { it.action == action } }
                        if (found == null) delay(50)
                    }
                    found
                }
                phone.start()
                pc.start()

                val seen = phone.waitFor("the PC in the phone's list") { s ->
                    s.connection == AccountConnection.LIVE && s.others.any { it.deviceId == "pc-live" && it.connected }
                }
                assertEquals("IOP", seen.others.first { it.deviceId == "pc-live" }.name)
                assertEquals("computer", seen.others.first().type)

                // The PC plays something; the phone hears about it.
                pc.publish(AccountNowPlaying(videoId = "v1", title = "TUSSI", artist = "LAJA", isPlaying = true, positionMs = 1000))
                val playing = phone.waitFor("what the PC plays") { s -> s.others.any { it.nowPlaying?.title == "TUSSI" } }
                assertTrue(playing.others.first().isPlaying)

                // The phone hands its music to the PC.
                phone.command(
                    "pc-live", DeviceActions.PLAY_QUEUE,
                    buildJsonObject {
                        put("index", 0)
                        put("positionMs", 5000)
                        put("tracks", JsonArray(listOf(buildJsonObject { put("videoId", "v2") })))
                    },
                )
                val received = commandOf(DeviceActions.PLAY_QUEUE)
                assertEquals("phone-live", received.from)
                assertEquals(DeviceActions.PLAY_QUEUE, received.action)
                assertEquals(JsonPrimitive(5000), received.payload["positionMs"])

                // The link is visible on both sides.
                val linked = phone.waitFor("the phone controlling the PC") { it.controlled?.deviceId == "pc-live" }
                assertNotNull(linked.controlled)
                pc.waitFor("the PC being controlled") { it.controller?.deviceId == "phone-live" }

                // A remote can pause its target.
                phone.command("pc-live", DeviceActions.PAUSE)
                assertEquals(DeviceActions.PAUSE, commandOf(DeviceActions.PAUSE).action)

                // The phone lets go.
                phone.detach()
                pc.waitFor("the PC free again") { it.controller == null }

                // Naming a device that is not there is refused with a reason, not silently dropped.
                phone.command("ghost", DeviceActions.PAUSE)
                withTimeout(8_000) { while (errors.isEmpty()) delay(50) }
                assertTrue(errors.first().isNotBlank())
            } finally {
                phone.stop()
                pc.stop()
                http.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun `a server without the channel is reported as unsupported`() {
        // The server answers 404 on this path; any URL that does is enough.
        val base = server ?: return
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val http = client()
            val d = AccountDevices(http, scope, serverBase = { "$base/not-a-server" }, proof = { "d".repeat(64) }, self = { DeviceSelf("x", "X", "phone") })
            try {
                d.start()
                d.waitFor("unsupported") { it.connection == AccountConnection.UNSUPPORTED }
            } finally {
                d.stop(); http.close(); scope.cancel()
            }
        }
    }
}
