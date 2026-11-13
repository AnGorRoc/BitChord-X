package com.music.bitchord.data.listentogether.account

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** Who this device is, as it introduces itself to the person's other devices. */
data class DeviceSelf(
    val deviceId: String,
    /** The name the person sees in the Connect list: "IOP", "Pixel de Ana". */
    val name: String,
    /** phone, tablet, computer, tv, watch or speaker. */
    val type: String,
)

@Serializable
private data class ConnectRequest(
    val accountProof: String,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
)

@Serializable
private data class ConnectResponse(val token: String)

/**
 * This device's side of the account channel: it keeps a socket open to the person's party server,
 * publishes what this device plays, and hears what the person's other devices play.
 *
 * It does not play anything and does not know about the player. Platform code feeds it
 * ([publish]) and acts on what comes back ([state], [commands]). That is also what makes the
 * hand-over rules testable apart from it — see [HandoffRules].
 *
 * The channel is optional by design. A server without it answers the connect call with 404 or 405,
 * and the client settles into [AccountConnection.UNSUPPORTED] and checks again only rarely.
 */
class AccountDevices(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    /** The server's https base, or null when none is set. Read on every attempt. */
    private val serverBase: suspend () -> String?,
    /** The account proof (see DeviceGroup.groupId), or null when it cannot be had. */
    private val proof: suspend () -> String?,
    private val self: () -> DeviceSelf,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<DeviceCommand>(extraBufferCapacity = 32)

    /** Commands addressed to this device by another of the person's devices. */
    val commands: SharedFlow<DeviceCommand> = _commands.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Short, user-facing reasons something was refused ("That device is not available."). */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var job: Job? = null
    @Volatile private var session: DefaultWebSocketSession? = null
    private var lastPublished: AccountNowPlaying? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        session = null
        lastPublished = null
        _state.value = AccountState()
    }

    /** Called when the server address, the account or this device's name may have changed. */
    fun restart() {
        stop()
        start()
    }

    // ---- what this device says and does -----------------------------------------------------

    /**
     * Tells the other devices what this one is playing; null for "nothing". Identical reports are
     * dropped, and a position that only moved along with the clock is not worth a message: the
     * viewers extrapolate it from [AccountNowPlaying.anchorMs].
     */
    fun publish(np: AccountNowPlaying?) {
        val previous = lastPublished
        if (np == previous) return
        if (np != null && previous != null && np.videoId == previous.videoId &&
            np.isPlaying == previous.isPlaying && np.volume == previous.volume &&
            kotlin.math.abs(np.positionMs - previous.positionMs) < POSITION_JITTER_MS
        ) return
        lastPublished = np
        sendFrame(
            buildJsonObject {
                put("type", "nowPlaying")
                if (np != null) put("nowPlaying", json.encodeToJsonElement(AccountNowPlaying.serializer(), np))
            },
        )
    }

    /** Makes this device the remote of [deviceId]. */
    fun attach(deviceId: String) = sendFrame(buildJsonObject { put("type", "attach"); put("to", deviceId) })

    /** Stops being a remote. */
    fun detach() = sendFrame(buildJsonObject { put("type", "detach") })

    fun rename(name: String) = sendFrame(buildJsonObject { put("type", "rename"); put("name", name) })

    /** Sends [action] to [to]. See [DeviceActions]. */
    fun command(to: String, action: String, payload: JsonObject = JsonObject(emptyMap())) {
        sendFrame(
            buildJsonObject {
                put("type", "command")
                put("to", to)
                put("action", action)
                put("payload", payload)
                put("id", Random.nextLong().toString(36))
            },
        )
    }

    private fun sendFrame(frame: JsonObject) {
        val s = session ?: return
        scope.launch { runCatching { s.send(Frame.Text(frame.toString())) } }
    }

    // ---- the socket -------------------------------------------------------------------------

    private suspend fun run() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            val base = serverBase()?.trimEnd('/')
            val account = if (base.isNullOrBlank()) null else proof()
            if (base.isNullOrBlank() || account == null) {
                _state.update { it.copy(connection = AccountConnection.IDLE) }
                delay(IDLE_RETRY_MS)
                continue
            }
            val me = self()
            try {
                _state.update { it.copy(connection = AccountConnection.CONNECTING, selfId = me.deviceId) }
                val response: HttpResponse = http.post("$base/api/account/connect") {
                    // Encoded and decoded here, so this works whatever the shared client negotiates.
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(ConnectRequest.serializer(), ConnectRequest(account, me.deviceId, me.name, me.type)))
                }
                if (response.status.value == 404 || response.status.value == 405) {
                    _state.update { it.copy(connection = AccountConnection.UNSUPPORTED, devices = emptyList()) }
                    delay(UNSUPPORTED_RETRY_MS)
                    continue
                }
                if (!response.status.isSuccess()) error("connect failed: HTTP ${response.status.value}")
                val token = json.decodeFromString(ConnectResponse.serializer(), response.bodyAsText()).token
                http.webSocket(
                    urlString = base.replaceFirst("http", "ws") + "/ws/account",
                    request = { header("Authorization", "Bearer $token") },
                ) {
                    session = this
                    backoffMs = 1_000L
                    // What this device is playing is not known to the server on a new socket.
                    lastPublished?.let { last ->
                        lastPublished = null
                        publish(last)
                    }
                    val ping = launch {
                        while (true) {
                            delay(PING_MS)
                            runCatching { send(Frame.Text("""{"type":"ping"}""")) }
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) onFrame(frame.readText())
                        }
                    } finally {
                        ping.cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A socket drops for ordinary reasons all day; the reason is kept for diagnostics.
                _state.update { it.copy(failure = "${failure::class.simpleName}: ${failure.message}") }
            } finally {
                session = null
            }
            if (!scope.isActive) return
            _state.update { it.copy(connection = AccountConnection.CONNECTING) }
            delay(backoffMs / 2 + Random.nextLong(backoffMs + 1))
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun onFrame(text: String) {
        val frame = AccountFrames.parse(text) ?: return
        AccountFrames.devices(frame)?.let { list ->
            _state.update { it.copy(connection = AccountConnection.LIVE, devices = list, failure = null) }
            return
        }
        AccountFrames.command(frame)?.let { _commands.emit(it); return }
        if (frame["type"]?.jsonPrimitive?.contentOrNull == "error") {
            val reason = frame["message"]?.jsonPrimitive?.contentOrNull
            if (!reason.isNullOrBlank()) _errors.emit(reason)
        }
    }

    private companion object {
        const val PING_MS = 25_000L
        const val IDLE_RETRY_MS = 15_000L
        const val UNSUPPORTED_RETRY_MS = 10 * 60_000L
        const val MAX_BACKOFF_MS = 30_000L

        /** A playhead that has only moved with the clock is not news. */
        const val POSITION_JITTER_MS = 4_000L
    }
}

/** Convenience for building a payload without importing the JSON builders everywhere. */
fun devicePayload(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*entries))

fun jsonString(value: String): JsonElement = JsonPrimitive(value)
