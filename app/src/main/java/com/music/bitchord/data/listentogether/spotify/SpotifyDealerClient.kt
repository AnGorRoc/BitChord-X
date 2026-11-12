package com.music.bitchord.data.listentogether.spotify

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.SpotifyToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Real-time event listener connected to Spotify's Dealer WebSocket gateway.
 */
class SpotifyDealerClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = HttpClient(OkHttp) {
        engine {
            config {
                readTimeout(0, TimeUnit.MILLISECONDS)
                connectTimeout(15, TimeUnit.SECONDS)
                pingInterval(30, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        install(WebSockets)
    }

    private var socketJob: Job? = null
    @Volatile private var isRunning = false

    private val _events = MutableSharedFlow<DealerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DealerEvent> = _events.asSharedFlow()

    sealed interface DealerEvent {
        data class PlaybackUpdated(val state: SpotifyJamPlaybackState) : DealerEvent
        data class SessionMembersUpdated(val members: List<SpotifyJamMember>) : DealerEvent
        data class SessionEnded(val reason: String = "ended") : DealerEvent
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        socketJob?.cancel()
        socketJob = scope.launch { runLoop() }
    }

    fun stop() {
        isRunning = false
        socketJob?.cancel()
        socketJob = null
    }

    private suspend fun runLoop() {
        var backoffMs = 1_000L
        while (isRunning && scope.isActive) {
            val token = SpotifyToken.accessToken()
            if (token.isNullOrBlank()) {
                delay(3_000L)
                continue
            }

            try {
                val url = "wss://dealer.spotify.com/?access_token=$token"
                http.webSocket(urlString = url) {
                    backoffMs = 1_000L
                    Log.i(TAG, "connected to Spotify dealer websocket")

                    val pingJob = launch {
                        while (isActive) {
                            delay(30_000L)
                            runCatching { send(Frame.Text("{\"type\":\"ping\"}")) }
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handleMessage(frame.readText())
                            }
                        }
                    } finally {
                        pingJob.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "dealer socket dropped: ${e.message}")
            }

            if (!isRunning) break
            delay(backoffMs / 2 + Random.nextLong(backoffMs + 1))
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun handleMessage(raw: String) {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val uri = root["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val payloads = root["payloads"]?.jsonArray

        // 1. Session updates: members, join/leave
        if (uri.contains("social-connect") || uri.contains("session")) {
            val payloadStr = payloads?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?: root["payload"]?.jsonPrimitive?.contentOrNull
            if (!payloadStr.isNullOrBlank()) {
                parseSessionUpdate(payloadStr)
            }
        }

        // 2. Cluster / Player state updates: track, position, playing state
        if (uri.contains("connect-state") || uri.contains("cluster")) {
            val payloadStr = payloads?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?: root["payload"]?.jsonPrimitive?.contentOrNull
            if (!payloadStr.isNullOrBlank()) {
                parseClusterUpdate(payloadStr)
            }
        }
    }

    private suspend fun parseSessionUpdate(payloadStr: String) {
        val obj = runCatching { json.parseToJsonElement(payloadStr).jsonObject }.getOrNull() ?: return
        val sessionObj = obj["session"]?.jsonObject ?: obj
        val membersArray = sessionObj["members"]?.jsonArray
            ?: sessionObj["session_members"]?.jsonArray
        if (membersArray != null) {
            val members = membersArray.mapNotNull { element ->
                val m = element.jsonObject
                val username = m["username"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = m["display_name"]?.jsonPrimitive?.contentOrNull ?: username
                val avatarUrl = m["avatar_url"]?.jsonPrimitive?.contentOrNull
                    ?: m["image_url"]?.jsonPrimitive?.contentOrNull
                val isHost = m["is_host"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                SpotifyJamMember(
                    username = username,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    isHost = isHost,
                    isConnected = true,
                )
            }
            _events.emit(DealerEvent.SessionMembersUpdated(members))
        }

        val state = sessionObj["state"]?.jsonPrimitive?.contentOrNull
        if (state == "ENDED" || state == "TERMINATED") {
            _events.emit(DealerEvent.SessionEnded(state))
        }
    }

    private suspend fun parseClusterUpdate(payloadStr: String) {
        val obj = runCatching { json.parseToJsonElement(payloadStr).jsonObject }.getOrNull() ?: return
        val cluster = obj["cluster"]?.jsonObject ?: obj
        val playerState = cluster["player_state"]?.jsonObject ?: return

        val track = playerState["track"]?.jsonObject
        val trackUri = track?.get("uri")?.jsonPrimitive?.contentOrNull
            ?: playerState["track_uri"]?.jsonPrimitive?.contentOrNull
        val metadata = track?.get("metadata")?.jsonObject
        val title = metadata?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
        val artist = metadata?.get("artist_name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val album = metadata?.get("album_title")?.jsonPrimitive?.contentOrNull.orEmpty()
        val durationMs = metadata?.get("duration")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val imageUrl = metadata?.get("image_url")?.jsonPrimitive?.contentOrNull

        val isPlaying = playerState["is_playing"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: !playerState["is_paused"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull().let { it == true }
        val positionMs = playerState["position_as_of_timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: playerState["position"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: 0L
        val timestamp = playerState["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: System.currentTimeMillis()

        val nextTracks = playerState["next_tracks"]?.jsonArray?.mapNotNull {
            it.jsonObject["uri"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()

        val playbackState = SpotifyJamPlaybackState(
            trackUri = trackUri,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            imageUrl = imageUrl,
            isPlaying = isPlaying,
            positionMs = positionMs,
            timestampMs = timestamp,
            nextTrackUris = nextTracks,
        )
        _events.emit(DealerEvent.PlaybackUpdated(playbackState))
    }

    companion object {
        private const val TAG = "SpotifyDealerClient"
    }
}
