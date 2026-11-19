package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.selectOfflineAutoplayTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAutoplayTest {
    private fun song(id: String, artist: String = "Other", album: String? = null) =
        Song(id, id.uppercase(), artist, null, albumName = album)

    private fun pick(
        pool: List<Song>,
        seed: Song = song("seed", "Me"),
        queued: Set<String> = emptySet(),
        history: Set<String> = emptySet(),
        shared: Set<String> = emptySet(),
        limit: Int = 5,
    ) = selectOfflineAutoplayTracks(seed, pool, queued, history, shared, limit).map { it.videoId }

    @Test
    fun theSeedAndWhatIsAlreadyQueuedAreNeverPicked() {
        val ids = pick(listOf(song("seed"), song("a"), song("b")), queued = setOf("a"))
        assertEquals(listOf("b"), ids)
    }

    @Test
    fun sameArtistComesBeforeUnrelatedSongs() {
        val ids = pick(listOf(song("x"), song("y"), song("mine", "Me")), limit = 1)
        assertEquals(listOf("mine"), ids)
    }

    @Test
    fun theLimitIsRespectedAndNothingIsInventedWhenThePoolIsEmpty() {
        assertEquals(2, pick(List(6) { song("s$it") }, limit = 2).size)
        assertTrue(pick(emptyList()).isEmpty())
        assertTrue(pick(listOf(song("a")), limit = 0).isEmpty())
    }

    @Test
    fun songsHeardThisSessionWaitUnlessThereIsNothingElse() {
        assertEquals(listOf("b"), pick(listOf(song("a"), song("b")), history = setOf("a")))
        assertEquals(listOf("a"), pick(listOf(song("a")), history = setOf("a")))
    }

    @Test
    fun aTrackAppearsOnlyOnce() {
        val ids = pick(listOf(song("a"), song("a")))
        assertEquals(1, ids.size)
        assertFalse("seed" in ids)
    }
}
