package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchRankingTest {
    private fun track(title: String, artist: String, id: String = title) =
        SearchResult.Track(Song(videoId = id, title = title, artist = artist, thumbnailUrl = null))

    private fun artist(name: String, audience: String = "Artist • 1M monthly audience") =
        SearchResult.Browse(BrowseItem("UC$name", name, audience, null, BrowseType.ARTIST))

    private fun album(title: String, by: String) =
        SearchResult.Browse(BrowseItem("MP$title", title, "Album • $by • 1982", null, BrowseType.ALBUM))

    private fun kinds(ranked: RankedSearch) = ranked.sections.map { it.kind }

    @Test
    fun anArtistNamedByTheQueryLeadsTheSongsThatOnlyMentionThem() {
        val rows = listOf(
            track("Billie Jean", "Michael Jackson"),
            track("Smooth Criminal", "Michael Jackson"),
            album("Thriller", "Michael Jackson"),
            artist("Michael Jackson", "Artist • 550M monthly audience"),
        )
        val ranked = rankAllSearch(rows, "michael jackson")
        assertEquals(SearchSectionKind.ARTISTS, kinds(ranked).first())
        assertEquals(
            listOf(SearchSectionKind.ARTISTS, SearchSectionKind.SONGS, SearchSectionKind.ALBUMS),
            kinds(ranked),
        )
    }

    @Test
    fun aSongTitledByTheQueryStillLeadsItsOwnPage() {
        val rows = listOf(
            artist("Thrillerz"),
            track("Thriller", "Michael Jackson"),
            album("Thriller", "Michael Jackson"),
        )
        val ranked = rankAllSearch(rows, "thriller")
        // Song and album are an exact match each; the song was listed first, so it stays first.
        assertEquals(SearchSectionKind.SONGS, kinds(ranked).first())
        assertEquals(SearchSectionKind.ARTISTS, kinds(ranked).last())
    }

    @Test
    fun morePopularArtistsComeFirstAmongEquals() {
        val rows = listOf(
            artist("Drake Bell", "Artist • 900K monthly audience"),
            artist("Drake Hogestyn", "Artist • 3.2M monthly audience"),
            artist("Drake", "Artist • 80K monthly audience"),
        )
        val ranked = rankAllSearch(rows, "drake")
        val titles = ranked.sections.single().rows.map { (it as SearchResult.Browse).item.title }
        // An exact name beats a bigger audience; between the two that merely start with it, audience decides.
        assertEquals(listOf("Drake", "Drake Hogestyn", "Drake Bell"), titles)
    }

    @Test
    fun anArtistSharingAWordOutranksOneSharingNone() {
        val rows = listOf(
            artist("Michael Jackson", "Artist • 550M monthly audience"),
            artist("Akon", "Artist • 205M monthly audience"),
            artist("Jackson 5", "Artist • 125M monthly audience"),
        )
        val titles = rankAllSearch(rows, "michael jackson").sections.single().rows
            .map { (it as SearchResult.Browse).item.title }
        assertEquals(listOf("Michael Jackson", "Jackson 5", "Akon"), titles)
    }

    @Test
    fun songsByTheNamedArtistComeBeforeTheirPlaylists() {
        val rows = listOf(
            SearchResult.Browse(BrowseItem("PL1", "Michael Jackson Greatest Hits", "Playlist • 13M views", null, BrowseType.PLAYLIST)),
            track("Billie Jean", "Michael Jackson"),
            artist("Michael Jackson", "Artist • 550M monthly audience"),
        )
        assertEquals(
            listOf(SearchSectionKind.ARTISTS, SearchSectionKind.SONGS, SearchSectionKind.PLAYLISTS),
            kinds(rankAllSearch(rows, "michael jackson")),
        )
    }

    @Test
    fun theTopCardGivesWayToACloserArtist() {
        val card = SearchResult.TopTrack(Song("v1", "Michael", "Some Band", null))
        val rows = listOf(card, artist("Michael Jackson", "Artist • 550M monthly audience"))
        val ranked = rankAllSearch(rows, "michael jackson")
        assertFalse(ranked.topResultLeads)
        assertEquals(listOf(SearchSectionKind.ARTISTS, SearchSectionKind.SONGS), kinds(ranked))
    }

    @Test
    fun theTopCardLeadsWhenItIsTheClosestThing() {
        val card = SearchResult.TopTrack(Song("v1", "Thriller", "Michael Jackson", null))
        val rows = listOf(card, artist("Michael Jackson"))
        val ranked = rankAllSearch(rows, "thriller")
        assertTrue(ranked.topResultLeads)
    }

    @Test
    fun aHalfTypedLastWordStillMatches() {
        val rows = listOf(track("Billie Jean", "Michael Jackson"), artist("Michael Jackson"))
        val ranked = rankAllSearch(rows, "michael jack")
        assertEquals(SearchSectionKind.ARTISTS, kinds(ranked).first())
    }
}
