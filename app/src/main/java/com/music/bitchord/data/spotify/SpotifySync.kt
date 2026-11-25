package com.music.bitchord.data.spotify

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.music.bitchord.R
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isUnresolvedSpotify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Why a playlist, or the whole run, could not be imported. [SIGNED_OUT] is the
 * Spotify sign-in; [YOUTUBE_SIGNED_OUT] the YouTube Music one an import to the
 * account writes with; [RATE_LIMITED] YouTube asking to slow down.
 */
enum class SyncErrorKind { SIGNED_OUT, YOUTUBE_SIGNED_OUT, RATE_LIMITED, NETWORK, OTHER }

/**
 * What a failure means to the person: an expired sign-in (Spotify answering
 * 401/403, or no token to send) is theirs to fix, a dropped connection passes.
 */
fun syncErrorKind(e: Throwable): SyncErrorKind {
    if (e is YouTubeWriteException) return e.kind
    val status = httpStatusOf(e)
    return when {
        e is SpotifySignInExpiredException ||
            e.message.orEmpty().contains("sign-in expired", ignoreCase = true) ||
            status == 401 || status == 403 -> SyncErrorKind.SIGNED_OUT
        e is IOException -> SyncErrorKind.NETWORK
        else -> SyncErrorKind.OTHER
    }
}

/** A write to YouTube Music that gave up, already read as a [SyncErrorKind]. */
class YouTubeWriteException(val kind: SyncErrorKind, detail: String) : Exception(detail)

private fun ExportErrorKind.toSyncKind(): SyncErrorKind = when (this) {
    ExportErrorKind.SIGNED_OUT -> SyncErrorKind.YOUTUBE_SIGNED_OUT
    ExportErrorKind.RATE_LIMITED -> SyncErrorKind.RATE_LIMITED
    ExportErrorKind.NETWORK -> SyncErrorKind.NETWORK
    ExportErrorKind.OTHER -> SyncErrorKind.OTHER
}

/** A playlist that did not make it, and why. */
data class SyncFailure(
    val playlist: SpotifyPlaylist,
    val kind: SyncErrorKind,
    val detail: String,
)

/** Where an import puts what it matched. */
sealed interface SyncTarget {
    /** A playlist kept on this device. */
    data object Device : SyncTarget

    /**
     * A playlist on the signed-in YouTube Music account. With [likedAsLikes],
     * Spotify's Liked Songs become likes on YouTube Music rather than a
     * playlist of their own.
     */
    data class YouTube(
        val privacy: PlaylistPrivacy = PlaylistPrivacy.PRIVATE,
        val likedAsLikes: Boolean = false,
    ) : SyncTarget
}

/** What a running import is doing to the playlist under way. */
enum class SyncPhase {
    /** Reading the playlist's tracks from Spotify. */
    READING,

    /** Looking each track up on YouTube Music. */
    MATCHING,

    /** Sending the matches to a playlist on YouTube Music. */
    UPLOADING,

    /** Liking the matches on YouTube Music. */
    LIKING,
}

/** Where the copy of the Spotify library stands. */
sealed interface SpotifySyncState {
    data object Idle : SpotifySyncState

    /**
     * Playlist number [index] (1-based) of [total] is under way, in [phase];
     * [songsDone] of [songsTotal] are through that phase. [failures] are the
     * playlists already given up on. [throttled] while YouTube's limit is
     * being waited out.
     */
    data class Running(
        val index: Int,
        val total: Int,
        val playlist: SpotifyPlaylist,
        val songsDone: Int,
        val songsTotal: Int,
        val failures: List<SyncFailure>,
        val target: SyncTarget = SyncTarget.Device,
        val phase: SyncPhase = SyncPhase.READING,
        val throttled: Boolean = false,
    ) : SpotifySyncState {
        /** How far through the whole run, 0 to 1. */
        val fraction: Float
            get() {
                val part = if (songsTotal > 0) songsDone.toFloat() / songsTotal else 0f
                // An import to the account is two halves a playlist: matching,
                // then writing. One to the device is matching alone.
                val inPlaylist = when {
                    target == SyncTarget.Device -> part
                    phase == SyncPhase.READING -> 0f
                    phase == SyncPhase.MATCHING -> part / 2
                    else -> 0.5f + part / 2
                }
                return ((index - 1) + inPlaylist.coerceIn(0f, 1f)) / total
            }
    }

    /**
     * The run ended: [imported] playlists saved, [added] songs new to the copy,
     * [unmatched] with no YouTube Music version. [liked] songs were liked on
     * YouTube Music, when Liked Songs went there as likes.
     */
    data class Finished(
        val imported: Int,
        val added: Int,
        val unmatched: Int,
        val failures: List<SyncFailure>,
        val cancelled: Boolean,
        val unmatchedTracks: List<SpotifyTrack> = emptyList(),
        val target: SyncTarget = SyncTarget.Device,
        val liked: Int = 0,
    ) : SpotifySyncState

    /** The run could not go on at all, as when a sign-in has expired. */
    data class Aborted(
        val kind: SyncErrorKind,
        val detail: String,
        val imported: Int = 0,
        val target: SyncTarget = SyncTarget.Device,
    ) : SpotifySyncState
}

/**
 * Copies the Spotify library: every playlist (and Liked Songs) as a playlist
 * of its YouTube Music matches, kept on this device or written to the
 * YouTube Music account, which is what actually plays.
 *
 * Re-running is cheap and is how new songs arrive — a song already matched is
 * reused from the cache, so only what Spotify gained is looked up, and an
 * account playlist only receives what it does not hold yet (see
 * [PlaylistUploader]). The work runs in its own scope, held alive by
 * [SpotifySyncService] with a notification, so neither leaving the Spotify page
 * nor the app stops it.
 *
 * One playlist failing does not stop the rest: it is recorded and offered for
 * [retryFailed]. A lost sign-in, or YouTube asking to slow down, ends the run,
 * since every later playlist would fail the same way.
 */
object SpotifySync {
    private const val TAG = "SpotifySync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var appContext: Context? = null

    private val _state = MutableStateFlow<SpotifySyncState>(SpotifySyncState.Idle)
    val state: StateFlow<SpotifySyncState> = _state.asStateFlow()

    val running: Boolean get() = _state.value is SpotifySyncState.Running

    /** The target of the last run, so [retryFailed] writes where it did. */
    @Volatile
    private var lastTarget: SyncTarget = SyncTarget.Device

    /** Titles of what is being written to the account, by ledger key, for the Library to show them as they land. */
    private val uploadTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The ledger key a Spotify playlist's account copy is recorded under in [YouTubeExportLedger]. */
    fun ledgerKey(spotifyId: String): String = LEDGER_PREFIX + spotifyId

    /** Whether this Spotify playlist already has a copy on the signed-in YouTube Music account. */
    fun isOnYouTube(spotifyId: String): Boolean = YouTubeExportLedger.get(ledgerKey(spotifyId)) != null

    /** The title of the account playlist recorded under [ledgerKey], while this run knows it. */
    fun uploadTitle(ledgerKey: String): String? = uploadTitles[ledgerKey]

    /** Copies [playlists] in the background; ignored while a run is already going. */
    @Synchronized
    fun start(playlists: List<SpotifyPlaylist>, target: SyncTarget = SyncTarget.Device) {
        if (running || playlists.isEmpty()) return
        lastTarget = target
        _state.value = SpotifySyncState.Running(1, playlists.size, playlists.first(), 0, 0, emptyList(), target)
        job = scope.launch { run(playlists, target) }
        keepAlive()
    }

    /** Stops the run; playlists already saved stay. */
    fun cancel() {
        job?.cancel()
    }

    /** Tries again just the playlists the last run gave up on. */
    fun retryFailed() {
        val failed = (_state.value as? SpotifySyncState.Finished)?.failures?.map { it.playlist }.orEmpty()
        if (failed.isNotEmpty()) start(failed, lastTarget)
    }

    /** Puts the result away once it has been read. */
    fun dismiss() {
        if (!running) _state.value = SpotifySyncState.Idle
    }

    /** Hands the run to the foreground service, which keeps the process up and reports it. */
    private fun keepAlive() {
        val app = appContext ?: return
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, SpotifySyncService::class.java))
        }.onFailure {
            // Refused only with no window and no exemption: the run still goes
            // on while the process lives, just without the notification.
            Log.w(TAG, "could not start the import service: ${it.message}")
        }
    }

    private suspend fun run(playlists: List<SpotifyPlaylist>, target: SyncTarget) {
        var imported = 0
        var added = 0
        var unmatched = 0
        var liked = 0
        val failures = mutableListOf<SyncFailure>()
        val allUnmatched = mutableListOf<SpotifyTrack>()
        // One uploader for the run, so a slow-down YouTube asked for carries
        // over to the next playlist rather than starting at full pace again.
        val uploader = (target as? SyncTarget.YouTube)?.let { youTubeUploader(it.privacy) }
        // Likewise one pace for every search of the run, across playlists.
        val pacer = RequestPacer()
        val matcher = SpotifyTrackMatcher(
            lookUp = { track -> SpotifyImporter.lookUp(track, pacer) },
            parallelism = MATCH_PARALLELISM,
            waitsMs = MATCH_RETRY_WAITS_MS,
        )
        try {
            playlists.forEachIndexed { index, playlist ->
                _state.value = SpotifySyncState.Running(
                    index = index + 1,
                    total = playlists.size,
                    playlist = playlist,
                    songsDone = 0,
                    songsTotal = 0,
                    failures = failures.toList(),
                    target = target,
                )
                try {
                    val result = syncPlaylist(playlist, target, uploader, matcher, pacer)
                    imported++
                    added += result.added
                    unmatched += result.unmatched
                    liked += result.liked
                    allUnmatched += result.unmatchedTracks
                    if (result.lookupFailures > 0) {
                        failures += SyncFailure(
                            playlist,
                            SyncErrorKind.NETWORK,
                            "${result.lookupFailures} songs could not be looked up",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val kind = syncErrorKind(e)
                    Log.w(TAG, "${playlist.name}: $kind ${e.message}")
                    if (kind == SyncErrorKind.SIGNED_OUT ||
                        kind == SyncErrorKind.YOUTUBE_SIGNED_OUT ||
                        kind == SyncErrorKind.RATE_LIMITED
                    ) {
                        _state.value = SpotifySyncState.Aborted(kind, e.message.orEmpty(), imported, target)
                        return
                    }
                    failures += SyncFailure(playlist, kind, e.message.orEmpty())
                }
            }
            _state.value = SpotifySyncState.Finished(
                imported = imported,
                added = added,
                unmatched = unmatched,
                failures = failures.toList(),
                cancelled = false,
                unmatchedTracks = allUnmatched.toList(),
                target = target,
                liked = liked,
            )
        } catch (e: CancellationException) {
            _state.value = SpotifySyncState.Finished(
                imported = imported,
                added = added,
                unmatched = unmatched,
                failures = failures.toList(),
                cancelled = true,
                unmatchedTracks = allUnmatched.toList(),
                target = target,
                liked = liked,
            )
            throw e
        }
    }

    private class Result(
        val added: Int,
        val unmatched: Int,
        val lookupFailures: Int,
        val unmatchedTracks: List<SpotifyTrack>,
        val liked: Int = 0,
    )

    /**
     * Lookups in flight at once. The pace is the [RequestPacer]'s; this only
     * lets a slow answer overlap the next request rather than set the rate.
     */
    private const val MATCH_PARALLELISM = 3

    /** A failed lookup is tried again after these, while the pacer cools every other search down too. */
    private val MATCH_RETRY_WAITS_MS = listOf(5_000L, 20_000L, 60_000L)
    private const val LEDGER_PREFIX = "spotify:"

    private suspend fun syncPlaylist(
        playlist: SpotifyPlaylist,
        target: SyncTarget,
        uploader: PlaylistUploader?,
        matcher: SpotifyTrackMatcher,
        pacer: RequestPacer,
    ): Result {
        val tracks = SpotifyLibrary.tracks(playlist.id)
        val known = LocalPlaylistStore.findBySpotifyId(playlist.id)?.matchedSongs().orEmpty()
        publishProgress(playlist, SyncPhase.MATCHING, 0, tracks.size)
        val done = AtomicInteger(0)
        val outcomes: List<TrackMatch> = coroutineScope {
            tracks.map { track ->
                async {
                    val outcome = (known[track.id] ?: SpotifyCache.getMatch(track.id))
                        ?.let { TrackMatch.Found(it) }
                        ?: matcher.match(track)
                    // YouTube has stopped answering altogether: asking on for
                    // every remaining track would only keep it that way.
                    if (outcome is TrackMatch.Failed && pacer.exhausted) {
                        throw YouTubeWriteException(
                            SyncErrorKind.RATE_LIMITED,
                            outcome.cause.message ?: "YouTube Music stopped answering",
                        )
                    }
                    if (outcome is TrackMatch.Found && !outcome.song.isUnresolvedSpotify) {
                        SpotifyCache.putMatch(track.id, outcome.song)
                    }
                    publishProgress(playlist, SyncPhase.MATCHING, done.incrementAndGet(), tracks.size)
                    outcome
                }
            }.awaitAll()
        }
        val kept = tracks.indices.filter { outcomes[it] is TrackMatch.Found }
        val failed = outcomes.count { it is TrackMatch.Failed }
        val unmatchedTracks = tracks.indices
            .filter { outcomes[it] is TrackMatch.NotFound }
            .map { tracks[it] }
        val songs = kept.map { (outcomes[it] as TrackMatch.Found).song }

        var liked = 0
        var added: Int
        when (target) {
            SyncTarget.Device -> {
                // A song whose lookup failed is not kept out for good: it is simply not
                // saved yet, and the playlist is reported as incomplete so a retry
                // looks those up again.
                LocalPlaylistStore.saveSpotifyPlaylist(
                    spotifyId = playlist.id,
                    title = playlist.name,
                    songs = songs,
                    trackIds = kept.map { tracks[it].id },
                )
                added = kept.count { tracks[it].id !in known }
            }
            is SyncTarget.YouTube -> {
                if (playlist.id == SpotifyLibrary.LIKED_ID && target.likedAsLikes) {
                    liked = likeAll(playlist, songs)
                    added = liked
                } else {
                    added = upload(playlist, songs, uploader!!)
                }
            }
        }
        return Result(
            added = added,
            unmatched = outcomes.count { it is TrackMatch.NotFound },
            lookupFailures = failed,
            unmatchedTracks = unmatchedTracks,
            liked = liked,
        )
    }

    /** The account playlist's title: Liked Songs is named so it can't be taken for YouTube's own. */
    private fun accountTitle(playlist: SpotifyPlaylist): String =
        if (playlist.id == SpotifyLibrary.LIKED_ID) {
            appContext?.getString(R.string.spotify_liked_playlist_title) ?: "Spotify Liked Songs"
        } else {
            playlist.name
        }

    /**
     * Writes [songs] to the account playlist this Spotify playlist went to
     * before, or a new one; returns how many were new to it.
     */
    private suspend fun upload(playlist: SpotifyPlaylist, songs: List<Song>, uploader: PlaylistUploader): Int {
        val key = ledgerKey(playlist.id)
        val title = accountTitle(playlist)
        uploadTitles[key] = title
        val before = YouTubeExportLedger.get(key)?.exported.orEmpty()
        val ids = songs.map { it.videoId }
        val result = uploader.upload(localId = key, title = title, songIds = ids) { state ->
            when (state) {
                is ExportState.Running -> publishProgress(
                    playlist, SyncPhase.UPLOADING, state.done, state.total, state.throttled,
                )
                else -> Unit
            }
        }
        return when (result) {
            is ExportState.Done -> ids.count { it !in before }
            is ExportState.Failed -> {
                // Nothing matched is not a write that failed: the playlist is
                // simply empty, and the unmatched count already says so.
                if (result.total == 0) return 0
                throw YouTubeWriteException(result.kind.toSyncKind(), result.detail)
            }
            else -> 0
        }
    }

    /**
     * Likes [songs] on YouTube Music, oldest Spotify like first so the newest
     * ends up newest there too. What is already liked is skipped. Paced like
     * [PlaylistUploader], since every like is a write of its own; returns how
     * many were liked.
     */
    private suspend fun likeAll(playlist: SpotifyPlaylist, songs: List<Song>): Int {
        val alreadyLiked = LikeState.overrides.value
        val pending = songs.asReversed()
            .map { it.videoId }
            .filter { PlaylistUploader.VIDEO_ID.matches(it) }
            .distinct()
            .filter { alreadyLiked[it] != LikeStatus.LIKE }
        if (pending.isEmpty()) return 0
        publishProgress(playlist, SyncPhase.LIKING, 0, pending.size)
        var gap = LIKE_GAP_MS
        pending.forEachIndexed { index, videoId ->
            if (index > 0) delay((gap * Random.nextDouble(0.8, 1.2)).toLong())
            var attempt = 0
            while (true) {
                val outcome = YtMusicRepository.rate(videoId, LikeStatus.LIKE)
                val error = outcome.exceptionOrNull() ?: break
                if (error is CancellationException) throw error
                val kind = exportErrorKind(error)
                val waits = when (kind) {
                    ExportErrorKind.RATE_LIMITED -> PlaylistUploader.RATE_LIMIT_WAITS_MS
                    ExportErrorKind.NETWORK -> PlaylistUploader.NETWORK_WAITS_MS
                    else -> emptyList()
                }
                if (attempt >= waits.size) {
                    throw YouTubeWriteException(kind.toSyncKind(), error.message.orEmpty())
                }
                gap = minOf(gap * 2, PlaylistUploader.MAX_GAP_MS)
                publishProgress(playlist, SyncPhase.LIKING, index, pending.size, throttled = true)
                delay(waits[attempt++])
            }
            LikeState.set(videoId, LikeStatus.LIKE)
            publishProgress(playlist, SyncPhase.LIKING, index + 1, pending.size)
        }
        return pending.size
    }

    private fun youTubeUploader(privacy: PlaylistPrivacy) = PlaylistUploader(
        target = object : YouTubeTarget {
            override suspend fun create(title: String, videoIds: List<String>): String =
                YtMusicRepository.createPlaylist(title, privacy, videoIds).getOrThrow()

            override suspend fun add(playlistId: String, videoIds: List<String>) {
                YtMusicRepository.addToPlaylist(playlistId, videoIds).getOrThrow()
            }

            override suspend fun contents(playlistId: String): Set<String>? =
                YtMusicRepository.playlistEntries("VL$playlistId").getOrNull()?.map { it.videoId }?.toSet()
        },
        ledger = YouTubeExportLedger,
    )

    /** Pause between two likes; doubled whenever YouTube pushes back. */
    private const val LIKE_GAP_MS = 1_500L

    private fun publishProgress(
        playlist: SpotifyPlaylist,
        phase: SyncPhase,
        done: Int,
        total: Int,
        throttled: Boolean = false,
    ) {
        _state.update { state ->
            if (state is SpotifySyncState.Running && state.playlist.id == playlist.id) {
                // Counts only move forward within a phase; matches land out of order.
                val songsDone = if (state.phase == phase) maxOf(state.songsDone, done) else done
                state.copy(songsDone = songsDone, songsTotal = total, phase = phase, throttled = throttled)
            } else {
                state
            }
        }
    }
}

/**
 * Whether [browseId] is a copy of Spotify's Liked Songs: the one kept on this
 * device, or the separate playlist written to the account. Those are drawn
 * with the green liked cover.
 */
fun isSpotifyLikedCopy(browseId: String?): Boolean {
    if (browseId.isNullOrBlank()) return false
    if (browseId.startsWith("local:playlist:")) {
        return LocalPlaylistStore.getPlaylist(browseId)?.spotifyId == SpotifyLibrary.LIKED_ID
    }
    val youtubeId = YouTubeExportLedger.get(SpotifySync.ledgerKey(SpotifyLibrary.LIKED_ID))?.youtubeId
        ?: return false
    return browseId == youtubeId || browseId == "VL$youtubeId"
}
