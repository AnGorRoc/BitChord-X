package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.LibraryPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads, ahead of the listener, the pages they are going to open — so that opening one is showing
 * it rather than waiting on YouTube for it.
 *
 * Only what is missing: a page already kept in [BrowseCache] is left alone here, because opening
 * it shows the kept copy at once and checks it behind (that is the page's job, not this one's).
 * What is known to be wanted comes first — Liked Music, then the library's playlists, its albums,
 * its artists — and the work runs two requests at a time, a moment after the app has settled, so
 * it never competes with what is on screen.
 *
 * Both applications get this for free: [YtMusicRepository.library] and
 * [YtMusicRepository.userPlaylists] hand their results here as they land.
 */
object BrowsePrefetcher {

    private const val TAG = "BitChord"

    /** Let the screen that asked for the library draw before anything is read behind it. */
    private const val SETTLE_MS = 4_000L
    /** A breath between background reads, so YouTube sees a listener rather than a crawler. */
    private const val PAUSE_MS = 350L
    private const val PARALLEL = 2

    const val MAX_PLAYLISTS = 80
    const val MAX_ALBUMS = 40
    /** Artists read whole — songs and discography — rather than only their landing. */
    const val MAX_FULL_ARTISTS = 12
    const val MAX_ARTISTS = 60

    /** Turned off by an application that should not read ahead (a metered connection, say). */
    @Volatile
    var enabled: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLEL)
    private val queued = ConcurrentHashMap.newKeySet<String>()

    /** The library just loaded: everything on it that has never been read is read now. */
    fun onLibrary(page: LibraryPage) {
        val byTitle = page.shelves.associateBy { it.title }
        val playlists = buildList {
            add(YtMusicRepository.LIKED_MUSIC)
            byTitle[YtMusicRepository.PLAYLISTS_SHELF]?.items?.mapNotNullTo(this) { it.browseId }
        }
        warmCollections(playlists.take(MAX_PLAYLISTS))
        warmCollections(byTitle["Albums"]?.items?.mapNotNull { it.browseId }.orEmpty().take(MAX_ALBUMS))
        val artists = listOfNotNull(byTitle["Artists"], byTitle["Subscriptions"])
            .flatMap { shelf -> shelf.items.mapNotNull { it.browseId } }
            .filter { it.startsWith("UC") }
            .distinct()
            .take(MAX_ARTISTS)
        warmArtists(artists.take(MAX_FULL_ARTISTS), full = true)
        warmArtists(artists.drop(MAX_FULL_ARTISTS), full = false)
    }

    /** Albums and playlists, each read whole and kept unless it already is. */
    fun warmCollections(browseIds: List<String>) {
        browseIds.distinct().filter(YtMusicRepository::isKeptCollection).forEach { id ->
            enqueue("c:$id") {
                if (BrowseCache.hasCollection(id)) return@enqueue
                YtMusicRepository.collectionFull(id)
                    .onFailure { Log.d(TAG, "prefetch: collection $id not read: ${it.message}") }
            }
        }
    }

    /**
     * Artists' pages: [full] reads songs and discography too (several requests); otherwise only
     * the landing (one), which is already enough for the page to open on — it reads the rest itself.
     */
    fun warmArtists(browseIds: List<String>, full: Boolean) {
        browseIds.distinct().forEach { id ->
            enqueue("a:$full:$id") {
                val kept = BrowseCache.artist(id)
                if (kept != null && (kept.complete || !full)) return@enqueue
                val result = if (full) YtMusicRepository.artistPage(id) else YtMusicRepository.artistLanding(id)
                result.onFailure { Log.d(TAG, "prefetch: artist $id not read: ${it.message}") }
            }
        }
    }

    /**
     * One artist about to be wanted — the one playing, say. Read whole straight away, ahead of the
     * background queue, and only if nothing whole is kept or what is kept is no longer current.
     */
    fun warmArtistNow(browseId: String) {
        if (!enabled || !browseId.startsWith("UC")) return
        val kept = BrowseCache.artist(browseId)
        if (kept != null && kept.complete && kept.ageMs() < YtMusicRepository.ARTIST_FRESH_MS) return
        scope.launch { runCatching { YtMusicRepository.artistPage(browseId) } }
    }

    /** One album about to be wanted; read only if never read. */
    fun warmCollectionNow(browseId: String) {
        if (!enabled || !YtMusicRepository.isKeptCollection(browseId)) return
        if (BrowseCache.hasCollection(browseId)) return
        scope.launch { runCatching { YtMusicRepository.collectionFull(browseId) } }
    }

    private fun enqueue(tag: String, work: suspend () -> Unit) {
        if (!enabled) return
        val owner = Innertube.cacheScope()
        val key = "$owner:$tag"
        if (!queued.add(key)) return
        scope.launch {
            try {
                delay(SETTLE_MS)
                gate.withPermit {
                    // Signed out or switched account meanwhile: this was the other one's to read.
                    if (!enabled || Innertube.cacheScope() != owner) return@withPermit
                    work()
                    delay(PAUSE_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "prefetch: $tag failed: ${e.message}")
            } finally {
                queued.remove(key)
            }
        }
    }
}
