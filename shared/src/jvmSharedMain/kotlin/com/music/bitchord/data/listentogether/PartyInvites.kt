package com.music.bitchord.data.listentogether

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** An invite read out of a link: which party, and on which server it lives. */
data class ParsedJamInvite(
    val code: String,
    /**
     * The server holding the party, already normalised. Null only for a bare code with no link
     * around it, which means "whatever server this device would use anyway".
     */
    val serverUrl: String? = null,
    val spotifyJamToken: String? = null,
    val spotifyJamUrl: String? = null,
) {
    val isSpotifyJam: Boolean get() = !spotifyJamToken.isNullOrBlank()
}

/** The servers every build knows by name, whatever its own default is. */
object PartyServers {
    /** The original BitChord's party server: where its users' parties live. */
    const val ORIGINAL_OFFICIAL = "https://api.bitchord.kushagrasingh.in"

    /** The original BitChord's public web origin, which its invite links use. */
    const val ORIGINAL_WEB = "https://bitchord.kushagrasingh.in"

    /** Feature names an extended server lists at `/api/info`. */
    const val FEATURE_LISTENING_MODE = "listeningMode"
    const val FEATURE_MY_DEVICES = "myDevices"

    /**
     * The features a server's `/api/info` body lists. Empty for anything unreadable, which is what
     * the original server's 404 page is: baseline protocol only.
     */
    fun parseFeatures(body: String): Set<String> = runCatching {
        Json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
            ?.map { it.jsonPrimitive.content }?.toSet()
    }.getOrNull().orEmpty()

    fun isOriginalOfficial(server: String?): Boolean =
        server != null && normalize(server).equals(ORIGINAL_OFFICIAL, ignoreCase = true)

    /**
     * Canonical form of a server base: lower-case scheme and host, no trailing slash, no query or
     * fragment. Blank or unusable input gives "".
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return ""
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return ""
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme !in setOf("http", "https") || host.isNullOrBlank() || uri.userInfo != null) return ""
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://$host$port$path"
    }
}

/**
 * Every invite shape both apps share, read and written in one place so a link made on one device
 * resolves to the same party and the same server on any other — phone, PC, this build or the
 * original BitChord.
 *
 * The rule that makes links portable: **a link names its server**. The only links that do not are
 * the original app's, and those mean the original app's official server, never "whatever server
 * the reader happens to use" — that reading is what sent people to a party that was not there.
 */
object PartyInvites {

    const val CODE_LENGTH = 6

    private const val ORIGINAL_HOST = "bitchord.kushagrasingh.in"
    private const val CUSTOM_SCHEME = "bitchord"
    private const val CUSTOM_HOST = "party"
    private val INVITE_PATH = Regex("""^(.*)/invite/([A-Za-z0-9]{$CODE_LENGTH})$""", RegexOption.IGNORE_CASE)

    /** Reads any invite link. Null for text that is not one, including a bare code. */
    fun parse(value: String?): ParsedJamInvite? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()
        val query = uri.rawQuery
        val namedServer = queryParam(query, "server")?.let(PartyServers::normalize)?.ifBlank { null }

        spotifyToken(uri, scheme, host)?.let { token ->
            return ParsedJamInvite(
                code = token.take(CODE_LENGTH).uppercase(),
                spotifyJamToken = token,
                spotifyJamUrl = "https://open.spotify.com/socialsession/$token",
            )
        }

        // bitchord://party/<CODE>?server=<SERVER> or bitchord://party?code=<CODE>
        if (scheme == CUSTOM_SCHEME && host == CUSTOM_HOST) {
            val candidate = uri.path.orEmpty().trim('/').ifBlank { null }
                ?: queryParam(query, "code")
                ?: return null
            val code = cleanCode(candidate) ?: return null
            return ParsedJamInvite(code, namedServer ?: PartyServers.ORIGINAL_OFFICIAL)
        }

        if (scheme != "https" && scheme != "http") return null
        if (host.isNullOrBlank()) return null
        val match = INVITE_PATH.matchEntire(uri.rawPath.orEmpty()) ?: return null
        val code = match.groupValues[2].uppercase()

        // The original web domain: its own links carry no server and mean its official one.
        if (host == ORIGINAL_HOST) {
            if (scheme != "https") return null
            return ParsedJamInvite(code, namedServer ?: PartyServers.ORIGINAL_OFFICIAL)
        }

        // Any other host is a party server's own landing page, and the party lives there.
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val origin = PartyServers.normalize("$scheme://$host$port${match.groupValues[1]}")
        return ParsedJamInvite(code, namedServer ?: origin.ifBlank { return null })
    }

    /**
     * Reads what somebody typed or pasted into a code field: a link of any shape, or a code with
     * stray spaces or dashes. Null when it is neither.
     */
    fun fromInput(value: String?): ParsedJamInvite? {
        parse(value)?.let { return it }
        val code = cleanCode(value.orEmpty()) ?: return null
        return ParsedJamInvite(code, serverUrl = null)
    }

    /**
     * The link to share for a party on [server]. A party on the original official server gets the
     * original app's own link, so its users can open it; any other gets the server's landing page,
     * which both apps understand and which opens either one through its `bitchord://` button.
     */
    fun url(code: String, server: String?): String {
        val base = server?.let(PartyServers::normalize).orEmpty()
        return if (base.isBlank() || base.equals(PartyServers.ORIGINAL_OFFICIAL, ignoreCase = true)) {
            "${PartyServers.ORIGINAL_WEB}/invite/${code.uppercase()}"
        } else {
            "$base/invite/${code.uppercase()}"
        }
    }

    /** The app-scheme form of an invite, always naming its server when there is one. */
    fun schemeUrl(code: String, server: String?): String {
        val base = server?.let(PartyServers::normalize).orEmpty()
        val normalizedCode = code.uppercase()
        return if (base.isBlank()) {
            "$CUSTOM_SCHEME://$CUSTOM_HOST/$normalizedCode"
        } else {
            "$CUSTOM_SCHEME://$CUSTOM_HOST/$normalizedCode?server=${URLEncoder.encode(base, "UTF-8")}"
        }
    }

    private fun spotifyToken(uri: URI, scheme: String, host: String?): String? {
        val raw = when {
            scheme == "spotify" -> {
                val ssp = uri.schemeSpecificPart.orEmpty()
                when {
                    host == "socialsession" || host == "jam" ->
                        uri.path.orEmpty().trim('/').ifBlank { ssp.substringAfterLast(':') }
                    ssp.startsWith("socialsession:") || ssp.startsWith("jam:") -> ssp.substringAfter(':')
                    else -> null
                }
            }
            (scheme == "https" || scheme == "http") &&
                (host == "open.spotify.com" || host?.endsWith(".spotify.com") == true) -> {
                val path = uri.path.orEmpty()
                when {
                    "/socialsession/" in path -> path.substringAfter("/socialsession/")
                    "/jam/" in path -> path.substringAfter("/jam/")
                    else -> null
                }
            }
            else -> null
        }
        return raw?.substringBefore('?')?.substringBefore('#')?.trim('/')?.takeIf(String::isNotBlank)
    }

    private fun cleanCode(raw: String): String? =
        raw.filter(Char::isLetterOrDigit).uppercase().takeIf { it.length == CODE_LENGTH }

    private fun queryParam(query: String?, name: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.isNotEmpty() && it[0].equals(name, ignoreCase = true) }
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }
}
