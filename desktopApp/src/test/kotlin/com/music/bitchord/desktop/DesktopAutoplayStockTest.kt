package com.music.bitchord.desktop

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopAutoplayStockTest {

    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT) =
        Song(id, id.uppercase(), "Artist", null, queueTier = tier)

    private fun auto(id: String) = song(id, QueueTier.AUTOPLAY)

    @Test
    fun theBaseIsTheLastHandPickedTrackStillAhead() {
        val songs = listOf(song("a"), song("b"), song("c"), auto("x"), auto("y"))
        assertEquals("c", DesktopAutoplay.baseOf(songs, index = 0)?.videoId)
    }

    @Test
    fun whenOnlyAutoplayIsLeftThereIsNoBase() {
        val songs = listOf(song("a"), auto("x"), auto("y"))
        assertNull(DesktopAutoplay.baseOf(songs, index = 1))
    }

    @Test
    fun aTrackPlayedOnItsOwnIsItsOwnBase() {
        assertEquals("a", DesktopAutoplay.baseOf(listOf(song("a")), index = 0)?.videoId)
    }

    @Test
    fun noPlayingTrackMeansNoBase() {
        assertNull(DesktopAutoplay.baseOf(listOf(song("a")), index = -1))
        assertNull(DesktopAutoplay.baseOf(emptyList(), index = 0))
    }

    @Test
    fun theStationIsAskedForAgainOnlyOnceTheStockIsDownToAFullQueue() {
        assertEquals(15, DesktopAutoplay.neededFor(queuedAhead = 0, reserved = 0))
        assertEquals(5, DesktopAutoplay.neededFor(queuedAhead = 10, reserved = 0))
        // A full queue and a reserve of five is a full stock: nothing to ask for.
        assertEquals(0, DesktopAutoplay.neededFor(queuedAhead = 10, reserved = 5))
        assertEquals(0, DesktopAutoplay.neededFor(queuedAhead = 8, reserved = 7))
        // Once the queue draws the reserve down to ten, the next request tops it back up.
        assertEquals(5, DesktopAutoplay.neededFor(queuedAhead = 10, reserved = 0))
        assertEquals(15, DesktopAutoplay.neededFor(queuedAhead = 0, reserved = 0))
    }

    @Test
    fun aFetchedBatchFillsTheQueueAndTheRestWaits() {
        val fresh = (1..15).map { auto("t$it") }
        val (queued, held) = DesktopAutoplay.split(fresh, room = 10)
        assertEquals(10, queued.size)
        assertEquals(5, held.size)
        assertEquals("t11", held.first().videoId)
    }

    @Test
    fun aFullQueueHoldsTheWholeBatch() {
        val fresh = (1..4).map { auto("t$it") }
        val (queued, held) = DesktopAutoplay.split(fresh, room = 0)
        assertEquals(0, queued.size)
        assertEquals(4, held.size)
        assertEquals(0, DesktopAutoplay.split(fresh, room = -3).first.size)
    }
}
