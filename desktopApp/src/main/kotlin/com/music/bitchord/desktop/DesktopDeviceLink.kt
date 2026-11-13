package com.music.bitchord.desktop

import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.listentogether.account.AccountDevice
import com.music.bitchord.data.listentogether.account.AccountDevices
import com.music.bitchord.data.listentogether.account.AccountNowPlaying
import com.music.bitchord.data.listentogether.account.DeviceActions
import com.music.bitchord.data.listentogether.account.DeviceCommand
import com.music.bitchord.data.listentogether.account.Handoff
import com.music.bitchord.data.listentogether.account.HandoffForce
import com.music.bitchord.data.listentogether.account.HandoffRules
import com.music.bitchord.data.listentogether.account.HandoffWindow
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * This computer among the person's other devices, in both roles the phone has.
 *
 * As a **target** it reports what the engine plays and applies what another device asks: play,
 * pause, seek, skip, a queue handed over, a queue to show. As a **remote** (the speaker is another
 * device) the computer's own engine is held quiet, the queue on screen is the other device's, and
 * the player draws the other device's clock in place of the engine's ([overlay]).
 *
 * The rules of what picking a device does are [HandoffRules]'s, shared with the phone. This file
 * only carries them out. Everything here runs on the UI scope.
 */
internal class DesktopDeviceLink(
    private val scope: CoroutineScope,
    private val account: AccountDevices,
    private val engine: DesktopPlaybackEngine,
    private val queue: () -> DesktopQueue,
    /** Replaces the queue on screen and the selected song with it. */
    private val setQueue: (DesktopQueue) -> Unit,
    private val next: () -> Unit,
    private val previous: () -> Unit,
    private val enqueue: (Song, Boolean) -> Unit,
    private val say: (String) -> Unit,
) {
    private class Status(
        val mediaId: String,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val volume: Double?,
        /** System.nanoTime() when it arrived, to run the playhead on from. */
        val atNanos: Long,
        val stamp: Long,
    )

    var active = false
        private set
    var targetId: String? = null
        private set

    private var status: Status? = null
    private var wantsPlay = false
    private var requestedPositionMs = 0L
    private var requestedAtNanos = 0L
    private var awaitingConfirm: String? = null
    private var confirmJob: Job? = null

    /** Waiting to see whether the target that dropped off comes back. See [onAccountState]. */
    private var awayJob: Job? = null
    private var pulling = false

    private val _overlay = MutableStateFlow<DesktopPlaybackState?>(null)

    /** What the player should draw instead of the engine's own state while another device sounds. */
    val overlay: StateFlow<DesktopPlaybackState?> = _overlay.asStateFlow()

    fun start() {
        scope.launch { account.commands.collect { handle(it) } }
        // What the server refused ("not controlling that device", "too many devices"): without this
        // a command that goes nowhere looks like nothing happening until a timeout.
        scope.launch { account.errors.collect { say(it) } }
        // Entering a party while this computer is another device's remote: the party takes the
        // player, so the remote link ends here instead of the two fighting over it.
        scope.launch {
            DesktopListenTogether.state
                .map { DesktopListenTogether.partyOwnsPlayer() }
                .distinctUntilChanged()
                .collect { owns -> if (owns && active) endLocally(false) }
        }
        scope.launch { account.state.collect { onAccountState(it.devices.firstOrNull { d -> d.deviceId == targetId }) } }
        scope.launch {
            while (true) {
                delay(PUBLISH_MS)
                publish()
                mirrorEdits()
            }
        }
        scope.launch {
            while (true) {
                delay(OVERLAY_MS)
                refreshOverlay()
            }
        }
    }

    // ---- as a target -------------------------------------------------------------------------

    private fun publish() {
        val state = engine.state.value
        val song = state.song
        if (active || song == null) {
            account.publish(null)
            return
        }
        account.publish(
            AccountNowPlaying(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationMs = state.durationMs,
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                volume = state.volume.toDouble(),
            ),
        )
    }

    /** The songs in this computer's queue as the controller last knew them. */
    private var queueSig = ""

    private fun signature() = queue().songs.joinToString(",") { it.videoId }

    /** An edit made on this computer while another device is its remote: the remote's copy follows. */
    private fun mirrorEdits() {
        if (active) return
        val now = signature()
        if (now == queueSig) return
        queueSig = now
        val controller = account.state.value.controller ?: return
        answer(controller.deviceId, pullIt = false)
    }

    private fun handle(command: DeviceCommand) {
        // The party owns this player; a command from another device would be undone by it at once.
        if (DesktopListenTogether.partyOwnsPlayer()) return
        val payload = command.payload
        when (command.action) {
            DeviceActions.PLAY -> engine.play()
            DeviceActions.PAUSE -> engine.pause()
            DeviceActions.SEEK -> long(payload, "positionMs")?.let(engine::seekTo)
            DeviceActions.NEXT -> next()
            DeviceActions.PREVIOUS -> previous()
            DeviceActions.SET_VOLUME -> double(payload, "volume")?.let { engine.setVolume(it.toFloat().coerceIn(0f, 1f)) }
            DeviceActions.PLAY_QUEUE -> playQueue(payload)
            DeviceActions.ENQUEUE -> {
                val playNext = bool(payload, "playNext") == true
                tracksFrom(payload).forEach { enqueue(it.toDesktopSong(), playNext) }
            }
            DeviceActions.SNAPSHOT -> answer(command.from, pullIt = false)
            DeviceActions.RELEASE -> answer(command.from, pullIt = true)
        }
        if (command.action == DeviceActions.PLAY_QUEUE || command.action == DeviceActions.ENQUEUE) queueSig = signature()
        publish()
    }

    private fun playQueue(payload: JsonObject) {
        val tracks = tracksFrom(payload)
        if (tracks.isEmpty()) return
        val index = (int(payload, "index") ?: 0).coerceIn(0, tracks.lastIndex)
        val position = long(payload, "positionMs") ?: 0L
        val playNow = bool(payload, "isPlaying") ?: true
        val mirror = bool(payload, "mirror") == true
        val keep = bool(payload, "keepPlaying") == true
        val songs = tracks.map { it.toDesktopSong() }

        val local = queue()
        if (keep && local.current?.videoId == songs[index].videoId) {
            // Same song, a new order after it: swap what comes after, without a restart.
            setQueue(DesktopQueue(local.songs.take(local.index + 1) + songs.drop(index + 1), local.index))
            return
        }
        setQueue(DesktopQueue(songs, index))
        if (mirror) {
            // For display only: this computer is showing what another device plays, so it stays quiet.
            engine.pause()
        } else {
            engine.load(songs[index], playNow, position)
            onPulled()
        }
    }

    /**
     * Sends this computer's queue to [to]. [pullIt] means the sender is taking the sound: this
     * computer stops and the copy arrives playing. Otherwise it is only so the sender can show it.
     */
    private fun answer(to: String, pullIt: Boolean) {
        val local = queue()
        val (tracks, index) = window(local.songs, local.index.coerceAtLeast(0)) ?: return
        val state = engine.state.value
        account.command(
            to, DeviceActions.PLAY_QUEUE,
            buildJsonObject {
                put("tracks", tracksJson(tracks))
                put("index", index)
                put("positionMs", state.positionMs)
                put("isPlaying", pullIt && state.isPlaying)
                put("link", false)
                put("mirror", !pullIt)
            },
        )
        if (pullIt) engine.pause()
    }

    // ---- as a remote -------------------------------------------------------------------------

    /** What happens when the person taps [deviceId]. */
    fun pick(deviceId: String, force: HandoffForce?) {
        val target = account.state.value.devices.firstOrNull { it.deviceId == deviceId }
        if (target == null || !target.connected) {
            say("That device is not available.")
            return
        }
        // A party and a hand-over would fight over the same player. One or the other.
        if (DesktopListenTogether.partyOwnsPlayer()) {
            say("This computer is playing a party. Leave it, or turn off “Play on this device”, first.")
            return
        }
        if (target.partyCode != null) {
            say("${target.name} is in a party. Leave it there first.")
            return
        }
        // A remote is not a speaker: what it plays comes from the device it controls.
        if (target.controlling != null) {
            say("${target.name} is controlling another device.")
            return
        }
        when (HandoffRules.onPick(target, queue().songs.isNotEmpty(), force)) {
            Handoff.CONTROL_ONLY -> controlOnly(target)
            Handoff.SEND_MY_MUSIC -> sendMine(target)
            Handoff.PULL_THEIR_MUSIC -> pull(target)
        }
    }

    /** The person tapped "This device" while another is the speaker: bring the sound back. */
    fun comeBack() {
        val target = account.state.value.devices.firstOrNull { it.deviceId == targetId } ?: return endLocally(false)
        pull(target)
    }

    private fun controlOnly(target: AccountDevice) {
        begin(target.deviceId)
        account.attach(target.deviceId)
        account.command(target.deviceId, DeviceActions.SNAPSHOT)
        awaitingConfirm = ""
        armConfirmation(null)
    }

    private fun sendMine(target: AccountDevice) {
        val local = queue()
        val current = local.current ?: return
        if (window(local.songs, local.index.coerceAtLeast(0)) == null) {
            // A file that lives on this computer alone: the other device has nothing to play.
            say("This song is a file on this computer, so it can't be played on another device.")
            return
        }
        begin(target.deviceId)
        val state = engine.state.value
        wantsPlay = state.isPlaying
        requestedPositionMs = state.positionMs
        requestedAtNanos = System.nanoTime()
        sendQueue(local.songs, local.index.coerceAtLeast(0), wantsPlay, requestedPositionMs)
        awaitingConfirm = current.videoId
        armConfirmation(current.videoId, wantsPlay)
    }

    private fun pull(target: AccountDevice) {
        begin(target.deviceId)
        pulling = true
        account.attach(target.deviceId)
        account.command(target.deviceId, DeviceActions.RELEASE)
        scope.launch {
            delay(CONFIRM_TIMEOUT_MS)
            if (pulling) {
                pulling = false
                say("The other device did not answer.")
                endLocally(false)
            }
        }
    }

    private fun begin(deviceId: String) {
        confirmJob?.cancel()
        targetId = deviceId
        active = true
        status = account.state.value.devices.firstOrNull { it.deviceId == deviceId }?.nowPlaying?.let(::statusOf)
        refreshOverlay()
    }

    /** Keeps this computer audible until the target says it is playing, then quiets it. */
    private fun armConfirmation(handedVideoId: String?, handedPlaying: Boolean = true) {
        confirmJob?.cancel()
        confirmJob = scope.launch {
            var waited = 0L
            while (awaitingConfirm != null && waited < HANDOVER_TIMEOUT_MS) {
                delay(CONFIRM_POLL_MS)
                waited += CONFIRM_POLL_MS
                val target = account.state.value.devices.firstOrNull { it.deviceId == targetId }
                if (HandoffRules.shouldSilenceLocal(target, handedVideoId, handedPlaying)) {
                    awaitingConfirm = null
                    engine.pause()
                    refreshOverlay()
                    return@launch
                }
            }
            if (awaitingConfirm != null) {
                // The target never took it up. Nothing was silenced, so nobody is left in silence.
                awaitingConfirm = null
                say("The other device did not start playing.")
                command(DeviceActions.PAUSE)
                endLocally(false)
            }
        }
    }

    /** Stops being a remote, leaving this computer at the other device's place, paused. */
    fun endLocally(resumeHere: Boolean) {
        if (!active) return
        val s = status
        val wasPlaying = wantsPlay
        val position = positionNow()
        confirmJob?.cancel()
        awayJob?.cancel()
        awayJob = null
        awaitingConfirm = null
        pulling = false
        active = false
        targetId = null
        status = null
        wantsPlay = false
        account.detach()
        val local = queue()
        val at = s?.mediaId?.let { id -> local.songs.indexOfFirst { it.videoId == id } }?.takeIf { it >= 0 }
        local.songs.getOrNull(at ?: local.index)?.let { song ->
            if (at != null && at != local.index) setQueue(DesktopQueue(local.songs, at))
            engine.load(song, resumeHere && wasPlaying, position)
        }
        _overlay.value = null
    }

    /** The other device handed its music back (a pull): it is loaded here, and the link is over. */
    private fun onPulled() {
        if (!pulling) return
        pulling = false
        active = false
        targetId = null
        status = null
        wantsPlay = false
        confirmJob?.cancel()
        account.detach()
        _overlay.value = null
    }

    // ---- transport while remote --------------------------------------------------------------

    fun play() {
        wantsPlay = true
        command(DeviceActions.PLAY)
    }

    fun pause() {
        wantsPlay = false
        command(DeviceActions.PAUSE)
    }

    fun seekTo(positionMs: Long) {
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedAtNanos = System.nanoTime()
        command(DeviceActions.SEEK, buildJsonObject { put("positionMs", requestedPositionMs) })
    }

    fun skipNext() = command(DeviceActions.NEXT)
    fun skipPrevious() = command(DeviceActions.PREVIOUS)

    fun setVolume(fraction: Float) =
        command(DeviceActions.SET_VOLUME, buildJsonObject { put("volume", fraction.coerceIn(0f, 1f).toDouble()) })

    /** A song picked here while another device is the speaker plays there, and the queue shown follows. */
    fun playOnRemote(songs: List<Song>) {
        if (songs.isEmpty()) return
        setQueue(DesktopQueue(songs, 0))
        wantsPlay = true
        requestedPositionMs = 0L
        requestedAtNanos = System.nanoTime()
        sendQueue(songs, 0, true, 0L)
    }

    /** Adds to the queue of the device that is playing. */
    fun enqueueOnRemote(song: Song, playNext: Boolean) {
        command(
            DeviceActions.ENQUEUE,
            buildJsonObject {
                put("tracks", tracksJson(listOf(song.toPartyTrack(0L))))
                put("playNext", playNext)
            },
        )
    }

    private fun command(action: String, payload: JsonObject = JsonObject(emptyMap())) {
        targetId?.let { account.command(it, action, payload) }
    }

    /** The songs that go with a hand-over and where the current one is among them; see [HandoffWindow]. */
    private fun window(songs: List<Song>, current: Int): Pair<List<PartyTrack>, Int>? {
        val slice = HandoffWindow.slice(songs.map { it.videoId }, current) { !HandoffWindow.isDeviceFile(it) } ?: return null
        return slice.indices.map { songs[it].toPartyTrack(0L) } to slice.index
    }

    private fun sendQueue(songs: List<Song>, current: Int, play: Boolean, positionMs: Long) {
        val id = targetId ?: return
        val (tracks, index) = window(songs, current) ?: return
        account.command(
            id, DeviceActions.PLAY_QUEUE,
            buildJsonObject {
                put("tracks", tracksJson(tracks))
                put("index", index)
                put("positionMs", positionMs)
                put("isPlaying", play)
            },
        )
    }

    // ---- the other device moved --------------------------------------------------------------

    private fun onAccountState(target: AccountDevice?) {
        if (!active) return
        if (target == null || !target.connected) {
            // Only away may mean back in a moment (the server keeps the link for that long); give it
            // the time before handing the sound back, so a network blip does not end the link.
            if (target != null) {
                if (awayJob == null) {
                    awayJob = scope.launch {
                        delay(AWAY_GRACE_MS)
                        awayJob = null
                        val now = account.state.value.devices.firstOrNull { it.deviceId == targetId }
                        if (active && (now == null || !now.connected)) {
                            say("${now?.name ?: "The other device"} disconnected.")
                            endLocally(false)
                        }
                    }
                }
                return
            }
            say("The other device disconnected.")
            endLocally(false)
            return
        }
        awayJob?.cancel()
        awayJob = null
        val np = target.nowPlaying
        if (np != null && np.updatedAtMs != status?.stamp) {
            status = statusOf(np)
            requestedAtNanos = 0L
            if (awaitingConfirm == null) wantsPlay = np.isPlaying
            // The other device moved on by itself: the queue shown follows it.
            val local = queue()
            val at = local.songs.indexOfFirst { it.videoId == np.videoId }
            if (at >= 0 && at != local.index) setQueue(DesktopQueue(local.songs, at))
        }
        refreshOverlay()
    }

    private fun statusOf(np: AccountNowPlaying) = Status(
        mediaId = np.videoId,
        isPlaying = np.isPlaying,
        positionMs = np.positionMs,
        durationMs = np.durationMs,
        volume = np.volume,
        atNanos = System.nanoTime(),
        stamp = np.updatedAtMs,
    )

    private fun positionNow(): Long {
        val s = status
        if (s == null || System.nanoTime() - requestedAtNanos < SEEK_GRACE_NANOS) return requestedPositionMs
        if (!s.isPlaying) return s.positionMs
        val ran = (System.nanoTime() - s.atNanos) / 1_000_000L
        return (s.positionMs + ran).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs) else it }
    }

    private fun refreshOverlay() {
        if (!active) {
            if (_overlay.value != null) _overlay.value = null
            return
        }
        val base = engine.state.value
        val s = status
        _overlay.value = base.copy(
            song = queue().current ?: base.song,
            isPlaying = awaitingConfirm == null && s?.isPlaying == true,
            isLoading = s == null || awaitingConfirm != null,
            positionMs = positionNow(),
            positionSampledAtNanos = System.nanoTime(),
            durationMs = s?.durationMs?.takeIf { it > 0 } ?: base.durationMs,
            volume = (s?.volume ?: base.volume.toDouble()).toFloat(),
        )
    }

    // ---- wire helpers ------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun tracksJson(list: List<PartyTrack>): JsonArray =
        JsonArray(list.map { json.encodeToJsonElement(PartyTrack.serializer(), it) })

    private fun tracksFrom(payload: JsonObject): List<PartyTrack> =
        (payload["tracks"] as? JsonArray).orEmpty().mapNotNull {
            runCatching { json.decodeFromJsonElement(PartyTrack.serializer(), it) }.getOrNull()
        }.filter { it.videoId.isNotBlank() }

    private fun long(p: JsonObject, k: String) = p[k]?.jsonPrimitive?.longOrNull
    private fun int(p: JsonObject, k: String) = p[k]?.jsonPrimitive?.intOrNull
    private fun bool(p: JsonObject, k: String) = p[k]?.jsonPrimitive?.booleanOrNull
    private fun double(p: JsonObject, k: String) = p[k]?.jsonPrimitive?.doubleOrNull

    private companion object {
        const val PUBLISH_MS = 1_000L
        const val OVERLAY_MS = 250L
        const val CONFIRM_TIMEOUT_MS = 8_000L

        /** A little longer than the server's own grace for a dropped socket. */
        const val AWAY_GRACE_MS = 15_000L
        const val HANDOVER_TIMEOUT_MS = 20_000L
        const val CONFIRM_POLL_MS = 250L
        const val SEEK_GRACE_NANOS = 1_500_000_000L

        /** How much of the queue one hand-over carries; the server allows 500. */
    }
}
