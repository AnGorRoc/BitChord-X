package com.music.bitchord

import com.music.bitchord.data.BrowseCache
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SubscriptionState
import com.music.bitchord.data.model.WatchTarget
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowseCacheTest {

    private val dir = Files.createTempDirectory("browse-cache").toFile()

    @BeforeTest
    fun setUp() {
        Innertube.cookie = null
        BrowseCache.init(dir)
        BrowseCache.clearMemory()
    }

    @AfterTest
    fun tearDown() {
        BrowseCache.awaitWrites()
        BrowseCache.clearMemory()
        Innertube.cookie = null
        dir.deleteRecursively()
    }

    private fun song(id: String) = Song(
        videoId = id,
        title = "Song $id",
        artist = "Artist",
        thumbnailUrl = "https://img/$id",
        durationText = "3:21",
        artistId = "UCartist",
        artists = listOf(ArtistRef("Artist", "UCartist"), ArtistRef("Guest")),
        albumId = "MPREb_album",
        albumName = "Album",
        setVideoId = "set-$id",
        isExplicit = true,
    )

    private val shelf = HomeShelf(
        title = "Albums",
        items = listOf(ShelfItem("Album", "2024", "https://img/a", null, "MPREb_album")),
        moreBrowseId = "MPAD",
        moreParams = "p",
    )

    private fun artist(songs: Int) = ArtistPage(
        songs = (1..songs).map { song("v$it") },
        moreSongsBrowseId = "VLtop",
        sections = listOf(shelf),
        thumbnailUrl = "https://img/artist",
        name = "Artist",
        description = "Bio",
        subscriberCountText = "1M",
        monthlyListenerCount = "2M",
        subscription = SubscriptionState("UCartist", subscribed = true),
        radio = WatchTarget("RDartist", params = "x"),
        shuffle = WatchTarget("PLshuffle"),
    )

    @Test
    fun artistSurvivesARestartWhole() {
        BrowseCache.putArtist("UCartist", artist(20), complete = true)
        BrowseCache.awaitWrites()
        BrowseCache.clearMemory()

        val kept = BrowseCache.artist("UCartist")!!
        assertTrue(kept.complete)
        assertEquals(artist(20), kept.value)
    }

    @Test
    fun aLandingNeverReplacesAWholePage() {
        BrowseCache.putArtist("UCartist", artist(20), complete = true)
        BrowseCache.putArtist("UCartist", artist(5), complete = false)

        val kept = BrowseCache.artist("UCartist")!!
        assertTrue(kept.complete)
        assertEquals(20, kept.value.songs.size)
    }

    @Test
    fun aLandingIsKeptWhenThereIsNothingBetter() {
        BrowseCache.putArtist("UCartist", artist(5), complete = false)
        val kept = BrowseCache.artist("UCartist")!!
        assertFalse(kept.complete)
        assertEquals(5, kept.value.songs.size)
    }

    @Test
    fun collectionSurvivesARestartAndCanBeForgotten() {
        val page = YtMusicRepository.SongPage(
            songs = listOf(song("a"), song("b")),
            continuation = "token",
            suggested = listOf(song("s")),
            library = LibraryState("OLAK5uy_x", saved = true),
            owned = true,
            header = InnertubeParser.BrowseHeader("Mix", "Playlist • 2 songs", "https://img/p"),
            description = "About",
            sections = listOf(shelf),
        )
        BrowseCache.putCollection("VLmix", page)
        BrowseCache.awaitWrites()
        BrowseCache.clearMemory()

        val kept = BrowseCache.collection("VLmix")!!.value
        // Kept whole: there is nothing after it to page to.
        assertEquals(page.copy(continuation = null), kept)

        BrowseCache.removeCollection("VLmix")
        BrowseCache.awaitWrites()
        BrowseCache.clearMemory()
        assertNull(BrowseCache.collection("VLmix"))
    }

    @Test
    fun oneAccountsPagesNeverOpenUnderAnother() {
        Innertube.cookie = "SAPISID=first; HSID=x"
        BrowseCache.putArtist("UCartist", artist(3), complete = true)
        assertTrue(BrowseCache.artist("UCartist") != null)

        Innertube.cookie = "SAPISID=second; HSID=x"
        assertNull(BrowseCache.artist("UCartist"))

        // The same sign-in with a rotated cookie is the same account.
        Innertube.cookie = "SAPISID=first; HSID=rotated"
        assertTrue(BrowseCache.artist("UCartist") != null)
    }
}
