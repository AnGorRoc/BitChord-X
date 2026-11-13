package com.music.bitchord.data.listentogether.account

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a device says it is playing. The device itself is the only source of it. */
@Serializable
data class AccountNowPlaying(
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    /** Server time [positionMs] was measured at, to draw the playhead of a device we only watch. */
    val anchorMs: Long = 0L,
    val volume: Double? = null,
    val updatedAtMs: Long = 0L,
)

/** One of the person's devices, as the server lists it. */
@Serializable
data class AccountDevice(
    val deviceId: String,
    val name: String,
    val type: String = "other",
    val connected: Boolean = false,
    val lastSeenMs: Long = 0L,
    val nowPlaying: AccountNowPlaying? = null,
    /** The device currently acting as this one's remote. */
    val controlledBy: String? = null,
    /** The device this one is acting as the remote of. */
    val controlling: String? = null,
    /** The party this device is in, if any. */
    val partyCode: String? = null,
    /** The person asked for this device not to be offered on their other devices. */
    val hidden: Boolean = false,
    /** This device does not let the person's other devices drive it. */
    val noControl: Boolean = false,
    /** Connected, but nothing on it is there to carry out a command until it is opened. */
    val unreachable: Boolean = false,
) {
    val isPlaying: Boolean get() = connected && nowPlaying?.isPlaying == true

    /** A song in its player, playing or paused: devices publish nothing when they have none. */
    val hasMusic: Boolean get() = connected && nowPlaying != null
}

/** How this device asks to be treated by the person's others. The default is today's behaviour. */
data class DevicePrefs(
    val hidden: Boolean = false,
    val noControl: Boolean = false,
    val unreachable: Boolean = false,
)

/** A command another of the person's devices sent to this one. */
data class DeviceCommand(
    val from: String,
    val action: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val id: String? = null,
)

/** The commands one device may send another. Mirrors the server's list. */
object DeviceActions {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SEEK = "seek"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val SET_VOLUME = "setVolume"
    const val PLAY_QUEUE = "playQueue"
    const val ENQUEUE = "enqueue"
    const val RELEASE = "release"

    /** Asks the target to send its queue back, for display; see the server's `snapshot`. */
    const val SNAPSHOT = "snapshot"

    /** Asks the target to join the sender's party (payload: code, server) as the same person. */
    const val JOIN_PARTY = "joinParty"

    /** Asks the target, already in the sender's party, to be (or stop being) the one that sounds. */
    const val PARTY_LISTEN = "partyListen"
}

enum class AccountConnection {
    /** Not trying: no server, not signed in, or the account proof is not available. */
    IDLE,
    CONNECTING,
    LIVE,

    /** The server has no account channel (the original one, or an older build). */
    UNSUPPORTED,
}

data class AccountState(
    val connection: AccountConnection = AccountConnection.IDLE,
    val selfId: String? = null,
    val devices: List<AccountDevice> = emptyList(),
    /** Why the last attempt failed, for diagnostics; cleared once connected. */
    val failure: String? = null,
) {
    val self: AccountDevice? get() = devices.firstOrNull { it.deviceId == selfId }
    val others: List<AccountDevice> get() = devices.filter { it.deviceId != selfId }

    /** The device this one is acting as the remote of, if any. */
    val controlled: AccountDevice? get() = self?.controlling?.let { id -> devices.firstOrNull { it.deviceId == id } }

    /** The device that is remote-controlling this one, if any. */
    val controller: AccountDevice? get() = self?.controlledBy?.let { id -> devices.firstOrNull { it.deviceId == id } }

    /**
     * The device the sound is coming out of for this person right now, as the Connect card shows:
     * the one being controlled, otherwise this one.
     */
    val soundingDevice: AccountDevice? get() = controlled ?: partySibling ?: self

    /**
     * In a party whose sound is on another of the person's devices: this one follows and controls it
     * while that one plays. The mark is that this device reports nothing playing and a device in
     * the same party does.
     */
    private val partySibling: AccountDevice?
        get() {
            val me = self ?: return null
            val code = me.partyCode ?: return null
            if (me.nowPlaying != null) return null
            return others.firstOrNull { it.connected && it.partyCode == code && it.nowPlaying != null }
        }
}

/** Reads the frames of the account socket. Kept apart from the socket so it can be tested. */
object AccountFrames {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    /** The devices list of a `welcome` or `devices` frame, or null for any other frame. */
    fun devices(frame: JsonObject): List<AccountDevice>? {
        val type = frame["type"]?.jsonPrimitive?.contentOrNull
        if (type != "devices" && type != "welcome") return null
        val array = frame["devices"] as? kotlinx.serialization.json.JsonArray ?: return null
        return array.mapNotNull { runCatching { json.decodeFromJsonElement(AccountDevice.serializer(), it) }.getOrNull() }
            .map { it.copy(controlledBy = it.controlledBy?.ifBlank { null }, controlling = it.controlling?.ifBlank { null }) }
    }

    fun command(frame: JsonObject): DeviceCommand? {
        if (frame["type"]?.jsonPrimitive?.contentOrNull != "command") return null
        val from = frame["from"]?.jsonPrimitive?.contentOrNull ?: return null
        val action = frame["action"]?.jsonPrimitive?.contentOrNull ?: return null
        val payload = (frame["payload"] as? JsonObject) ?: JsonObject(emptyMap())
        return DeviceCommand(from, action, payload, frame["id"]?.jsonPrimitive?.contentOrNull)
    }

    fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
}
