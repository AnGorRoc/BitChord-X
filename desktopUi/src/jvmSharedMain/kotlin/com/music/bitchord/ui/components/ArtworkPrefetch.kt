package com.music.bitchord.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged

/** How many rows past the bottom of the viewport get their covers fetched ahead of the scroll. */
const val ARTWORK_PREFETCH_AHEAD = 8

/**
 * Predictively warms Coil's disk/memory cache with a collection of image URLs.
 *
 * Called during startup, tab transitions or when fresh shelves arrive so that
 * upcoming images are pre-downloaded and ready before their composables measure.
 */
fun prefetchArtwork(context: PlatformContext, urls: Iterable<String?>) {
    val loader = SingletonImageLoader.get(context)
    val seen = mutableSetOf<String>()
    for (url in urls) {
        if (!url.isNullOrBlank() && seen.add(url)) {
            loader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}

/**
 * Asks Coil for the covers of the rows just below [state]'s viewport, so the
 * ones the scroll reaches next are already cached when their row composes.
 *
 * The rows on screen are left to their own `AsyncImage`, so they still load
 * first; only the rows past them are fetched early. [artwork] is indexed the way
 * the lazy list is — one entry per lazy item, null for an item with no cover —
 * and each URL must be the one its row loads, or Coil's cache misses it.
 */
@Composable
fun PrefetchArtworkAhead(
    state: LazyListState,
    artwork: List<String?>,
) {
    val context = LocalPlatformContext.current
    val currentArtwork by rememberUpdatedState(artwork)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index to currentArtwork }
            .distinctUntilChanged()
            .collect { (last, list) ->
                if (last == null) return@collect
                val loader = SingletonImageLoader.get(context)
                for (index in last + 1..minOf(last + ARTWORK_PREFETCH_AHEAD, list.lastIndex)) {
                    val url = list[index] ?: continue
                    loader.enqueue(ImageRequest.Builder(context).data(url).build())
                }
            }
    }
}

/**
 * Prefetches chunked/grouped artwork ahead of horizontal or vertical scrolling,
 * e.g. for multi-row columns where each lazy item holds a list of tracks.
 */
@Composable
fun PrefetchArtworkGroupsAhead(
    state: LazyListState,
    artworkGroups: List<List<String?>>,
    ahead: Int = ARTWORK_PREFETCH_AHEAD,
) {
    val context = LocalPlatformContext.current
    val currentGroups by rememberUpdatedState(artworkGroups)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index to currentGroups }
            .distinctUntilChanged()
            .collect { (last, groups) ->
                if (last == null) return@collect
                val loader = SingletonImageLoader.get(context)
                for (groupIndex in last + 1..minOf(last + ahead, groups.lastIndex)) {
                    val group = groups[groupIndex]
                    for (url in group) {
                        if (!url.isNullOrBlank()) {
                            loader.enqueue(ImageRequest.Builder(context).data(url).build())
                        }
                    }
                }
            }
    }
}

/**
 * Prefetches the covers of vertical shelves/rows just below the viewport in a
 * vertical feed ([HomeScreen], [ExploreScreen]), ensuring that when the listener
 * scrolls down, upcoming shelves arrive with their initial artwork already cached.
 */
@Composable
fun PrefetchVerticalArtworkAhead(
    state: LazyListState,
    artworkByItem: List<List<String?>>,
    ahead: Int = 3,
) {
    val context = LocalPlatformContext.current
    val currentArtwork by rememberUpdatedState(artworkByItem)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index to currentArtwork }
            .distinctUntilChanged()
            .collect { (last, list) ->
                if (last == null) return@collect
                val loader = SingletonImageLoader.get(context)
                for (shelfIndex in last + 1..minOf(last + ahead, list.lastIndex)) {
                    val items = list[shelfIndex]
                    for (url in items) {
                        if (!url.isNullOrBlank()) {
                            loader.enqueue(ImageRequest.Builder(context).data(url).build())
                        }
                    }
                }
            }
    }
}
