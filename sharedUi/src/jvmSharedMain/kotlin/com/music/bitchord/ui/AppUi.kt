package com.music.bitchord.ui

import com.music.bitchord.data.model.Song
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.data.settings.SongSwipeAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the shared pages — Home, Explore, Library, Search and the rows they are
 * built from — need from the app around them: a handful of the user's settings
 * and which tracks are on disk. The phone answers from `AppSettings` and
 * `Downloads`, the desktop from its own stores; the pages read the same values
 * either way. Installed once at start, beside [com.music.bitchord.ui.player.PlayerPlatform].
 */
interface AppUiHost {
    /** A swipe on a track row plays it next rather than adding it to the end of the queue. */
    val swipeToPlayNext: StateFlow<Boolean>

    /** Action triggered when swiping a song row from left to right (start to end). */
    val swipeRightAction: StateFlow<SongSwipeAction>
        get() = MutableStateFlow(SongSwipeAction.PLAY_NEXT)

    /** Action triggered when swiping a song row from right to left (end to start). */
    val swipeLeftAction: StateFlow<SongSwipeAction>
        get() = MutableStateFlow(SongSwipeAction.ADD_TO_QUEUE)

    /** Playlist browse ids pinned to the front of the Library's Playlists shelf, in pin order. */
    val pinnedPlaylists: StateFlow<List<String>>

    /** How a Library "Show all" grid is ordered. */
    val librarySort: StateFlow<LibrarySort>

    /** Whether the Library's collections are cards or stacked rows with a small cover. */
    val libraryViewType: StateFlow<LibraryViewType>

    /** Video ids of the tracks already downloaded, for the mark on their rows. */
    @Composable
    fun downloadedIds(): Set<String>

    /**
     * True while the app is in offline mode (either manually or detected network loss).
     */
    @Composable
    fun isOffline(): Boolean = false

    /**
     * True while the app is offline and [song] is not saved on the phone: its row is shown dimmed, and it
     * can be neither played nor queued. False online, and always on the desktop.
     */
    @Composable
    fun unplayableOffline(song: Song): Boolean = false

    /**
     * True while the app is offline and a feed/history item ([videoId] or [browseId])
     * is not saved or cached on the phone.
     */
    @Composable
    fun unplayableOffline(videoId: String?, browseId: String?): Boolean = false

    /**
     * Up to 4 artwork URLs from offline-available songs, used to build the
     * 2x2 collage cover of the generated Offline music playlist.
     */
    @Composable
    fun offlineCoverArtworks(): List<String> = emptyList()

    /**
     * Up to 4 artwork URLs from the songs of the playlist kept on this device under [browseId] (an
     * imported one included), for the same 2x2 collage cover; empty when there is none to draw.
     */
    @Composable
    fun localPlaylistCoverArtworks(browseId: String): List<String> = emptyList()

    /**
     * Whether [browseId] is a copy of Spotify's Liked Songs kept apart from the
     * account's own likes — on the device or as a playlist of its own — and so
     * drawn with [GreenLikedCover][com.music.bitchord.ui.components.GreenLikedCover].
     */
    @Composable
    fun isGreenLikedCover(browseId: String?): Boolean = false

    /**
     * A release's motion cover, for Home's Top Picks — the same lookup the
     * album page makes. Null is the normal answer, and the only one where the
     * user has animated artwork off or the platform has no lookup.
     */
    suspend fun releaseMotion(album: String, artist: String): CanvasArtwork? = null

    /**
     * Apple Music's looping clip of the YouTube artist [browseId], billed as
     * [artistName], for Home's Top Picks. Null as for [releaseMotion].
     */
    suspend fun artistMotion(browseId: String, artistName: String): CanvasArtwork? = null
}

object AppUi {
    @Volatile
    private var installed: AppUiHost? = null

    fun install(host: AppUiHost) {
        installed = host
    }

    val host: AppUiHost
        get() = checkNotNull(installed) { "AppUi.install was not called" }
}

/**
 * Whether the feeds answer a pull at the top with a refresh.
 *
 * On a phone that is a drag with a finger. A window has no such gesture — only
 * the mouse wheel, which scrolling up past the top of a page would turn into a
 * refresh nobody asked for — so the desktop switches it off.
 */
val LocalPullToRefreshEnabled = staticCompositionLocalOf { true }
