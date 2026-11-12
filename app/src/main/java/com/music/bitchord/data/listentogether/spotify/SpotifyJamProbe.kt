package com.music.bitchord.data.listentogether.spotify

import android.os.Build
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.SpotifyToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Diagnostic run for the Spotify Jam bridge, dev builds only.
 *
 * The bridge talks to Spotify's internal endpoints, which are not documented, so what they accept
 * can only be learned by trying. This creates a jam, tries each command the bridge would need, and
 * records every HTTP status and every message the Dealer pushes in between, so the answers do not
 * have to be guessed. It ends the jam when it is done.
 *
 * Nothing secret goes in the report: access tokens, the client token and the jam's join token are
 * cut before anything is written.
 */
object SpotifyJamProbe {

    private const val SPCLIENT = "https://spclient.wg.spotify.com"
    private const val SESSIONS = "$SPCLIENT/social-connect/v2/sessions"
    private const val CONNECT_STATE = "$SPCLIENT/connect-state/v1"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // Well-known tracks, only so there is something to queue; the ids are not special.
    private const val TRACK_A = "spotify:track:4PTG3Z6ehGkBFwjybzWkR8"
    private const val TRACK_B = "spotify:track:003vvx7Niy0yvhvHT4a68B"
    private const val TRACK_C = "spotify:track:7tFiyTwD0nx5a1eklYtX2J"

    private val secretPatterns = listOf(
        Regex("""("(?:access_token|accessToken|client_token|token|authorization)"\s*:\s*")[^"]+"""") to "$1<cut>\"",
        Regex("""access_token=[^&"\s]+""") to "access_token=<cut>",
        Regex("""Bearer\s+[A-Za-z0-9._\-]+""") to "Bearer <cut>",
        Regex("""(socialsession[:/])([A-Za-z0-9]{3})[A-Za-z0-9]+""") to "$1$2…",
    )

    private fun redact(text: String): String =
        secretPatterns.fold(text) { acc, (re, to) -> re.replace(acc, to) }

    /** [redact], for the adb console. */
    internal fun redactForReport(text: String): String = redact(text)

    suspend fun run(): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        val startedAt = System.currentTimeMillis()
        fun line(text: String) {
            val t = (System.currentTimeMillis() - startedAt) / 1000.0
            out.append("[t+%.1fs] ".format(t)).append(text).append('\n')
        }

        line("Spotify Jam probe · flavor=${BuildConfig.FLAVOR} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}")
        val tier = runCatching { SpotifyToken.accountTier() }.getOrNull()
        line("tier=$tier  deviceId=${SpotifyToken.deviceId().take(6)}…")
        // The plan check has been wrong before, so it is reported rather than trusted: Spotify
        // itself refuses a jam to an account that cannot host one, and that answer is the real one.
        run {
            val token = SpotifyToken.accessToken()
            if (token == null) {
                line("STOP: no Spotify token (is the account signed in?)")
                return@withContext out.toString()
            }
            val plan = Regex(""""product"\s*:\s*"([^"]*)"""")
            val personal = Regex("""("(?:email|display_name|id)"\s*:\s*")[^"]*"""")
            for ((label, url) in listOf(
                "plan from /v1/me" to "https://api.spotify.com/v1/me",
                "plan from product_state" to "$SPCLIENT/melody/v1/product_state?market=from_token",
            )) {
                val headers = SpotifyJamApi.authHeaders() ?: continue
                val shown = runCatching {
                    Http.client.newCall(
                        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build(),
                    ).execute().use { r ->
                        val body = r.body.string()
                        "HTTP ${r.code} product=${plan.find(body)?.groupValues?.get(1)} bodyStart=" +
                            redact(body.replace(personal, "$1<cut>\"")).take(300)
                    }
                }.getOrElse { "ERROR ${it::class.simpleName}: ${it.message}" }
                line("$label -> $shown")
            }
        }

        val dealer = SpotifyDealerClient()
        dealer.rawTap = { raw -> line("DEALER ${redact(raw).take(2500)}") }
        dealer.start()

        suspend fun call(label: String, method: String, url: String, body: String? = null) {
            val headers = SpotifyJamApi.authHeaders()
            if (headers == null) {
                line("$label -> no token")
                return
            }
            val result = runCatching {
                val builder = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                val payload = (body ?: "").toRequestBody(jsonType)
                when (method) {
                    "GET" -> builder.get()
                    "DELETE" -> builder.delete()
                    "PUT" -> builder.put(payload)
                    else -> builder.post(payload)
                }
                Http.client.newCall(builder.build()).execute().use { r ->
                    "HTTP ${r.code} ${redact(r.body.string()).take(1500)}"
                }
            }.getOrElse { "ERROR ${it::class.simpleName}: ${it.message}" }
            line("$label [$method ${redact(url).substringAfter(".com")}] -> $result")
        }

        try {
            line("STEP 1: create the jam")
            // Which verb the endpoint takes is the first thing to learn, so each is shown. They
            // all ask for the current session, so none can make a second one.
            for (verb in listOf("GET", "PUT", "POST")) {
                call("create via $verb", verb, "$SESSIONS/current?create_if_not_found=true&device_id=${SpotifyToken.deviceId()}", "{}")
                delay(1_000)
            }
            val created = SpotifyJamApi.createSession()
            val session = created.getOrNull()
            if (session == null) {
                line("create FAILED: ${created.exceptionOrNull()?.message}")
                return@withContext out.toString()
            }
            line("created: sessionId=${session.sessionId.take(6)}… isHost=${session.isHost} " +
                "activeDevice=${session.activeDeviceId} members=${session.members.size}")
            call("current session", "GET", "$SESSIONS/current")
            delay(6_000)

            val me = SpotifyToken.deviceId()
            val target = session.activeDeviceId
            val toTarget = if (target.isNullOrBlank()) null else "$CONNECT_STATE/player/command/from/$me/to/$target"
            val noTarget = "$CONNECT_STATE/player/command"

            line("STEP 2: queue three tracks (Web API)")
            for ((i, uri) in listOf(TRACK_A, TRACK_B, TRACK_C).withIndex()) {
                call("queue ${i + 1}", "POST", "https://api.spotify.com/v1/me/player/queue?uri=$uri")
                delay(2_500)
            }

            line("STEP 3: queue one track through connect-state")
            call("add_to_queue", "POST", "$noTarget/add_to_queue", """{"track_uri":"$TRACK_A"}""")
            delay(3_000)

            line("STEP 4: playback commands, with and without a target device")
            for ((name, payload) in listOf(
                "resume" to "{}", "pause" to "{}", "resume" to "{}",
                "seek_to" to """{"value":30000}""", "seek_to" to """{"position":30000}""",
                "skip_next" to "{}", "skip_prev" to "{}",
            )) {
                if (toTarget != null) call("$name (to device)", "POST", "$toTarget/$name", payload)
                call("$name (untargeted)", "POST", "$noTarget/$name", payload)
                delay(3_000)
            }

            line("STEP 5: play one specific track")
            call(
                "play uri (connect-state)", "POST", "${toTarget ?: noTarget}/play",
                """{"context":{"uri":"$TRACK_B","url":"context://$TRACK_B"},"options":{}}""",
            )
            delay(3_000)
            call("play uri (Web API)", "PUT", "https://api.spotify.com/v1/me/player/play", """{"uris":["$TRACK_C"]}""")
            delay(4_000)

            line("STEP 6: session state, then end the jam")
            call("current session", "GET", "$SESSIONS/current")
            val ended = SpotifyJamApi.leaveSession(session.sessionId, isHost = true)
            line("leaveSession(host) -> $ended")
            delay(3_000)
            call("current session after ending", "GET", "$SESSIONS/current")
        } catch (e: Exception) {
            line("CRASH ${e::class.simpleName}: ${e.message}")
        } finally {
            dealer.rawTap = null
            dealer.stop()
            line("done")
        }
        out.toString()
    }
}
