package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Home's own shelves, built from what this device has played rather than
 * fetched as a page: Continue listening, Listen again, Because you listened to
 * X, and New from your artists.
 *
 * Each is drawn with the ordinary shelf components, and only when it has
 * enough to be a shelf ([MIN_ITEMS]); a thin one takes no room. Titles are
 * keys ([CONTINUE], [LISTEN_AGAIN], [BECAUSE] + name, [NEW_FROM_ARTISTS]) that
 * the UI turns into the app's language.
 */
object PersonalShelves {

    private const val TAG = "BitChord"

    const val CONTINUE = "bc:continue"
    const val LISTEN_AGAIN = "bc:listen_again"
    const val BECAUSE = "bc:because:"
    const val NEW_FROM_ARTISTS = "bc:new_from_artists"

    /** Fewer cards than this and the shelf is not shown. */
    const val MIN_ITEMS = 4

    private const val MAX_ITEMS = 12
    private const val NEW_RELEASE_ARTISTS = 4

    /** Every shelf that has enough to show, in the order Home offers them. */
    suspend fun build(): List<HomeShelf> = coroutineScope {
        val recentAsync = async { runCatching { ListeningStats.recent(80) }.getOrDefault(emptyList()) }
        val quietAsync = async { runCatching { ListeningStats.lovedButQuiet() }.getOrDefault(emptyList()) }
        val weekAsync = async { runCatching { ListeningStats.summary(ReplayPeriod.THIS_WEEK) }.getOrNull() }
        val monthAsync = async { runCatching { ListeningStats.summary(ReplayPeriod.THIS_MONTH) }.getOrNull() }

        val recent = recentAsync.await()
        val played = recent.map { it.first.videoId }.toSet()
        val because = async { becauseYouListened(weekAsync.await()?.songs?.firstOrNull()?.song, played) }
        val fresh = async {
            newFromArtists(monthAsync.await()?.artists.orEmpty().mapNotNull { it.browseId }.take(NEW_RELEASE_ARTISTS))
        }
        listOfNotNull(
            continueListening(recent.map { it.first }),
            because.await(),
            listenAgain(quietAsync.await()),
            fresh.await(),
        ).filter { it.items.size >= MIN_ITEMS }
            .also { shelves -> Log.d(TAG, "personal shelves: ${shelves.map { it.title to it.items.size }}") }
    }

    /** The albums of what was played last, newest first, to pick up where it was left. */
    fun continueListening(recent: List<Song>): HomeShelf? {
        val albums = recent
            .filter { !it.albumId.isNullOrBlank() && !it.albumName.isNullOrBlank() }
            .distinctBy { it.albumId }
            .take(MAX_ITEMS)
            .map { song ->
                ShelfItem(
                    title = song.albumName.orEmpty(),
                    subtitle = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = null,
                    browseId = song.albumId,
                )
            }
        return albums.takeIf { it.isNotEmpty() }?.let { HomeShelf(CONTINUE, it, personal = true) }
    }

    /** Songs played a lot, and not lately. */
    fun listenAgain(songs: List<Song>): HomeShelf? =
        songs.takeIf { it.isNotEmpty() }?.let { list ->
            HomeShelf(LISTEN_AGAIN, list.take(MAX_ITEMS).map(::songItem), layout = ShelfLayout.LIST, personal = true)
        }

    /** A radio off the song of the week, minus what has been played already. */
    private suspend fun becauseYouListened(seed: Song?, played: Set<String>): HomeShelf? {
        if (seed == null || seed.videoId.contains("://") || seed.videoId.startsWith("local:")) return null
        val radio = YtMusicRepository.radio(seed.videoId).getOrNull().orEmpty()
        val items = radio.filter { it.videoId != seed.videoId && it.videoId !in played }
            .distinctBy { it.videoId }
            .take(MAX_ITEMS)
            .map(::songItem)
        val name = seed.artist.substringBefore(",").substringBefore(" & ").trim().ifBlank { seed.title }
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(BECAUSE + name, it, personal = true) }
    }

    /** The newest release on each of the month's most played artists' pages. */
    private suspend fun newFromArtists(artistIds: List<String>): HomeShelf? = coroutineScope {
        val pages = artistIds.map { id -> async { YtMusicRepository.artistLanding(id).getOrNull() } }.awaitAll()
        val releases = pages.filterNotNull().flatMap { page ->
            page.sections
                .mapNotNull { shelf -> shelf.items.firstOrNull()?.takeIf { it.browseId?.startsWith("MPRE") == true } }
                .take(2)
        }
            .distinctBy { it.browseId }
            // Newest year first; YouTube writes it into the subtitle.
            .sortedByDescending { item -> YEAR.find(item.subtitle)?.value?.toIntOrNull() ?: 0 }
            .take(MAX_ITEMS)
        releases.takeIf { it.isNotEmpty() }?.let { HomeShelf(NEW_FROM_ARTISTS, it, personal = true) }
    }

    private val YEAR = Regex("""\b(19|20)\d{2}\b""")

    private fun songItem(song: Song) = ShelfItem(
        title = song.title,
        subtitle = song.artist,
        thumbnailUrl = song.thumbnailUrl,
        videoId = song.videoId,
        browseId = null,
    )
}
