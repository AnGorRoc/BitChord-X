package com.music.bitchord.playback.cast

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.listentogether.account.AccountDevice
import com.music.bitchord.data.listentogether.account.AccountDevices
import com.music.bitchord.data.listentogether.account.AccountNowPlaying
import com.music.bitchord.data.listentogether.account.DeviceActions
import com.music.bitchord.data.listentogether.account.DeviceCommand
import com.music.bitchord.data.listentogether.account.Handoff
import com.music.bitchord.data.listentogether.account.HandoffForce
import com.music.bitchord.data.listentogether.account.HandoffRules
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toPartyTrack
import com.music.bitchord.playback.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Another of the person's own devices as the speaker, the way Cast or the watch can be.
 *
 * It keeps their model: this phone's player owns the *queue* and is held quiet while the other
 * device is the speaker; the other device owns the *clock*, which the session player reports in
 * place of the quiet local one, so the app, the notification and the widgets show what is really
 * playing. What differs is the link. Nothing here talks to hardware; commands and the other
 * device's own reports travel through the account channel ([AccountDevices]).
 *
 * Which of the three things picking a device does (control it, send it my music, bring its music
 * here) is decided by [HandoffRules], not here. Main thread throughout.
 */
internal class DeviceSpeaker(
    private val scope: CoroutineScope,
    private val localPlayer: () -> ExoPlayer?,
    private val account: AccountDevices,
    private val onChanged: () -> Unit,
    private val say: (String) -> Unit,
) : RemoteSpeaker {

    override var sink: CastSink? = null

    override var active = false
        private set

    /** The device that is the speaker, while [active]. */
    var targetId: String? = null
        private set

    private class Status(
        val mediaId: String,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val volume: Double?,
        /** elapsedRealtime when it arrived, to run the playhead on from. */
        val at: Long,
        /** The report's own stamp, so an unchanged one is not mistaken for news. */
        val stamp: Long,
    )

    private var status: Status? = null
    private var wantsPlay = false
    private var requestedPositionMs = 0L
    private var requestedAt = 0L

    /** A hand-over is on its way; this device stays audible until the target confirms it. */
    private var awaitingConfirm: String? = null
    private var confirmJob: Job? = null

    /** The remote's queue is being loaded into the quiet local player; its moves are not the person's. */
    var applyingMirror = false

    /**
     * The player reports its own moves a beat after they happen, so a flag raised around the load
     * is already down when the report arrives. Anything reported before this time is the mirror
     * settling, not the person, and must not be sent back to the device it came from.
     */
    private var ignoreLocalUntil = 0L

    /** The remote's queue has just been put in this phone's player. */
    fun markMirrorApplied() {
        ignoreLocalUntil = SystemClock.elapsedRealtime() + MIRROR_SETTLE_MS
    }

    private fun settling() = applyingMirror || SystemClock.elapsedRealtime() < ignoreLocalUntil

    /** This device asked for the other's music and is waiting for it to arrive. */
    private var pulling = false

    init {
        scope.launch { account.state.collect { onAccountState(it.devices.firstOrNull { d -> d.deviceId == targetId }) } }
    }

    override val playWhenReady: Boolean get() = wantsPlay
    override val isPlaying: Boolean get() = active && status?.isPlaying == true && awaitingConfirm == null

    override val playbackState: Int
        get() = when {
            awaitingConfirm != null -> Player.STATE_BUFFERING
            status == null -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

    override val positionMs: Long
        get() {
            val s = status
            if (s == null || SystemClock.elapsedRealtime() - requestedAt < SEEK_GRACE_MS) return requestedPositionMs
            if (!s.isPlaying) return s.positionMs
            val ran = SystemClock.elapsedRealtime() - s.at
            return (s.positionMs + ran).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs) else it }
        }

    override val durationMs: Long get() = status?.durationMs?.takeIf { it > 0 } ?: C.TIME_UNSET

    override val deviceVolume: Int get() = ((status?.volume ?: 0.0) * 100).toInt().coerceIn(0, 100)

    // ---- picking a device -------------------------------------------------------------------

    /** What happens when the person taps [deviceId]. */
    fun pick(deviceId: String, force: HandoffForce?) {
        val target = account.state.value.devices.firstOrNull { it.deviceId == deviceId }
        if (target == null || !target.connected) {
            say("That device is not available.")
            return
        }
        val player = localPlayer()
        val hasMusic = player != null && player.mediaItemCount > 0
        when (HandoffRules.onPick(target, hasMusic, force)) {
            Handoff.CONTROL_ONLY -> controlOnly(target)
            Handoff.SEND_MY_MUSIC -> sendMine(target)
            Handoff.PULL_THEIR_MUSIC -> pull(target)
        }
    }

    /** The person tapped "This phone" while another device is the speaker: bring the sound back. */
    fun comeBack() {
        val target = account.state.value.devices.firstOrNull { it.deviceId == targetId } ?: return endLocally(resumeHere = false)
        pull(target)
    }

    private fun controlOnly(target: AccountDevice) {
        beginAs(target.deviceId)
        // Show what the target plays: ask for its queue, which arrives as a mirror (see DeviceTarget).
        account.attach(target.deviceId)
        account.command(target.deviceId, DeviceActions.SNAPSHOT)
        // Silence this phone as soon as the target is heard playing.
        awaitingConfirm = ""
        armConfirmation(handedVideoId = null)
    }

    private fun sendMine(target: AccountDevice) {
        val player = localPlayer() ?: return
        val item = player.currentMediaItem ?: return
        beginAs(target.deviceId)
        wantsPlay = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        requestedPositionMs = player.currentPosition.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        sendQueue(player, wantsPlay, requestedPositionMs)
        awaitingConfirm = item.mediaId
        armConfirmation(handedVideoId = item.mediaId)
    }

    private fun pull(target: AccountDevice) {
        beginAs(target.deviceId)
        pulling = true
        account.attach(target.deviceId)
        account.command(target.deviceId, DeviceActions.RELEASE)
        scope.launch {
            delay(CONFIRM_TIMEOUT_MS)
            if (pulling) {
                pulling = false
                say("The other device did not answer.")
                endLocally(resumeHere = false)
            }
        }
    }

    private fun beginAs(deviceId: String) {
        confirmJob?.cancel()
        targetId = deviceId
        active = true
        // What the target is already playing is known; starting from it is what keeps this phone's
        // own mirror from looking like a change.
        status = account.state.value.devices.firstOrNull { it.deviceId == deviceId }?.nowPlaying?.let(::statusOf)
        sink?.onRemoteRouteChanged()
        onChanged()
    }

    /** Keeps this phone audible until the target says it is playing, then quiets it. */
    private fun armConfirmation(handedVideoId: String?) {
        confirmJob?.cancel()
        confirmJob = scope.launch {
            var waited = 0L
            while (awaitingConfirm != null && waited < HANDOVER_TIMEOUT_MS) {
                delay(CONFIRM_POLL_MS)
                waited += CONFIRM_POLL_MS
                val target = account.state.value.devices.firstOrNull { it.deviceId == targetId }
                if (HandoffRules.shouldSilenceLocal(target, handedVideoId)) {
                    awaitingConfirm = null
                    quietLocalPlayer()
                    sink?.onRemotePlaybackChanged()
                    onChanged()
                    return@launch
                }
            }
            if (awaitingConfirm != null) {
                // The target never took it up. Nothing was silenced, so nobody is left in silence.
                awaitingConfirm = null
                say("The other device did not start playing.")
                // It may still start late; left alone it would play on with nobody holding its remote.
                command(DeviceActions.PAUSE)
                endLocally(resumeHere = false)
            }
        }
    }

    private fun quietLocalPlayer() {
        val player = localPlayer() ?: return
        // Idle, not just paused: a paused player keeps buffering whatever it is moved to.
        player.pause()
        player.stop()
    }

    // ---- ending -----------------------------------------------------------------------------

    /**
     * Stops being a remote and leaves this phone at the other device's place, paused. [resumeHere]:
     * carry on playing here.
     */
    fun endLocally(resumeHere: Boolean) {
        if (!active) return
        val s = status
        val wasPlaying = wantsPlay
        val position = positionMs
        val old = targetId
        confirmJob?.cancel()
        awaitingConfirm = null
        pulling = false
        active = false
        targetId = null
        status = null
        wantsPlay = false
        if (old != null) account.detach()
        localPlayer()?.let { player ->
            val index = s?.mediaId?.let { indexOf(player, it) }
            if (index != null) player.seekTo(index, position) else if (player.mediaItemCount > 0) player.seekTo(position)
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
            if (resumeHere && wasPlaying) player.play()
        }
        sink?.onRemoteRouteChanged()
        onChanged()
    }

    /** The other device handed its music back (a pull): it is loaded here, and the link is over. */
    fun onPulled() {
        if (!pulling) return
        pulling = false
        active = false
        targetId = null
        status = null
        wantsPlay = false
        confirmJob?.cancel()
        account.detach()
        sink?.onRemoteRouteChanged()
        onChanged()
    }

    // ---- transport --------------------------------------------------------------------------

    override fun play() {
        if (!active) return
        wantsPlay = true
        command(DeviceActions.PLAY)
        onChanged()
    }

    override fun pause() {
        if (!active) return
        wantsPlay = false
        command(DeviceActions.PAUSE)
        onChanged()
    }

    override fun seekTo(positionMs: Long) {
        if (!active) return
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        command(DeviceActions.SEEK, buildJsonObject { put("positionMs", requestedPositionMs) })
        onChanged()
    }

    override fun setVolume(fraction: Float) {
        if (!active) return
        command(DeviceActions.SET_VOLUME, buildJsonObject { put("volume", fraction.coerceIn(0f, 1f).toDouble()) })
    }

    private fun command(action: String, payload: JsonObject = JsonObject(emptyMap())) {
        targetId?.let { account.command(it, action, payload) }
    }

    // ---- this phone's player moved ----------------------------------------------------------

    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active || settling() || item == null || awaitingConfirm != null) return
        // Already there: the other device moved first and this is the phone catching up.
        if (item.mediaId == status?.mediaId) return
        val player = localPlayer() ?: return
        wantsPlay = wantsPlay || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        requestedPositionMs = 0L
        requestedAt = SystemClock.elapsedRealtime()
        sendQueue(player, wantsPlay, 0L)
        onChanged()
    }

    override fun onLocalQueueChanged() {
        if (!active || settling() || awaitingConfirm != null) return
        val player = localPlayer() ?: return
        // Same song, new order after it: the target replaces its queue without restarting.
        sendQueue(player, wantsPlay, positionMs, keepPlaying = true)
    }

    override fun onLocalRepeatChanged(repeatMode: Int) = Unit

    override fun onLocalStartedPlaying(player: Player) {
        if (!active || awaitingConfirm != null) return
        player.pause()
        play()
    }

    // ---- the other device moved -------------------------------------------------------------

    private fun onAccountState(target: AccountDevice?) {
        if (!active) return
        if (target == null || !target.connected) {
            // The remote went away: the person gets their own sound back, paused where it was.
            say("${target?.name ?: "The other device"} disconnected.")
            endLocally(resumeHere = false)
            return
        }
        val np = target.nowPlaying
        if (np != null && np.updatedAtMs != status?.stamp) {
            val before = status
            status = statusOf(np)
            requestedAt = 0L
            if (awaitingConfirm == null) wantsPlay = np.isPlaying
            // The other device moved on by itself: this phone's queue follows.
            val player = localPlayer()
            if (player != null && np.videoId.isNotEmpty() && player.currentMediaItem?.mediaId != np.videoId) {
                indexOf(player, np.videoId)?.let {
                    applyingMirror = true
                    try { player.seekTo(it, 0L) } finally { applyingMirror = false }
                }
            }
            sink?.onRemotePlaybackChanged()
            if (before?.volume != np.volume) sink?.onRemoteVolumeChanged()
            onChanged()
        }
    }

    private fun statusOf(np: AccountNowPlaying) = Status(
        mediaId = np.videoId,
        isPlaying = np.isPlaying,
        positionMs = np.positionMs,
        durationMs = np.durationMs,
        volume = np.volume,
        at = SystemClock.elapsedRealtime(),
        stamp = np.updatedAtMs,
    )

    // ---- sending the queue ------------------------------------------------------------------

    private fun sendQueue(player: Player, play: Boolean, positionMs: Long, keepPlaying: Boolean = false) {
        val id = targetId ?: return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val tracks = (index until minOf(player.mediaItemCount, index + MAX_HANDOVER))
            .map { player.getMediaItemAt(it).toSong().toPartyTrack(0L) }
        if (tracks.isEmpty()) return
        account.command(
            id, DeviceActions.PLAY_QUEUE,
            buildJsonObject {
                put("tracks", DeviceWire.tracks(tracks))
                put("index", 0)
                put("positionMs", positionMs)
                put("isPlaying", play)
                if (keepPlaying) put("keepPlaying", true)
            },
        )
    }

    private fun indexOf(player: Player, mediaId: String): Int? {
        val from = player.currentMediaItemIndex.coerceAtLeast(0)
        return (from until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
            ?: (0 until from).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
    }

    private companion object {
        const val SEEK_GRACE_MS = 1_500L
        const val CONFIRM_TIMEOUT_MS = 8_000L

        /** A computer has to find the song's stream before it can play it, which takes longer than an answer. */
        const val HANDOVER_TIMEOUT_MS = 20_000L
        const val CONFIRM_POLL_MS = 250L

        /** How long after loading the remote's queue the player's own reports are not the person's. */
        const val MIRROR_SETTLE_MS = 2_500L

        /** How much of the queue one hand-over carries; the server allows 500. */
        const val MAX_HANDOVER = 200
    }
}

/** Songs on the account channel's wire. */
internal object DeviceWire {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun tracks(list: List<PartyTrack>): JsonArray =
        JsonArray(list.map { json.encodeToJsonElement(PartyTrack.serializer(), it) })

    fun tracksFrom(payload: JsonObject): List<PartyTrack> =
        (payload["tracks"] as? JsonArray).orEmpty().mapNotNull {
            runCatching { json.decodeFromJsonElement(PartyTrack.serializer(), it) }.getOrNull()
        }.filter { it.videoId.isNotBlank() }

    fun long(payload: JsonObject, key: String): Long? = payload[key]?.jsonPrimitive?.longOrNull
    fun int(payload: JsonObject, key: String): Int? = payload[key]?.jsonPrimitive?.intOrNull
    fun bool(payload: JsonObject, key: String): Boolean? = payload[key]?.jsonPrimitive?.booleanOrNull
    fun double(payload: JsonObject, key: String): Double? = payload[key]?.jsonPrimitive?.doubleOrNull
}

/**
 * This phone as the *target* of another device: it applies what the person's other devices ask of
 * it, and tells them what it is playing.
 *
 * It drives the ExoPlayer directly, like the service's own moves, so it is never mistaken for the
 * person using this phone. Main thread.
 */
internal class DeviceTarget(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val account: AccountDevices,
    private val speaker: DeviceSpeaker,
) {
    private var last: AccountNowPlaying? = null

    fun start() {
        scope.launch { account.commands.collect { handle(it) } }
        scope.launch {
            while (true) {
                delay(PUBLISH_MS)
                publish()
            }
        }
    }

    /** What this phone plays, for the person's other devices. Nothing while it is only a remote. */
    private fun publish() {
        val p = player()
        val item = p?.currentMediaItem
        if (p == null || item == null || speaker.active) {
            account.publish(null)
            return
        }
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
        account.publish(
            AccountNowPlaying(
                videoId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                thumbnailUrl = item.mediaMetadata.artworkUri?.toString(),
                durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
                isPlaying = playing,
                positionMs = p.currentPosition.coerceAtLeast(0L),
                volume = p.volume.toDouble(),
            ).also { last = it },
        )
    }

    private fun handle(command: DeviceCommand) {
        val p = player() ?: return
        val payload = command.payload
        when (command.action) {
            DeviceActions.PLAY -> p.play()
            DeviceActions.PAUSE -> p.pause()
            DeviceActions.SEEK -> DeviceWire.long(payload, "positionMs")?.let { p.seekTo(it) }
            DeviceActions.NEXT -> p.seekToNextMediaItem()
            DeviceActions.PREVIOUS -> p.seekToPreviousMediaItem()
            DeviceActions.SET_VOLUME -> DeviceWire.double(payload, "volume")?.let { p.volume = it.toFloat().coerceIn(0f, 1f) }
            DeviceActions.PLAY_QUEUE -> playQueue(p, command, payload)
            DeviceActions.ENQUEUE -> enqueue(p, payload)
            DeviceActions.SNAPSHOT -> answer(p, command.from, pullIt = false)
            DeviceActions.RELEASE -> answer(p, command.from, pullIt = true)
        }
        // Report at once rather than at the next tick, so the other device's controls feel immediate.
        publish()
    }

    private fun playQueue(p: ExoPlayer, command: DeviceCommand, payload: JsonObject) {
        val tracks = DeviceWire.tracksFrom(payload)
        if (tracks.isEmpty()) return
        val index = (DeviceWire.int(payload, "index") ?: 0).coerceIn(0, tracks.lastIndex)
        val position = DeviceWire.long(payload, "positionMs") ?: 0L
        val playNow = DeviceWire.bool(payload, "isPlaying") ?: true
        val mirror = DeviceWire.bool(payload, "mirror") == true
        val keep = DeviceWire.bool(payload, "keepPlaying") == true
        val items = tracks.map { it.toSong().toMediaItem() }

        if (keep && p.currentMediaItem?.mediaId == tracks[index].videoId) {
            // Same song: swap what comes after it, without a restart.
            val current = p.currentMediaItemIndex
            if (current + 1 < p.mediaItemCount) p.removeMediaItems(current + 1, p.mediaItemCount)
            p.addMediaItems(items.drop(index + 1))
            return
        }
        speaker.applyingMirror = mirror
        if (mirror) speaker.markMirrorApplied()
        try {
            p.setMediaItems(items, index, position)
            if (mirror) {
                // For display only: this phone is showing what another device plays, so it stays quiet.
                p.pause()
            } else {
                p.prepare()
                p.playWhenReady = playNow
            }
        } finally {
            speaker.applyingMirror = false
        }
        if (!mirror) speaker.onPulled()
    }

    private fun enqueue(p: ExoPlayer, payload: JsonObject) {
        val items = DeviceWire.tracksFrom(payload).map { it.toSong().toMediaItem() }
        if (items.isEmpty()) return
        if (DeviceWire.bool(payload, "playNext") == true && p.mediaItemCount > 0) {
            p.addMediaItems(p.currentMediaItemIndex + 1, items)
        } else {
            p.addMediaItems(items)
        }
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
    }

    /**
     * Sends this phone's queue to [to]. [pullIt] means the sender is taking the sound: this phone
     * stops and the copy arrives playing. Otherwise it is only so the sender can show it.
     */
    private fun answer(p: ExoPlayer, to: String, pullIt: Boolean) {
        val index = p.currentMediaItemIndex.coerceAtLeast(0)
        val tracks = (index until minOf(p.mediaItemCount, index + 200)).map { p.getMediaItemAt(it).toSong().toPartyTrack(0L) }
        if (tracks.isEmpty()) return
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        account.command(
            to, DeviceActions.PLAY_QUEUE,
            buildJsonObject {
                put("tracks", DeviceWire.tracks(tracks))
                put("index", 0)
                put("positionMs", p.currentPosition.coerceAtLeast(0L))
                put("isPlaying", if (pullIt) playing else false)
                put("link", false)
                put("mirror", !pullIt)
            },
        )
        if (pullIt) {
            p.pause()
            p.stop()
        }
    }

    private companion object {
        const val PUBLISH_MS = 1_000L
    }
}
