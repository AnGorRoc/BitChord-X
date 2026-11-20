package com.music.bitchord.data.presence

import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.Http
import com.music.bitchord.data.telemetry.TelemetryLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class PresencePlatform(val wire: String) { Android("android"), Pc("pc") }

/**
 * Tells the BitChord server this install is open, so it can publish how many
 * apps are open right now (`GET /api/stats/live`).
 *
 * While open, one small POST goes out every few minutes. The server sets the
 * interval in each reply, so it can slow every client down without an update.
 * Closing sends one more POST so the count drops straight away; if that one is
 * lost, the server forgets the install a minute or two after its last ping.
 *
 * Only a random per-install UUID and the platform are sent, never anything
 * about the listener or what they play. This always goes to the official
 * server, not a custom Listen Together one, so the public count is only ours.
 */
class Presence(
    private val platform: PresencePlatform,
    private val installId: String,
    private val endpoint: String = ENDPOINT,
    private val appVersion: String = "",
    private val levelProvider: () -> TelemetryLevel = { TelemetryLevel.FULL },
    private val useWebSocket: Boolean = false,
) {
    private val open = MutableStateFlow(false)
    @Volatile private var announced = false
    @Volatile private var intervalSec = DEFAULT_INTERVAL_SEC
    @Volatile private var activeSocket: WebSocket? = null

    init {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            if (throwable !is CancellationException) {
                DebugLog.d(TAG, "presence scope uncaught error: ${throwable.message}")
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler).launch {
            // collectLatest: a change of state cancels whatever the last one was
            // still doing, so a close cancels the socket/ping loop and a quick
            // reopen cancels a pending close.
            open.collectLatest { isOpen ->
                if (isOpen) {
                    var wsUnsupported = !useWebSocket
                    while (true) {
                        if (!wsUnsupported) {
                            val supported = runWebSocketSession()
                            if (!supported) {
                                wsUnsupported = true
                            } else {
                                delay(WS_RECONNECT_DELAY_MS)
                                continue
                            }
                        }
                        send(open = true)
                        delay(nextDelayMs())
                    }
                } else if (announced) {
                    activeSocket?.close(1000, "closed")
                    activeSocket = null
                    delay(CLOSE_DEBOUNCE_MS)
                    send(open = false)
                }
            }
        }
    }

    fun setOpen(isOpen: Boolean) {
        open.value = isOpen && levelProvider().sends
    }

    /**
     * For process exit, where nothing launched would get to run: closes the
     * socket or sends the HTTP close on the calling thread within two seconds.
     */
    fun closeBlocking() {
        open.value = false
        activeSocket?.close(1000, "exit")
        activeSocket = null
        if (announced) send(open = false, timeoutSec = 2)
    }

    private fun wsEndpoint(): String? {
        if (!endpoint.endsWith("/api/presence")) return null
        val wsBase = when {
            endpoint.startsWith("https://") -> "wss://" + endpoint.removePrefix("https://")
            endpoint.startsWith("http://") -> "ws://" + endpoint.removePrefix("http://")
            else -> return null
        }
        return wsBase.removeSuffix("/api/presence") + "/ws/presence"
    }

    /**
     * Connects `/ws/presence`, sends the hello frame, and suspends until the
     * socket drops or the coroutine is cancelled. Returns `false` only if the
     * server explicitly rejected the upgrade with HTTP 404/405/501 (older server).
     */
    private suspend fun runWebSocketSession(): Boolean {
        val url = wsEndpoint() ?: return false
        val level = levelProvider()
        if (!level.sends) return true
        val closed = CompletableDeferred<Boolean>()
        val hello = buildJsonObject {
            put("id", idFor(level))
            put("platform", platform.wire)
            if (appVersion.isNotBlank()) put("version", appVersion)
            if (level == TelemetryLevel.MINIMAL) put("level", level.wire)
        }.toString()

        val req = Request.Builder().url(url).build()
        val ws = Http.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                announced = true
                webSocket.send(hello)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                announced = false
                closed.complete(true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val unsupported = code == 404 || code == 405 || code == 501
                DebugLog.d(TAG, "presence ws closed/failed (HTTP $code): ${t.message}")
                closed.complete(!unsupported)
            }
        })
        activeSocket = ws
        return try {
            closed.await()
        } catch (c: CancellationException) {
            ws.close(1000, "closed")
            announced = false
            throw c
        } finally {
            if (activeSocket === ws) activeSocket = null
        }
    }

    /** The install's own ID, or at [TelemetryLevel.MINIMAL] one that changes every UTC day. */
    private fun idFor(level: TelemetryLevel): String =
        TelemetryLevel.idFor(level, installId, TelemetryLevel.today())

    private fun nextDelayMs(): Long {
        val base = intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC) * 1000L
        // ±10%, so a release that starts thousands of apps at once spreads out.
        return base + Random.nextLong(-base / 10, base / 10 + 1)
    }

    private fun send(open: Boolean, timeoutSec: Long = 15) {
        val level = levelProvider()
        if (open && !level.sends) return
        val body = buildJsonObject {
            put("id", idFor(level))
            put("platform", platform.wire)
            put("open", open)
            if (appVersion.isNotBlank()) {
                put("version", appVersion)
            }
            if (level == TelemetryLevel.MINIMAL) put("level", level.wire)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url(endpoint).post(body).build()
        // Set before sending: a ping that times out may still have been counted,
        // so it still needs a close.
        if (open) announced = true
        try {
            val call = Http.client.newCall(request)
            call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (!open) announced = false
                if (!response.isSuccessful) {
                    DebugLog.w(TAG, "presence ping refused: HTTP ${response.code}")
                    return
                }
                val text = response.body.string().orEmpty()
                runCatching { Json.parseToJsonElement(text).jsonObject["intervalSec"]?.jsonPrimitive?.intOrNull }
                    .getOrNull()
                    ?.let { intervalSec = it }
            }
        } catch (e: Exception) {
            // Offline, or the server is down. The next ping is soon enough.
            DebugLog.d(TAG, "presence ping failed: ${e.message}")
        }
    }

    companion object {
        const val ENDPOINT = "https://api.bitchord.kushagrasingh.in/api/presence"
        private const val TAG = "Presence"
        private const val DEFAULT_INTERVAL_SEC = 60
        private const val MIN_INTERVAL_SEC = 30
        private const val MAX_INTERVAL_SEC = 3600
        private const val CLOSE_DEBOUNCE_MS = 2_000L
        private const val WS_RECONNECT_DELAY_MS = 5_000L
        private val JSON = "application/json".toMediaType()
    }
}
