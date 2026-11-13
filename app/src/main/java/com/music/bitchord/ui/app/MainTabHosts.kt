package com.music.bitchord

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickMood
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.withoutVideos
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SongSwipeAction
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.SearchSource
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.topBarHeight
import com.music.bitchord.ui.screens.ExploreScreen
import com.music.bitchord.ui.screens.HomeScreen
import com.music.bitchord.ui.screens.MoodGenrePlaylistsScreen
import com.music.bitchord.ui.screens.SearchScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTabHost(
    viewModel: MainViewModel,
    listState: LazyListState,
    currentSong: Song?,
    isPlaying: Boolean,
    signedIn: Boolean,
    refreshing: Boolean,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    accountName: String?,
    artworkMotion: Boolean,
    shelfSong: (ShelfItem) -> Song?,
    onSignIn: () -> Unit,
    onPlayRadio: (Song, QueueSource) -> Unit,
    onShelfLongPress: (ShelfItem) -> Unit,
    onOpenTopPick: (TopPick, String) -> Unit,
    onPlayMood: (TopPickMood, String) -> Unit,
    onMoodsShowAll: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val homeState by viewModel.home.collectAsStateWithLifecycle()
    val hideRecents by AppSettings.hideRecents.collectAsStateWithLifecycle()
    val hideHomeVideos by AppSettings.hideHomeVideos.collectAsStateWithLifecycle()
    val hideTopPicks by AppSettings.hideTopPicks.collectAsStateWithLifecycle()
    val hideHomeMoods by AppSettings.hideHomeMoods.collectAsStateWithLifecycle()
    // Only what Home draws: Top Picks reads the unfiltered feed from the view model.
    val visibleHomeState = remember(homeState, hideHomeVideos, hideRecents) {
        val loaded = homeState as? UiState.Success
        if (loaded != null && (hideHomeVideos || hideRecents)) {
            var shelves = loaded.data
            if (hideRecents) shelves = shelves.filterNot { it.title == YtMusicRepository.RECENT_TITLE }
            if (hideHomeVideos) shelves = shelves.withoutVideos()
            UiState.Success(shelves)
        } else {
            homeState
        }
    }
    val homeLoadingMore by viewModel.homeLoadingMore.collectAsStateWithLifecycle()
    val homeRecentlyPlayedLoading by viewModel.homeRecentlyPlayedLoading.collectAsStateWithLifecycle()
    val topPicks by viewModel.topPicks.collectAsStateWithLifecycle()
    val topPicksLoading by viewModel.topPicksLoading.collectAsStateWithLifecycle()

    HomeScreen(
        state = visibleHomeState,
        listState = listState,
        currentSong = currentSong,
        isPlaying = isPlaying,
        title = null,
        signedIn = signedIn,
        onSignIn = onSignIn,
        onItemClick = { item, shelfTitle ->
            val song = shelfSong(item)
            // Hoisted because ShelfItem lives in :shared, and
            // Kotlin will not smart-cast a public nullable
            // property declared in another module.
            val browseId = item.browseId
            when {
                song != null -> onPlayRadio(
                    song,
                    QueueSource(shelfTitle, PlaybackSourceType.HOME),
                )
                browseId != null -> viewModel.openDetail(
                    browseId = browseId,
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                )
            }
        },
        onItemLongPress = onShelfLongPress,
        onRetry = viewModel::loadHome,
        refreshing = refreshing,
        onRefresh = { viewModel.refresh(MainViewModel.Feed.HOME) },
        pullState = pullState,
        contentPadding = contentPadding,
        onLoadMore = viewModel::loadMoreHome,
        loadingMore = homeLoadingMore,
        recentlyPlayedLoading = homeRecentlyPlayedLoading && !hideRecents,
        topPicks = topPicks,
        topPicksLoading = topPicksLoading,
        listenerName = accountName,
        // Neither shelf is drawn without its callback.
        onTopPickClick = onOpenTopPick.takeUnless { hideTopPicks },
        onMoodClick = onPlayMood.takeUnless { hideHomeMoods },
        onMoodsShowAll = onMoodsShowAll,
        artworkMotion = artworkMotion,
        onOpenDownloads = onOpenDownloads,
        onTopPickLongPress = { pick ->
            pick.browseId?.let { browseId ->
                onShelfLongPress(
                    ShelfItem(
                        title = pick.title,
                        subtitle = pick.artistName.orEmpty(),
                        thumbnailUrl = pick.images.firstOrNull(),
                        videoId = null,
                        browseId = browseId,
                    ),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExploreTabHost(
    viewModel: MainViewModel,
    selectedMoodGenre: MoodGenre?,
    exploreListState: LazyListState,
    moodGenreListState: LazyListState,
    refreshing: Boolean,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    shelfSong: (ShelfItem) -> Song?,
    onPlayRadio: (Song, QueueSource) -> Unit,
    onPlayFrom: (List<Song>, Int, QueueSource) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onShelfLongPress: (ShelfItem) -> Unit,
) {
    if (selectedMoodGenre != null) {
        val moodGenreShelves by viewModel.moodGenreShelves.collectAsStateWithLifecycle()
        MoodGenrePlaylistsScreen(
            title = selectedMoodGenre.title,
            state = moodGenreShelves,
            listState = moodGenreListState,
            onItemClick = { item ->
                item.videoId?.let { videoId ->
                    onPlayRadio(
                        Song(
                            videoId = videoId,
                            title = item.title,
                            artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                            thumbnailUrl = item.thumbnailUrl,
                            isVideo = item.isVideo,
                        ),
                        QueueSource(
                            selectedMoodGenre.title,
                            PlaybackSourceType.EXPLORE,
                            selectedMoodGenre.browseId,
                        ),
                    )
                } ?: item.browseId?.let { browseId ->
                    viewModel.openDetail(
                        browseId = browseId,
                        title = item.title,
                        subtitle = item.subtitle,
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            },
            onRetry = { viewModel.openMoodGenre(selectedMoodGenre) },
            contentPadding = contentPadding,
        )
    } else {
        val exploreState by viewModel.explore.collectAsStateWithLifecycle()
        val chartsState by viewModel.charts.collectAsStateWithLifecycle()
        val chartsRegion by viewModel.chartsRegion.collectAsStateWithLifecycle()
        val newReleasesState by viewModel.newReleases.collectAsStateWithLifecycle()

        ExploreScreen(
            state = exploreState,
            listState = exploreListState,
            onCategoryClick = viewModel::openMoodGenre,
            onRetry = viewModel::loadExplore,
            refreshing = refreshing,
            onRefresh = { viewModel.refresh(MainViewModel.Feed.EXPLORE) },
            pullState = pullState,
            contentPadding = contentPadding,
            chartsState = chartsState,
            newReleasesState = newReleasesState,
            chartsRegion = chartsRegion,
            onRegionChange = viewModel::setChartsRegion,
            onRefreshCharts = viewModel::refreshCharts,
            onSongClick = { item ->
                item.videoId?.let { videoId ->
                    onPlayRadio(
                        Song(
                            videoId = videoId,
                            title = item.title,
                            artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                            thumbnailUrl = item.thumbnailUrl,
                            isVideo = item.isVideo,
                        ),
                        QueueSource("Charts", PlaybackSourceType.BROWSE, "FEmusic_charts"),
                    )
                }
            },
            onSongOptionsClick = onShelfLongPress,
            onItemClick = { item ->
                val song = shelfSong(item)
                val browseId = item.browseId
                when {
                    song != null -> onPlayRadio(
                        song,
                        QueueSource("Explore", PlaybackSourceType.EXPLORE),
                    )
                    browseId != null -> viewModel.openDetail(
                        browseId = browseId,
                        title = item.title,
                        subtitle = item.subtitle,
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            },
            onPlayAll = { items ->
                val songs = items.mapNotNull { item ->
                    item.videoId?.let { vid ->
                        Song(
                            videoId = vid,
                            title = item.title,
                            artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                            thumbnailUrl = item.thumbnailUrl,
                        )
                    }
                }
                if (songs.isNotEmpty()) {
                    onPlayFrom(songs, 0, QueueSource("Top Charts", PlaybackSourceType.BROWSE, "FEmusic_charts"))
                }
            },
            onShufflePlay = { items ->
                val songs = items.mapNotNull { item ->
                    item.videoId?.let { vid ->
                        Song(
                            videoId = vid,
                            title = item.title,
                            artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                            thumbnailUrl = item.thumbnailUrl,
                        )
                    }
                }.shuffled()
                if (songs.isNotEmpty()) {
                    onPlayFrom(songs, 0, QueueSource("Top Charts", PlaybackSourceType.BROWSE, "FEmusic_charts"))
                }
            },
            onAddToQueue = { items ->
                val songs = items.mapNotNull { item ->
                    item.videoId?.let { vid ->
                        Song(
                            videoId = vid,
                            title = item.title,
                            artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                            thumbnailUrl = item.thumbnailUrl,
                        )
                    }
                }
                songs.forEach { onAddToQueue(it) }
            },
            onOpenTrendingPlaylist = { regionCode, regionName, allSongs, videoShelf, artistShelf ->
                viewModel.openTrendingDetail(regionCode, regionName, allSongs, videoShelf, artistShelf)
            },
            onOpenTopicPlaylist = viewModel::openTopicPlaylist,
        )
    }
}

@Composable
internal fun SearchTabHost(
    viewModel: MainViewModel,
    context: Context,
    query: String,
    searchSource: SearchSource,
    searchLabel: String,
    currentSong: Song?,
    isPlaying: Boolean,
    listState: LazyListState,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    contentPadding: PaddingValues,
    downloadIdFor: (String?) -> String?,
    onPlayRadio: (Song, QueueSource) -> Unit,
    onPlayFrom: (List<Song>, Int, QueueSource) -> Unit,
    onOpenSongMenu: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onSongSwipeAction: (Song, SongSwipeAction) -> Unit,
    onSongHoldPlaylist: (Song) -> Unit,
    onSetPlaylistTarget: (Song) -> Unit,
    onOpenBrowseMenu: (BrowseTarget) -> Unit,
    onBlockedAddonSongClick: (Song) -> Unit,
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val libraryResults by viewModel.libraryResults.collectAsStateWithLifecycle()
    val addonResults by viewModel.addonResults.collectAsStateWithLifecycle()
    // Collected rather than read once, so addon rows in search re-dim the
    // moment the quality setting, the connection or the watch's cap moves.
    val addonCeiling = AppSettings.ceilingFor(
        wifi = AppSettings.audioQualityWifi.collectAsStateWithLifecycle().value,
        cellular = AppSettings.audioQualityCellular.collectAsStateWithLifecycle().value,
        metered = AppSettings.meteredConnection.collectAsStateWithLifecycle().value,
        override = AppSettings.streamCeilingOverride.collectAsStateWithLifecycle().value,
    )
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchSuggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val typeaheadResults by viewModel.typeaheadResults.collectAsStateWithLifecycle()
    val addonHistory by viewModel.addonSearchHistory.collectAsStateWithLifecycle()
    val searchLoadingMore by viewModel.searchLoadingMore.collectAsStateWithLifecycle()
    val searchScrollReset by viewModel.searchScrollReset.collectAsStateWithLifecycle()

    SearchScreen(
        query = query,
        onQueryChange = viewModel::onQueryChange,
        filter = filter,
        currentSong = currentSong,
        isPlaying = isPlaying,
        onFilterChange = viewModel::onFilterChange,
        results = results,
        loadingMore = searchLoadingMore,
        onLoadMore = viewModel::loadMoreSearchResults,
        listState = listState,
        scrollResetTrigger = searchScrollReset,
        focusRequested = focusRequested,
        onFocusHandled = onFocusHandled,
        // Search hits are alternatives to each other, not a running
        // order — play the one tapped and build a station from it.
        onSongClick = { songs, index ->
            songs.getOrNull(index)?.let { song ->
                viewModel.recordEntity(
                    SearchHistoryEntity(
                        id = song.videoId,
                        title = song.title,
                        subtitle = song.artist.ifEmpty { "" },
                        artworkUrl = song.thumbnailUrl,
                        entityType = EntityType.TRACK,
                    ),
                )
                onPlayRadio(song, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
            }
        },
        onSongLongPress = onOpenSongMenu,
        onSongSwipe = onSongSwipe,
        onSongSwipeAction = onSongSwipeAction,
        onSongHoldPlaylist = onSongHoldPlaylist,
        onTopResultPlay = { song ->
            viewModel.recordEntity(
                SearchHistoryEntity(
                    id = song.videoId,
                    title = song.title,
                    subtitle = song.artist.ifEmpty { "" },
                    artworkUrl = song.thumbnailUrl,
                    entityType = EntityType.TRACK,
                ),
            )
            onPlayRadio(song, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
        },
        onTopResultPlaylist = { song ->
            viewModel.recordEntity(
                SearchHistoryEntity(
                    id = song.videoId,
                    title = song.title,
                    subtitle = song.artist.ifEmpty { "" },
                    artworkUrl = song.thumbnailUrl,
                    entityType = EntityType.TRACK,
                ),
            )
            viewModel.loadPlaylists()
            onSetPlaylistTarget(song)
        },
        onBrowseClick = { item ->
            viewModel.recordEntity(
                SearchHistoryEntity(
                    id = item.browseId,
                    title = item.title,
                    subtitle = item.subtitle.ifBlank { "" },
                    artworkUrl = item.thumbnailUrl,
                    entityType = when (item.type) {
                        BrowseType.ALBUM -> EntityType.ALBUM
                        BrowseType.ARTIST -> EntityType.ARTIST
                        BrowseType.PLAYLIST -> EntityType.PLAYLIST
                        BrowseType.OTHER -> EntityType.OTHER
                    },
                ),
            )
            viewModel.openDetail(
                browseId = item.browseId,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
                type = item.type,
            )
        },
        onBrowseLongPress = { item ->
            // A search row does say what it is, so its own type is
            // better than what the browse id can be read to mean.
            if (item.type != BrowseType.ARTIST) {
                onOpenBrowseMenu(
                    BrowseTarget(
                        browseId = item.browseId,
                        title = item.title,
                        subtitle = item.subtitle,
                        thumbnailUrl = item.thumbnailUrl,
                        type = item.type,
                        downloadId = downloadIdFor(item.browseId),
                    ),
                )
            }
        },
        history = searchHistory,
        suggestions = searchSuggestions,
        typeaheadResults = typeaheadResults,
        onSubmit = viewModel::submitSearch,
        // Suggestions land in search history via searchFor → recordSearch.
        // History items (onHistoryClick) navigate/play without re-logging.
        onSuggestionClick = viewModel::searchFor,
        onHistoryClick = { entity ->
            // Tap a history entity: navigate to it or play it directly.
            // Do NOT recordEntity here — tapping an existing history item
            // must not update its timestamp and push it to the top.
            when (entity.entityType) {
                EntityType.TRACK -> {
                    // Play the track by its video id
                    onPlayRadio(
                        Song(
                            videoId = entity.id,
                            title = entity.title,
                            artist = entity.subtitle,
                            thumbnailUrl = entity.artworkUrl,
                        ),
                        QueueSource(entity.title, PlaybackSourceType.SEARCH),
                    )
                }
                EntityType.ALBUM, EntityType.ARTIST, EntityType.PLAYLIST, EntityType.OTHER -> {
                    viewModel.openDetail(
                        browseId = entity.id,
                        title = entity.title,
                        subtitle = entity.subtitle,
                        thumbnailUrl = entity.artworkUrl,
                    )
                }
            }
        },
        onHistoryRemove = viewModel::removeSearch,
        onHistoryClear = viewModel::clearSearchHistory,
        onTypeaheadLongPress = onOpenSongMenu,
        contentPadding = contentPadding,
        // Snug under the bar rather than a page's breathing
        // gap below it: the field is in the bar (see the bar's
        // accessory), so what comes next is the field's own
        // suggestions and filters, and a gap reads as a break.
        topPadding = topBarHeight() + 4.dp,
        showField = false,
        // Source switcher is in top bar accessory.
        sourceSwitcher = null,
        searchingLibrary = searchSource == SearchSource.LIBRARY,
        libraryResults = libraryResults,
        searchingAddons = searchSource == SearchSource.ADDONS,
        addonResults = addonResults,
        isBlocked = { song ->
            val configId = SourceRegistry.parseTrackKey(song.videoId)?.first
            !SourceRegistry.permitsForPlayback(configId, addonCeiling)
        },
        onBlockedSongClick = onBlockedAddonSongClick,
        addonHistory = addonHistory,
        onAddonHistoryClick = { term ->
            viewModel.onQueryChange(term)
            viewModel.submitSearch()
        },
        onAddonHistoryRemove = viewModel::removeAddonSearch,
        onAddonHistoryClear = viewModel::clearAddonSearchHistory,
        // The same queue opening the Local Music folder and
        // tapping the row there would give.
        onLibrarySongClick = { songs, index ->
            onPlayFrom(
                songs,
                index,
                if (searchSource == SearchSource.ADDONS) {
                    QueueSource(
                        context.getString(R.string.search_source_addons),
                        PlaybackSourceType.BROWSE,
                        "addons:search",
                    )
                } else {
                    QueueSource(
                        context.getString(R.string.local_music),
                        PlaybackSourceType.BROWSE,
                        "local:all",
                    )
                },
            )
        },
    )
}
