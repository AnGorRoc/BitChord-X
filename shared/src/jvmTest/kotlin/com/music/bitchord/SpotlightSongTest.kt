package com.music.bitchord

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickKind
import com.music.bitchord.data.model.selectSpotlightSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpotlightSongTest {

    private fun track(
        title: String,
        artist: String,
        videoId: String,
    ) = ShelfItem(
        title = title,
        subtitle = artist,
        thumbnailUrl = "https://example.com/$videoId.jpg",
        videoId = videoId,
        browseId = null,
    )

    @Test
    fun `excludes currently playing song and listening history`() {
        val recentsShelf = HomeShelf(
            title = "Recents",
            items = listOf(
                track("Last Song", "Recent Artist", "vid_recent_1"),
                track("Previous Song", "Recent Artist", "vid_recent_2"),
            ),
            layout = ShelfLayout.LIST,
        )

        val quickPicksShelf = HomeShelf(
            title = "Quick picks",
            items = listOf(
                track("Currently Playing Track", "Some Artist", "vid_playing"),
                track("Recommended Discovery Track", "Some Artist", "vid_disc_1"),
            ),
            layout = ShelfLayout.LIST,
        )

        val currentSong = Song(
            videoId = "vid_playing",
            title = "Currently Playing Track",
            artist = "Some Artist",
            thumbnailUrl = null,
        )

        val selected = selectSpotlightSong(
            shelves = listOf(recentsShelf, quickPicksShelf),
            currentSong = currentSong,
        )

        assertNotNull(selected)
        assertEquals("vid_disc_1", selected?.videoId)
    }

    @Test
    fun `prioritizes new release from favorite artist over generic trending`() {
        val recentsShelf = HomeShelf(
            title = "Recents",
            items = listOf(
                track("Old Hit", "Favorite Artist", "vid_old"),
            ),
            layout = ShelfLayout.LIST,
        )

        val newReleasesShelf = HomeShelf(
            title = "New releases",
            items = listOf(
                track("Brand New Single", "Favorite Artist", "vid_fav_new"),
            ),
            layout = ShelfLayout.CARDS,
        )

        val trendingShelf = HomeShelf(
            title = "Trending music",
            items = listOf(
                track("Global Viral Track", "Random Pop Star", "vid_viral"),
            ),
            layout = ShelfLayout.CARDS,
        )

        val selected = selectSpotlightSong(
            shelves = listOf(recentsShelf, newReleasesShelf, trendingShelf),
            currentSong = null,
        )

        assertNotNull(selected)
        // Favorite artist new release (50 + 30 + 20 synergy = 100) vs Trending (25)
        assertEquals("vid_fav_new", selected?.videoId)
    }

    @Test
    fun `prioritizes personalized quick picks when no new releases exist`() {
        val recentsShelf = HomeShelf(
            title = "Recents",
            items = listOf(
                track("Heard Track", "Indie Band", "vid_heard"),
            ),
            layout = ShelfLayout.LIST,
        )

        val quickPicksShelf = HomeShelf(
            title = "Quick picks",
            items = listOf(
                track("Fresh Gem", "Indie Band", "vid_indie_unheard"),
            ),
            layout = ShelfLayout.LIST,
        )

        val trendingShelf = HomeShelf(
            title = "Top Charts",
            items = listOf(
                track("Top Hit", "Unrelated Artist", "vid_top"),
            ),
            layout = ShelfLayout.CARDS,
        )

        val selected = selectSpotlightSong(
            shelves = listOf(recentsShelf, quickPicksShelf, trendingShelf),
            currentSong = null,
        )

        assertNotNull(selected)
        // Quick picks + favorite artist (50 + 35 = 85) beats Top Charts (25)
        assertEquals("vid_indie_unheard", selected?.videoId)
    }

    @Test
    fun `uses topPicks artist preferences to score candidates`() {
        val topPicks = listOf(
            TopPick(
                kind = TopPickKind.FEATURED_ARTIST,
                title = "Artist Spotlight",
                subtitle = "Rock Legend",
                artistName = "Rock Legend",
            ),
        )

        val exploreShelf = HomeShelf(
            title = "Explore new albums & singles",
            items = listOf(
                track("Legend New Anthem", "Rock Legend", "vid_legend_new"),
                track("Other Release", "Unknown Artist", "vid_other"),
            ),
            layout = ShelfLayout.CARDS,
        )

        val selected = selectSpotlightSong(
            shelves = listOf(exploreShelf),
            currentSong = null,
            topPicks = topPicks,
        )

        assertNotNull(selected)
        assertEquals("vid_legend_new", selected?.videoId)
    }

    @Test
    fun `gracefully handles empty or non-track feeds`() {
        val emptyShelf = HomeShelf(
            title = "Empty",
            items = emptyList(),
            layout = ShelfLayout.CARDS,
        )

        val selected = selectSpotlightSong(
            shelves = listOf(emptyShelf),
            currentSong = null,
        )

        assertNull(selected)
    }
}
