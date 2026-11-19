package com.music.bitchord.desktop

import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isMatchPending
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.spotify.SpotifyTrackMatcher
import com.music.bitchord.data.spotify.TrackMatch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections

/** The Library row that opens the Spotify account's playlists; the phone's browse id. */
internal const val SPOTIFY_LIBRARY_ID = "app:spotify"

/** The Library grid's title for those playlists, which is also how the grid is told apart. */
internal const val SPOTIFY_SHELF = "Spotify"

/** Matches looked up at once; the phone's figure, gentle enough on YouTube Music's search. */
private const val MATCH_PARALLELISM = 4

/** A card on the Spotify grid, opening a [DesktopCollection] of its tracks. */
internal fun SpotifyPlaylist.toShelfItem() = ShelfItem(
    title = name,
    subtitle = owner ?: SPOTIFY_SHELF,
    thumbnailUrl = imageUrl,
    videoId = null,
    browseId = SPOTIFY_PAGE_PREFIX + id,
)

/** A row for a Spotify track whose YouTube Music version has not been found yet. */
internal fun SpotifyTrack.asPendingSong() = Song(
    videoId = SPOTIFY_PENDING_PREFIX + id,
    title = title,
    artist = artist,
    thumbnailUrl = imageUrl,
    durationText = durationMs.takeIf { it > 0 }?.let { ms -> "%d:%02d".format(ms / 60000, ms / 1000 % 60) },
    albumName = album,
)

internal object DesktopSpotifyCache {
    private const val KEY_MATCHES = "spotify_track_matches_v1"
    private const val MAX_MATCHES = 2000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val persistence = DesktopPersistence()

    @Serializable
    private data class CachedMatch(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val artistId: String? = null,
        val albumId: String? = null,
        val albumName: String? = null,
        val isExplicit: Boolean? = null,
    ) {
        fun toSong(): Song = Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationText = durationText,
            artistId = artistId,
            albumId = albumId,
            albumName = albumName,
            isExplicit = isExplicit,
        )

        companion object {
            fun fromSong(song: Song): CachedMatch = CachedMatch(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationText = song.durationText,
                artistId = song.artistId,
                albumId = song.albumId,
                albumName = song.albumName,
                isExplicit = song.isExplicit,
            )
        }
    }

    private val matches: MutableMap<String, Song> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Song>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>): Boolean =
                size > MAX_MATCHES
        },
    )

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = persistence.string(KEY_MATCHES)
            if (raw.isNotBlank()) {
                runCatching {
                    val decoded = json.decodeFromString<Map<String, CachedMatch>>(raw)
                    decoded.forEach { (id, cached) -> matches[id] = cached.toSong() }
                }
            }
            loaded = true
        }
    }

    fun getMatch(trackId: String): Song? {
        ensureLoaded()
        return matches[trackId]
    }

    fun putMatch(trackId: String, song: Song) {
        if (trackId.isBlank() || song.isUnresolvedSpotify) return
        ensureLoaded()
        matches[trackId] = song
    }

    // ---- Playlists, kept so the library and each playlist open at once --------------------

    @Serializable
    private data class KeptPlaylist(val id: String, val name: String, val owner: String? = null, val imageUrl: String? = null)

    private val folder: java.io.File by lazy {
        DesktopMediaCache.directory.parent.resolve("spotify_cache").toFile().apply { mkdirs() }
    }

    private fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** The account's playlists as last read, or null when never read. */
    fun playlists(): List<SpotifyPlaylist>? = runCatching {
        val file = java.io.File(folder, "playlists.json")
        if (!file.isFile) return null
        json.decodeFromString<List<KeptPlaylist>>(file.readText()).map { SpotifyPlaylist(it.id, it.name, it.owner, it.imageUrl) }
    }.getOrNull()

    fun savePlaylists(playlists: List<SpotifyPlaylist>) {
        runCatching {
            val kept = playlists.map { KeptPlaylist(it.id, it.name, it.owner, it.imageUrl) }
            java.io.File(folder, "playlists.json").writeText(json.encodeToString(kept))
        }
    }

    /** One playlist's rows as last shown — matched songs, and those still waiting — or null. */
    fun playlistRows(playlistId: String): List<Song>? = runCatching {
        val file = java.io.File(folder, "playlist_${safeName(playlistId)}.json")
        if (!file.isFile) return null
        json.decodeFromString<List<CachedMatch>>(file.readText()).map { it.toSong() }
    }.getOrNull()

    fun savePlaylistRows(playlistId: String, rows: List<Song>) {
        if (rows.isEmpty()) return
        runCatching {
            val kept = rows.map(CachedMatch::fromSong)
            java.io.File(folder, "playlist_${safeName(playlistId)}.json").writeText(json.encodeToString(kept))
        }
        flush()
    }

    fun clearPlaylists() {
        folder.listFiles()?.forEach { it.delete() }
    }

    fun flush() {
        if (!loaded) return
        val snapshot = synchronized(matches) {
            matches.mapValues { (_, song) -> CachedMatch.fromSong(song) }
        }
        runCatching {
            persistence.saveString(KEY_MATCHES, json.encodeToString(snapshot))
        }
    }
}

private val spotifyTrackMatcher = SpotifyTrackMatcher(SpotifyImporter::lookUp)

/**
 * Fills a Spotify playlist page the way the phone's `MainViewModel` does: Spotify's tracks as each
 * page of them arrives (immediately resolved from [DesktopSpotifyCache] when known), then every
 * remaining row swapped in place for its YouTube Music match with retry/backoff via
 * [SpotifyTrackMatcher], or marked as having none. [update] applies a change to the page if it is
 * still the one open.
 */
internal suspend fun loadSpotifyPlaylist(
    playlistId: String,
    update: ((DesktopCollection) -> DesktopCollection) -> Unit,
) = coroutineScope {
    launch {
        // The library card only had a thumbnail.
        runCatching { SpotifyLibrary.cover(playlistId) }.getOrNull()
            ?.let { cover -> update { it.copy(thumbnailUrl = cover) } }
    }
    val tracks = SpotifyLibrary.tracks(playlistId) { soFar ->
        val initialRows = soFar.map { track ->
            DesktopSpotifyCache.getMatch(track.id)
                ?.let { cached -> cached.copy(thumbnailUrl = cached.thumbnailUrl ?: track.imageUrl) }
                ?: track.asPendingSong()
        }
        update { it.copy(songs = initialRows, loading = false) }
    }
    check(tracks.isNotEmpty()) { "This Spotify playlist has no tracks." }
    // Matches found before the page is left are worth keeping, not only those of a finished run.
    coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { DesktopSpotifyCache.flush() }
    val gate = Semaphore(MATCH_PARALLELISM)
    var dirty = false
    tracks.forEachIndexed { index, track ->
        val cached = DesktopSpotifyCache.getMatch(track.id)
        if (cached != null) {
            val row = cached.copy(thumbnailUrl = cached.thumbnailUrl ?: track.imageUrl)
            update { page ->
                if (index < page.songs.size) {
                    page.copy(songs = page.songs.toMutableList().also { it[index] = row })
                } else {
                    page
                }
            }
            return@forEachIndexed
        }
        launch {
            gate.withPermit {
                val outcome = spotifyTrackMatcher.match(track)
                val match = (outcome as? TrackMatch.Found)?.song
                if (match != null && !match.isUnresolvedSpotify) {
                    DesktopSpotifyCache.putMatch(track.id, match)
                    dirty = true
                }
                val row = match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.imageUrl)
                    ?: track.asPendingSong().copy(videoId = SPOTIFY_MISSING_PREFIX + track.id)
                update { page ->
                    if (index < page.songs.size) {
                        page.copy(songs = page.songs.toMutableList().also { it[index] = row })
                    } else {
                        page
                    }
                }
            }
        }
    }
    if (dirty) {
        DesktopSpotifyCache.flush()
    }
}

/**
 * Reads the Spotify library ahead of the listener, the desktop's counterpart of the phone's
 * `SpotifyPrefetch`: once per run, a moment after start, the playlist list is read and kept, every
 * playlist with nothing kept has its tracks kept (matched where a lookup already found them), and
 * the most recent ones are matched within a budget — every match is a YouTube Music search.
 * A playlist open on screen ([isOpen]) is left to its page.
 */
internal object DesktopSpotifyPrefetch {
    private const val SETTLE_MS = 6_000L
    private const val MATCH_PLAYLISTS = 10
    private const val MATCH_BUDGET = 500
    private const val MAX_PLAYLISTS = 50
    private const val SAVE_EVERY = 20

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var job: kotlinx.coroutines.Job? = null

    fun start(isOpen: (String) -> Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            kotlinx.coroutines.delay(SETTLE_MS)
            try {
                run(isOpen)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DesktopTrackLog.log("spotify prefetch stopped: ${e.message}")
            } finally {
                DesktopSpotifyCache.flush()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(isOpen: (String) -> Boolean) {
        val playlists = runCatching { SpotifyLibrary.playlists() }.getOrNull()
            ?.also(DesktopSpotifyCache::savePlaylists)
            ?: DesktopSpotifyCache.playlists()
            ?: return
        val budget = java.util.concurrent.atomic.AtomicInteger(MATCH_BUDGET)
        playlists.take(MAX_PLAYLISTS).forEachIndexed { index, playlist ->
            if (isOpen(playlist.id)) return@forEachIndexed
            val kept = DesktopSpotifyCache.playlistRows(playlist.id)
            val matchThis = index < MATCH_PLAYLISTS && budget.get() > 0
            if (!kept.isNullOrEmpty() && (!matchThis || kept.none { it.isMatchPending })) return@forEachIndexed
            val tracks = runCatching { SpotifyLibrary.tracks(playlist.id) }.getOrNull() ?: return@forEachIndexed
            if (tracks.isEmpty()) return@forEachIndexed
            val rows = tracks.map { track ->
                DesktopSpotifyCache.getMatch(track.id)
                    ?.let { it.copy(thumbnailUrl = it.thumbnailUrl ?: track.imageUrl) }
                    ?: track.asPendingSong()
            }.toMutableList()
            fun save() {
                if (isOpen(playlist.id)) return
                DesktopSpotifyCache.savePlaylistRows(playlist.id, synchronized(rows) { rows.toList() })
            }
            save()
            if (!matchThis) return@forEachIndexed
            val gate = Semaphore(2)
            val done = java.util.concurrent.atomic.AtomicInteger()
            coroutineScope {
                rows.indices.filter { rows[it].isMatchPending }.forEach { i ->
                    launch {
                        gate.withPermit {
                            if (isOpen(playlist.id) || budget.decrementAndGet() < 0) return@withPermit
                            val track = tracks[i]
                            val match = runCatching { spotifyTrackMatcher.match(track) }.getOrNull()
                                .let { it as? TrackMatch.Found }?.song
                                ?.takeIf { !it.isUnresolvedSpotify }
                            match?.let { DesktopSpotifyCache.putMatch(track.id, it) }
                            val row = match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.imageUrl)
                                ?: track.asPendingSong().copy(videoId = SPOTIFY_MISSING_PREFIX + track.id)
                            synchronized(rows) { rows[i] = row }
                            if (done.incrementAndGet() % SAVE_EVERY == 0) save()
                        }
                    }
                }
            }
            save()
        }
    }
}
