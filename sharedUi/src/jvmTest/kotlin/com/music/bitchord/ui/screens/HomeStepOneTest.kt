package com.music.bitchord.ui.screens

import com.music.bitchord.data.HomeGreeting
import com.music.bitchord.data.homeOrder
import com.music.bitchord.data.tailLast
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.selectSpotlightSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/** Home's first round of changes: the greeting, the borrowed shelves last, a spotlight that does not repeat. */
class HomeStepOneTest {

    private val now = LocalDateTime.of(2026, 11, 25, 9, 0) // a Wednesday morning
    private val nowMs = 1_800_000_000_000L
    private val day = 24L * 3_600_000

    private fun pick(
        at: LocalDateTime = now,
        name: String? = null,
        lastPlayed: Long? = nowMs - 3_600_000,
        artist: String? = "Dei V",
        previous: HomeGreeting.Kind? = null,
        seed: Int = 1,
    ) = HomeGreeting.pick(at, name, lastPlayed, artist, nowMs, previous, Random(seed))

    // ---- The greeting --------------------------------------------------------

    @Test
    fun `a long absence is a welcome back`() {
        assertEquals(HomeGreeting.Kind.WELCOME_BACK, pick(lastPlayed = nowMs - 4 * day).kind)
    }

    @Test
    fun `the hour decides which greeting of the day`() {
        val kinds = (0..40).map { pick(at = now.withHour(3), artist = null, seed = it).kind }.toSet()
        assertEquals(setOf(HomeGreeting.Kind.LATE_NIGHT), kinds)
    }

    @Test
    fun `it never repeats the last one when there is another to say`() {
        repeat(40) { seed ->
            assertNotEquals(HomeGreeting.Kind.MORNING, pick(previous = HomeGreeting.Kind.MORNING, seed = seed).kind)
        }
    }

    @Test
    fun `friday and the last artist are among what it can say`() {
        val friday = now.withDayOfMonth(27) // a Friday
        val kinds = (0..60).map { pick(at = friday, seed = it).kind }.toSet()
        assertTrue(HomeGreeting.Kind.FRIDAY in kinds)
        assertTrue(HomeGreeting.Kind.CONTINUE_ARTIST in kinds)
    }

    @Test
    fun `an old last play does not offer to continue that artist`() {
        val kinds = (0..40).map { pick(lastPlayed = nowMs - 2 * day - 1, seed = it).kind }.toSet()
        assertTrue(HomeGreeting.Kind.CONTINUE_ARTIST !in kinds)
    }

    @Test
    fun `a name is used when there is one`() {
        assertTrue(pick(name = "Pedro").named)
        assertTrue(!pick(name = null).named)
    }

    // ---- Borrowed shelves last ----------------------------------------------

    private fun shelf(title: String, vararg ids: String, tail: Boolean = false) = HomeShelf(
        title = title,
        items = ids.map { ShelfItem(title = it, subtitle = "Artist", thumbnailUrl = null, videoId = it, browseId = null) },
        tail = tail,
    )

    @Test
    fun `shelves borrowed from Explore go after the feed`() {
        val ordered = listOf(
            shelf("Trending", "t", tail = true),
            shelf("Quick picks", "q"),
            shelf("Albums & singles", "a", tail = true),
            shelf("Mixed for you", "m"),
        ).tailLast().map { it.title }

        assertEquals(listOf("Quick picks", "Mixed for you", "Trending", "Albums & singles"), ordered)
    }

    // ---- Our own shelves (step 2) ---------------------------------------------

    private fun mine(title: String) = HomeShelf(title, listOf(ShelfItem(title, "", null, "v$title", null)), personal = true)

    @Test
    fun `two of ours lead after Recents, the rest every third feed shelf, borrowed ones last`() {
        val feed = listOf(
            shelf("Recents", "r"),
            shelf("F1", "1"), shelf("F2", "2"), shelf("F3", "3"), shelf("F4", "4"),
            shelf("Trending", "t", tail = true),
        )
        val order = feed.homeOrder(listOf(mine("P1"), mine("P2"), mine("P3"), mine("P4"))).map { it.title }

        assertEquals(listOf("Recents", "P1", "P2", "F1", "F2", "F3", "P3", "F4", "P4", "Trending"), order)
    }

    @Test
    fun `without shelves of ours the order is the feed's`() {
        val feed = listOf(shelf("F1", "1"), shelf("Trending", "t", tail = true), shelf("F2", "2"))
        assertEquals(listOf("F1", "F2", "Trending"), feed.homeOrder(emptyList()).map { it.title })
    }

    @Test
    fun `continue listening is the albums of the last plays, newest first, once each`() {
        fun song(id: String, album: String?) = com.music.bitchord.data.model.Song(
            videoId = id, title = id, artist = "A", thumbnailUrl = null, albumId = album?.let { "MPRE$it" }, albumName = album,
        )
        val shelf = com.music.bitchord.data.PersonalShelves.continueListening(
            listOf(song("1", "Bad"), song("2", "Bad"), song("3", null), song("4", "Thriller")),
        )
        assertEquals(listOf("Bad", "Thriller"), shelf?.items?.map { it.title })
        assertTrue(shelf?.personal == true)
    }

    // ---- The spotlight -------------------------------------------------------

    @Test
    fun `the spotlight skips what the shelf under it shows`() {
        val shelves = listOf(shelf("Quick picks", "a", "b"), shelf("New releases", "c"))

        val picked = selectSpotlightSong(shelves, avoid = setOf("a", "b"))

        assertEquals("c", picked?.videoId)
    }
}
