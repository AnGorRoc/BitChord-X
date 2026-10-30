package com.music.bitchord.data.model

/** Which kind of result a section of the All page holds. */
enum class SearchSectionKind { SONGS, ARTISTS, ALBUMS, PLAYLISTS, MORE }

/** One run of the All page's results; [rows] never holds the promoted [SearchResult.TopTrack]. */
data class RankedSearchSection(val kind: SearchSectionKind, val rows: List<SearchResult>)

/**
 * The All page after it has been put in the order the query asked for.
 *
 * [topResult] is the promoted song card. It heads the page only while [topResultLeads];
 * otherwise something that is not a song answers the query more closely, and the
 * card is shown at the head of the Songs section instead.
 */
data class RankedSearch(
    val topResult: SearchResult.TopTrack?,
    val topResultLeads: Boolean,
    val sections: List<RankedSearchSection>,
)

/**
 * Orders the mixed All results by how closely they answer [query], not by the
 * kind of thing they are.
 *
 * YouTube Music answers a query like "michael jackson" with songs by that artist
 * and the artist's own page, and the page used to list every song before any
 * artist. A name that is the query itself belongs ahead of tracks that only
 * mention it, so each section is placed by its closest row, and within a section
 * the closest rows come first, the more popular first among equals. Rows that
 * tie keep the order YouTube gave them.
 */
fun rankAllSearch(rows: List<SearchResult>, query: String): RankedSearch {
    val words = searchWords(query)
    val topTrack = rows.filterIsInstance<SearchResult.TopTrack>().firstOrNull()
    val topCloseness = topTrack?.let { songCloseness(it.song, words) } ?: 0
    val closestBrowse = rows.filterIsInstance<SearchResult.Browse>()
        .maxOfOrNull { browseCloseness(it.item, words) } ?: 0
    val topLeads = topTrack != null && topCloseness >= closestBrowse

    fun songs(): List<SearchResult> = rows.filterIsInstance<SearchResult.Track>()
        .sortedByDescending { songCloseness(it.song, words) }

    fun browse(type: BrowseType): List<SearchResult> =
        rows.filterIsInstance<SearchResult.Browse>()
            .filter { it.item.type == type }
            .sortedWith(
                compareByDescending<SearchResult.Browse> { browseCloseness(it.item, words) }
                    .thenByDescending { audienceOf(it.item.subtitle) },
            )

    val candidates = listOf(
        SearchSectionKind.ARTISTS to browse(BrowseType.ARTIST),
        SearchSectionKind.SONGS to songs(),
        SearchSectionKind.ALBUMS to browse(BrowseType.ALBUM),
        SearchSectionKind.PLAYLISTS to browse(BrowseType.PLAYLIST),
        SearchSectionKind.MORE to browse(BrowseType.OTHER),
    )
    val sections = candidates
        .filter { (kind, list) -> list.isNotEmpty() || (kind == SearchSectionKind.SONGS && topTrack != null && !topLeads) }
        .map { (kind, list) ->
            val best = list.maxOfOrNull { closenessOf(it, words) } ?: 0
            val lead = if (kind == SearchSectionKind.SONGS && topTrack != null && !topLeads) topCloseness else 0
            RankedSearchSection(kind, list) to maxOf(best, lead)
        }
        // Stable: sections that are equally close stay in the order they were listed above.
        .sortedByDescending { it.second }
        .map { it.first }
    return RankedSearch(topTrack, topLeads, sections)
}

private fun closenessOf(row: SearchResult, words: List<String>): Int = when (row) {
    is SearchResult.TopTrack -> songCloseness(row.song, words)
    is SearchResult.Track -> songCloseness(row.song, words)
    is SearchResult.Browse -> browseCloseness(row.item, words)
}

private const val EXACT = 4
private const val LEADING = 3
private const val CONTAINS = 2
private const val PARTIAL = 1

private fun songCloseness(song: Song, words: List<String>): Int {
    val title = closeness(searchWords(song.title), words)
    val artist = closeness(searchWords(song.artist), words)
    // A song by the artist the query names is that artist's, as close as a
    // title that starts with the query; one that merely credits them is nearer
    // than a stray word.
    val named = when {
        artist == EXACT -> LEADING
        artist >= CONTAINS || containsAll(searchWords(song.title + " " + song.artist), words) -> CONTAINS
        else -> 0
    }
    return maxOf(title, named)
}

private fun browseCloseness(item: BrowseItem, words: List<String>): Int {
    val title = closeness(searchWords(item.title), words)
    val withByline = if (containsAll(searchWords(item.title + " " + item.subtitle), words)) CONTAINS else 0
    return maxOf(title, withByline)
}

/** The whole query, the front of the title, all the query's words in it, then any one of them. */
private fun closeness(title: List<String>, query: List<String>): Int {
    if (title.isEmpty() || query.isEmpty()) return 0
    val leading = title.size >= query.size && query.indices.all { wordMatches(title[it], query[it], it == query.lastIndex) }
    return when {
        leading && title.size == query.size && title.last() == query.last() -> EXACT
        leading -> LEADING
        containsAll(title, query) -> CONTAINS
        query.indices.any { i -> title.any { wordMatches(it, query[i], i == query.lastIndex) } } -> PARTIAL
        else -> 0
    }
}

private fun containsAll(text: List<String>, query: List<String>): Boolean {
    if (query.isEmpty()) return false
    return query.indices.all { i -> text.any { wordMatches(it, query[i], i == query.lastIndex) } }
}

/** The last word of a query is usually still being typed, so it may be only the start of one. */
private fun wordMatches(word: String, typed: String, last: Boolean): Boolean =
    word == typed || (last && word.startsWith(typed))

private fun searchWords(text: String): List<String> = buildString {
    for (c in text.lowercase()) {
        append(
            when {
                c.isLetterOrDigit() -> foldAccent(c)
                else -> ' '
            },
        )
    }
}.split(' ').filter { it.isNotEmpty() }

private fun foldAccent(c: Char): Char = when (c) {
    in "àáâãäåā" -> 'a'
    in "èéêëē" -> 'e'
    in "ìíîïī" -> 'i'
    in "òóôõöøō" -> 'o'
    in "ùúûüū" -> 'u'
    'ñ' -> 'n'
    'ç' -> 'c'
    'ý', 'ÿ' -> 'y'
    else -> c
}

private val AUDIENCE = Regex("""(\d+(?:[.,]\d+)?)\s*([kKmMbB])?\b""")

/** The "12.3M monthly audience" style figure in an artist's byline, or 0 when it states none. */
private fun audienceOf(subtitle: String): Double {
    val match = AUDIENCE.find(subtitle) ?: return 0.0
    val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0.0
    val scale = when (match.groupValues[2].lowercase()) {
        "k" -> 1_000.0
        "m" -> 1_000_000.0
        "b" -> 1_000_000_000.0
        else -> 1.0
    }
    return number * scale
}
