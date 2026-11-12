package com.music.bitchord.data.listentogether.spotify

import android.os.SystemClock
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Bidirectional bridge connecting Spotify Jam sessions with BitChord Listen Together.
 */
object SpotifyJamBridge {

    private const val TAG = "SpotifyJamBridge"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dealerClient = SpotifyDealerClient(scope)
    private val bridgeMutex = Mutex()

    private val _session = MutableStateFlow<SpotifyJamSession?>(null)
    val session: StateFlow<SpotifyJamSession?> = _session.asStateFlow()

    private val _playbackState = MutableStateFlow<SpotifyJamPlaybackState?>(null)
    val playbackState: StateFlow<SpotifyJamPlaybackState?> = _playbackState.asStateFlow()

    val isBridgeActive: Boolean get() = _session.value != null

    // Cache of videoId <-> spotifyUri
    private val videoIdToSpotifyUri = ConcurrentHashMap<String, String>()
    private val spotifyUriToPartyTrack = ConcurrentHashMap<String, PartyTrack>()

    // Echo guard: prevents outgoing commands from looping back as inbound updates
    private class EchoSignature(
        val action: String,
        val trackUri: String?,
        val positionMs: Long,
        val expiresAtMs: Long,
    )
    private val echoSignatures = mutableListOf<EchoSignature>()

    private var dealerCollectJob: Job? = null

    /**
     * Initializes or connects Dealer listener if a session is restored.
     */
    fun init() {
        startDealerCollection()
    }

    private fun startDealerCollection() {
        dealerCollectJob?.cancel()
        dealerCollectJob = scope.launch {
            dealerClient.events.collect { event ->
                when (event) {
                    is SpotifyDealerClient.DealerEvent.PlaybackUpdated -> handleInboundPlayback(event.state)
                    is SpotifyDealerClient.DealerEvent.SessionMembersUpdated -> handleInboundMembers(event.members)
                    is SpotifyDealerClient.DealerEvent.SessionEnded -> handleInboundSessionEnded(event.reason)
                }
            }
        }
    }

    /**
     * Creates a Spotify Jam session as Host and links it. (Requires Premium)
     */
    suspend fun createJam(): Result<SpotifyJamSession> = bridgeMutex.withLock {
        val result = SpotifyJamApi.createSession()
        result.onSuccess { newSession ->
            _session.value = newSession
            dealerClient.start()
            Log.i(TAG, "started Spotify Jam: ${newSession.sessionId} uri=${newSession.joinSessionUri}")
        }.onFailure {
            Log.w(TAG, "failed to start Spotify Jam: ${it.message}")
        }
        result
    }

    /**
     * Joins an existing Spotify Jam session. (Supported for both Premium & Free)
     */
    suspend fun joinJam(sessionTokenOrUri: String): Result<SpotifyJamSession> = bridgeMutex.withLock {
        val result = SpotifyJamApi.joinSession(sessionTokenOrUri)
        result.onSuccess { joinedSession ->
            _session.value = joinedSession
            dealerClient.start()
            Log.i(TAG, "joined Spotify Jam: ${joinedSession.sessionId}")
        }.onFailure {
            Log.w(TAG, "failed to join Spotify Jam: ${it.message}")
        }
        result
    }

    /**
     * Leaves or ends the active Spotify Jam session.
     */
    suspend fun leaveJam(): Boolean = bridgeMutex.withLock {
        val current = _session.value ?: return true
        val ok = SpotifyJamApi.leaveSession(current.sessionId, current.isHost)
        dealerClient.stop()
        _session.value = null
        _playbackState.value = null
        echoSignatures.clear()
        Log.i(TAG, "left Spotify Jam")
        ok
    }

    // ---- Inbound Handlers (Spotify -> BitChord) -----------------------

    private suspend fun handleInboundPlayback(state: SpotifyJamPlaybackState) {
        val now = SystemClock.elapsedRealtime()
        synchronized(echoSignatures) {
            echoSignatures.removeAll { it.expiresAtMs < now }
            val matchingEcho = echoSignatures.firstOrNull { echo ->
                echo.trackUri == state.trackUri &&
                    abs(echo.positionMs - state.positionMs) < 2500L
            }
            if (matchingEcho != null) {
                echoSignatures.remove(matchingEcho)
                Log.d(TAG, "ignoring echo for track ${state.trackUri}")
                return
            }
        }

        _playbackState.value = state
        val uri = state.trackUri ?: return
        val cachedTrack = spotifyUriToPartyTrack[uri]

        val partyTrack = if (cachedTrack != null) {
            cachedTrack
        } else {
            val spotifyTrack = SpotifyTrack(
                id = uri.substringAfterLast(":"),
                title = state.title,
                artist = state.artist,
                album = state.album,
                durationMs = state.durationMs.toInt(),
                imageUrl = state.imageUrl,
            )
            val matchedSong = SpotifyImporter.lookUp(spotifyTrack)
            if (matchedSong != null) {
                val pt = PartyTrack(
                    videoId = matchedSong.videoId,
                    title = matchedSong.title,
                    artist = matchedSong.artist,
                    thumbnailUrl = matchedSong.thumbnailUrl ?: state.imageUrl,
                    durationMs = state.durationMs,
                )
                videoIdToSpotifyUri[matchedSong.videoId] = uri
                spotifyUriToPartyTrack[uri] = pt
                pt
            } else {
                PartyTrack(
                    videoId = "spotify_${uri.substringAfterLast(":")}",
                    title = state.title,
                    artist = state.artist,
                    thumbnailUrl = state.imageUrl,
                    durationMs = state.durationMs,
                )
            }
        }

        // Calculate latency-compensated position
        val latencyOffset = (System.currentTimeMillis() - state.timestampMs).coerceAtLeast(0L)
        val compensatedPosMs = state.positionMs + latencyOffset

        // If in a ListenTogether room, update party state if we are host or authorized
        if (ListenTogether.state.value.inParty) {
            val currentPlayback = ListenTogether.state.value.playback
            if (currentPlayback.track?.videoId != partyTrack.videoId) {
                ListenTogether.setTrack(partyTrack, compensatedPosMs, state.isPlaying)
            } else if (currentPlayback.isPlaying != state.isPlaying) {
                if (state.isPlaying) ListenTogether.play(compensatedPosMs) else ListenTogether.pause(compensatedPosMs)
            } else if (abs((ListenTogether.partyPositionMs() ?: 0L) - compensatedPosMs) > 1500L) {
                ListenTogether.seek(compensatedPosMs)
            }
        }
    }

    private fun handleInboundMembers(members: List<SpotifyJamMember>) {
        _session.update { current ->
            current?.copy(members = members)
        }
    }

    private suspend fun handleInboundSessionEnded(reason: String) {
        Log.i(TAG, "Spotify Jam ended remotely: $reason")
        leaveJam()
    }

    // ---- Outbound Sync (BitChord -> Spotify Jam) ----------------------

    fun onLocalPlay(positionMs: Long) {
        val s = _session.value ?: return
        recordEcho("play", _playbackState.value?.trackUri, positionMs)
        scope.launch {
            SpotifyJamApi.sendCommand("resume", s.activeDeviceId)
        }
    }

    fun onLocalPause(positionMs: Long) {
        val s = _session.value ?: return
        recordEcho("pause", _playbackState.value?.trackUri, positionMs)
        scope.launch {
            SpotifyJamApi.sendCommand("pause", s.activeDeviceId)
        }
    }

    fun onLocalSeek(positionMs: Long) {
        val s = _session.value ?: return
        recordEcho("seek", _playbackState.value?.trackUri, positionMs)
        scope.launch {
            SpotifyJamApi.sendCommand("seek_to", s.activeDeviceId)
        }
    }

    fun onLocalTrackChanged(song: Song, positionMs: Long, isPlaying: Boolean) {
        val s = _session.value ?: return
        scope.launch {
            val spotifyUri = videoIdToSpotifyUri[song.videoId]
            if (spotifyUri != null) {
                recordEcho("setTrack", spotifyUri, positionMs)
                SpotifyJamApi.addToQueue(spotifyUri)
                if (isPlaying) {
                    SpotifyJamApi.sendCommand("resume", s.activeDeviceId)
                }
            }
        }
    }

    private fun recordEcho(action: String, trackUri: String?, positionMs: Long) {
        val signature = EchoSignature(
            action = action,
            trackUri = trackUri,
            positionMs = positionMs,
            expiresAtMs = SystemClock.elapsedRealtime() + 3_000L,
        )
        synchronized(echoSignatures) {
            echoSignatures.add(signature)
        }
    }
}
