package com.music.bitchord.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.rounded.CloudOff
import com.music.bitchord.ui.components.ArtworkGrid
import com.music.bitchord.ui.components.GreenLikedCover
import com.music.bitchord.ui.components.trackArtSize
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.AppUi
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.music.bitchord.ui.components.longPressMenuClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import com.music.bitchord.ui.components.ShelfRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import com.music.bitchord.ui.icons.BitChordIcons
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.PrefetchVerticalArtworkAhead
import com.music.bitchord.ui.components.ShimmerBox
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickMood
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.selectSpotlightSong
import java.util.Locale
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.HERO_CARD_RATIO
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.VideoThumbnails
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.components.SignInBanner
import com.music.bitchord.ui.components.feedMoreSkeleton
import com.music.bitchord.ui.components.feedSkeleton
import com.music.bitchord.ui.components.recentlyPlayedSkeleton
import com.music.bitchord.ui.components.heroCardWidth
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.trackColumnWidth
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MeshPalette

/** How far the Listen now heading and feed sit above the shared top inset. */
private val HOME_TITLE_LIFT = 28.dp

private const val RECENTS_TITLE = "Recents"

/** The spotlight billboard's size, shared with the placeholder that holds its place. */
private val SPOTLIGHT_HEIGHT = 210.dp
private val SPOTLIGHT_CORNER = 24.dp
private val SPOTLIGHT_GAP = 20.dp

/** Holds the spotlight billboard's place while the feed loads. */
private fun androidx.compose.foundation.lazy.LazyListScope.spotlightSkeleton() {
    item(key = "skeleton:spotlight") {
        ShimmerBox(
            modifier = Modifier
                .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = SPOTLIGHT_GAP)
                .fillMaxWidth()
                .height(SPOTLIGHT_HEIGHT),
            shape = RoundedCornerShape(SPOTLIGHT_CORNER),
        )
    }
}

/** The cover a shelf's card loads, at the size it loads it, so a prefetch is a cache hit. */
private fun shelfArtwork(shelf: HomeShelf, index: Int, item: ShelfItem): String? = when {
    shelf.layout == ShelfLayout.VIDEO -> item.thumbnailUrl
    shelf.layout == ShelfLayout.LIST ||
        (index == 0 && shelf.title.equals(RECENTS_TITLE, ignoreCase = true)) -> item.thumbnailUrl.artworkAt(ROW_ART_PX)
    index == 0 && shelf.layout == ShelfLayout.CARDS -> item.thumbnailUrl.artworkAt(HEADER_ART_PX)
    else -> item.thumbnailUrl.artworkAt(CARD_ART_PX)
}
private const val RECENT_TRACKS_PER_COLUMN = 4

private fun List<ShelfItem>.playingItemMatcher(song: Song?): (ShelfItem) -> Boolean {
    song ?: return { false }
    // The card that was played, when the shelf holds it, and only that one —
    // see [com.music.bitchord.data.model.playingRowMatcher], which rows use.
    if (any { it.videoId == song.videoId }) return { it.videoId == song.videoId }
    val byName = firstOrNull { it.videoId != null && it.title == song.title && it.subtitle == song.artist }
        ?: return { false }
    return { it == byName }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    /** The tapped item and the recommendation shelf it came from. */
    onItemClick: (ShelfItem, String) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /** The page's heading, or null for none — the desktop's pages carry no heading. */
    title: String?,
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    /**
     * Holding a card rather than tapping it. Every card on the feed answers,
     * whichever kind it is: the caller reads the item the same way it does for
     * a tap, so a track card opens the track menu and a card that points at a
     * collection opens the album / playlist one.
     */
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    // Explore doesn't page — only Home has a continuation worth following.
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    recentlyPlayedLoading: Boolean = false,
    /**
     * Whether the feed's first shelf gets the big full-bleed cards. The phone
     * leads with them; on a window as wide as a desktop's they are posters, so
     * there every shelf is the ordinary row.
     */
    leadHero: Boolean = true,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    /**
     * The "Top Picks for You" row above Recents. Null while it has not been
     * built yet, empty when there is nothing to pick from. The row is only
     * drawn by a caller that can act on it, i.e. passes [onTopPickClick].
     */
    topPicks: List<TopPick>? = null,
    topPicksLoading: Boolean = false,
    /** The signed-in account's name, for the personal station's "Name's Station". */
    listenerName: String? = null,
    onTopPickClick: ((TopPick, String) -> Unit)? = null,
    onTopPickLongPress: ((TopPick) -> Unit)? = null,
    /** A Find Your Mood tile, and its name. The shelf is only drawn when this is set. */
    onMoodClick: ((TopPickMood, String) -> Unit)? = null,
    /** Find Your Mood's Show all: every mood and activity. */
    onMoodsShowAll: () -> Unit = {},
    /** Whether the Top Picks and mood artwork may animate — see [LocalArtworkMotion]. */
    artworkMotion: Boolean = true,
    onOpenDownloads: (() -> Unit)? = null,
    /** The Replay year story's button beside the wordmark, or null for none. See [ReplayYearButton]. */
    replayYear: ReplayYearBadge? = null,
) {
    val isOffline = AppUi.host.isOffline()
    // The billboard keeps the card it opened with while the feed still holds
    // it: a revalidated feed whose first card shelf now leads with another
    // would otherwise swap the biggest picture on the page under the reader.
    val heldSpotlight = remember { arrayOfNulls<ShelfItem>(1) }
    val spotlightItem = remember(state, currentSong, topPicks) {
        val shelves = (state as? UiState.Success)?.data ?: return@remember null
        // The shelf drawn right under the billboard (Recents aside, which is
        // only ever played tracks): its cards are never the billboard's too.
        val below = shelves.tailLast()
            .firstOrNull { !it.title.equals(RECENTS_TITLE, ignoreCase = true) }
            ?.items?.mapNotNull { it.videoId }?.toSet().orEmpty()
        val held = heldSpotlight[0]
        val kept = held?.let { previous ->
            if (currentSong?.videoId != previous.videoId && previous.videoId !in below) {
                shelves.firstNotNullOfOrNull { shelf ->
                    shelf.items.firstOrNull { it.videoId == previous.videoId && it.browseId == previous.browseId }
                }
            } else null
        }
        (kept ?: selectSpotlightSong(shelves, currentSong, topPicks, avoid = below))
            .also { heldSpotlight[0] = it }
    }
    // Top Picks' pictures and motion wait for the feed — see LocalArtworkImages.
    val feedReady = state !is UiState.Loading
    CompositionLocalProvider(
        LocalArtworkMotion provides (artworkMotion && feedReady),
        LocalArtworkImages provides feedReady,
    ) {
    // The loops also hold still while the feed is being scrolled — see
    // LocalArtworkScrolling for why that is asked rather than provided.
    PausedWhileScrolling(listState) {

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        // A feed opened at the top stays there until the listener scrolls it: the skeleton gives
        // way to the real shelves (and Recents and Top Picks land above them) and the list may
        // otherwise settle further down, on the spotlight. A feed that comes back scrolled, from
        // another tab, is left where it was.
        val openedAtTop = remember(listState) {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
        if (openedAtTop) {
            LaunchedEffect(listState) {
                var touched = false
                launch { snapshotFlow { listState.isScrollInProgress }.first { it }; touched = true }
                snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
                    if (!touched && index != 0) listState.scrollToItem(0)
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // The heading sits higher than the other tabs' first row: pull the
            // whole feed up by [HOME_TITLE_LIFT] with it.
            contentPadding = if (title != null) {
                PaddingValues(
                    top = (contentPadding.calculateTopPadding() - HOME_TITLE_LIFT).coerceAtLeast(0.dp),
                    bottom = contentPadding.calculateBottomPadding(),
                )
            } else {
                contentPadding
            },
        ) {
            if (title != null) {
                item { HomeTitle(replayYear) }
            }
            if (!signedIn && onSignIn != null) {
                item {
                    // Read in this item, so a scroll starting or ending
                    // recomposes the banner alone.
                    SignInBanner(
                        onSignIn = onSignIn,
                        modifier = Modifier.padding(bottom = 8.dp),
                        motion = LocalArtworkMotion.current && !listState.isScrollInProgress,
                    )
                }
            }
            if (onTopPickClick != null) {
                when {
                    !topPicks.isNullOrEmpty() -> item(key = "top-picks") {
                        Box(Modifier.animateItem()) {
                            TopPicksShelf(
                                picks = topPicks,
                                listenerName = listenerName,
                                onClick = onTopPickClick,
                                onLongPress = onTopPickLongPress,
                            )
                        }
                    }
                    topPicks == null && topPicksLoading && !isOffline -> topPicksSkeleton()
                }
            }
            when (state) {
                is UiState.Loading -> {
                    if (isOffline) {
                        item(key = "offline-home-empty") {
                            MessageState(
                                message = stringResource(Res.string.offline_empty),
                                actionLabel = stringResource(Res.string.retry),
                                onAction = onRetry,
                                secondaryActionLabel = if (onOpenDownloads != null) stringResource(Res.string.downloaded) else null,
                                onSecondaryAction = onOpenDownloads,
                            )
                        }
                    } else if (recentlyPlayedLoading) {
                        // In the order the real page lays them out: the
                        // billboard, then Recents, then the shelves. Each
                        // lands in its own place rather than pushing the
                        // others about.
                        spotlightSkeleton()
                        recentlyPlayedSkeleton()
                        // Recents owns the leading layout while its request is
                        // pending, so the feed behind it starts with ordinary
                        // shelf placeholders rather than another hero card.
                        feedSkeleton(firstIsHero = false)
                    } else {
                        spotlightSkeleton()
                        feedSkeleton(firstIsHero = leadHero)
                    }
                }
                is UiState.Error -> item {
                    MessageState(
                        message = state.message,
                        actionLabel = stringResource(Res.string.retry),
                        onAction = onRetry,
                        secondaryActionLabel = if (onOpenDownloads != null) stringResource(Res.string.downloaded) else null,
                        onSecondaryAction = onOpenDownloads,
                    )
                }
                is UiState.Success -> {
                    if (spotlightItem != null) {
                        item(key = "home-hero-spotlight-billboard") {
                            HeroBillboardSpotlight(
                                item = spotlightItem,
                                onClick = { onItemClick(spotlightItem, "Spotlight") },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = SPOTLIGHT_GAP),
                            )
                        }
                    }
                    // Below the billboard, where Recents itself lands: Recents
                    // is the feed's first shelf, which comes after it.
                    if (recentlyPlayedLoading && !isOffline) {
                        recentlyPlayedSkeleton()
                    }
                    // The loading skeleton already owns the hero slot. Until
                    // Recently Played lands, every real shelf must retain its
                    // compact-card layout instead of briefly becoming a hero.
                    itemsIndexedShelves(
                        shelves = state.data.tailLast(),
                        onMoodClick = onMoodClick,
                        onMoodsShowAll = onMoodsShowAll,
                        onItemClick = onItemClick,
                        onItemLongPress = onItemLongPress,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        firstIsHero = leadHero && (!recentlyPlayedLoading || isOffline),
                    )
                    if (loadingMore && !isOffline) feedMoreSkeleton()
                }
            }
        }
    }

    }
    }

    if (state is UiState.Success) {
        // One entry per item of the list above, so "the next few items" lands
        // on the right shelves: the rows ahead of the feed's first shelf have
        // no covers of their own here (Top Picks fetches its own), and Find
        // Your Mood sits between the first shelf and the second. The covers of
        // the first screen are asked for by the view model as the feed lands.
        val leadingItems = (if (title != null) 1 else 0) +
            (if (!signedIn && onSignIn != null) 1 else 0) +
            (if (onTopPickClick != null && (!topPicks.isNullOrEmpty() || (topPicks == null && topPicksLoading && !isOffline))) 1 else 0) +
            (if (spotlightItem != null) 1 else 0) +
            (if (recentlyPlayedLoading && !isOffline) 1 else 0)
        val hasMoods = onMoodClick != null
        val artworkByItem = remember(state.data, leadingItems, hasMoods) {
            buildList {
                repeat(leadingItems) { add(emptyList()) }
                state.data.tailLast().forEachIndexed { index, shelf ->
                    if (index == 1 && hasMoods) add(emptyList())
                    add(shelf.items.take(6).map { item -> shelfArtwork(shelf, index, item) })
                }
            }
        }
        PrefetchVerticalArtworkAhead(
            state = listState,
            artworkByItem = artworkByItem,
            ahead = 3,
        )
    }

    if (onLoadMore != null && state is UiState.Success) {
        val loadMore by rememberUpdatedState(onLoadMore)
        // Re-checked on every layout change, rather than on the rising edge of
        // "the tail is in view". A page that appends only a shelf or two leaves
        // the list still near its end, so an edge-triggered effect would never
        // fire a second time: the feed dead-ended at the bottom with no
        // skeleton and no request in flight to explain it. Restarting on
        // [loadingMore] re-checks the moment a page settles, so the next one is
        // asked for while the tail is still on screen to show it loading.
        LaunchedEffect(listState, loadingMore, isOffline) {
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (!isOffline && !loadingMore && total > 0 && lastVisible >= total - 3) loadMore()
            }
        }
    }
}

/**
 * The feed in the order Home draws it: the listener's own shelves first, the
 * ones borrowed from New releases and Explore ([HomeShelf.tail]) after them,
 * each group in the order it came. Shelves paged in later still land above
 * the borrowed ones, so those stay at the very end.
 */
internal fun List<HomeShelf>.tailLast(): List<HomeShelf> =
    if (none { it.tail }) this else filterNot { it.tail } + filter { it.tail }


/**
 * Recents mirrors the artist page's top-tracks pager. Any other lead shelf keeps
 * Apple's full-bleed treatment, while the remaining shelves use square cards.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedShelves(
    shelves: List<HomeShelf>,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
    firstIsHero: Boolean = true,
    onMoodClick: ((TopPickMood, String) -> Unit)? = null,
    onMoodsShowAll: () -> Unit = {},
) {
    // Keyed by title, not by position: Recents arriving at the top used to
    // shift every index, so every shelf was thrown away and built again (and
    // lost its horizontal scroll). Titles are kept unique by the view model;
    // a repeat that slips through still gets a key of its own.
    val seen = HashMap<String, Int>()
    val keys = shelves.map { shelf ->
        val base = "shelf:" + shelf.title.lowercase(Locale.ROOT)
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        if (n == 1) base else "$base#$n"
    }
    shelves.forEachIndexed { index, shelf ->
        // Find Your Mood follows the lead shelf, as on Apple Music's Home.
        if (index == 1 && onMoodClick != null) {
            item(key = "find-your-mood") {
                Box(Modifier.animateItem()) {
                    FindYourMoodShelf(onMoodClick, onMoodsShowAll)
                }
            }
        }
        item(key = keys[index]) {
            Box(Modifier.animateItem()) {
            val openItem: (ShelfItem) -> Unit = remember(shelf.title, onItemClick) { { item -> onItemClick(item, shelf.title) } }
            // Listen again and Quick picks are lists on the web player, as
            // Recents is here: one pager for all three.
            if (shelf.layout == ShelfLayout.LIST ||
                (index == 0 && shelf.title.equals(RECENTS_TITLE, ignoreCase = true))
            ) {
                RecentShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            } else if (index == 0 && firstIsHero && shelf.layout == ShelfLayout.CARDS) {
                HeroShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            } else {
                Shelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            }
            }
        }
    }
}

/** The same four-rows-per-page treatment used by an artist's Top songs. */
@Composable
private fun RecentShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        RecentSectionHeader(title = localizeShelfTitle(shelf.title), subtitle = localizeShelfSubtitle(shelf.subtitle))
        val isCurrentItem = shelf.items.playingItemMatcher(currentSong)
        BoxWithConstraints {
            val columnWidth = trackColumnWidth(maxWidth)
            val chunkedColumns = remember(shelf.items) { shelf.items.chunked(RECENT_TRACKS_PER_COLUMN) }
            val columnArtwork = remember(chunkedColumns) {
                chunkedColumns.map { col ->
                    col.map { it.thumbnailUrl.artworkAt(ROW_ART_PX) }
                }
            }
            ShelfRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                artworkGroups = columnArtwork,
            ) {
                items(
                    chunkedColumns,
                    key = { col -> col.firstOrNull()?.let { it.videoId ?: it.browseId ?: it.title } ?: col.hashCode() },
                ) { column ->
                    Column(Modifier.width(columnWidth)) {
                        column.forEach { item ->
                            RecentTrackRow(
                                item = item,
                                onClick = { onItemClick(item) },
                                onLongPress = onItemLongPress?.let { { it(item) } },
                                isCurrent = isCurrentItem(item),
                                isPlaying = isCurrentItem(item) && isPlaying,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentTrackRow(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    isCurrent: Boolean,
    isPlaying: Boolean,
) {
    val offlineBlocked = AppUi.host.unplayableOffline(videoId = item.videoId, browseId = item.browseId)
    CompactTrackRow(
        title = item.title,
        subtitle = item.subtitle,
        thumbnailUrl = item.thumbnailUrl,
        onClick = if (offlineBlocked) ({}) else onClick,
        onLongPress = onLongPress.takeUnless { offlineBlocked },
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        isVideo = item.isVideo,
        dimmed = offlineBlocked,
    )
}

/**
 * One row of Recents' four-to-a-column pager. Public so the desktop artist page
 * can list its top songs the same way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactTrackRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    /** A music video: its thumbnail at 16:9 rather than squared off. */
    isVideo: Boolean = false,
    dimmed: Boolean = false,
) {
    val dimAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.38f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "compactTrackDim",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (dimmed) {
                    Modifier
                } else {
                    Modifier
                        .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
                        .contextClick(onLongPress)
                },
            )
            .alpha(dimAlpha)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.trackArtSize(48.dp, isVideo)) {
            AsyncImage(
                model = thumbnailUrl.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(7.dp))
                    .thumbnailBorder(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onLongPress != null) {
            RowMoreButton(
                onClick = onLongPress,
                hangOutset = 0.dp,
                modifier = Modifier.padding(end = 5.dp),
            )
        }
    }
}

/**
 * Listen now's large heading, with the wordmark stacked above it.
 *
 * Only the home tab carries it — the top bar no longer shows the logo, so this
 * is where the app's mark lives. Its height is taken from the heading's own
 * font size rather than a fixed dp, so the two keep their proportion under the
 * system font scale.
 */
@Composable
private fun HomeTitle(
    replayYear: ReplayYearBadge? = null,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.displayLarge
    val logoHeight = with(LocalDensity.current) { (style.fontSize * LOGO_TO_FONT).toDp() }
    Column(modifier = modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp)) {
        // The wordmark, and the year story's button right after it when there
        // is a year to tell. Sized off the wordmark so the two keep their
        // proportion under the system font scale.
        Row(
            modifier = Modifier.padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .height(logoHeight)
                    .aspectRatio(LOGO_ASPECT),
            )
            if (replayYear != null) {
                Spacer(Modifier.width(14.dp))
                ReplayYearButton(replayYear, height = logoHeight * 0.68f)
            }
        }
    }
}

/** The wordmark's height against the heading's font size. */
private const val LOGO_TO_FONT = 0.9f

/** ic_logo's viewport, 730 x 484. */
private const val LOGO_ASPECT = 730f / 484f

/**
 * Shared by the home feed, Explore and Library so headings line up across tabs.
 *
 * [onShowAll] is only ever set on Library, whose rows stop at five cards
 * rather than running the shelf's whole length — see [LibraryGridShelf].
 * Home and Explore never pass it, so their heading is unchanged.
 */
@Composable
internal fun SectionHeader(title: String, subtitle: String = "", onShowAll: (() -> Unit)? = null) {
    val displayTitle = localizeShelfTitle(title)
    val displaySubtitle = localizeShelfSubtitle(subtitle)
    Row(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (displaySubtitle.isNotBlank()) {
                Text(
                    text = displaySubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onShowAll != null) {
            Text(
                text = stringResource(Res.string.show_all),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onShowAll)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}
@Composable
internal fun localizeShelfTitle(title: String): String {
    val trimmed = title.trim()
    return when {
        trimmed.equals("Recents", ignoreCase = true) ||
            trimmed.equals("Recently played", ignoreCase = true) ||
            trimmed.equals("Gần đây", ignoreCase = true) ||
            trimmed.equals("最近", ignoreCase = true) ->
            stringResource(Res.string.shelf_recents)
        trimmed.equals("Playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát", ignoreCase = true) ||
            trimmed.equals("再生リスト", ignoreCase = true) ->
            stringResource(Res.string.playlists)
        trimmed.equals("Albums", ignoreCase = true) ||
            trimmed.equals("Album", ignoreCase = true) ||
            trimmed.equals("アルバム", ignoreCase = true) ->
            stringResource(Res.string.albums)
        trimmed.equals("Artists", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ", ignoreCase = true) ||
            trimmed.equals("アーティスト", ignoreCase = true) ->
            stringResource(Res.string.artists)
        trimmed.equals("Subscriptions", ignoreCase = true) ||
            trimmed.equals("Đã đăng ký", ignoreCase = true) ||
            trimmed.equals("登録チャンネル", ignoreCase = true) ->
            stringResource(Res.string.subscriptions)
        trimmed.equals("Trending community playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát cộng đồng thịnh hành", ignoreCase = true) ||
            trimmed.equals("Danh sách phát thịnh hành trong cộng đồng người dùng", ignoreCase = true) ||
            trimmed.equals("急上昇のコミュニティ再生リスト", ignoreCase = true) ->
            stringResource(Res.string.shelf_trending_community_playlists)
        trimmed.equals("Featured playlists for you", ignoreCase = true) ||
            trimmed.equals("Danh sách phát đề xuất cho bạn", ignoreCase = true) ||
            trimmed.equals("Danh sách phát nổi bật dành cho bạn", ignoreCase = true) ||
            trimmed.equals("おすすめの再生リスト", ignoreCase = true) ->
            stringResource(Res.string.shelf_featured_playlists_for_you)
        trimmed.equals("Quick picks", ignoreCase = true) ||
            trimmed.equals("Lựa chọn nhanh", ignoreCase = true) ||
            trimmed.equals("Chọn nhanh đài phát", ignoreCase = true) ||
            trimmed.equals("クイック ミックス", ignoreCase = true) ->
            stringResource(Res.string.shelf_quick_picks)
        trimmed.equals("Listen again", ignoreCase = true) ||
            trimmed.equals("Nghe lại", ignoreCase = true) ||
            trimmed.equals("もう一度聴く", ignoreCase = true) ->
            stringResource(Res.string.shelf_listen_again)
        trimmed.equals("Mixed for you", ignoreCase = true) ||
            trimmed.equals("Dành riêng cho bạn", ignoreCase = true) ||
            trimmed.equals("ミックス", ignoreCase = true) ->
            stringResource(Res.string.shelf_mixed_for_you)
        trimmed.startsWith("Similar to", ignoreCase = true) -> {
            val rest = trimmed.substring(10).trim()
            stringResource(Res.string.shelf_similar_to, rest)
        }
        trimmed.startsWith("Tương tự như", ignoreCase = true) -> {
            val rest = trimmed.substring(12).trim()
            stringResource(Res.string.shelf_similar_to, rest)
        }
        trimmed.equals("Forgotten favorites", ignoreCase = true) ||
            trimmed.equals("Giai điệu quen thuộc", ignoreCase = true) ||
            trimmed.equals("よく聴いたお気に入りの曲", ignoreCase = true) ->
            stringResource(Res.string.shelf_forgotten_favorites)
        trimmed.equals("Recommended music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc đề xuất", ignoreCase = true) ||
            trimmed.equals("おすすめのミュージック ビデオ", ignoreCase = true) ->
            stringResource(Res.string.shelf_recommended_music_videos)
        trimmed.equals("From your library", ignoreCase = true) ||
            trimmed.equals("Từ thư viện của bạn", ignoreCase = true) ||
            trimmed.equals("ライブラリから", ignoreCase = true) ->
            stringResource(Res.string.shelf_from_your_library)
        trimmed.equals("Charts", ignoreCase = true) ||
            trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
            trimmed.equals("チャート", ignoreCase = true) ->
            stringResource(Res.string.shelf_charts)
        trimmed.equals("New releases", ignoreCase = true) ||
            trimmed.equals("Bản phát hành mới", ignoreCase = true) ||
            trimmed.equals("最新リリース", ignoreCase = true) ->
            stringResource(Res.string.shelf_new_releases)
        trimmed.equals("Top music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc hàng đầu", ignoreCase = true) ||
            trimmed.equals("人気のミュージック ビデオ", ignoreCase = true) ->
            stringResource(Res.string.shelf_top_music_videos)
        trimmed.equals("For you", ignoreCase = true) ||
            trimmed.equals("Dành cho bạn", ignoreCase = true) ||
            trimmed.equals("あなたへのおすすめ", ignoreCase = true) ->
            stringResource(Res.string.shelf_for_you)
        trimmed.equals("Hits today", ignoreCase = true) ||
            trimmed.equals("Today's Hits", ignoreCase = true) ||
            trimmed.equals("Bản hit hôm nay", ignoreCase = true) ||
            trimmed.equals("今日のヒット曲", ignoreCase = true) ->
            stringResource(Res.string.shelf_hits_today)
        trimmed.equals("Artists on the rise", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ đang lên", ignoreCase = true) ->
            stringResource(Res.string.shelf_artists_on_the_rise)
        trimmed.equals("Concerts", ignoreCase = true) ||
            trimmed.equals("Buổi hòa nhạc", ignoreCase = true) ||
            trimmed.equals("コンサート", ignoreCase = true) ->
            stringResource(Res.string.shelf_concerts)
        trimmed.startsWith("Shorts", ignoreCase = true) ||
            trimmed.equals("Shorts nổi bật", ignoreCase = true) ||
            trimmed.equals("ショート", ignoreCase = true) ->
            stringResource(Res.string.shelf_shorts)
        trimmed.equals("Trending", ignoreCase = true) ||
            trimmed.equals("Thịnh hành", ignoreCase = true) ||
            trimmed.equals("急上昇", ignoreCase = true) ->
            stringResource(Res.string.shelf_trending)
        else -> title
    }
}
@Composable
internal fun localizeShelfSubtitle(subtitle: String): String {
    val trimmed = subtitle.trim()
    return when {
        trimmed.equals("TOP TUNES RIGHT NOW", ignoreCase = true) ||
            trimmed.equals("Top tunes right now", ignoreCase = true) ||
            trimmed.equals("Giai điệu hàng đầu hiện nay", ignoreCase = true) ||
            trimmed.equals("注目の曲", ignoreCase = true) ->
            stringResource(Res.string.shelf_top_tunes_right_now)
        trimmed.equals("From the community", ignoreCase = true) ||
            trimmed.equals("Từ cộng đồng", ignoreCase = true) ||
            trimmed.equals("コミュニティより", ignoreCase = true) ->
            stringResource(Res.string.shelf_from_the_community)
        trimmed.equals("YouTube Charts", ignoreCase = true) ||
            trimmed.equals("Bảng xếp hạng YouTube", ignoreCase = true) ||
            trimmed.equals("YouTube チャート", ignoreCase = true) ->
            stringResource(Res.string.shelf_youtube_charts)
        else -> subtitle
    }
}

@Composable
internal fun localizeCardSubtitle(subtitle: String): String {
    if (subtitle.isBlank()) return subtitle
    val delimiter = " • "
    val parts = subtitle.split(delimiter)
    val songLabel = stringResource(Res.string.shelf_song_item)
    val singleLabel = stringResource(Res.string.shelf_single)
    val chartLabel = stringResource(Res.string.shelf_chart)
    val playlistLabel = stringResource(Res.string.playlist)
    val localizedParts = parts.map { part ->
        val trimmed = part.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        when {
            trimmed.equals("Song", ignoreCase = true) || trimmed.equals("Titre", ignoreCase = true) ||
                trimmed.equals("Bài hát", ignoreCase = true) || trimmed.equals("曲", ignoreCase = true) -> songLabel
            trimmed.equals("Single", ignoreCase = true) || trimmed.equals("Đĩa đơn", ignoreCase = true) ||
                trimmed.equals("シングル", ignoreCase = true) -> singleLabel
            trimmed.equals("Chart", ignoreCase = true) || trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
                trimmed.equals("チャート", ignoreCase = true) -> chartLabel
            trimmed.equals("Playlist", ignoreCase = true) || trimmed.equals("Danh sách phát", ignoreCase = true) ||
                trimmed.equals("再生リスト", ignoreCase = true) -> playlistLabel
            lower.endsWith(" views") || lower.endsWith(" view") || lower.endsWith(" lượt xem") ||
                lower.endsWith(" 回視聴") || lower.endsWith("回視聴") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(Res.string.card_views_format, count) else part
            }
            lower.endsWith(" plays") || lower.endsWith(" play") || lower.endsWith(" lượt phát") ||
                lower.endsWith(" 回再生") || lower.endsWith("回再生") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(Res.string.card_plays_format, count) else part
            }
            lower.endsWith(" songs") || lower.endsWith(" song") || lower.endsWith(" bài hát") ||
                lower.endsWith(" 曲") || lower.endsWith("曲") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(Res.string.card_songs_format, count) else part
            }
            else -> part
        }
    }
    return localizedParts.joinToString(delimiter)
}

@Composable
private fun HeroShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(shelf.title, shelf.subtitle)
        val isCurrentItem = shelf.items.playingItemMatcher(currentSong)
        // Measured rather than taken as a share of the parent, because the card
        // has a ceiling as well as a fraction — see [heroCardWidth]. A fixed
        // width is also the only one of the two the aspect ratio below can turn
        // into a height, so the card keeps its shape however it was arrived at.
        BoxWithConstraints {
            val cardWidth = heroCardWidth(maxWidth)
            val artworkList = remember(shelf.items) {
                shelf.items.map { it.thumbnailUrl.artworkAt(HEADER_ART_PX) }
            }
            ShelfRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                artwork = artworkList,
            ) {
                items(shelf.items, key = { it.videoId ?: it.browseId ?: it.title }) { item ->
                    HeroCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        modifier = Modifier.width(cardWidth),
                        isCurrent = isCurrentItem(item),
                        isPlaying = isCurrentItem(item) && isPlaying,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroBillboardSpotlight(
    item: ShelfItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineBlocked = AppUi.host.unplayableOffline(videoId = item.videoId, browseId = item.browseId)
    val dimAlpha by animateFloatAsState(
        targetValue = if (offlineBlocked) 0.38f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "headerDim",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SPOTLIGHT_HEIGHT)
            .clip(RoundedCornerShape(SPOTLIGHT_CORNER))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .alpha(dimAlpha)
            .then(if (offlineBlocked) Modifier else Modifier.clickable(onClick = onClick)),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.88f),
                        ),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.featured_badge),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(Res.string.play),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
            }
        }
    }
}

/** Big card: artwork with the caption laid over a scrim, as on Listen Now. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Box(
        modifier = modifier
            .aspectRatio(HERO_CARD_RATIO)
            .clip(RoundedCornerShape(18.dp))
            .thumbnailBorder(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                val displaySubtitle = localizeCardSubtitle(item.subtitle)
                Text(
                    text = displaySubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * [leadingCard] rides at the head of the row, ahead of the content — the
 * Library tab's "New playlist" tile, which belongs among the playlists rather
 * than in a bar somewhere above them. [onItemLongPress] opens the album /
 * playlist menu, and is null only where a card points at something with no
 * track list behind it to act on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Shelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    leadingCard: (@Composable () -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(shelf.title, shelf.subtitle)
        val isCurrentItem = shelf.items.playingItemMatcher(currentSong)
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            artwork = remember(shelf.items, shelf.layout, leadingCard) {
                List(if (leadingCard != null) 1 else 0) { null } + shelf.items.map { item ->
                    // Video cards load the raw thumbnail; the rest load it sized.
                    if (shelf.layout == ShelfLayout.VIDEO) item.thumbnailUrl else item.thumbnailUrl.artworkAt(CARD_ART_PX)
                }
            },
        ) {
            leadingCard?.let { card -> item(key = "leading") { card() } }
            items(shelf.items, key = { it.videoId ?: it.browseId ?: it.title }) { item ->
                if (shelf.layout == ShelfLayout.VIDEO) {
                    VideoShelfCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        isCurrent = isCurrentItem(item),
                        isPlaying = isCurrentItem(item) && isPlaying,
                    )
                } else {
                    ShelfCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        isCurrent = isCurrentItem(item),
                        isPlaying = isCurrentItem(item) && isPlaying,
                    )
                }
            }
        }
    }
}

/**
 * A music video, as the web player lays one out: the thumbnail at its own
 * 16:9 rather than cropped into a square, the title and credit under it. About
 * as tall as the square cards, so a video shelf sits in the feed's rhythm.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoShelfCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(VIDEO_CARD_WIDTH),
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    titleColor: Color = MaterialTheme.colorScheme.onBackground,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    placeholderColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val offlineBlocked = AppUi.host.unplayableOffline(videoId = item.videoId, browseId = item.browseId)
    val dimAlpha by animateFloatAsState(
        targetValue = if (offlineBlocked) 0.38f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "videoCardDim",
    )
    Column(
        modifier = modifier
            .then(
                if (offlineBlocked) {
                    Modifier
                } else {
                    Modifier
                        .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
                        .contextClick(onLongPress)
                },
            )
            .alpha(dimAlpha),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(VIDEO_ASPECT)
                .clip(RoundedCornerShape(12.dp))
                .thumbnailBorder(RoundedCornerShape(12.dp))
                .background(placeholderColor),
        ) {
            AsyncImage(
                // Video stills are not resizable googleusercontent art; the
                // largest the row listed is already about card size.
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = localizeCardSubtitle(item.subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A music video's frame, or a square one when the listener asked for that — see [VideoThumbnails]. */
val VIDEO_ASPECT: Float get() = if (VideoThumbnails.square) 1f else 16f / 9f

/** Wide enough that a 16:9 card stands about as tall as a square [SHELF_CARD_WIDTH] one. */
val VIDEO_CARD_WIDTH: Dp get() = if (VideoThumbnails.square) SHELF_CARD_WIDTH else 248.dp

/**
 * A card that isn't a thing yet — the dashed "New playlist" tile at the head
 * of the Library's playlist row, sized to sit in line with the covers beside
 * it rather than as a button bolted above them.
 */
@Composable
internal fun NewShelfCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    /** A full-colour mark drawn in place of [icon], for a card that stands for a brand. */
    logo: DrawableResource? = null,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(com.music.bitchord.ui.theme.cardSurface()),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                // The mark is a disc with the bars cut out, so tinting it
                // leaves the bars clear: white on dark, black on light.
                Icon(
                    painter = painterResource(logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(44.dp),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A device-folder card: mesh gradient in the folder's colours with its glyph
 * on top. One composable for local music, WebDAV and SMB rather than three
 * copies of the same box.
 */
@Composable
private fun ServiceCard(colors: List<Color>, trackKey: String, icon: ImageVector) {
    val palette = remember { MeshPalette(colors) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        MeshGradientBackground(
            palette = palette,
            trackKey = trackKey,
            continuous = true,
            blurRadius = 24.dp,
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    /** Set on a Library playlist card that's in [AppSettings.pinnedPlaylists][com.music.bitchord.data.settings.AppSettings.pinnedPlaylists]. */
    isPinned: Boolean = false,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    val offlineBlocked = AppUi.host.unplayableOffline(videoId = item.videoId, browseId = item.browseId)
    val dimAlpha by animateFloatAsState(
        targetValue = if (offlineBlocked) 0.38f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "shelfCardDim",
    )
    val isArtist = item.browseId?.removePrefix("MPLA")?.startsWith("UC") == true
    val cardShape = if (isArtist) CircleShape else RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = modifier
            .then(
                if (offlineBlocked) {
                    Modifier
                } else {
                    Modifier
                        .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
                        .contextClick(onLongPress)
                },
            )
            .alpha(dimAlpha),
    ) {
        when (item.browseId) {
            "local:downloads" -> {
                val palette = remember { MeshPalette(listOf(Color(0xFF1E3C72), Color(0xFF2A5298))) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    MeshGradientBackground(
                        palette = palette,
                        trackKey = "local:downloads",
                        continuous = true,
                        blurRadius = 24.dp,
                    )
                    Icon(
                        imageVector = BitChordIcons.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            "local:all" -> ServiceCard(
                colors = listOf(Color(0xFF134E5E), Color(0xFF71B280)),
                trackKey = "local:all",
                icon = Icons.Rounded.LibraryMusic,
            )
            "local:offlinemusic" -> {
                val artworks = AppUi.host.offlineCoverArtworks()
                if (artworks.isNotEmpty()) {
                    val tiles = if (artworks.size >= 4) artworks.take(4) else List(4) { artworks[it % artworks.size] }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .thumbnailBorder(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.weight(1f)) {
                                AsyncImage(
                                    model = tiles[0].artworkAt(CARD_ART_PX / 2),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                AsyncImage(
                                    model = tiles[1].artworkAt(CARD_ART_PX / 2),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            Row(Modifier.weight(1f)) {
                                AsyncImage(
                                    model = tiles[2].artworkAt(CARD_ART_PX / 2),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                AsyncImage(
                                    model = tiles[3].artworkAt(CARD_ART_PX / 2),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.30f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                } else {
                    ServiceCard(
                        colors = listOf(Color(0xFF232526), Color(0xFF414345)),
                        trackKey = "local:offlinemusic",
                        icon = Icons.Rounded.CloudOff,
                    )
                }
            }
            "local:cache" -> ServiceCard(
                colors = listOf(Color(0xFF42275A), Color(0xFF734B6D)),
                trackKey = "local:cache",
                icon = Icons.Rounded.Cached,
            )
            "local:webdav" -> ServiceCard(
                colors = listOf(Color(0xFF3A1C71), Color(0xFFD76D77)),
                trackKey = "local:webdav",
                icon = Icons.Rounded.Folder,
            )
            "local:smb" -> ServiceCard(
                colors = listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
                trackKey = "local:smb",
                icon = Icons.Rounded.Storage,
            )
            else -> {
                // A playlist kept on this device (an imported one included) has no cover of its own,
                // so it is drawn as the same 2x2 collage the other playlists show.
                val gridTiles = item.browseId?.takeIf { it.startsWith("local:playlist:") || it.startsWith("local:offline:") }
                    ?.let { AppUi.host.localPlaylistCoverArtworks(it) }
                    .orEmpty()
                val greenLiked = AppUi.host.isGreenLikedCover(item.browseId)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(cardShape)
                        .thumbnailBorder(cardShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (greenLiked) {
                        GreenLikedCover(Modifier.fillMaxSize())
                    } else if (gridTiles.isNotEmpty()) {
                        ArtworkGrid(List(4) { gridTiles[it % gridTiles.size] }, Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isArtist) Arrangement.Center else Arrangement.Start,
        ) {
            if (isPinned) {
                Icon(
                    imageVector = BitChordIcons.Pin,
                    contentDescription = stringResource(Res.string.pinned),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        val displaySubtitle = localizeCardSubtitle(item.subtitle)
        Text(
            text = displaySubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (isArtist) Modifier.fillMaxWidth() else Modifier,
        )
    }
}

/**
 * [ShelfCard] as a row: a small cover, the title and subtitle beside it. The
 * Library's list style — an artist's cover is a disc, as it is in search.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfListRow(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
) {
    val offlineBlocked = AppUi.host.unplayableOffline(videoId = item.videoId, browseId = item.browseId)
    val dimAlpha by animateFloatAsState(
        targetValue = if (offlineBlocked) 0.38f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "shelfListRowDim",
    )
    val shape = if (item.browseId?.removePrefix("MPLA")?.startsWith("UC") == true) CircleShape else RoundedCornerShape(LIST_COVER_RADIUS)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (offlineBlocked) {
                    Modifier
                } else {
                    Modifier
                        .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
                        .contextClick(onLongPress)
                },
            )
            .alpha(dimAlpha)
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val gridTiles = item.browseId?.takeIf { it.startsWith("local:playlist:") || it.startsWith("local:offline:") }
            ?.let { AppUi.host.localPlaylistCoverArtworks(it) }
            .orEmpty()
        val greenLiked = AppUi.host.isGreenLikedCover(item.browseId)
        Box(
            modifier = Modifier
                .size(LIST_COVER_SIZE)
                .clip(shape)
                .thumbnailBorder(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (greenLiked) {
                GreenLikedCover(Modifier.fillMaxSize(), iconSize = 24.dp)
            } else if (gridTiles.isNotEmpty()) {
                ArtworkGrid(
                    tiles = List(4) { gridTiles[it % gridTiles.size] },
                    modifier = Modifier.fillMaxSize(),
                    artPx = LIST_COVER_ART_PX / 2,
                )
            } else {
                AsyncImage(
                    model = item.thumbnailUrl.artworkAt(LIST_COVER_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPinned) {
                    Icon(
                        imageVector = BitChordIcons.Pin,
                        contentDescription = stringResource(Res.string.pinned),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = localizeCardSubtitle(item.subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** [NewShelfCard] as a row, for the list style. */
@Composable
internal fun NewShelfListRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    logo: DrawableResource? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(LIST_COVER_SIZE)
                .clip(RoundedCornerShape(LIST_COVER_RADIUS))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                Icon(
                    painter = painterResource(logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(30.dp),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The Library's list style: a cover about as big as Spotify's, so it reads at a glance. */
private val LIST_COVER_SIZE = 64.dp
private val LIST_COVER_RADIUS = 8.dp
private const val LIST_COVER_ART_PX = 256
