package com.music.bitchord.playback.cast

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.ListenTogether
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
import com.music.bitchord.playback.DeviceLink
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
    private val say: (DeviceNotice) -> Unit,
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
        set(value) {
            field = value
            refreshConnecting()
        }
    private var confirmJob: Job? = null

    /** Waiting to see whether the target that dropped off comes back. See [onAccountState]. */
    private var awayJob: Job? = null

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
        set(value) {
            field = value
            refreshConnecting()
        }

    /** What the Connect sheet shows as "Connecting…": the target, from the tap until it answers. */
    private fun refreshConnecting() {
        DeviceLink.setConnecting(
            partyHandover ?: if (active && (awaitingConfirm != null || pulling)) targetId else null,
        )
    }

    init {
        scope.launch {
            account.state.collect { state ->
                watchOwnConnection(state)
                onAccountState(state.devices.firstOrNull { d -> d.deviceId == targetId })
            }
        }
    }

    private val linkWatch = LinkWatch()

    /** Waiting to see whether this phone's own connection comes back. See [watchOwnConnection]. */
    private var offlineJob: Job? = null

    /**
     * The other end of this is [onAccountState]: that one covers the target dropping, this one the
     * phone itself. Offline past the grace the server has freed the link and no command can reach
     * the target any more, so the sound comes back here instead of the phone going on showing a
     * device it can no longer drive. Back in time, the first list tells whether the link survived.
     */
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
                        endLocally(resumeHere = false)
                    }
                }
            }
            return
        }
        offlineJob?.cancel()
        offlineJob = null
        if (back && awaitingConfirm == null && !pulling && LinkWatch.lost(state.self, targetId)) {
            say(DeviceNotice.Disconnected(null))
            endLocally(resumeHere = false)
        }
    }

    /** When the person last pressed play or pause here: what they asked wins over a report for a moment. */
    private var intentAt = NEVER

    /**
     * The target is between songs: it reported the new one not playing yet, which is it loading and
     * not it pausing. Until then the bar keeps saying what it said. See [onAccountState].
     */
    private var holdPlayingUntil = NEVER

    /** The person pressed play or pause: shown at once, and re-read against the target once it has had time to answer. */
    private fun noteIntent() {
        intentAt = SystemClock.elapsedRealtime()
        scope.launch {
            delay(INTENT_GRACE_MS + 50)
            if (active) reanchor()
        }
    }

    /**
     * Tells the session what it shows now, at once. A controller runs the playhead on from the last
     * thing it was told, so anything that changes what is true here (a press, a skip, a seek, the end of
     * the link) has to say so, or the bar waits for the target's next report to find out.
     */
    private fun reanchor() {
        sink?.onRemotePlaybackChanged()
        onChanged()
    }

    private fun recentIntent() = SystemClock.elapsedRealtime() - intentAt < INTENT_GRACE_MS

    /**
     * What the player shows as playing. The person's own press wins for a moment over what the
     * target last reported, which was true before it heard them; a change of song is not a pause.
     */
    private fun effectivePlaying(): Boolean = when {
        recentIntent() -> wantsPlay
        status?.isPlaying == true -> true
        else -> SystemClock.elapsedRealtime() < holdPlayingUntil
    }

    override val playWhenReady: Boolean get() = effectivePlaying()
    override val isPlaying: Boolean get() = active && awaitingConfirm == null && effectivePlaying()

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
            if (!effectivePlaying()) return s.positionMs
            val ran = SystemClock.elapsedRealtime() - s.at
            return (s.positionMs + ran).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs) else it }
        }

    override val durationMs: Long get() = status?.durationMs?.takeIf { it > 0 } ?: C.TIME_UNSET

    override val deviceVolume: Int get() = ((status?.volume ?: 0.0) * 100).toInt().coerceIn(0, 100)

    // ---- picking a device -------------------------------------------------------------------

    /** What happens when the person taps [deviceId]. */
    fun pick(deviceId: String, force: HandoffForce?) {
        val target = account.state.value.devices.firstOrNull { it.deviceId == deviceId }
        // One rule for both the sheet (which stays open on a refusal) and this (which says why).
        val myParty = ListenTogether.state.value.code?.takeIf { ListenTogether.state.value.inParty }
        HandoffRules.blockedBy(target, myParty)?.let { say(it); return }
        if (target == null) return
        val player = localPlayer()
        val hasMusic = player != null && player.mediaItemCount > 0
        when (HandoffRules.onPick(target, hasMusic, force, myParty)) {
            Handoff.CONTROL_ONLY -> controlOnly(target)
            Handoff.SEND_PARTY -> sendParty(target, myParty ?: return)
            Handoff.MOVE_PARTY_SOUND -> movePartySound(target)
            Handoff.SEND_MY_MUSIC -> sendMine(target)
            Handoff.PULL_THEIR_MUSIC -> pull(target)
        }
    }

    /** The device a party is on its way to; shown as "Connecting…" until it is in. */
    private var partyHandover: String? = null
    private var partyHandoverJob: Job? = null

    /**
     * This device is in a party and the person picked one that is not: it joins as the same person
     * and sounds, and this one stays in the party as its control. Nothing here goes quiet until the
     * other is in, so a join that fails leaves the music where it was.
     */
    private fun sendParty(target: AccountDevice, code: String) {
        val server = ListenTogether.activePartyServerBase() ?: run { say(DeviceNotice.NoStart); return }
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
                    ListenTogether.setListenHere(false)
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

    /** Both devices are in the party: the sound moves to [target], and this one follows it. */
    private fun movePartySound(target: AccountDevice) {
        account.command(target.deviceId, DeviceActions.PARTY_LISTEN, buildJsonObject { put("on", true) })
        ListenTogether.setListenHere(false)
    }

    /** The person tapped "This phone" while another device is the speaker: bring the sound back. */
    fun comeBack() {
        val code = ListenTogether.state.value.code?.takeIf { ListenTogether.state.value.inParty }
        if (code != null && !ListenTogether.listenHere.value) {
            // The party sounds on another of the person's devices: take it here, and quiet that one.
            ListenTogether.setListenHere(true)
            account.state.value.others
                .filter { it.connected && it.partyCode.equals(code, ignoreCase = true) }
                .forEach { account.command(it.deviceId, DeviceActions.PARTY_LISTEN, buildJsonObject { put("on", false) }) }
            return
        }
        val target = account.state.value.devices.firstOrNull { it.deviceId == targetId } ?: return endLocally(resumeHere = false)
        // Only a remote that gave its music away has anything to come back for. One that was only
        // controlling lets go and leaves the other device playing, exactly as it was.
        if (gaveMusic) pull(target) else endLocally(resumeHere = false)
    }

    private fun controlOnly(target: AccountDevice) {
        beginAs(target.deviceId)
        gaveMusic = false
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
        if (player.handoffWindow() == null) {
            // A file that lives on this phone alone: the other device has nothing to play.
            say(DeviceNotice.LocalFile)
            return
        }
        beginAs(target.deviceId)
        gaveMusic = true
        wantsPlay = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        requestedPositionMs = player.currentPosition.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        sendQueue(player, wantsPlay, requestedPositionMs)
        awaitingConfirm = item.mediaId
        armConfirmation(handedVideoId = item.mediaId, handedPlaying = wantsPlay)
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
                say(DeviceNotice.NoAnswer)
                endLocally(resumeHere = false)
            }
        }
    }

    /** What this phone had before it became a remote, to give back when it stops being one. */
    private class Stash(val items: List<MediaItem>, val index: Int, val positionMs: Long)

    private var stash: Stash? = null

    /**
     * This phone gave its own music to the target ([sendMine]), so leaving brings it back. When it only
     * controlled what the target was playing, leaving leaves that music where it is.
     */
    private var gaveMusic = false

    private fun beginAs(deviceId: String) {
        confirmJob?.cancel()
        // Only the first time: moving from one device to another keeps the original, not the mirror.
        if (!active) {
            stash = localPlayer()?.let { p ->
                Stash(
                    items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) },
                    index = p.currentMediaItemIndex.coerceAtLeast(0),
                    positionMs = p.currentPosition.coerceAtLeast(0L),
                )
            }
        }
        targetId = deviceId
        active = true
        holdPlayingUntil = NEVER
        intentAt = NEVER
        // What the target is already playing is known; starting from it is what keeps this phone's
        // own mirror from looking like a change.
        status = account.state.value.devices.firstOrNull { it.deviceId == deviceId }?.playhead()?.let(::statusOf)
        // And whether it is playing: a phone that connects to a device that is playing shows playing,
        // not the stale "paused" it had before it was anything's remote.
        wantsPlay = status?.isPlaying == true
        sink?.onRemoteRouteChanged()
        onChanged()
    }

    /** Keeps this phone audible until the target says it is playing, then quiets it. */
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
                    quietLocalPlayer()
                    sink?.onRemotePlaybackChanged()
                    onChanged()
                    return@launch
                }
            }
            if (awaitingConfirm != null) {
                // The target never took it up. Nothing was silenced, so nobody is left in silence.
                awaitingConfirm = null
                say(DeviceNotice.NoStart)
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
        holdPlayingUntil = NEVER
        navExpected = null
        gaveMusic = false
        if (old != null) account.detach()
        val kept = stash
        stash = null
        localPlayer()?.let { player ->
            if (kept != null) {
                // What this phone had before it controlled anything, not what the other device had.
                if (kept.items.isEmpty()) {
                    player.clearMediaItems()
                } else {
                    player.setMediaItems(kept.items, kept.index.coerceIn(0, kept.items.lastIndex), kept.positionMs)
                    player.prepare()
                    player.pause()
                }
            } else {
                val index = s?.mediaId?.let { indexOf(player, it) }
                if (index != null) player.seekTo(index, position) else if (player.mediaItemCount > 0) player.seekTo(position)
                if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
                if (resumeHere && wasPlaying) player.play()
            }
        }
        sink?.onRemoteRouteChanged()
        reanchor()
    }

    /** The other device handed its music back (a pull): it is loaded here, and the link is over. */
    fun onPulled() {
        if (!pulling) return
        pulling = false
        // The other device's music is what the person asked for here; nothing is given back.
        stash = null
        navExpected = null
        holdPlayingUntil = NEVER
        active = false
        targetId = null
        status = null
        wantsPlay = false
        confirmJob?.cancel()
        gaveMusic = false
        account.detach()
        sink?.onRemoteRouteChanged()
        reanchor()
    }

    // ---- transport --------------------------------------------------------------------------

    override fun play() {
        if (!active) return
        wantsPlay = true
        noteIntent()
        command(DeviceActions.PLAY)
        reanchor()
    }

    override fun pause() {
        if (!active) return
        wantsPlay = false
        noteIntent()
        command(DeviceActions.PAUSE)
        reanchor()
    }

    override fun seekTo(positionMs: Long) {
        if (!active) return
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        command(DeviceActions.SEEK, buildJsonObject { put("positionMs", requestedPositionMs) })
        reanchor()
    }

    /**
     * The song the phone's mirror was just moved to by a skip made here, and until when the target's
     * reports of the songs it passed through on the way there are not news. Without it two quick
     * skips pulled the bar back to the first, then forward again.
     */
    private var navExpected: String? = null
    private var navUntil = 0L

    /** Skips on the target. It moves through its own queue, so no queue travels and none can arrive late. */
    override fun next(): Boolean {
        if (!active || pulling || awaitingConfirm != null) return false
        navigate(+1)
        command(DeviceActions.NEXT)
        return true
    }

    override fun jumpToPrevious(): Boolean {
        if (!active || pulling || awaitingConfirm != null) return false
        navigate(-1)
        command(DeviceActions.PREVIOUS)
        return true
    }

    /** Moves the phone's mirror to where the skip is going, at once, so the bar answers the tap. */
    private fun navigate(delta: Int) {
        val player = localPlayer() ?: return
        val to = player.currentMediaItemIndex + delta
        if (to !in 0 until player.mediaItemCount) return
        navExpected = player.getMediaItemAt(to).mediaId
        navUntil = SystemClock.elapsedRealtime() + NAV_WINDOW_MS
        ignoreLocalUntil = SystemClock.elapsedRealtime() + NAV_SETTLE_MS
        applyingMirror = true
        try { player.seekTo(to, 0L) } finally { applyingMirror = false }
        requestedPositionMs = 0L
        requestedAt = SystemClock.elapsedRealtime()
        reanchor()
        // A report held back meanwhile is looked at again when the window closes.
        scope.launch {
            delay(NAV_WINDOW_MS + 50)
            if (active) onAccountState(account.state.value.devices.firstOrNull { it.deviceId == targetId })
        }
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
        if (item.mediaId == status?.mediaId || item.mediaId == navExpected) return
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
        if (!active || awaitingConfirm != null || pulling) return
        player.pause()
        play()
    }

    // ---- the other device moved -------------------------------------------------------------

    private fun onAccountState(target: AccountDevice?) {
        if (!active) return
        if (target == null || !target.connected) {
            // A device that is only away may be back in a moment (a mobile network blip; the
            // server keeps the link for that long). One that is gone for good is not. Waiting
            // before giving the sound back means a blip does not drop the whole link.
            if (target != null) {
                if (awayJob == null) {
                    awayJob = scope.launch {
                        delay(AWAY_GRACE_MS)
                        awayJob = null
                        val now = account.state.value.devices.firstOrNull { it.deviceId == targetId }
                        if (active && (now == null || !now.connected)) {
                            say(DeviceNotice.Disconnected(now?.name))
                            endLocally(resumeHere = false)
                        }
                    }
                }
                return
            }
            // Forgotten by the server: it is not coming back.
            say(DeviceNotice.Disconnected(null))
            endLocally(resumeHere = false)
            return
        }
        awayJob?.cancel()
        awayJob = null
        val np = target.nowPlaying
        if (np != null && np.updatedAtMs != status?.stamp) {
            // A song the target is passing through while the person skips ahead: the phone already
            // shows where the skips end, and is not sent back to where they began.
            val expected = navExpected
            if (expected != null && SystemClock.elapsedRealtime() < navUntil && np.videoId != expected) return
            if (expected != null && np.videoId == expected) navExpected = null
            val before = status
            status = statusOf(target.playhead() ?: np)
            requestedAt = 0L
            // A song change that comes with "not playing" is the target loading it, not pausing.
            val changedSong = before != null && before.mediaId != np.videoId
            holdPlayingUntil = if (changedSong && !np.isPlaying && (before?.isPlaying == true || wantsPlay)) {
                SystemClock.elapsedRealtime() + HOLD_PLAYING_MS
            } else {
                NEVER
            }
            if (holdPlayingUntil != NEVER) {
                scope.launch {
                    delay(HOLD_PLAYING_MS + 50)
                    sink?.onRemotePlaybackChanged()
                    onChanged()
                }
            }
            if (awaitingConfirm == null && !recentIntent()) wantsPlay = np.isPlaying || holdPlayingUntil != NEVER
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
        val (tracks, index) = player.handoffWindow() ?: return
        account.command(
            id, DeviceActions.PLAY_QUEUE,
            buildJsonObject {
                put("tracks", DeviceWire.tracks(tracks))
                put("index", index)
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

        /** "Not set", far enough back that no grace window around it can be open. */
        const val NEVER = Long.MIN_VALUE / 2

        /** What the person pressed wins over the target's last report for this long. */
        const val INTENT_GRACE_MS = 2_500L

        /** A new song reported "not playing" is it loading; the bar keeps saying playing this long. */
        const val HOLD_PLAYING_MS = 3_000L

        /** After a skip made here, reports of the songs passed on the way are not news for this long. */
        const val NAV_WINDOW_MS = 2_500L
        const val NAV_SETTLE_MS = 800L
        const val CONFIRM_TIMEOUT_MS = 8_000L

        /** A little longer than the server's own grace for a dropped socket. */
        const val AWAY_GRACE_MS = 15_000L

        /** A computer has to find the song's stream before it can play it, which takes longer than an answer. */
        const val HANDOVER_TIMEOUT_MS = 20_000L
        const val CONFIRM_POLL_MS = 250L

        /** How long after loading the remote's queue the player's own reports are not the person's. */
        const val MIRROR_SETTLE_MS = 2_500L

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
    /**
     * The door every external surface knocks on (the session's player), which is where a party tells
     * the person's play, pause and skip from its own corrections. Null before the session exists.
     */
    private val partyDoor: () -> Player? = { null },
) {
    private var last: AccountNowPlaying? = null

    /** The songs in this phone's queue as the controller last knew them, to notice an edit made here. */
    private var queueSig = ""

    private fun signature(p: Player) = (0 until p.mediaItemCount).joinToString(",") { p.getMediaItemAt(it).mediaId }

    /** An edit made on this phone while another device is its remote: the remote's copy follows. */
    private fun mirrorEdits() {
        val p = player() ?: return
        val now = signature(p)
        if (now == queueSig) return
        queueSig = now
        val controller = account.state.value.controller ?: return
        answer(p, controller.deviceId, pullIt = false)
    }

    fun start() {
        scope.launch { account.commands.collect { handle(it) } }
        scope.launch {
            while (true) {
                delay(PUBLISH_MS)
                publish()
                mirrorEdits()
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
        // Moving a party between the person's own devices: it needs no link and says nothing about
        // this player, so it is handled before anything that depends on who owns it.
        when (command.action) {
            DeviceActions.JOIN_PARTY -> { joinParty(payload); return }
            DeviceActions.PARTY_LISTEN -> {
                if (ListenTogether.state.value.inParty) ListenTogether.setListenHere(DeviceWire.bool(payload, "on") == true)
                return
            }
        }
        // The party owns this player and reconciles it many times a second, so a queue or a song put
        // into it would be undone at once. What a remote can still do is what any listener in the
        // party can: play, pause, seek and skip, through the same door the notification uses, so
        // the party's own rules (a locked transport, say) decide.
        if (ListenTogether.partyOwnsPlayer()) {
            handleInParty(p, command)
            return
        }
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
        // What the controller sent is already its own copy; only edits made here are sent back.
        if (command.action == DeviceActions.PLAY_QUEUE || command.action == DeviceActions.ENQUEUE) queueSig = signature(p)
        // Report at once rather than at the next tick, so the other device's controls feel immediate.
        publish()
    }

    private fun handleInParty(p: ExoPlayer, command: DeviceCommand) {
        val door = partyDoor() ?: return
        val payload = command.payload
        when (command.action) {
            DeviceActions.PLAY -> door.play()
            DeviceActions.PAUSE -> door.pause()
            DeviceActions.SEEK -> DeviceWire.long(payload, "positionMs")?.let { door.seekTo(it) }
            DeviceActions.NEXT -> door.seekToNextMediaItem()
            DeviceActions.PREVIOUS -> door.seekToPreviousMediaItem()
            DeviceActions.SET_VOLUME -> DeviceWire.double(payload, "volume")?.let { p.volume = it.toFloat().coerceIn(0f, 1f) }
            // The party's own queue, shown on the remote; nothing is linked back to it.
            DeviceActions.SNAPSHOT -> answer(p, command.from, pullIt = false)
            else -> Unit
        }
        publish()
    }

    /** Another of the person's devices has a party and asked this one to take it. */
    private fun joinParty(payload: JsonObject) {
        val code = payload["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return
        val server = payload["server"]?.jsonPrimitive?.contentOrNull
        // A party owns the player: being somebody's remote at the same time would pull two ways.
        if (speaker.active) speaker.endLocally(resumeHere = false)
        scope.launch { ListenTogether.joinFromOwnDevice(code, server) }
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
        // The sound was handed back to this phone: it stops being the other device's remote *before*
        // its player starts, or the player's own start is taken for the person asking the other
        // device to play, and the device that just gave the sound up starts it again.
        if (!mirror) speaker.onPulled()
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
        val (tracks, index) = p.handoffWindow() ?: return
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        account.command(
            to, DeviceActions.PLAY_QUEUE,
            buildJsonObject {
                put("tracks", DeviceWire.tracks(tracks))
                put("index", index)
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

/**
 * The songs of this player that go with a hand-over, and where the current one is among them (see
 * [HandoffWindow]); null when the current song cannot be played on another device.
 */
internal fun Player.handoffWindow(): Pair<List<PartyTrack>, Int>? {
    val ids = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }
    val slice = HandoffWindow.slice(ids, currentMediaItemIndex.coerceAtLeast(0)) { !HandoffWindow.isDeviceFile(it) }
        ?: return null
    return slice.indices.map { getMediaItemAt(it).toSong().toPartyTrack(0L) } to slice.index
}
