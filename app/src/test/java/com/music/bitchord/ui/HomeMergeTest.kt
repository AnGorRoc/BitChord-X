package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a revalidated Home feed lands on the one already on screen. */
class HomeMergeTest {

    private fun shelf(title: String, vararg items: String) = HomeShelf(
        title = title,
        subtitle = "",
        items = items.map { ShelfItem(title = it, subtitle = "", thumbnailUrl = null, videoId = it, browseId = null) },
    )

    private fun List<HomeShelf>.titles() = map { it.title }

    @Test
    fun `shelves on screen keep their order and take their fresh contents`() {
        val current = listOf(shelf("A", "a1"), shelf("B", "b1"), shelf("C", "c1"))
        val fresh = listOf(shelf("C", "c2"), shelf("A", "a2"), shelf("B", "b2"))

        val merged = stableHomeMerge(current, fresh, previousRecent = null, freshRecent = null)

        assertEquals(listOf("A", "B", "C"), merged.titles())
        assertEquals(listOf("a2"), merged[0].items.map { it.title })
    }

    @Test
    fun `dropped shelves leave and new ones go at the end`() {
        val current = listOf(shelf("A"), shelf("B"), shelf("C"))
        val fresh = listOf(shelf("New"), shelf("C"), shelf("A"))

        val merged = stableHomeMerge(current, fresh, previousRecent = null, freshRecent = null)

        assertEquals(listOf("A", "C", "New"), merged.titles())
    }

    @Test
    fun `recents is swapped in where it was, under whatever title it now has`() {
        val oldRecent = shelf("Recently played", "r1")
        val current = listOf(shelf("A"), oldRecent, shelf("B"))
        val newRecent = shelf("Recientes", "r2")
        val fresh = listOf(newRecent, shelf("B"), shelf("A"))

        val merged = stableHomeMerge(current, fresh, previousRecent = oldRecent, freshRecent = newRecent)

        assertEquals(listOf("A", "Recientes", "B"), merged.titles())
    }

    @Test
    fun `a recents that was not on screen leads`() {
        val current = listOf(shelf("A"), shelf("B"))
        val newRecent = shelf("Recently played")
        val fresh = listOf(newRecent, shelf("A"), shelf("B"))

        val merged = stableHomeMerge(current, fresh, previousRecent = null, freshRecent = newRecent)

        assertEquals(listOf("Recently played", "A", "B"), merged.titles())
    }

    @Test
    fun `recents that has gone is dropped`() {
        val oldRecent = shelf("Recently played")
        val current = listOf(oldRecent, shelf("A"))

        val merged = stableHomeMerge(current, listOf(shelf("A")), previousRecent = oldRecent, freshRecent = null)

        assertEquals(listOf("A"), merged.titles())
    }
}
