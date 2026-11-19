package com.music.bitchord.data.spotify

import android.util.Log
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isMatchPending
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads the Spotify library ahead of the listener, so a playlist opens on its songs rather than on
 * a spinner and then a minute of matching.
 *
 * Once per launch, a moment after it: the list of playlists is read again (and kept), and every
 * playlist that has nothing kept yet has its tracks read and kept — each row already matched where
 * an earlier lookup found it, the rest waiting on their match. The most recent playlists then have
 * those matched too, within a budget per run, since every match is a search on YouTube Music and
 * a library of thousands would otherwise look like a crawler.
 *
 * A playlist the listener opens meanwhile is theirs: [isOpen] says so, and it is left to the page.
 */
object SpotifyPrefetch {
    private const val TAG = "BitChord"
    private const val SETTLE_MS = 6_000L
    /** How many of the playlists, newest first, have their songs matched ahead. */
    private const val MATCH_PLAYLISTS = 10
    /** Lookups one run may spend, across every playlist. */
    private const val MATCH_BUDGET = 500
    private const val MATCH_PARALLEL = 2
    private const val SAVE_EVERY = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(isOpen: (String) -> Boolean) {
        if (job?.isActive == true) return
        if (AppSettings.spotifySpdcToken.value.isBlank()) return
        job = scope.launch {
            delay(SETTLE_MS)
            try {
                run(isOpen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "spotify prefetch stopped: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(isOpen: (String) -> Boolean) {
        val playlists = runCatching { SpotifyLibrary.playlists() }.getOrNull()
            ?.also { SpotifyCache.savePlaylists(it) }
            ?: SpotifyCache.getPlaylists()
            ?: return
        val budget = AtomicInteger(MATCH_BUDGET)
        playlists.take(SpotifyCache.MAX_CACHED_PLAYLISTS).forEachIndexed { index, playlist ->
            if (AppSettings.spotifySpdcToken.value.isBlank()) return
            if (isOpen(playlist.id)) return@forEachIndexed
            val cached = SpotifyCache.getPlaylist(playlist.id)
            val pending = cached?.songs?.count { it.isMatchPending } ?: -1
            val matchThis = index < MATCH_PLAYLISTS && budget.get() > 0
            // Kept and nothing waiting on a match: the page checks it for changes itself when opened.
            if (cached != null && cached.songs.isNotEmpty() && (pending == 0 || !matchThis)) return@forEachIndexed

            val tracks = cached?.tracks?.takeIf { it.isNotEmpty() && it.size == cached.songs.size }
                ?: runCatching { SpotifyLibrary.tracks(playlist.id) }.getOrNull()
                ?: return@forEachIndexed
            if (tracks.isEmpty()) return@forEachIndexed
            val songs = tracks.mapIndexed { i, track ->
                cached?.songs?.getOrNull(i)?.takeIf { cached.tracks.getOrNull(i)?.id == track.id && !it.isMatchPending }
                    ?: SpotifyCache.getMatch(track.id)
                    ?: track.asPendingSong()
            }.toMutableList()
            val cover = cached?.coverUrl ?: playlist.imageUrl
            fun save() {
                if (isOpen(playlist.id)) return
                val snapshot = synchronized(songs) { songs.toList() }
                SpotifyCache.savePlaylist(playlist.id, playlist.name, cover, tracks, snapshot)
            }
            save()
            if (!matchThis) return@forEachIndexed

            val matcher = SpotifyTrackMatcher(SpotifyImporter::lookUp)
            val gate = Semaphore(MATCH_PARALLEL)
            val done = AtomicInteger()
            coroutineScope {
                songs.indices.filter { songs[it].isMatchPending }.forEach { i ->
                    launch {
                        gate.withPermit {
                            if (isOpen(playlist.id) || budget.decrementAndGet() < 0) return@withPermit
                            val track = tracks[i]
                            val outcome = try {
                                matcher.match(track)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                            val found = SpotifyCache.getMatch(track.id) ?: when (outcome) {
                                is TrackMatch.Found -> outcome.song.also { SpotifyCache.putMatch(track.id, it) }
                                // Looked for and not there; the page asks again when opened.
                                else -> track.asPendingSong().copy(videoId = SPOTIFY_MISSING_PREFIX + track.id)
                            }
                            synchronized(songs) { songs[i] = found }
                            if (done.incrementAndGet() % SAVE_EVERY == 0) save()
                        }
                    }
                }
            }
            save()
        }
    }

    private fun SpotifyTrack.asPendingSong() = Song(
        videoId = SPOTIFY_PENDING_PREFIX + id,
        title = title,
        artist = artist,
        thumbnailUrl = imageUrl,
        durationText = durationMs.takeIf { it > 0 }?.let { ms -> "%d:%02d".format(ms / 60000, ms / 1000 % 60) },
        albumName = album,
    )
}
