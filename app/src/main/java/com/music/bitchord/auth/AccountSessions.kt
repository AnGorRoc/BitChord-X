package com.music.bitchord.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** A Google login kept independently from every other captured login. */
@Serializable
data class GoogleAccountSession(
    @SerialName("id") val accountId: String,
    val cookie: String,
    val name: String = "",
    val email: String = "",
    val profiles: List<YouTubeProfile> = emptyList(),
    val activeProfileId: String? = null,
)

/** One Personal or Brand identity available under a Google login. */
@Serializable
data class YouTubeProfile(
    @SerialName("id") val profileId: String,
    val name: String,
    val handle: String = "",
    val avatar: String? = null,
    val pageId: String? = null,
    val dataSyncId: String? = null,
    val authUser: String? = null,
    @SerialName("brand") val isBrandAccount: Boolean = false,
)

/** Stable selector/swipe order: account insertion order, then profile order. */
fun flattenedProfiles(accounts: List<GoogleAccountSession>): List<Pair<String, String>> =
    accounts.flatMap { account -> account.profiles.map { account.accountId to it.profileId } }

fun adjacentProfile(
    accounts: List<GoogleAccountSession>, accountId: String?, profileId: String?, forward: Boolean,
): Pair<String, String>? {
    val items = flattenedProfiles(accounts)
    val index = items.indexOf(accountId to profileId)
    return items.getOrNull(index + if (forward) 1 else -1)
}

internal fun sessionId(cookie: String, dataSyncId: String?): String =
    dataSyncId?.takeIf { it.isNotBlank() } ?: sha256(cookie).take(24)

internal fun profileId(pageId: String?, dataSyncId: String?, name: String): String =
    pageId ?: dataSyncId ?: "profile:${sha256(name).take(16)}"

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }

private val sessionsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

internal fun List<GoogleAccountSession>.toJson(): String = runCatching {
    sessionsJson.encodeToString(this)
}.getOrDefault("[]")

internal fun sessionsFromJson(raw: String?): List<GoogleAccountSession> = runCatching {
    if (raw.isNullOrBlank()) return emptyList()
    sessionsJson.decodeFromString<List<GoogleAccountSession>>(raw)
}.getOrDefault(emptyList())
