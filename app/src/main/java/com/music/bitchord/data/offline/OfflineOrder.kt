package com.music.bitchord.data.offline

import com.music.bitchord.data.model.Song

/**
 * The rules for a list while the phone has no connection, kept apart from the Android types so they
 * can be tested. [isPlayable] answers for one song; every function here is a view of the list it is
 * given and never changes it, so what the listener built is back the moment the connection is.
 */

/** Whether [uri] names a file on this phone, as opposed to a server somewhere. */
internal fun isOnDeviceUri(uri: String?): Boolean =
    uri != null && (uri.startsWith("content://") || uri.startsWith("file://") || uri.startsWith("/"))

/**
 * The songs that can be played now first, the rest after, each group in the order it already had.
 * Used for a playlist while offline: what plays is on top, what does not is below, dimmed.
 */
internal fun List<Song>.offlineFirst(isPlayable: (Song) -> Boolean): List<Song> {
    if (isEmpty()) return this
    val (playable, rest) = partition(isPlayable)
    return if (playable.isEmpty() || rest.isEmpty()) this else playable + rest
}

/** What a tap on one row queues while offline: the playable songs, and where the tapped one landed. */
internal data class OfflineQueue(val songs: List<Song>, val index: Int)

/**
 * The queue to build when [index] is tapped in [songs] with no connection: only the songs that can be
 * played, so nothing is queued that would have to be skipped. Null when the tapped song itself cannot
 * be played, in which case the tap does nothing.
 */
internal fun List<Song>.offlineQueue(index: Int, isPlayable: (Song) -> Boolean): OfflineQueue? {
    val tapped = getOrNull(index) ?: return null
    if (!isPlayable(tapped)) return null
    val playable = filter(isPlayable)
    // The tapped song is an element of [playable], so this finds it; by identity first, since a list
    // can hold the same track twice.
    val position = playable.indexOfFirst { it === tapped }.takeIf { it >= 0 } ?: playable.indexOf(tapped)
    return OfflineQueue(playable, position.coerceAtLeast(0))
}

/**
 * Downloaded songs first, then the cached ones the downloads do not already cover: the content of the
 * Offline music playlist. A song in both appears once, as the download.
 */
internal fun mergeOffline(downloaded: List<Song>, cached: List<Song>): List<Song> {
    val seen = HashSet<String>()
    return (downloaded + cached).filter { seen.add(it.videoId) }
}
