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
import com.music.bitchord.data.listentogether.account.PlayIntent
import com.music.bitchord.data.listentogether.account.SeekIntent
import com.music.bitchord.data.listentogether.account.SkipIntent
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

    // What the person asked of the target, each held until a report shows it done; see [SeekIntent].
    private val clockMs = { System.nanoTime() / 1_000_000L }
    private val seek = SeekIntent(clockMs)
    private val press = PlayIntent(clockMs)
    private val skip = SkipIntent(clockMs)
    private var awaitingConfirm: String? = null
        set(value) {
            field = value
            refreshConnecting()
        }
    private var confirmJob: Job? = null

    /** Waiting to see whether the target that dropped off comes back. See [onAccountState]. */
    private var awayJob: Job? = null
    /** The target is between songs (it reported the new one not playing yet): loading, not pausing. */
    private var holdPlayingUntilNanos = NEVER

    /** The person's own press wins over the target's reports until one agrees with it. */
    private fun effectivePlaying(): Boolean = when {
        press.pending -> press.wantsPlay
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
    private var queueSig = 1 // the signature of an empty queue

    // A hash, not a joined string: this runs every second even while idle, and a long queue
    // would otherwise be rebuilt into a throwaway string each time.
    private fun signature(): Int {
        var h = 1
        for (song in queue().songs) h = 31 * h + song.videoId.hashCode()
        return h
    }

    /** An edit made on this computer while another device is its remote: the remote's copy follows. */
    private fun mirrorEdits() {
        if (active) return
        val now = signature()
        if (now == queueSig) return
        queueSig = now
        val controller = account.state.value.controller ?: return
        answer(controller.deviceId, pullIt = false)
    }

    /** The song that was playing when the last skip arrived, and when; see [seekAfterSkip]. */
    private var skipFromId: String? = null
    private var skipAtNanos = 0L
    private var pendingSeekJob: Job? = null

    private fun noteSkip() {
        skipFromId = engine.state.value.song?.videoId
        skipAtNanos = System.nanoTime()
        // A seek held for the song before this skip would land on the wrong one.
        pendingSeekJob?.cancel()
        pendingSeekJob = null
    }

    /**
     * A remote that skips and at once picks a place on the bar sends both within milliseconds, but the
     * new song takes a moment to open: a seek applied at once lands on the song being left, and the
     * new one then starts from the top. So a seek that follows a skip waits for the engine to have
     * moved on and finished loading (or for it to be plain that the skip changed nothing).
     */
    private fun seekAfterSkip(positionMs: Long, apply: (Long) -> Unit) {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        val from = skipFromId
        if (from == null || System.nanoTime() - skipAtNanos > SKIP_SEEK_WINDOW_NANOS) {
            apply(positionMs)
            return
        }
        pendingSeekJob = scope.launch {
            val deadline = System.nanoTime() + SKIP_SEEK_WAIT_NANOS
            while (System.nanoTime() < deadline) {
                val now = engine.state.value
                val moved = now.song?.videoId != from
                val unchanged = System.nanoTime() - skipAtNanos > SKIP_NOOP_NANOS
                if (!now.isLoading && (moved || unchanged)) break
                delay(40)
            }
            // The skip has landed: seeks from here on are in the new song and need no waiting.
            skipFromId = null
            apply(positionMs)
            publishSettled()
        }
    }

    /**
     * Reports again once the engine has done what was asked. Its commands run on the audio thread, so
     * a report made the moment one is handed over still has the old place; the remote holds its bar
     * against that one ([SeekIntent]) and is released by this.
     */
    private fun publishSettled() {
        scope.launch {
            delay(TARGET_SETTLE_MS)
            publish()
        }
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
                DeviceActions.SEEK -> long(payload, "positionMs")?.let { seekAfterSkip(it, userSeek) }
                DeviceActions.NEXT -> { noteSkip(); next() }
                DeviceActions.PREVIOUS -> { noteSkip(); previous() }
                DeviceActions.SET_VOLUME -> double(payload, "volume")?.let { engine.setVolume(it.toFloat().coerceIn(0f, 1f)) }
                DeviceActions.SNAPSHOT -> answer(command.from, pullIt = false)
            }
            publish()
            publishSettled()
            return
        }
        when (command.action) {
            DeviceActions.PLAY -> engine.play()
            DeviceActions.PAUSE -> engine.pause()
            DeviceActions.SEEK -> long(payload, "positionMs")?.let { seekAfterSkip(it, engine::seekTo) }
            DeviceActions.NEXT -> { noteSkip(); next() }
            DeviceActions.PREVIOUS -> { noteSkip(); previous() }
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
        publishSettled()
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
                    say(DeviceNotice.PartyOn(now?.name))
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
        say(DeviceNotice.PartyOn(target.name))
    }

    /** The person tapped "This device" while another is the speaker: bring the sound back. */
    fun comeBack() {
        val code = DesktopListenTogether.state.value.code?.takeIf { DesktopListenTogether.state.value.inParty }
        if (code != null && !DesktopListenTogether.listenHere.value) {
            DesktopListenTogether.setListenHere(true)
            account.state.value.others
                .filter { it.connected && it.partyCode.equals(code, ignoreCase = true) }
                .forEach { account.command(it.deviceId, DeviceActions.PARTY_LISTEN, buildJsonObject { put("on", false) }) }
            say(DeviceNotice.PartyHere)
            return
        }
        val target = account.state.value.devices.firstOrNull { it.deviceId == targetId } ?: return endLocally(false)
        // Only a remote that gave its music away has anything to come back for. One that was only
        // controlling lets go and leaves the other device playing, exactly as it was.
        if (gaveMusic) pull(target) else { endLocally(false); say(DeviceNotice.BackHere) }
    }

    /** This computer gave its own music to the target ([sendMine]), so leaving brings it back. */
    private var gaveMusic = false

    private fun controlOnly(target: AccountDevice) {
        begin(target.deviceId)
        gaveMusic = false
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
        gaveMusic = true
        val state = engine.state.value
        wantsPlay = state.isPlaying
        seek.expect(state.positionMs, current.videoId, resendable = false)
        sendQueue(local.songs, local.index.coerceAtLeast(0), wantsPlay, state.positionMs)
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
        clearIntents()
        status = account.state.value.devices.firstOrNull { it.deviceId == deviceId }?.playhead()?.let(::statusOf)
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
                    say(if (gaveMusic) DeviceNotice.PlayingOn(target?.name) else DeviceNotice.Controlling(target?.name))
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
        clearIntents()
        gaveMusic = false
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
        val from = account.state.value.devices.firstOrNull { it.deviceId == targetId }?.name
        // The other device's music is what the person asked for here; nothing is given back.
        stash = null
        holdPlayingUntilNanos = NEVER
        clearIntents()
        gaveMusic = false
        active = false
        targetId = null
        status = null
        wantsPlay = false
        confirmJob?.cancel()
        account.detach()
        _overlay.value = null
        say(DeviceNotice.BroughtHere(from))
    }

    // ---- transport while remote --------------------------------------------------------------

    fun play() {
        wantsPlay = true
        press.expect(true)
        command(DeviceActions.PLAY)
        followUp()
        refreshOverlay()
    }

    fun pause() {
        wantsPlay = false
        press.expect(false)
        command(DeviceActions.PAUSE)
        followUp()
        refreshOverlay()
    }

    fun seekTo(positionMs: Long) {
        val at = positionMs.coerceAtLeast(0L)
        // In the song a skip made here is heading for, if one is under way; else the one playing.
        seek.expect(at, skip.expectedId ?: status?.mediaId, resendable = true)
        command(DeviceActions.SEEK, buildJsonObject { put("positionMs", at) })
        followUp()
        refreshOverlay()
    }

    fun skipNext() = skipBy(+1, DeviceActions.NEXT)
    fun skipPrevious() = skipBy(-1, DeviceActions.PREVIOUS)

    /**
     * Shows the song the skip leads to at once, from its start, and sends the skip. Reports of the
     * song being left are held off until the target gets there (see [SkipIntent]); while the link is
     * still being made the queue shown is not the target's yet, so only the skip is sent.
     */
    private fun skipBy(delta: Int, action: String) {
        val local = queue()
        val to = local.index + delta
        if (awaitingConfirm == null && to in local.songs.indices) {
            val id = local.songs[to].videoId
            skip.expect(id)
            seek.expect(0L, id, resendable = false)
            setQueue(DesktopQueue(local.songs, to))
            // A report held back meanwhile is looked at again once the skip is given up.
            scope.launch {
                delay(SkipIntent.WINDOW_MS + 50)
                if (active) onAccountState(account.state.value.devices.firstOrNull { it.deviceId == targetId })
            }
        } else {
            seek.clear()
        }
        command(action)
        refreshOverlay()
    }

    /**
     * Looks again once the target has had time to answer: what it has not done yet is sent once more
     * (a command can be lost on a flaky network), and once an intent is given up the bar is drawn
     * from what the target says.
     */
    private fun followUp() {
        scope.launch {
            delay(SeekIntent.RESEND_MS + 50)
            if (!active) return@launch
            seek.resendPositionMs(effectivePlaying())?.let { at ->
                DesktopTrackLog.log("remote: seek unanswered, sending again: ${at}ms")
                command(DeviceActions.SEEK, buildJsonObject { put("positionMs", at) })
            }
            if (press.shouldResend()) {
                DesktopTrackLog.log("remote: press unanswered, sending again: play=${press.wantsPlay}")
                command(if (press.wantsPlay) DeviceActions.PLAY else DeviceActions.PAUSE)
            }
            delay(SeekIntent.GIVE_UP_MS - SeekIntent.RESEND_MS)
            if (active) refreshOverlay()
        }
    }

    private fun clearIntents() {
        seek.clear()
        press.clear()
        skip.clear()
    }

    fun setVolume(fraction: Float) =
        command(DeviceActions.SET_VOLUME, buildJsonObject { put("volume", fraction.coerceIn(0f, 1f).toDouble()) })

    /** A song picked here while another device is the speaker plays there, and the queue shown follows. */
    fun playOnRemote(songs: List<Song>) {
        if (songs.isEmpty()) return
        setQueue(DesktopQueue(songs, 0))
        wantsPlay = true
        skip.expect(songs[0].videoId)
        seek.expect(0L, songs[0].videoId, resendable = false)
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
        // It was away and is back: if it came back as a new run of its app, the server ended the link
        // meanwhile, and the sound has to come back here instead of this phone going on driving nothing.
        val returned = awayJob != null
        awayJob?.cancel()
        awayJob = null
        if (returned && awaitingConfirm == null && !pulling && LinkWatch.lost(account.state.value.self, targetId)) {
            say(DeviceNotice.Disconnected(target.name))
            endLocally(false)
            return
        }
        val np = target.nowPlaying
        if (np != null && np.updatedAtMs != status?.stamp) {
            // The song being left, or one passed through, while a skip made here is under way.
            if (!skip.accepts(np.videoId)) {
                refreshOverlay()
                return
            }
            val before = status
            val playhead = target.playhead() ?: np
            // A report from before a seek made here still has the old place; it does not move the bar.
            val shownPlaying = effectivePlaying()
            seek.accepts(np.videoId, playhead.positionMs, shownPlaying, settled = np.isPlaying == shownPlaying)
            status = statusOf(playhead)
            // A song change that comes with "not playing" is the target loading it, not pausing.
            val changedSong = before != null && before.mediaId != np.videoId
            val opening = changedSong && !np.isPlaying && (before?.isPlaying == true || wantsPlay)
            holdPlayingUntilNanos = when {
                opening -> System.nanoTime() + HOLD_PLAYING_NANOS
                np.isPlaying || changedSong -> NEVER
                // The same song, still not playing: still opening, so the hold runs on (see the phone's twin).
                else -> holdPlayingUntilNanos
            }
            // A report that still plays after a pause pressed here (or the other way) predates it.
            if (awaitingConfirm == null && press.accepts(np.isPlaying)) {
                wantsPlay = np.isPlaying || System.nanoTime() < holdPlayingUntilNanos
            }
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
        if (seek.pending) return seek.positionMs(effectivePlaying())
        if (s == null) return seek.targetPositionMs
        if (!effectivePlaying()) return s.positionMs
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
        /** Long enough for the audio thread to have run a command handed to it. */
        const val TARGET_SETTLE_MS = 250L

        /** A seek this soon after a skip is held for the new song; later ones are the person's own. */
        const val SKIP_SEEK_WINDOW_NANOS = 6_000_000_000L

        /** The longest a held seek waits for the new song to open. */
        const val SKIP_SEEK_WAIT_NANOS = 8_000_000_000L

        /** After this a skip that never started loading anything (end of queue) is not waited for. */
        const val SKIP_NOOP_NANOS = 900_000_000L

        /** "Not set": far enough back that no grace window around it can be open, whatever nanoTime reads. */
        const val NEVER = Long.MIN_VALUE / 2
        const val HOLD_PLAYING_NANOS = 3_000_000_000L

        /** How much of the queue one hand-over carries; the server allows 500. */
    }
}

/**
 * What the person is told about handing the sound around, in the language in force. The facts
 * themselves ([DeviceNotice]) carry no wording.
 */
internal fun DeviceNotice.english(): String {
    fun t(key: String, fallback: String) = DesktopStrings[key, fallback]
    fun t(key: String, fallback: String, name: String) = DesktopStrings.format(key, name, fallback = fallback)
    fun named(name: String?, key: String, fallback: String, generic: String, genericFallback: String) =
        if (name != null) t(key, fallback, name) else t(generic, genericFallback)
    return when (this) {
        DeviceNotice.Unavailable -> t("device_notice_unavailable", "That device is not available.")
        DeviceNotice.PartyOnThisDevice ->
            t("device_notice_party_here", "You’re in a party on this device. Leave it, or turn off “Play on this device”, first.")
        is DeviceNotice.TargetInParty -> t("device_notice_target_in_party", "%1\$s is in a party. Leave it there first.", name.orEmpty())
        is DeviceNotice.TargetIsRemote -> named(name, "device_notice_target_remote", "%1\$s is controlling another device.", "device_notice_target_remote_generic", "That device is controlling another device.")
        is DeviceNotice.TargetNotAllowed -> named(name, "device_notice_target_not_allowed", "%1\$s doesn't accept control from your other devices.", "device_notice_target_not_allowed_generic", "That device doesn't accept control from your other devices.")
        is DeviceNotice.TargetNotReachable -> named(name, "device_notice_target_not_reachable", "Open BitChord on %1\$s first.", "device_notice_target_not_reachable_generic", "Open BitChord on that device first.")
        DeviceNotice.LocalFile -> t("device_notice_local_file", "This song is a file on this device, so it can’t be played on another one.")
        DeviceNotice.NoAnswer -> t("device_notice_no_answer", "The other device did not answer.")
        DeviceNotice.NoStart -> t("device_notice_no_start", "The other device did not start playing.")
        is DeviceNotice.Disconnected -> named(name, "device_notice_disconnected", "%1\$s disconnected.", "device_notice_disconnected_generic", "The other device disconnected.")
        is DeviceNotice.PlayingOn -> named(name, "device_notice_playing_on", "Now playing on %1\$s", "device_notice_playing_on_generic", "Now playing on the other device")
        is DeviceNotice.Controlling -> named(name, "device_notice_controlling", "Controlling %1\$s", "device_notice_controlling_generic", "Controlling the other device")
        is DeviceNotice.BroughtHere -> named(name, "device_notice_brought_here", "Brought %1\$s’s music here", "device_notice_brought_here_generic", "Brought the music here")
        DeviceNotice.BackHere -> t("device_notice_back_here", "Back on this device")
        is DeviceNotice.PartyOn -> named(name, "device_notice_party_on", "The party now plays on %1\$s", "device_notice_party_on_generic", "The party now plays on the other device")
        DeviceNotice.PartyHere -> t("device_notice_party_here_again", "The party plays on this device again")
        DeviceNotice.TooManySongs -> t("device_notice_too_many", "Too many songs to hand over at once.")
        DeviceNotice.NotLinked -> t("device_notice_not_linked", "That device is no longer connected to this one.")
        is DeviceNotice.Refused -> message?.takeIf { it.isNotBlank() } ?: t("device_notice_refused", "The other device refused that.")
    }
}
