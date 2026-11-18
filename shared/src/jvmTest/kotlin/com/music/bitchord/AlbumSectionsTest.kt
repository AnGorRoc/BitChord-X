package com.music.bitchord

import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.albumNeedsArtistShelves
import com.music.bitchord.data.model.albumShelvesFromArtist
import com.music.bitchord.data.model.isArtistShelf
import com.music.bitchord.data.model.withoutRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumSectionsTest {

    private fun album(id: String) = ShelfItem(title = id, subtitle = "", thumbnailUrl = null, videoId = null, browseId = id)
    private fun artist(id: String) = ShelfItem(title = id, subtitle = "", thumbnailUrl = null, videoId = null, browseId = "UC$id")
    private fun video(id: String) = ShelfItem(title = id, subtitle = "", thumbnailUrl = null, videoId = id, browseId = null, isVideo = true)
    private fun artistPage(vararg shelves: HomeShelf) = ArtistPage(songs = emptyList(), moreSongsBrowseId = null, sections = shelves.toList())

    private val releases = HomeShelf("Albums", listOf(album("MPREb_a"), album("MPREb_b"), album("MPREb_this")))
    private val similar = HomeShelf("Fans might also like", listOf(artist("1"), artist("2"), artist("3")))

    @Test
    fun aShelfOfMostlyArtistChannelsIsAnArtistShelf() {
        assertTrue(similar.isArtistShelf())
        assertFalse(releases.isArtistShelf())
        assertFalse(HomeShelf("Empty", emptyList()).isArtistShelf())
    }

    @Test
    fun theAlbumItselfAndEmptiedShelvesAreTakenOut() {
        val onlyThis = HomeShelf("Singles", listOf(album("MPREb_this")))
        val result = listOf(releases, onlyThis).withoutRelease("MPREb_this")
        assertEquals(listOf("Albums"), result.map { it.title })
        assertEquals(listOf("MPREb_a", "MPREb_b"), result.single().items.map { it.browseId })
    }

    @Test
    fun anAlbumPageWithBothKindsOfShelfNeedsNothingFromTheArtist() {
        assertFalse(albumNeedsArtistShelves(listOf(releases, similar), "MPREb_this"))
        assertTrue(albumShelvesFromArtist(listOf(releases, similar), "MPREb_this", artistPage(releases, similar)).isEmpty())
    }

    @Test
    fun aPageWithReleasesButNoSimilarArtistsTakesOnlyTheSimilarArtists() {
        val current = listOf(releases)
        assertTrue(albumNeedsArtistShelves(current, "MPREb_this"))
        val extra = albumShelvesFromArtist(current, "MPREb_this", artistPage(HomeShelf("Singles", listOf(album("MPREb_s"))), similar))
        assertEquals(listOf("Fans might also like"), extra.map { it.title })
    }

    @Test
    fun aPageWithSimilarArtistsButNoReleasesTakesTheArtistsReleases() {
        val current = listOf(similar)
        assertTrue(albumNeedsArtistShelves(current, "MPREb_this"))
        val extra = albumShelvesFromArtist(current, "MPREb_this", artistPage(releases, HomeShelf("Singles", listOf(album("MPREb_s")))))
        assertEquals(listOf("Albums", "Singles"), extra.map { it.title })
        assertFalse(extra.flatMap { it.items }.any { it.browseId == "MPREb_this" })
    }

    @Test
    fun anAlbumPageWithNothingTakesTheArtistsReleasesWithoutTheAlbumOrVideos() {
        val videos = HomeShelf("Videos", listOf(video("v1")), layout = ShelfLayout.VIDEO)
        val extra = albumShelvesFromArtist(emptyList(), "MPREb_this", artistPage(releases, videos, similar))
        assertEquals(listOf("Albums", "Fans might also like"), extra.map { it.title })
        assertEquals(listOf("MPREb_a", "MPREb_b"), extra.first().items.map { it.browseId })
    }

    @Test
    fun aTitleThePageAlreadyShowsIsNotRepeated() {
        val current = listOf(HomeShelf("albums", listOf(album("MPREb_x"))))
        // The page's "albums" shelf has other releases, so only the similar artists are missing.
        val extra = albumShelvesFromArtist(current, "MPREb_this", artistPage(releases, similar))
        assertEquals(listOf("Fans might also like"), extra.map { it.title })
    }
}
