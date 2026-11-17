package com.music.bitchord.data.listentogether.account

/**
 * Which songs of a queue travel with a hand-over, and where the current one sits among them.
 *
 * A hand-over used to send the current song and what follows, so on the receiving device
 * "previous" had nowhere to go; it also cut the queue at a fixed length without saying so, and sent
 * files that exist only on the sending device, which the other one cannot play. This keeps a few
 * songs behind the current one, bounds the rest, and drops what the receiver could not play.
 */
object HandoffWindow {
    /** How many songs already played go along, so "previous" still works there. */
    const val HISTORY = 20

    /** How many songs after the current one go along; the server accepts 500 in one hand-over. */
    const val UPCOMING = 199

    /**
     * @param indices positions in the sender's queue, in order, of the songs that go.
     * @param index where the current song is among [indices].
     * @param truncated whether songs after the current one were left behind for want of room.
     */
    data class Slice(val indices: List<Int>, val index: Int, val truncated: Boolean)

    /**
     * The window around [current] of a queue whose songs are [ids]; null when the current song
     * itself cannot be handed over (none, or not [playable] elsewhere).
     */
    fun slice(ids: List<String>, current: Int, playable: (String) -> Boolean = { true }): Slice? {
        if (current !in ids.indices || !playable(ids[current])) return null
        val from = (current - HISTORY).coerceAtLeast(0)
        val to = (current + 1 + UPCOMING).coerceAtMost(ids.size)
        val indices = (from until to).filter { playable(ids[it]) }
        return Slice(indices, indices.indexOf(current), truncated = current + 1 + UPCOMING < ids.size)
    }

    /** Files that live on one device only: `content://` and `file://` addresses. */
    fun isDeviceFile(id: String): Boolean = id.startsWith("content://") || id.startsWith("file://")
}
