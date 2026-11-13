package com.music.bitchord.desktop

import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.listentogether.account.AccountDevice
import com.music.bitchord.data.listentogether.account.AccountConnection
import com.music.bitchord.data.listentogether.account.AccountDevices
import com.music.bitchord.data.listentogether.account.AccountState
import com.music.bitchord.data.listentogether.account.LinkWatch
import com.music.bitchord.data.listentogether.account.AccountNowPlaying
import com.music.bitchord.data.listentogether.account.DeviceActions
import com.music.bitchord.data.listentogether.account.DeviceCommand
import com.music.bitchord.data.listentogether.account.DeviceNotice
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
import kotlinx.serialization.json.contentOrNull
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
    private val say: (DeviceNotice) -> Unit,
    /**
     * The person's own play, pause and seek, which in a party go through the party's rules (a locked
     * transport, say) rather than straight to the engine.
     */
    private val userPlay: () -> Unit = { engine.play() },
    private val userPause: () -> Unit = { engine.pause() },
    private val userSeek: (Long) -> Unit = { engine.seekTo(it) },
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
        set(value) {
            field = value
            refreshConnecting()
        }
    private var confirmJob: Job? = null

    /** Waiting to see whether the target that dropped off comes back. See [onAccountState]. */
    private var awayJob: Job? = null
    /** When the person last pressed play or pause here: what they asked wins over a report for a moment. */
    private var intentAtNanos = NEVER

    /** The target is between songs (it reported the new one not playing yet): loading, not pausing. */
    private var holdPlayingUntilNanos = NEVER

    private fun recentIntent() = System.nanoTime() - intentAtNanos < INTENT_GRACE_NANOS

    private fun effectivePlaying(): Boolean = when {
        recentIntent() -> wantsPlay
        status?.isPlaying == true -> true
        else -> System.nanoTime() < holdPlayingUntilNanos
    }

    /** What this computer had before it became a remote, to give back when it stops being one. */
    private class Stash(val queue: DesktopQueue, val song: Song?, val positionMs: Long)

    private var stash: Stash? = null

    private var pulling = false
        set(value) {
            field = value
            refreshConnecting()
        }

    private val _connecting = MutableStateFlow<String?>(null)

    /** The device a hand-over is on its way to, for the Connect sheet's "Connecting…". */
    val connecting: StateFlow<String?> = _connecting.asStateFlow()

    private fun refreshConnecting() {
        _connecting.value = partyHandover ?: if (active && (awaitingConfirm != null || pulling)) targetId else null
    }

    private val linkWatch = LinkWatch()
    private var offlineJob: Job? = null

    /** This computer's own connection: past the grace the server has freed the link (see the phone's twin). */
    private fun watchOwnConnection(state: AccountState) {
        val back = linkWatch.reconnected(state.connection)
        if (!active) return
        if (state.connection != AccountConnection.LIVE) {
            if (offlineJob == null) {
                offlineJob = scope.launch {
                    delay(AWAY_GRACE_MS)
                    offlineJob = null
                    if (active && account.state.value.connection != AccountConnection.LIVE) {
                        say(DeviceNotice.Disconnected(null))
                        endLocally(false)
                    }
                }
            }
            return
        }
        offlineJob?.cancel()
        offlineJob = null
        if (back && awaitingConfirm == null && !pulling && LinkWatch.lost(state.self, targetId)) {
            say(DeviceNotice.Disconnected(null))
            endLocally(false)
        }
    }

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
        scope.launch {
            account.state.collect { state ->
                watchOwnConnection(state)
                onAccountState(state.devices.firstOrNull { d -> d.deviceId == targetId })
            }
        }
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

    private var lastPublishedPlaying = false

    private fun publish() {
        val state = engine.state.value
        val song = state.song
        if (active || song == null) {
            account.publish(null)
            return
        }
        // A song being opened is not a pause: the engine says "not playing" while it finds the stream,
        // and a remote that heard that showed paused for a second at every change of song. What was
        // being reported carries through the load.
        val playing = if (state.isLoading) lastPublishedPlaying else state.isPlaying
        lastPublishedPlaying = playing
        account.publish(
            AccountNowPlaying(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationMs = state.durationMs,
                isPlaying = playing,
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
        val payload = command.payload
        // Moving a party between the person's own devices needs no link and says nothing about the
        // engine, so it comes before anything that depends on who owns it.
        when (command.action) {
            DeviceActions.JOIN_PARTY -> { joinParty(payload); return }
            DeviceActions.PARTY_LISTEN -> {
                if (DesktopListenTogether.state.value.inParty) DesktopListenTogether.setListenHere(bool(payload, "on") == true)
                return
            }
        }
        // The party owns this player and a queue or song put into it would be undone at once; a remote
        // can still do what any listener can, through the same doors the person's own controls use.
        if (DesktopListenTogether.partyOwnsPlayer()) {
            when (command.action) {
                DeviceActions.PLAY -> userPlay()
                DeviceActions.PAUSE -> userPause()
                DeviceActions.SEEK -> long(payload, "positionMs")?.let(userSeek)
                DeviceActions.NEXT -> next()
                DeviceActions.PREVIOUS -> previous()
                DeviceActions.SET_VOLUME -> double(payload, "volume")?.let { engine.setVolume(it.toFloat().coerceIn(0f, 1f)) }
                DeviceActions.SNAPSHOT -> answer(command.from, pullIt = false)
            }
            publish()
            return
        }
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

    /** Another of the person's devices has a party and asked this computer to take it. */
    private fun joinParty(payload: JsonObject) {
        val code = payload["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return
        val server = payload["server"]?.jsonPrimitive?.contentOrNull
        if (active) endLocally(false)
        DesktopListenTogether.setListenHere(true)
        scope.launch { DesktopListenTogether.joinParty(code, server = server, fromOwnDevice = true) }
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
        // One rule for both the sheet (which stays open on a refusal) and this (which says why).
        val myParty = DesktopListenTogether.state.value.code?.takeIf { DesktopListenTogether.state.value.inParty }
        HandoffRules.blockedBy(target, myParty)?.let { say(it); return }
        if (target == null) return
        when (HandoffRules.onPick(target, queue().songs.isNotEmpty(), force, myParty)) {
            Handoff.CONTROL_ONLY -> controlOnly(target)
            Handoff.SEND_PARTY -> sendParty(target, myParty ?: return)
            Handoff.MOVE_PARTY_SOUND -> movePartySound(target)
            Handoff.SEND_MY_MUSIC -> sendMine(target)
            Handoff.PULL_THEIR_MUSIC -> pull(target)
        }
    }

    private var partyHandover: String? = null
    private var partyHandoverJob: Job? = null

    /** This computer is in a party and the person picked a device that is not: it joins and sounds. */
    private fun sendParty(target: AccountDevice, code: String) {
        val server = DesktopListenTogether.activeServer() ?: run { say(DeviceNotice.NoStart); return }
        partyHandoverJob?.cancel()
        partyHandover = target.deviceId
        refreshConnecting()
        account.command(
            target.deviceId, DeviceActions.JOIN_PARTY,
            buildJsonObject { put("code", code); put("server", server) },
        )
        partyHandoverJob = scope.launch {
            var waited = 0L
            while (waited < HANDOVER_TIMEOUT_MS) {
                delay(CONFIRM_POLL_MS)
                waited += CONFIRM_POLL_MS
                val now = account.state.value.devices.firstOrNull { it.deviceId == target.deviceId }
                if (now?.partyCode.equals(code, ignoreCase = true)) {
                    DesktopListenTogether.setListenHere(false)
                    partyHandover = null
                    refreshConnecting()
                    return@launch
                }
            }
            partyHandover = null
            refreshConnecting()
            say(DeviceNotice.NoAnswer)
        }
    }

    /** Both are in the party: the sound moves to [target], and this computer follows it. */
    private fun movePartySound(target: AccountDevice) {
        account.command(target.deviceId, DeviceActions.PARTY_LISTEN, buildJsonObject { put("on", true) })
        DesktopListenTogether.setListenHere(false)
    }

    /** The person tapped "This device" while another is the speaker: bring the sound back. */
    fun comeBack() {
        val code = DesktopListenTogether.state.value.code?.takeIf { DesktopListenTogether.state.value.inParty }
        if (code != null && !DesktopListenTogether.listenHere.value) {
            DesktopListenTogether.setListenHere(true)
            account.state.value.others
                .filter { it.connected && it.partyCode.equals(code, ignoreCase = true) }
                .forEach { account.command(it.deviceId, DeviceActions.PARTY_LISTEN, buildJsonObject { put("on", false) }) }
            return
        }
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
            say(DeviceNotice.LocalFile)
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
                say(DeviceNotice.NoAnswer)
                endLocally(false)
            }
        }
    }

    private fun begin(deviceId: String) {
        confirmJob?.cancel()
        // Only the first time: moving from one device to another keeps the original, not the mirror.
        if (!active) {
            val state = engine.state.value
            stash = Stash(queue(), state.song, state.positionMs)
        }
        targetId = deviceId
        active = true
        holdPlayingUntilNanos = NEVER
        intentAtNanos = NEVER
        status = account.state.value.devices.firstOrNull { it.deviceId == deviceId }?.nowPlaying?.let(::statusOf)
        // A computer that connects to a device that is playing shows playing, not a stale "paused".
        wantsPlay = status?.isPlaying == true
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
                say(DeviceNotice.NoStart)
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
        offlineJob?.cancel()
        offlineJob = null
        awaitingConfirm = null
        pulling = false
        active = false
        targetId = null
        status = null
        wantsPlay = false
        holdPlayingUntilNanos = NEVER
        account.detach()
        val kept = stash
        stash = null
        if (kept != null) {
            // What this computer had before it controlled anything, not what the other device had.
            setQueue(kept.queue)
            kept.song?.let { engine.load(it, false, kept.positionMs) }
        } else {
            val local = queue()
            val at = s?.mediaId?.let { id -> local.songs.indexOfFirst { it.videoId == id } }?.takeIf { it >= 0 }
            local.songs.getOrNull(at ?: local.index)?.let { song ->
                if (at != null && at != local.index) setQueue(DesktopQueue(local.songs, at))
                engine.load(song, resumeHere && wasPlaying, position)
            }
        }
        _overlay.value = null
    }

    /** The other device handed its music back (a pull): it is loaded here, and the link is over. */
    private fun onPulled() {
        if (!pulling) return
        pulling = false
        // The other device's music is what the person asked for here; nothing is given back.
        stash = null
        holdPlayingUntilNanos = NEVER
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
        intentAtNanos = System.nanoTime()
        command(DeviceActions.PLAY)
    }

    fun pause() {
        wantsPlay = false
        intentAtNanos = System.nanoTime()
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
                            say(DeviceNotice.Disconnected(now?.name))
                            endLocally(false)
                        }
                    }
                }
                return
            }
            say(DeviceNotice.Disconnected(null))
            endLocally(false)
            return
        }
        awayJob?.cancel()
        awayJob = null
        val np = target.nowPlaying
        if (np != null && np.updatedAtMs != status?.stamp) {
            val before = status
            status = statusOf(np)
            requestedAtNanos = 0L
            // A song change that comes with "not playing" is the target loading it, not pausing.
            val changedSong = before != null && before.mediaId != np.videoId
            holdPlayingUntilNanos = if (changedSong && !np.isPlaying && (before?.isPlaying == true || wantsPlay)) {
                System.nanoTime() + HOLD_PLAYING_NANOS
            } else {
                NEVER
            }
            if (awaitingConfirm == null && !recentIntent()) wantsPlay = np.isPlaying || holdPlayingUntilNanos != NEVER
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
            isPlaying = awaitingConfirm == null && effectivePlaying(),
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
        const val INTENT_GRACE_NANOS = 2_500_000_000L

        /** "Not set": far enough back that no grace window around it can be open, whatever nanoTime reads. */
        const val NEVER = Long.MIN_VALUE / 2
        const val HOLD_PLAYING_NANOS = 3_000_000_000L

        /** How much of the queue one hand-over carries; the server allows 500. */
    }
}

/**
 * What the person is told about handing the sound around. The desktop app's own messages are
 * English throughout, so this is too; the facts themselves ([DeviceNotice]) carry no wording.
 */
internal fun DeviceNotice.english(): String = when (this) {
    DeviceNotice.Unavailable -> "That device is not available."
    DeviceNotice.PartyOnThisDevice ->
        "You're in a party on this device. Leave it, or turn off “Play on this device”, first."
    is DeviceNotice.TargetInParty -> "$name is in a party. Leave it there first."
    is DeviceNotice.TargetIsRemote ->
        if (name != null) "$name is controlling another device." else "That device is controlling another device."
    is DeviceNotice.TargetNotAllowed ->
        if (name != null) "$name doesn't accept control from your other devices." else "That device doesn't accept control from your other devices."
    is DeviceNotice.TargetNotReachable ->
        if (name != null) "Open BitChord on $name first." else "Open BitChord on that device first."
    DeviceNotice.LocalFile -> "This song is a file on this device, so it can't be played on another one."
    DeviceNotice.NoAnswer -> "The other device did not answer."
    DeviceNotice.NoStart -> "The other device did not start playing."
    is DeviceNotice.Disconnected -> if (name != null) "$name disconnected." else "The other device disconnected."
    DeviceNotice.TooManySongs -> "Too many songs to hand over at once."
    DeviceNotice.NotLinked -> "That device is no longer connected to this one."
    is DeviceNotice.Refused -> message?.takeIf { it.isNotBlank() } ?: "The other device refused that."
}
