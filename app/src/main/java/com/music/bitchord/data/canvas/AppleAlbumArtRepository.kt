package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Custom album design / logo fetched from Apple Music editorial artwork.
 *
 * In Apple Music (and iOS players like MONO), major album releases feature
 * custom title treatments (transparent wordmarks) or store flowcase banners
 * designed specifically for the album by the artist's record label.
 */
data class AppleAlbumLogo(
    val url: String,
    val aspect: Float = 1f,
    val isTransparent: Boolean = true,
)

object AppleAlbumArtRepository {

    private const val TAG = "AppleAlbumArt"
    private const val CACHE_SIZE = 64
    private const val LOGO_WIDTH = 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class Entry(val logo: AppleAlbumLogo?)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CACHE_SIZE
    }

    private val _updates = MutableStateFlow(0)
    val updates: StateFlow<Int> = _updates

    private fun cacheKey(album: String, artist: String): String =
        "${artist.normalizeForMatch()}|${album.normalizeForMatch()}"

    fun cached(album: String, artist: String): AppleAlbumLogo? {
        val key = cacheKey(album, artist)
        return synchronized(cache) { cache[key] }?.logo
    }

    fun cachedArtistLogo(artist: String): AppleAlbumLogo? {
        val key = "artist:${cleanArtistName(artist).normalizeForMatch()}"
        return synchronized(cache) { cache[key] }?.logo
    }

    suspend fun logoFor(album: String, artist: String): AppleAlbumLogo? {
        val key = cacheKey(album, artist)
        synchronized(cache) { cache[key] }?.let { return it.logo }

        val entry = withContext(Dispatchers.IO) {
            runCatching { lookup(album, artist) }
                .onFailure { Log.d(TAG, "lookup failed for $album by $artist: $it") }
                .getOrNull()
        } ?: return null

        synchronized(cache) { cache[key] = entry }
        _updates.value++
        return entry.logo
    }

    suspend fun artistLogoFor(artist: String): AppleAlbumLogo? {
        val clean = cleanArtistName(artist)
        if (clean.isBlank()) return null
        val key = "artist:${clean.normalizeForMatch()}"
        synchronized(cache) { cache[key] }?.let { return it.logo }

        val entry = withContext(Dispatchers.IO) {
            runCatching { lookupArtist(clean) }
                .onFailure { Log.d(TAG, "lookup artist logo failed for $artist: $it") }
                .getOrNull()
        } ?: return null

        synchronized(cache) { cache[key] = entry }
        _updates.value++
        return entry.logo
    }

    private fun cleanArtistName(raw: String): String {
        return raw.substringBefore(" feat.")
            .substringBefore(" ft.")
            .substringBefore(" & ")
            .substringBefore(" , ")
            .trim()
    }

    private fun lookup(album: String, artist: String): Entry {
        val bearer = AppleMusicCanvas.token() ?: return Entry(null)
        val term = if (album.contains(artist, ignoreCase = true)) album else "$artist $album"

        val url = "${AppleMusicCanvas.AMP}/${AppleMusicCanvas.storefront}/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "albums")
            .addQueryParameter("limit", "5")
            .addQueryParameter("extend", "editorialArtwork")
            .build()
            .toString()

        val request = Request.Builder().url(url).apply {
            header("Authorization", "Bearer $bearer")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", "Mozilla/5.0")
        }.build()

        val body = runCatching {
            Http.client.newCall(request).execute().use { res ->
                if (res.isSuccessful) res.body.string() else null
            }
        }.getOrNull() ?: return Entry(null)

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return Entry(null)
        val hits = root["results"]?.jsonObject
            ?.get("albums")?.jsonObject
            ?.get("data")?.jsonArray
            ?: return Entry(null)

        val ranked = hits.mapNotNull { hit ->
            val record = hit as? JsonObject ?: return@mapNotNull null
            val score = AppleMusicCanvas.score(record, album, artist, album, albumIsSelf = true)
                ?: return@mapNotNull null
            score to record
        }.sortedByDescending { it.first }

        for ((score, record) in ranked) {
            if (score < AppleMusicCanvas.MIN_SCORE) break
            val attributes = record["attributes"]?.jsonObject ?: continue
            val edArt = attributes["editorialArtwork"]?.jsonObject ?: continue

            // Only pure transparent title treatment / logo wordmarks
            val tt = edArt["titleTreatment"]?.jsonObject
                ?: edArt["musicContentColorLogoTrimmed"]?.jsonObject
            if (tt != null) {
                val rawUrl = tt["url"]?.jsonPrimitive?.contentOrNull
                val w = tt["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1000
                val h = tt["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 300
                if (rawUrl != null && w > 0 && h > 0) {
                    val targetH = (LOGO_WIDTH.toLong() * h / w).toInt()
                    val logoUrl = fill(rawUrl, LOGO_WIDTH, targetH, "png")
                    Log.d(TAG, "Found transparent title logo for '$album'")
                    return Entry(AppleAlbumLogo(logoUrl, w.toFloat() / h, isTransparent = true))
                }
            }
        }

        return Entry(null)
    }

    private fun lookupArtist(artist: String): Entry {
        val bearer = AppleMusicCanvas.token() ?: return Entry(null)
        val term = artist.trim()
        if (term.isBlank()) return Entry(null)

        val url = "${AppleMusicCanvas.AMP}/${AppleMusicCanvas.storefront}/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "artists")
            .addQueryParameter("limit", "5")
            .addQueryParameter("extend", "editorialArtwork")
            .build()
            .toString()

        val request = Request.Builder().url(url).apply {
            header("Authorization", "Bearer $bearer")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", "Mozilla/5.0")
        }.build()

        val body = runCatching {
            Http.client.newCall(request).execute().use { res ->
                if (res.isSuccessful) res.body.string() else null
            }
        }.getOrNull() ?: return Entry(null)

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return Entry(null)
        val hits = root["results"]?.jsonObject
            ?.get("artists")?.jsonObject
            ?.get("data")?.jsonArray
            ?: return Entry(null)

        val normTarget = artist.normalizeForMatch()

        for (hit in hits) {
            val record = hit as? JsonObject ?: continue
            val attributes = record["attributes"]?.jsonObject ?: continue
            val name = attributes["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val normName = name.normalizeForMatch()
            if (normName != normTarget && !normTarget.startsWith(normName) && !normName.startsWith(normTarget)) continue

            val edArt = attributes["editorialArtwork"]?.jsonObject ?: continue
            val tt = edArt["musicContentColorLogoTrimmed"]?.jsonObject
                ?: edArt["titleTreatment"]?.jsonObject
            if (tt != null) {
                val rawUrl = tt["url"]?.jsonPrimitive?.contentOrNull
                val w = tt["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1000
                val h = tt["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 300
                if (rawUrl != null && w > 0 && h > 0) {
                    val targetH = (LOGO_WIDTH.toLong() * h / w).toInt().coerceAtLeast(1)
                    val logoUrl = fill(rawUrl, LOGO_WIDTH, targetH, "png")
                    Log.d(TAG, "Found transparent artist logo for '$artist'")
                    return Entry(AppleAlbumLogo(logoUrl, w.toFloat() / h, isTransparent = true))
                }
            }
        }

        return Entry(null)
    }

    private fun fill(urlTemplate: String, width: Int, height: Int, format: String): String {
        var res = urlTemplate
            .replace("{w}", width.toString())
            .replace("{h}", height.toString())
            .replace("{f}", format)
            .replace("{c}", "")
        if (format == "png" && res.endsWith(".jpg", ignoreCase = true)) {
            res = res.substring(0, res.length - 4) + ".png"
        }
        return res
    }
}
