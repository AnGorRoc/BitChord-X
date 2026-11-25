package com.music.bitchord.data.model

/**
 * Normalizes subtitle string to extract the clean primary artist name.
 */
private fun extractArtistName(subtitle: String): String {
    return subtitle.split(" • ").firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/**
 * Selects a high-relevance featured song for the Home spotlight billboard by simultaneously evaluating:
 * 1. Strict exclusion: the song must not have been played already (neither currently playing nor in listening history/recents).
 * 2. Taste/history affinity: matches frequent artists from recent plays or personalized recommendation shelves (Quick picks, Similar to).
 * 3. Freshness: newly released singles/tracks.
 * 4. Popularity: trending / chart-topping tracks.
 * 5. Multi-factor synergy: bonuses when a favorite artist has a new or trending release.
 */
fun selectSpotlightSong(
    shelves: List<HomeShelf>,
    currentSong: Song? = null,
    topPicks: List<TopPick>? = null,
    /**
     * Tracks that must not be picked though they would qualify: the cards of
     * the shelf drawn right under the billboard, so the biggest picture on the
     * page is never repeated a few pixels below it.
     */
    avoid: Set<String> = emptySet(),
): ShelfItem? {
    // 1. Strict Exclusion: Gather already listened / currently playing video IDs
    val listenedSongIds = mutableSetOf<String>()
    currentSong?.videoId?.let { listenedSongIds.add(it) }
    listenedSongIds += avoid

    shelves.forEach { shelf ->
        val titleLower = shelf.title.lowercase()
        if (titleLower.contains("recent") ||
            titleLower.contains("historial") ||
            titleLower.contains("listen again") ||
            titleLower.contains("volver a escuchar")
        ) {
            shelf.items.forEach { item ->
                item.videoId?.let { listenedSongIds.add(it) }
            }
        }
    }

    // 2. Extract favorite / frequent artists from history and top picks
    val favoriteArtists = mutableSetOf<String>()

    // From Recents shelf
    shelves.firstOrNull { it.title.equals("Recents", ignoreCase = true) }?.items?.forEach { item ->
        val artist = extractArtistName(item.subtitle).lowercase()
        if (artist.isNotBlank()) favoriteArtists.add(artist)
    }

    // From TopPicks (if available)
    topPicks?.forEach { pick ->
        pick.artistName?.let { name ->
            val normalized = name.lowercase().trim()
            if (normalized.isNotBlank()) favoriteArtists.add(normalized)
        }
    }

    // 3. Multi-factor candidate scoring
    data class Candidate(val item: ShelfItem, val score: Int)
    val candidates = mutableListOf<Candidate>()

    shelves.forEach { shelf ->
        val titleLower = shelf.title.lowercase()
        // Do not pick recommendations from pure history shelves
        if (titleLower.contains("recent") || titleLower.contains("historial")) {
            return@forEach
        }

        val isPersonalizedShelf = titleLower.contains("quick") ||
            titleLower.contains("pick") ||
            titleLower.contains("para ti") ||
            titleLower.contains("for you") ||
            titleLower.contains("similar") ||
            titleLower.contains("mix") ||
            titleLower.contains("descubr") ||
            titleLower.contains("discover") ||
            titleLower.contains("recomend")

        val isNewReleaseShelf = titleLower.contains("new") ||
            titleLower.contains("novedad") ||
            titleLower.contains("estreno") ||
            titleLower.contains("release") ||
            titleLower.contains("lanzamiento")

        val isTrendingShelf = titleLower.contains("trend") ||
            titleLower.contains("chart") ||
            titleLower.contains("éxito") ||
            titleLower.contains("top") ||
            titleLower.contains("popular") ||
            titleLower.contains("explore")

        shelf.items.forEach { item ->
            val videoId = item.videoId
            // Must be a playable track and must NOT have been listened to
            if (videoId == null || videoId in listenedSongIds) {
                return@forEach
            }

            var score = 0
            val artistName = extractArtistName(item.subtitle).lowercase()

            // Factor A: Taste / History Affinity (+50 pts)
            val isFavoriteArtist = artistName.isNotBlank() && favoriteArtists.any {
                it == artistName || it.contains(artistName) || artistName.contains(it)
            }
            if (isFavoriteArtist) {
                score += 50
            }

            // Factor B: Personalized algorithmic recommendation (+35 pts)
            if (isPersonalizedShelf) {
                score += 35
            }

            // Factor C: New release / Freshness (+30 pts)
            if (isNewReleaseShelf) {
                score += 30
            } else if (item.subtitle.contains("2026") || item.subtitle.contains("2025") || item.subtitle.contains("Single", ignoreCase = true)) {
                score += 15
            }

            // Factor D: Trending / Popularity (+25 pts)
            if (isTrendingShelf) {
                score += 25
            }

            // Factor E: Synergistic bonus (Favorite artist with a new or trending release: +20 pts)
            if (isFavoriteArtist && (isNewReleaseShelf || isTrendingShelf)) {
                score += 20
            }

            candidates.add(Candidate(item, score))
        }
    }

    // 4. Return the candidate with the highest combined score
    return candidates.maxByOrNull { it.score }?.item
        // Fallback: any unplayed track from non-recent shelves
        ?: shelves.filterNot { it.title.equals("Recents", ignoreCase = true) }
            .flatMap { it.items }
            .firstOrNull { it.videoId != null && it.videoId !in listenedSongIds }
        // Final fallback: the first card item in a card shelf (if all tracks were somehow excluded)
        ?: shelves.firstOrNull { it.layout == ShelfLayout.CARDS && !it.title.equals("Recents", ignoreCase = true) }
            ?.items?.firstOrNull()
}
