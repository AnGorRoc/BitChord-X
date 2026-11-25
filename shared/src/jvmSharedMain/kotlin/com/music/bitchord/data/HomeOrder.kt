package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf

/**
 * The feed in the order Home draws it: the listener's own shelves first, the
 * ones borrowed from New releases and Explore ([HomeShelf.tail]) after them,
 * each group in the order it came. Shelves paged in later still land above
 * the borrowed ones, so those stay at the very end.
 *
 * Shared by the phone's and the PC's Home.
 */
fun List<HomeShelf>.tailLast(): List<HomeShelf> =
    if (none { it.tail }) this else filterNot { it.tail } + filter { it.tail }

/**
 * The whole of Home's feed in the order it is drawn: Recents first when the
 * feed leads with it, then at most two of the [personal] shelves, then the
 * feed with the rest of them worked in every third shelf, and the borrowed
 * shelves ([tailLast]) at the very end. Two of our own above YouTube's feed
 * and no more, so Home stays Home.
 */
fun List<HomeShelf>.homeOrder(personal: List<HomeShelf>): List<HomeShelf> {
    val ordered = tailLast()
    if (personal.isEmpty()) return ordered
    val tail = ordered.filter { it.tail }
    val feed = ordered.filterNot { it.tail }
    val out = ArrayList<HomeShelf>(size + personal.size)
    val startsWithRecents = feed.firstOrNull()?.title.equals(YtMusicRepository.RECENT_TITLE, ignoreCase = true)
    if (startsWithRecents) out += feed.first()
    out += personal.take(PERSONAL_LEAD)
    val rest = ArrayDeque(personal.drop(PERSONAL_LEAD))
    feed.drop(if (startsWithRecents) 1 else 0).forEachIndexed { index, shelf ->
        out += shelf
        if ((index + 1) % PERSONAL_EVERY == 0) rest.removeFirstOrNull()?.let(out::add)
    }
    out += rest
    out += tail
    return out
}

/** How many of our own shelves may come before YouTube's feed. */
private const val PERSONAL_LEAD = 2

/** Past those, one of ours after every this many of the feed's. */
private const val PERSONAL_EVERY = 3
