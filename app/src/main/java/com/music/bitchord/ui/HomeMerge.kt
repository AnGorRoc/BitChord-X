package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import java.util.Locale

/**
 * A revalidated Home feed laid over the one on screen without rearranging it.
 *
 * Each shelf on screen keeps its place and takes its fresh contents. Recently
 * played ([previousRecent] on screen, [freshRecent] fetched) is swapped in
 * where the old one was, whatever YouTube titles it now. Shelves the fresh
 * feed dropped leave, and ones it added go at the end, below where anyone is
 * reading. The fresh order itself is for the next open.
 */
internal fun stableHomeMerge(
    current: List<HomeShelf>,
    fresh: List<HomeShelf>,
    previousRecent: HomeShelf?,
    freshRecent: HomeShelf?,
): List<HomeShelf> {
    fun HomeShelf.key() = title.lowercase(Locale.ROOT)
    val recentKey = freshRecent?.key()
    val freshByKey = fresh.filter { it.key() != recentKey }.associateBy { it.key() }
    val kept = current.mapNotNull { shelf ->
        if (previousRecent != null && shelf === previousRecent) freshRecent else freshByKey[shelf.key()]
    }
    val keptKeys = kept.mapTo(HashSet()) { it.key() }
    val added = fresh.filter { it.key() !in keptKeys }
    // A Recents that was not on screen before leads, as it does in a fresh feed.
    return if (freshRecent != null && freshRecent.key() !in keptKeys) {
        listOf(freshRecent) + kept + added.filter { it !== freshRecent }
    } else {
        kept + added
    }
}
