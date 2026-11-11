package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import com.music.bitchord.ui.components.playlistRowsSkeleton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.ui.theme.DestructiveRed
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.spotify.SpotifyCache
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.spotify.SpotifySync
import com.music.bitchord.data.spotify.SpotifySyncState
import com.music.bitchord.data.spotify.SyncErrorKind
import com.music.bitchord.data.spotify.syncErrorKind
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.ui.components.DestinationOptionCard
import com.music.bitchord.ui.components.MenuFrostDark
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SpotifyImportDestination
import com.music.bitchord.ui.components.dialogFrostBackground
import com.music.bitchord.ui.components.liveMenuFrost
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.topBarContentPadding
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import org.jetbrains.compose.resources.painterResource

/**
 * The Spotify page's playlists and, while one is being made, the choice of
 * which to import. Held above the page so the top bar's import button can start the
 * choosing — the same split as [LocalFolderState] for Downloads.
 */
@Stable
class SpotifyLibraryState {
    var playlists by mutableStateOf<List<SpotifyPlaylist>>(SpotifyCache.getPlaylists().orEmpty())
        internal set

    /** True while the list is being picked from rather than opened. */
    var selecting by mutableStateOf(false)
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var showIntroModal by mutableStateOf(false)
    var destination by mutableStateOf(SpotifyImportDestination.LOCAL)

    fun requestStartSelecting() {
        showIntroModal = true
    }

    fun dismissIntroModal() {
        showIntroModal = false
    }

    /** Starts choosing, with what is not on the device yet already ticked. */
    fun startSelecting() {
        showIntroModal = false
        val imported = LocalPlaylistStore.playlists.value.mapNotNull { it.spotifyId }.toSet()
        selectedIds = playlists.map { it.id }.filter { it !in imported }.toSet()
        selecting = true
    }

    fun toggle(playlist: SpotifyPlaylist) {
        selectedIds = if (playlist.id in selectedIds) selectedIds - playlist.id else selectedIds + playlist.id
    }

    fun selectAll() {
        selectedIds = playlists.mapTo(linkedSetOf()) { it.id }
    }

    /** Unticks everything but stays in the choosing, unlike [clear]. */
    fun deselectAll() {
        selectedIds = emptySet()
    }

    fun clear() {
        selecting = false
        selectedIds = emptySet()
        showIntroModal = false
    }

    /** Imports what is ticked and leaves the choosing. */
    fun importSelected(onYouTubeImport: ((List<SpotifyPlaylist>) -> Unit)? = null) {
        val selected = playlists.filter { it.id in selectedIds }
        if (destination == SpotifyImportDestination.YOUTUBE && onYouTubeImport != null) {
            onYouTubeImport(selected)
        } else {
            SpotifySync.dismiss()
            SpotifySync.start(selected)
        }
        clear()
    }
}

/**
 * The signed-in Spotify account's playlists. A tap opens one as an ordinary
 * playlist page ([onOpenPlaylist]) — this screen is only the way in.
 *
 * Importing happens on the list itself, the way Downloads selects: the rows
 * take a tick, and a bar floats under the top bar with the count and the
 * action. The same bar then reports the import while it runs and once it
 * ends, so the page never leaves for a dialog.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun SpotifyLibraryScreen(
    state: SpotifyLibraryState,
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTokenSetup: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    signedIn: Boolean = false,
    bottomBarsHeight: Dp = 0.dp,
    onOpenLinkImport: (() -> Unit)? = null,
    onImportToYouTube: ((List<SpotifyPlaylist>) -> Unit)? = null,
) {
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    var isManualRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by a pull from the top of the list, to read the library again.
    var reload by remember { mutableIntStateOf(0) }
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(cookie, reload) {
        if (cookie.isBlank()) {
            state.playlists = emptyList()
            state.clear()
            SpotifyCache.clear()
            error = null
            isManualRefreshing = false
            return@LaunchedEffect
        }
        if (state.playlists.isEmpty()) {
            val cached = SpotifyCache.getPlaylists()
            if (!cached.isNullOrEmpty()) {
                state.playlists = cached
            }
        }
        loading = true
        error = null
        try {
            val fresh = SpotifyLibrary.playlists()
            state.playlists = fresh
            SpotifyCache.savePlaylists(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state.playlists.isEmpty() || reload > 0) {
                error = when (syncErrorKind(e)) {
                    SyncErrorKind.SIGNED_OUT -> context.getString(R.string.spotify_sync_error_signed_out)
                    SyncErrorKind.NETWORK -> context.getString(R.string.spotify_sync_error_network)
                    SyncErrorKind.OTHER -> context.getString(R.string.spotify_sync_error_other, e.message.orEmpty())
                }
            }
        } finally {
            loading = false
            isManualRefreshing = false
        }
    }

    // A library kept up to date brings the playlists already imported up to date.
    val autoSync by AppSettings.spotifyAutoSync.collectAsStateWithLifecycle()
    // Not on every look at the page: a run asks about every playlist, so one
    // that finished a few hours ago stands.
    LaunchedEffect(state.playlists, autoSync) {
        if (!autoSync || !SpotifyCache.autoSyncDue(AUTO_SYNC_MIN_INTERVAL_MS)) return@LaunchedEffect
        val imported = LocalPlaylistStore.playlists.value.mapNotNull { it.spotifyId }.toSet()
        val due = state.playlists.filter { it.id in imported }
        if (due.isEmpty()) return@LaunchedEffect
        SpotifySync.start(due)
        SpotifyCache.markAutoSynced()
    }

    val sync by SpotifySync.state.collectAsStateWithLifecycle()
    val local by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val importedIds = remember(local) { local.mapNotNull { it.spotifyId }.toSet() }
    val failures = remember(sync) {
        when (val s = sync) {
            is SpotifySyncState.Running -> s.failures
            is SpotifySyncState.Finished -> s.failures
            else -> emptyList()
        }.associateBy { it.playlist.id }
    }
    val activeId = (sync as? SpotifySyncState.Running)?.playlist?.id
    var unmatchedDialogTracks by remember { mutableStateOf<List<SpotifyTrack>?>(null) }

    BackHandler(enabled = state.selecting) {
        state.clear()
    }

    // Pulling down from the top reads the library again. The indicator sits
    // where the page's own content starts, and only appears during explicit manual refresh.
    PullToRefreshBox(
        isRefreshing = isManualRefreshing,
        onRefresh = {
            isManualRefreshing = true
            reload++
        },
        state = pullState,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isManualRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.spotify_logo),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(38.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.spotify),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            if (cookie.isBlank()) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            text = stringResource(R.string.spotify_connect_subtitle),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onOpenLogin) {
                            Text(stringResource(R.string.spotify_sign_in))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenTokenSetup) {
                            Text(stringResource(R.string.spotify_use_spdc_token))
                        }
                    }
                }
            } else {
                if (state.selecting) {
                    item { SelectionHeader(state = state, signedIn = signedIn) }
                }
                // Clean playlist skeleton rows while loading initially
                if (loading && state.playlists.isEmpty()) {
                    playlistRowsSkeleton(count = 6, keyPrefix = "skeleton:spotify")
                }
                error?.let { message ->
                    item {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                items(state.playlists, key = { it.id }) { item ->
                    val failure = failures[item.id]
                    LibraryRow(
                        title = item.name,
                        subtitle = when {
                            failure != null -> syncErrorText(failure.kind, failure.detail)
                            item.id in importedIds -> listOfNotNull(
                                item.owner?.takeIf { it.isNotBlank() },
                                stringResource(R.string.spotify_sync_imported),
                            ).joinToString(" · ")
                            else -> item.owner.orEmpty()
                        },
                        subtitleIsError = failure != null,
                        imageUrl = item.imageUrl,
                        trailing = when {
                            state.selecting -> if (item.id in state.selectedIds) RowTrailing.Selected else RowTrailing.Unselected
                            item.id == activeId -> RowTrailing.Importing
                            else -> RowTrailing.None
                        },
                        onClick = { if (state.selecting) state.toggle(item) else onOpenPlaylist(item) },
                    )
                }
            }
        }

        if (!state.selecting && sync !is SpotifySyncState.Idle) {
            SyncBar(
                sync = sync,
                onSignIn = onOpenLogin,
                onViewUnmatched = { unmatchedDialogTracks = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topBarContentPadding(), start = PAGE_GUTTER, end = PAGE_GUTTER),
            )
        }

        unmatchedDialogTracks?.let { tracks ->
            UnmatchedTracksDialog(
                tracks = tracks,
                onDismiss = { unmatchedDialogTracks = null },
            )
        }

        // The one action of choosing sits where the thumb is, resting right above
        // the miniplayer with its respective gap.
        val haptics = rememberHaptics()
        val bottomOffset = if (bottomBarsHeight > 0.dp) {
            bottomBarsHeight + 14.dp
        } else {
            (contentPadding.calculateBottomPadding() - 50.dp).coerceAtLeast(16.dp)
        }
        AnimatedVisibility(
            visible = state.selecting,
            enter = fadeIn(spring(0.82f, 380f)) +
                    slideInVertically(spring(0.82f, 380f)) { it } +
                    scaleIn(spring(0.82f, 380f), initialScale = 0.85f),
            exit = fadeOut(spring(0.82f, 380f)) +
                   slideOutVertically(spring(0.82f, 380f)) { it } +
                   scaleOut(spring(0.82f, 380f), targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomOffset),
        ) {
            val count = state.selectedIds.size
            val import = stringResource(R.string.spotify_import_button)
            val destTag = if (state.destination == SpotifyImportDestination.LOCAL) {
                stringResource(R.string.spotify_import_destination_tag_local)
            } else {
                stringResource(R.string.spotify_import_destination_tag_youtube)
            }
            BarPill(
                icon = if (state.destination == SpotifyImportDestination.LOCAL) Icons.Rounded.PhoneAndroid else Icons.Rounded.Cloud,
                label = if (count > 0) "$import ($destTag) · $count" else "$import ($destTag)",
                filled = true,
                enabled = count > 0,
                onClick = {
                    haptics.play(Haptic.Resume)
                    state.importSelected(onImportToYouTube)
                },
                modifier = Modifier
                    .shadow(12.dp, CircleShape)
                    .animateContentSize(spring(0.82f, 380f))
                    .widthIn(min = 200.dp),
            )
        }
        }
    }
}

/**
 * Clean and comfortable selection header showing selected count, interactive destination pill,
 * select/deselect action, and auto-sync switch without cluttered raw text.
 */
@Composable
private fun SelectionHeader(
    state: SpotifyLibraryState,
    signedIn: Boolean,
) {
    val count = state.selectedIds.size
    val allSelected = state.playlists.isNotEmpty() && count == state.playlists.size
    val autoSync by AppSettings.spotifyAutoSync.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = state::clear) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(R.string.selected_count, count),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            // Interactive destination toggle pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = signedIn) {
                        state.destination = if (state.destination == SpotifyImportDestination.LOCAL) {
                            SpotifyImportDestination.YOUTUBE
                        } else {
                            SpotifyImportDestination.LOCAL
                        }
                    }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (state.destination == SpotifyImportDestination.LOCAL) {
                            Icons.Rounded.PhoneAndroid
                        } else {
                            Icons.Rounded.Cloud
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = if (state.destination == SpotifyImportDestination.LOCAL) {
                            stringResource(R.string.spotify_import_destination_tag_local)
                        } else {
                            stringResource(R.string.spotify_import_destination_tag_youtube)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { if (allSelected) state.deselectAll() else state.selectAll() }) {
                Text(
                    text = stringResource(if (allSelected) R.string.deselect_all else R.string.select_all),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = autoSync,
                    role = Role.Switch,
                    onValueChange = {
                        haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)
                        AppSettings.setSpotifyAutoSync(it)
                    },
                )
                .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Sync,
                contentDescription = null,
                tint = if (autoSync) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.spotify_sync_auto),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.spotify_sync_auto_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = autoSync, onCheckedChange = null)
        }
        HorizontalDivider(Modifier.padding(horizontal = PAGE_GUTTER).padding(bottom = 4.dp))
    }
}

@Composable
private fun syncErrorText(kind: SyncErrorKind, detail: String): String = when (kind) {
    SyncErrorKind.SIGNED_OUT -> stringResource(R.string.spotify_sync_error_signed_out)
    SyncErrorKind.NETWORK -> stringResource(R.string.spotify_sync_error_network)
    SyncErrorKind.OTHER -> stringResource(R.string.spotify_sync_error_other, detail)
}

/**
 * The floating pill the page's bars share: the shape, lift and fill of
 * Downloads' selection bar ([LocalFolderSelectionBar]), so both read as the
 * same control.
 */
@Composable
private fun FloatingBar(
    modifier: Modifier,
    progress: (() -> Float)? = null,
    indeterminate: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Box(modifier.floating().heightIn(min = 48.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
        // A hairline of progress along the bar's foot rather than a widget of its own.
        val trackModifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
        when {
            progress != null -> LinearProgressIndicator(
                progress = progress,
                modifier = trackModifier,
                trackColor = Color.Transparent,
                drawStopIndicator = {},
            )
            indeterminate -> LinearProgressIndicator(modifier = trackModifier, trackColor = Color.Transparent)
        }
    }
}

/** The lift, corners and fill the page's floating bars share with Downloads' ([LocalFolderSelectionBar]). */
@Composable
private fun Modifier.floating(): Modifier {
    val shape = RoundedCornerShape(14.dp)
    return fillMaxWidth()
        .shadow(10.dp, shape)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
}

/** Height of the two capsules at the foot of the selection bar. */
private val BAR_PILL_HEIGHT = 44.dp

/**
 * The capsule of choosing, in the primary colour (white with black type on
 * dark, as Play is). [enabled] off dims it until something is ticked.
 */
@Composable
private fun BarPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when {
        filled -> (if (dark) Color.White else scheme.primary) to (if (dark) Color.Black else scheme.onPrimary)
        else -> scheme.onSurface.copy(alpha = 0.08f) to scheme.onSurface
    }
    Row(
        modifier = modifier
            .height(BAR_PILL_HEIGHT)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where an import stands, in the selection bar's place: the playlist under way
 * and a hairline of progress while it runs, then how it ended.
 */
@Composable
private fun SyncBar(
    sync: SpotifySyncState,
    onSignIn: () -> Unit,
    onViewUnmatched: (List<SpotifyTrack>) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (sync) {
        is SpotifySyncState.Running -> FloatingBar(
            modifier = modifier,
            progress = if (sync.songsTotal > 0) ({ sync.fraction }) else null,
            indeterminate = sync.songsTotal == 0,
        ) {
            BarText(
                title = sync.playlist.name,
                subtitle = if (sync.songsTotal > 0) {
                    stringResource(
                        R.string.spotify_sync_step_matching,
                        sync.index, sync.total, sync.songsDone, sync.songsTotal,
                    )
                } else {
                    stringResource(R.string.spotify_sync_step_reading, sync.index, sync.total)
                },
            )
            TextButton(onClick = SpotifySync::cancel) {
                Text(stringResource(R.string.cancel), color = DestructiveRed)
            }
        }
        is SpotifySyncState.Finished -> FloatingBar(modifier) {
            BarText(
                title = if (sync.cancelled) {
                    stringResource(R.string.spotify_sync_cancelled, sync.imported)
                } else {
                    stringResource(R.string.spotify_sync_done, sync.imported, sync.added, sync.unmatched)
                },
                subtitle = sync.failures.takeIf { it.isNotEmpty() }?.let {
                    stringResource(R.string.spotify_sync_some_failed, it.size)
                },
                subtitleIsError = true,
            )
            if (sync.unmatchedTracks.isNotEmpty()) {
                TextButton(onClick = { onViewUnmatched(sync.unmatchedTracks) }) {
                    Text(stringResource(R.string.spotify_sync_unmatched_view, sync.unmatchedTracks.size))
                }
            }
            if (sync.failures.isNotEmpty()) {
                TextButton(onClick = SpotifySync::retryFailed) {
                    Text(stringResource(R.string.spotify_sync_retry))
                }
            }
            CloseButton()
        }
        is SpotifySyncState.Aborted -> FloatingBar(modifier) {
            BarText(title = syncErrorText(sync.kind, sync.detail), titleIsError = true)
            if (sync.kind == SyncErrorKind.SIGNED_OUT) {
                TextButton(
                    onClick = {
                        SpotifySync.dismiss()
                        onSignIn()
                    },
                ) { Text(stringResource(R.string.spotify_sign_in)) }
            }
            CloseButton()
        }
        SpotifySyncState.Idle -> Unit
    }
}

@Composable
private fun UnmatchedTracksDialog(
    tracks: List<SpotifyTrack>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.spotify_sync_unmatched_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.spotify_sync_unmatched_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tracks) { track ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (track.artist.isNotBlank()) {
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun RowScope.BarText(
    title: String,
    subtitle: String? = null,
    titleIsError: Boolean = false,
    subtitleIsError: Boolean = false,
) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (titleIsError) MaterialTheme.colorScheme.error else ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (subtitleIsError) MaterialTheme.colorScheme.error else ink.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CloseButton() {
    IconButton(onClick = SpotifySync::dismiss) {
        Icon(
            Icons.Rounded.Close,
            contentDescription = stringResource(R.string.close),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}


/** How long after an automatic sync the next one waits, however often the page is opened. */
private const val AUTO_SYNC_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

private enum class RowTrailing { None, Selected, Unselected, Importing }

/** The Library's list style: a 64 dp cover, about Spotify's own size. */
private val ROW_COVER_SIZE = 64.dp
private val ROW_COVER_SHAPE = RoundedCornerShape(8.dp)

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    subtitleIsError: Boolean,
    imageUrl: String?,
    trailing: RowTrailing,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(ROW_COVER_SIZE)
                .clip(ROW_COVER_SHAPE)
                .thumbnailBorder(ROW_COVER_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (subtitleIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (trailing) {
            RowTrailing.Selected -> TrailingIcon(Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(R.string.selected))
            RowTrailing.Unselected -> TrailingIcon(Icons.Rounded.RadioButtonUnchecked, MaterialTheme.colorScheme.onSurfaceVariant, null)
            RowTrailing.Importing -> {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            RowTrailing.None -> Unit
        }
    }
}

@Composable
private fun TrailingIcon(icon: ImageVector, tint: Color, description: String?) {
    Spacer(Modifier.width(12.dp))
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
}

/**
 * Modern introductory modal for importing Spotify library playlists.
 * Informs the user they are entering the import system and gives them a clear choice
 * between local on-device storage and directly syncing to YouTube Music.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun SpotifyLibraryIntroDialog(
    hazeState: HazeState?,
    signedIn: Boolean,
    destination: SpotifyImportDestination,
    onDestinationChange: (SpotifyImportDestination) -> Unit,
    onStartSelecting: () -> Unit,
    onDismiss: () -> Unit,
    onOpenLinkImport: (() -> Unit)? = null,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val dialogShape = RoundedCornerShape(26.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(min = 300.dp, max = 360.dp)
                .fillMaxWidth()
                .dialogFrostBackground(hazeState, reduceDynamicBlur, dialogShape)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = 380f,
                    ),
                )
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(Res.drawable.spotify_logo),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(42.dp),
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.spotify_library_intro_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.spotify_library_intro_desc),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                ),
                color = Color.White.copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Local
                val isLocal = destination == SpotifyImportDestination.LOCAL
                DestinationOptionCard(
                    title = stringResource(R.string.spotify_import_target_local_title),
                    subtitle = stringResource(R.string.spotify_import_target_local_desc),
                    icon = Icons.Rounded.PhoneAndroid,
                    selected = isLocal,
                    enabled = true,
                    onClick = { onDestinationChange(SpotifyImportDestination.LOCAL) },
                )

                // YouTube
                val isYoutube = destination == SpotifyImportDestination.YOUTUBE
                DestinationOptionCard(
                    title = stringResource(R.string.spotify_import_target_youtube_title),
                    subtitle = if (signedIn) {
                        stringResource(R.string.spotify_import_target_youtube_desc)
                    } else {
                        stringResource(R.string.spotify_import_target_youtube_login_req)
                    },
                    icon = Icons.Rounded.Cloud,
                    selected = isYoutube && signedIn,
                    enabled = signedIn,
                    onClick = {
                        if (signedIn) onDestinationChange(SpotifyImportDestination.YOUTUBE)
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onStartSelecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
            ) {
                Text(
                    text = stringResource(R.string.spotify_library_intro_start_selecting),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.height(4.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.spotify_import_cancel),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.60f),
                )
            }
        }
    }
}
