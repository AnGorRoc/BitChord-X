package com.music.bitchord.data.listentogether.spotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.SpotifyToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Developer tool, dev flavor only: runs a list of HTTP calls with the app's own Spotify session
 * and writes what came back, so the internal API can be explored from a computer over adb
 * without tapping through the app.
 *
 *     adb push probe-request.json /sdcard/Android/data/<id>/files/probe-request.json
 *     adb shell am broadcast -a <id>.SPOTIFY_PROBE -p <id>
 *     adb pull /sdcard/Android/data/<id>/files/probe-out.txt
 *
 * The request file is a JSON array of steps: `{"m":"GET","u":"https://…","b":"{}"}`,
 * `{"sleep":2000}`, `{"dealer":"start"}` / `{"dealer":"stop"}` and `{"note":"…"}`. `{device}` in a
 * url or body stands for this app's Spotify device id. Calls are limited to Spotify's own hosts
 * and nothing secret is written out. This is meant to be removed once the bridge is built.
 */
object SpotifyProbeConsole {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val allowedHosts = setOf("spclient.wg.spotify.com", "api.spotify.com", "open.spotify.com")

    fun register(context: Context) {
        val appContext = context.applicationContext
        val action = "${appContext.packageName}.SPOTIFY_PROBE"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val pending = goAsync()
                scope.launch {
                    try {
                        run(appContext)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        // Exported on purpose, so that adb can reach it; only the dev flavor ever gets here.
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private suspend fun run(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val out = File(dir, "probe-out.txt")
        out.writeText("")
        val started = System.currentTimeMillis()
        fun line(text: String) {
            val t = (System.currentTimeMillis() - started) / 1000.0
            out.appendText("[t+%.1fs] %s\n".format(t, text))
        }
        val steps: JsonArray = runCatching {
            json.parseToJsonElement(File(dir, "probe-request.json").readText()).jsonArray
        }.getOrElse {
            line("cannot read probe-request.json: ${it.message}")
            return
        }
        line("probe console · ${Build.MODEL} · device=${SpotifyToken.deviceId().take(6)}…")

        val saved = mutableMapOf<String, String>()
        val dealer = SpotifyDealerClient()
        saved["device40"] = java.security.MessageDigest.getInstance("SHA-1")
            .digest(SpotifyToken.deviceId().toByteArray()).joinToString("") { "%02x".format(it) }
        dealer.rawTap = { raw ->
            // The first message of a dealer connection carries the id a device registration needs.
            Regex(""""Spotify-Connection-Id"\s*:\s*"([^"]+)"""").find(raw)?.let { saved["conn"] = it.groupValues[1] }
            line("DEALER ${SpotifyJamProbe.redactForReport(raw).take(3000)}")
        }
        try {
            for (step in steps) {
                val o = step.jsonObject
                val device = SpotifyToken.deviceId()
                fun fill(text: String): String =
                    saved.entries.fold(text.replace("{device}", device)) { acc, (k, v) -> acc.replace("{$k}", v) }
                when {
                    o["note"] != null -> line("NOTE ${o["note"]!!.jsonPrimitive.content}")
                    o["sleep"] != null -> delay(o["sleep"]!!.jsonPrimitive.content.toLong())
                    o["dealer"] != null -> {
                        if (o["dealer"]!!.jsonPrimitive.content == "start") dealer.start() else dealer.stop()
                        line("dealer ${o["dealer"]!!.jsonPrimitive.content}")
                    }
                    o["u"] != null -> {
                        val method = o["m"]?.jsonPrimitive?.contentOrNull ?: "GET"
                        val url = fill(o["u"]!!.jsonPrimitive.content)
                        val body = o["b"]?.jsonPrimitive?.contentOrNull?.let(::fill)
                        val reply = call(method, url, body, o["h"]?.jsonObject?.mapValues { fill(it.value.jsonPrimitive.content) }, ::line)
                        // `"save":{"as":"sid","key":"session_id"}` keeps a field of the reply for later steps.
                        o["save"]?.jsonObject?.let { save ->
                            val key = save["key"]!!.jsonPrimitive.content
                            val value = runCatching {
                                json.parseToJsonElement(reply).jsonObject[key]?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                            if (value != null) saved[save["as"]!!.jsonPrimitive.content] = value
                            line("saved ${save["as"]!!.jsonPrimitive.content}=${if (value != null) "yes" else "NOT FOUND"}")
                        }
                    }
                }
            }
        } finally {
            dealer.rawTap = null
            dealer.stop()
            line("done")
        }
    }

    private suspend fun call(
        method: String,
        url: String,
        body: String?,
        extraHeaders: Map<String, String>?,
        line: (String) -> Unit,
    ): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        if (host !in allowedHosts) {
            line("REFUSED $method $url (host not allowed)")
            return ""
        }
        val headers = SpotifyJamApi.authHeaders()
        if (headers == null) {
            line("$method $url -> no token")
            return ""
        }
        var raw = ""
        val result = runCatching {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            extraHeaders?.forEach { (k, v) -> builder.header(k, v) }
            val payload = (body ?: "").toRequestBody(jsonType)
            when (method.uppercase()) {
                "GET" -> builder.get()
                "DELETE" -> if (body != null) builder.delete(payload) else builder.delete()
                "PUT" -> builder.put(payload)
                "PATCH" -> builder.patch(payload)
                else -> builder.post(payload)
            }
            Http.client.newCall(builder.build()).execute().use { r ->
                val interesting = listOf("content-type", "allow", "www-authenticate", "retry-after")
                    .mapNotNull { n -> r.header(n)?.let { "$n=$it" } }
                raw = r.body.string()
                "HTTP ${r.code} ${interesting.joinToString(" ")} ${SpotifyJamProbe.redactForReport(raw).take(4000)}"
            }
        }.getOrElse { "ERROR ${it::class.simpleName}: ${it.message}" }
        line("$method ${SpotifyJamProbe.redactForReport(url).substringAfter(".com")} -> $result")
        return raw
    }
}
