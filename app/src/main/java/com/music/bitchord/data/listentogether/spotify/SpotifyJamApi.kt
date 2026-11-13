package com.music.bitchord.data.listentogether.spotify

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CANVAS_UA
import com.music.bitchord.data.canvas.SpotifyToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object SpotifyJamApi {

    private const val TAG = "SpotifyJamApi"
    private const val SPCLIENT_BASE = "https://spclient.wg.spotify.com"
    private const val SESSIONS_URL = "$SPCLIENT_BASE/social-connect/v2/sessions"
    private const val CONNECT_STATE_URL = "$SPCLIENT_BASE/connect-state/v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    internal suspend fun authHeaders(): Map<String, String>? {
        val token = SpotifyToken.accessToken() ?: return null
        val headers = mutableMapOf(
            "Authorization" to "Bearer $token",
            "User-Agent" to CANVAS_UA,
            "App-platform" to "WebPlayer",
            "Accept" to "application/json",
            "Origin" to "https://open.spotify.com",
            "Referer" to "https://open.spotify.com/",
        )
        SpotifyToken.clientToken()?.let { headers["Client-Token"] = it }
        return headers
    }

    /**
     * Resolves short links (e.g. spotify.link/xxx or spotify.app.link/xxx)
     * and extracts the Spotify Jam session token.
     */
    suspend fun resolveInvite(rawUrl: String): SpotifyJamInvite? = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("spotify:socialsession:")) {
            val token = trimmed.removePrefix("spotify:socialsession:").substringBefore('?').substringBefore('#')
            return@withContext if (token.isNotBlank()) SpotifyJamInvite(token, trimmed) else null
        }

        if (trimmed.contains("/socialsession/")) {
            val token = trimmed.substringAfter("/socialsession/").substringBefore('?').substringBefore('#').substringBefore('/')
            return@withContext if (token.isNotBlank()) SpotifyJamInvite(token, trimmed) else null
        }

        if (trimmed.contains("/jam/")) {
            val token = trimmed.substringAfter("/jam/").substringBefore('?').substringBefore('#').substringBefore('/')
            return@withContext if (token.isNotBlank()) SpotifyJamInvite(token, trimmed) else null
        }

        // Check if it's a short link that redirects
        if (trimmed.contains("spotify.link") || trimmed.contains("spotify.app.link")) {
            val resolvedUrl = runCatching {
                val req = Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", CANVAS_UA)
                    .build()
                Http.client.newCall(req).execute().use { response ->
                    response.request.url.toString()
                }
            }.getOrNull() ?: trimmed

            if (resolvedUrl.contains("/socialsession/")) {
                val token = resolvedUrl.substringAfter("/socialsession/").substringBefore('?').substringBefore('#').substringBefore('/')
                return@withContext if (token.isNotBlank()) SpotifyJamInvite(token, resolvedUrl) else null
            }
            if (resolvedUrl.contains("/jam/")) {
                val token = resolvedUrl.substringAfter("/jam/").substringBefore('?').substringBefore('#').substringBefore('/')
                return@withContext if (token.isNotBlank()) SpotifyJamInvite(token, resolvedUrl) else null
            }
        }

        null
    }

    /**
     * Creates a new Spotify Jam session. Requires a Spotify Premium account.
     */
    suspend fun createSession(): Result<SpotifyJamSession> = withContext(Dispatchers.IO) {
        val headers = authHeaders() ?: return@withContext Result.failure(IllegalStateException("Not signed in to Spotify"))
        val deviceId = SpotifyToken.deviceId()

        // Found by trying it on a real account: the endpoint is `current_or_new`, GET only, and it
        // creates the jam when there is none. Sending any `type` is refused ("not supported"), and
        // `sessions/current?create_if_not_found=…` only ever answered 404/405.
        val request = Request.Builder()
            .url("$SESSIONS_URL/current_or_new?local_device_id=$deviceId")
            .get()
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        runCatching {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val msg = "Failed to create Spotify Jam (HTTP ${response.code})"
                    Log.w(TAG, "$msg: $body")
                    throw IllegalStateException(msg)
                }
                parseSession(body, isHost = true)
            }
        }
    }

    /**
     * Joins an existing Spotify Jam session via its URI or token.
     * Works for both Premium and Free accounts.
     */
    suspend fun joinSession(sessionTokenOrUri: String): Result<SpotifyJamSession> = withContext(Dispatchers.IO) {
        val headers = authHeaders() ?: return@withContext Result.failure(IllegalStateException("Not signed in to Spotify"))
        val cleanUri = if (sessionTokenOrUri.startsWith("spotify:socialsession:")) {
            sessionTokenOrUri
        } else {
            "spotify:socialsession:$sessionTokenOrUri"
        }
        val deviceId = SpotifyToken.deviceId()

        // First attempt with listen_and_control
        var result = runCatching {
            val url = "$SESSIONS_URL/join?join_session_uri=$cleanUri&playback_control=listen_and_control&device_id=$deviceId"
            val req = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()

            Http.client.newCall(req).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: $body")
                }
                parseSession(body, isHost = false)
            }
        }

        // If rejected due to Free account tier, fallback to control_only
        if (result.isFailure) {
            result = runCatching {
                val fallbackUrl = "$SESSIONS_URL/join?join_session_uri=$cleanUri&playback_control=control_only&device_id=$deviceId"
                val req = Request.Builder()
                    .url(fallbackUrl)
                    .post("{}".toRequestBody(jsonMediaType))
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()

                Http.client.newCall(req).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Failed to join Spotify Jam (HTTP ${response.code})")
                    }
                    parseSession(body, isHost = false)
                }
            }
        }

        result
    }

    /**
     * Leaves or ends the current Spotify Jam session.
     */
    suspend fun leaveSession(sessionId: String?, isHost: Boolean): Boolean = withContext(Dispatchers.IO) {
        val headers = authHeaders() ?: return@withContext false
        val url = if (isHost && !sessionId.isNullOrBlank()) {
            "$SESSIONS_URL/$sessionId"
        } else {
            "$SESSIONS_URL/leave"
        }

        runCatching {
            val req = Request.Builder()
                .url(url)
                .apply {
                    if (isHost && !sessionId.isNullOrBlank()) delete() else post("{}".toRequestBody(jsonMediaType))
                    headers.forEach { (k, v) -> header(k, v) }
                }
                .build()

            Http.client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Sends playback control or queue operations to Spotify Jam / Connect State.
     */
    suspend fun sendCommand(
        command: String,
        targetDeviceId: String? = null,
        payload: JsonObject = buildJsonObject {},
    ): Boolean = withContext(Dispatchers.IO) {
        val headers = authHeaders() ?: return@withContext false
        val myDeviceId = SpotifyToken.deviceId()
        val url = if (!targetDeviceId.isNullOrBlank()) {
            "$CONNECT_STATE_URL/player/command/from/$myDeviceId/to/$targetDeviceId/$command"
        } else {
            "$CONNECT_STATE_URL/player/command/$command"
        }

        runCatching {
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()

            Http.client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "command '$command' failed: http ${response.code}")
                }
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    /**
     * Adds a track to the Spotify playback queue so it appears in the Jam.
     */
    suspend fun addToQueue(spotifyTrackUri: String): Boolean = withContext(Dispatchers.IO) {
        val headers = authHeaders() ?: return@withContext false
        val cleanUri = if (spotifyTrackUri.startsWith("spotify:track:")) {
            spotifyTrackUri
        } else {
            "spotify:track:$spotifyTrackUri"
        }

        // Try Web API queue endpoint first
        val webApiUrl = "https://api.spotify.com/v1/me/player/queue?uri=$cleanUri"
        val success = runCatching {
            val req = Request.Builder()
                .url(webApiUrl)
                .post("".toRequestBody(null))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            Http.client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        if (success) return@withContext true

        // Fallback to connect-state add_to_queue
        sendCommand("add_to_queue", payload = buildJsonObject {
            put("track_uri", cleanUri)
        })
    }

    private fun parseSession(jsonStr: String, isHost: Boolean): SpotifyJamSession {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val sessionObj = root["session"]?.jsonObject ?: root
        val sessionId = sessionObj["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // The real answer names the token itself (`join_session_token`); the uri form is the
        // fallback for older shapes.
        val tokenField = sessionObj["join_session_token"]?.jsonPrimitive?.contentOrNull
        val joinUri = sessionObj["join_session_uri"]?.jsonPrimitive?.contentOrNull
            ?: tokenField?.let { "spotify:socialsession:$it" }
            ?: "spotify:socialsession:$sessionId"
        val token = tokenField ?: joinUri.substringAfterLast(":")
        val ownerId = sessionObj["session_owner_id"]?.jsonPrimitive?.contentOrNull
        val joinUrl = "https://open.spotify.com/socialsession/$token"
        val ownerUsername = (sessionObj["session_members"] as? JsonArray)
            ?.map { it.jsonObject }
            ?.firstOrNull { ownerId != null && it["id"]?.jsonPrimitive?.contentOrNull == ownerId }
            ?.get("username")?.jsonPrimitive?.contentOrNull
        val hostUsername = sessionObj["host_username"]?.jsonPrimitive?.contentOrNull ?: ownerUsername.orEmpty()
        // Empty when no device of the host's is playing, which is the usual case here.
        val activeDeviceId = (sessionObj["active_device_id"] ?: sessionObj["hostActiveDeviceId"])
            ?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val isOwner = sessionObj["is_session_owner"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

        val membersArray = sessionObj["members"]?.jsonArray
            ?: sessionObj["session_members"]?.jsonArray
            ?: JsonArray(emptyList())

        val members = membersArray.mapNotNull { element ->
            val m = element.jsonObject
            val username = m["username"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val displayName = m["display_name"]?.jsonPrimitive?.contentOrNull ?: username
            val avatarUrl = m["avatar_url"]?.jsonPrimitive?.contentOrNull
                ?: m["image_url"]?.jsonPrimitive?.contentOrNull
            val memberIsHost = m["is_host"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: (ownerId != null && m["id"]?.jsonPrimitive?.contentOrNull == ownerId)
                ?: (username == hostUsername)
            SpotifyJamMember(
                username = username,
                displayName = displayName,
                avatarUrl = avatarUrl,
                isHost = memberIsHost,
                isConnected = true,
            )
        }

        return SpotifyJamSession(
            sessionId = sessionId,
            joinSessionUri = joinUri,
            joinSessionUrl = joinUrl,
            hostUsername = hostUsername,
            isHost = isHost || isOwner == true || (hostUsername.isNotBlank() && members.any { it.isHost && it.username == hostUsername }),
            members = members,
            activeDeviceId = activeDeviceId,
        )
    }
}
