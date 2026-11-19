package com.music.bitchord.ui

import com.music.bitchord.data.offline.OfflineLibrary
import com.music.bitchord.data.model.Song
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.AppleCatalogue
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads

/** The shared pages' settings and downloads, answered from the phone's own stores. */
object AndroidAppUiHost : AppUiHost {
    override val swipeToPlayNext = AppSettings.swipeToPlayNext
    override val swipeRightAction = AppSettings.swipeRightAction
    override val swipeLeftAction = AppSettings.swipeLeftAction
    override val pinnedPlaylists = AppSettings.pinnedPlaylists
    override val librarySort = AppSettings.librarySort
    override val libraryViewType = AppSettings.libraryViewType

    @Composable
    override fun downloadedIds(): Set<String> {
        val saved by Downloads.saved.collectAsStateWithLifecycle()
        return saved.keys
    }

    @Composable
    override fun unplayableOffline(song: Song): Boolean {
        val playable by OfflineLibrary.playable.collectAsStateWithLifecycle()
        return !OfflineLibrary.isPlayable(song, playable)
    }

    @Composable
    override fun unplayableOffline(videoId: String?, browseId: String?): Boolean {
        val playable by OfflineLibrary.playable.collectAsStateWithLifecycle()
        val collections by Downloads.collections.collectAsStateWithLifecycle()
        val activePlayable = playable ?: return false
        if (!videoId.isNullOrBlank()) {
            return videoId !in activePlayable
        }
        if (!browseId.isNullOrBlank()) {
            if (browseId.startsWith("local:") || OfflinePlaylists.isOfflinePlaylist(browseId)) {
                return false
            }
            val collection = collections[browseId] ?: collections[browseId.removePrefix("VL")]
            if (collection != null && collection.videoIds.any { it in activePlayable }) {
                return false
            }
            return true
        }
        return true
    }

    @Composable
    override fun offlineCoverArtworks(): List<String> {
        val playable by OfflineLibrary.playable.collectAsStateWithLifecycle()
        val context = LocalContext.current.applicationContext
        val artworks by produceState<List<String>>(initialValue = emptyList(), playable) {
            if (playable != null) {
                value = OfflineLibrary.offlineMusic(context)
                    .mapNotNull { it.thumbnailUrl?.takeIf(String::isNotBlank) }
                    .distinct()
                    .take(4)
            }
        }
        return artworks
    }

    override suspend fun releaseMotion(album: String, artist: String): CanvasArtwork? {
        if (!AppSettings.animatedCanvas.value || album.isBlank() || artist.isBlank()) return null
        return CanvasRepository.canvasForAlbum(album, artist)
    }

    /**
     * The clip the artist page plays, off the same proven Apple lookup — so
     * opening the artist from the card costs nothing more, and a card never
     * moves with somebody else's face. The YouTube page it is proved against is
     * fetched only when that lookup hasn't settled already.
     */
    override suspend fun artistMotion(browseId: String, artistName: String): CanvasArtwork? {
        if (!AppSettings.animatedCanvas.value) return null
        val art = AppleArtistArtRepository.artFor(browseId, artistName) {
            YtMusicRepository.artistLanding(browseId).getOrNull()?.let(AppleCatalogue::titlesOf).orEmpty()
        }
        return art?.videoUrl?.let { CanvasArtwork(url = it) }
    }
}
