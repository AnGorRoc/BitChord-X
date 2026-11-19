package com.music.bitchord.data.offline

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What can still be played with no connection.
 *
 * [playable] is null while online, and everything is playable then. While offline (no network, or the
 * manual offline mode) it is the ids of the songs saved on the phone: the downloads and the songs the
 * cache holds from first byte to last. Everything that reacts to being offline reads this one value, so
 * the whole app changes at once when the connection goes, and changes back at once when it returns.
 */
object OfflineLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cachedIds = MutableStateFlow<Set<String>>(emptySet())

    val playable: StateFlow<Set<String>?> = combine(
        AppSettings.effectiveOffline,
        Downloads.saved,
        cachedIds,
    ) { offline, saved, cached -> if (offline) saved.keys + cached else null }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Reads the cache again whenever it goes offline and whenever the cache is emptied. */
    fun start() {
        scope.launch {
            AppSettings.effectiveOffline.collect { if (it) refreshCache() }
        }
        scope.launch {
            AudioCache.contentsChanged.drop(1).collect { refreshCache() }
        }
    }

    suspend fun refreshCache() {
        cachedIds.value = AudioCache.cachedSongs().filter { it.complete }.mapTo(HashSet()) { it.song.videoId }
    }

    /** Whether [song] can be played right now. */
    fun isPlayable(song: Song): Boolean = isPlayable(song, playable.value)

    fun isPlayable(song: Song, playable: Set<String>?): Boolean =
        playable == null || isOnDeviceUri(song.localUri) || song.videoId in playable

    /** [songs] as a playlist shows them offline: what plays first. Online it is [songs] itself. */
    fun arranged(songs: List<Song>, playable: Set<String>?): List<Song> =
        if (playable == null) songs else songs.offlineFirst { isPlayable(it, playable) }

    /** The queue for a tap on [index] of [songs] while offline, or null when that song cannot be played. */
    fun queueOffline(songs: List<Song>, index: Int, playable: Set<String>): Pair<List<Song>, Int>? =
        songs.offlineQueue(index) { isPlayable(it, playable) }?.let { it.songs to it.index }

    /** The songs of the Offline music playlist: the downloads, then what the cache holds whole. */
    suspend fun offlineMusic(context: android.content.Context): List<Song> {
        refreshCache()
        val downloaded = Downloads.getDownloadedSongs(context)
        val cached = AudioCache.cachedSongs().filter { it.complete }.map { it.song }
        return mergeOffline(downloaded, cached)
    }
}
