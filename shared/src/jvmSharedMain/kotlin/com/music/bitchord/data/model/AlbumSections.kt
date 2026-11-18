package com.music.bitchord.data.model

import com.music.bitchord.data.LocalGroups

/** Whether most of a shelf's cards are artists, which is what draws it round and reads as "similar artists". */
fun HomeShelf.isArtistShelf(): Boolean =
    items.isNotEmpty() &&
        items.count { it.browseId?.startsWith("UC") == true || LocalGroups.isArtist(it.browseId) } * 2 > items.size

/** An album page's carousels with the album itself taken out of them, and any carousel left empty dropped. */
fun List<HomeShelf>.withoutRelease(browseId: String): List<HomeShelf> =
    map { shelf -> shelf.copy(items = shelf.items.filter { it.browseId != browseId }) }
        .filter { it.items.isNotEmpty() }

/**
 * Whether an album page still lacks the carousels worth taking from its artist's page: other releases
 * by the artist, or similar artists.
 */
fun albumNeedsArtistShelves(current: List<HomeShelf>, albumId: String): Boolean {
    val hasOtherReleases = current.any { shelf ->
        !shelf.isArtistShelf() && shelf.items.any { it.browseId != albumId }
    }
    val hasSimilarArtists = current.any { it.isArtistShelf() }
    return !hasOtherReleases || !hasSimilarArtists
}

/**
 * The carousels to add under an album, taken from [artist]'s page: the artist's other releases when
 * the album page names none, and the first "similar artists" shelf when it has none of its own.
 * Music videos stay out, the album itself is left out of every shelf, and a title the page already
 * shows is not repeated.
 */
fun albumShelvesFromArtist(current: List<HomeShelf>, albumId: String, artist: ArtistPage): List<HomeShelf> {
    val hasOtherReleases = current.any { shelf ->
        !shelf.isArtistShelf() && shelf.items.any { it.browseId != albumId }
    }
    val hasSimilarArtists = current.any { it.isArtistShelf() }
    if (hasOtherReleases && hasSimilarArtists) return emptyList()

    val shelves = artist.sections.withoutVideos()
    val extra = buildList {
        if (!hasOtherReleases) addAll(shelves.filter { !it.isArtistShelf() }.withoutRelease(albumId))
        if (!hasSimilarArtists) {
            shelves.firstOrNull { it.isArtistShelf() }?.takeIf { it.items.isNotEmpty() }?.let(::add)
        }
    }
    val shown = current.mapTo(HashSet()) { it.title.trim().lowercase() }
    return extra.filter { it.title.trim().lowercase() !in shown }
}
