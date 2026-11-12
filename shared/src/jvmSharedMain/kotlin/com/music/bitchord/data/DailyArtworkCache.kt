package com.music.bitchord.data

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache de portadas representativas para las selecciones temáticas diarias (Daily Topics).
 *
 * Cada tema diario (ej. "Workout Hits", "80s Synthwave", "Lo-Fi Beats") se nutre de la
 * portada real de la primera canción devuelta por la búsqueda en YouTube Music.
 * Esto garantiza que la portada vista en la tarjeta de Explorar coincida 100% con
 * la lista de canciones que se abre al seleccionarla.
 */
object DailyArtworkCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun get(query: String): String? = cache[query]

    suspend fun getOrFetch(query: String): String? {
        cache[query]?.let { return it }
        return withContext(Dispatchers.IO) {
            val result = YtMusicRepository.searchPage(query, SearchFilter.SONGS)
            val thumb = result.getOrNull()?.rows?.mapNotNull {
                when (it) {
                    is SearchResult.Track -> it.song.thumbnailUrl
                    is SearchResult.TopTrack -> it.song.thumbnailUrl
                    else -> null
                }
            }?.firstOrNull()

            if (!thumb.isNullOrBlank()) {
                cache[query] = thumb
            }
            thumb
        }
    }
}
