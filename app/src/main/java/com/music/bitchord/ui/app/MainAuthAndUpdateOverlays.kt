package com.music.bitchord

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.auth.CapturedSession
import com.music.bitchord.auth.DiscordLoginScreen
import com.music.bitchord.auth.SpotifyLoginScreen
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.auth.YtMusicLoginScreen
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.UpdateAvailableDialog
import com.music.bitchord.ui.onboarding.OnboardingScreen
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** UIKit's sheet curve: leaves at once, settles long — a sheet let go of, not pushed. */
private val OnboardingDismissEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * Isolated lyrics-loading observer so changing lyric preferences never invalidates
 * [BitChordApp]'s root composition scope.
 */
@Composable
internal fun LyricsLoaderEffect(
    viewModel: MainViewModel,
    song: Song?,
    durationMs: Long,
) {
    // Lyrics follow whatever is playing; duration lands a beat after the track.
    // Keyed on the lyric settings too, so turning a source on or off, reordering
    // them or changing the syllable preference applies to the track already
    // playing rather than only the next one.
    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val lyricsSourceOrder by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()
    LaunchedEffect(
        song?.videoId,
        durationMs,
        syncedLyricsEnabled,
        lyricsSources,
        lyricsSourceOrder,
        prioritizeSyllableSync,
    ) {
        song?.let {
            viewModel.loadLyrics(
                it.videoId,
                it.title,
                it.artist,
                // The player's own length, and the catalogue's where it has
                // none yet. Paused, ExoPlayer never finishes preparing the
                // track it was skipped to, so it reports no duration at all —
                // and a lookup that waits for one waits for ever, which left
                // the lyrics of a paused track loading until it was played.
                durationMs.takeIf { ms -> ms > 0L } ?: it.durationMillis(),
                it.albumName,
                it.localUri,
            )
        }
    }
}

@Composable
internal fun OnboardingOverlay(
    onboardingShown: MutableTransitionState<Boolean>,
    signedIn: Boolean,
    accountName: String?,
    onSignIn: () -> Unit,
    onConnectSpotify: () -> Unit,
    onOpenSpotifyTokenSetup: () -> Unit,
) {
    // There from the first frame, so no way in; on the way out it drops
    // like a dismissed sheet onto the app it was standing in front of.
    AnimatedVisibility(
        visibleState = onboardingShown,
        enter = EnterTransition.None,
        exit = slideOutVertically(
            tween<IntOffset>(durationMillis = 560, easing = OnboardingDismissEasing),
        ) { it },
    ) {
        val spotifySpdcToken by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
        OnboardingScreen(
            signedIn = signedIn,
            spotifyConnected = spotifySpdcToken.isNotBlank(),
            initialName = remember {
                ListenTogether.nickname().ifBlank { accountName.orEmpty() }
            },
            onNameChosen = ListenTogether::setNickname,
            onSignIn = onSignIn,
            onConnectSpotify = onConnectSpotify,
            onOpenSpotifyTokenSetup = onOpenSpotifyTokenSetup,
            onFinish = AppSettings::setOnboardingComplete,
        )
    }
}

@Composable
internal fun GoogleSignInWebViewOverlay(
    mode: WebSessionMode,
    initialCookie: String?,
    onDismiss: () -> Unit,
    onSessionCaptured: (
        session: CapturedSession,
        autoAttempt: Boolean,
        onNeedsChoice: () -> Unit,
        onResult: (Boolean) -> Unit,
    ) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // Raised by "Use this channel", read by the browser as "take the
    // session from the page as it now stands". A counter rather than a
    // flag so a second tap, after a failed first one, is still a new
    // request rather than a value that was already true.
    var captureRequest by remember(mode) { mutableIntStateOf(0) }
    var captureFailed by remember(mode) { mutableStateOf(false) }
    var pageReady by remember(mode) { mutableStateOf(false) }
    var confirmingProfile by remember(mode) { mutableStateOf(false) }
    // A fresh sign-in confirms itself once, the first time the signed-in
    // Music page is up. A login with several channels is rolled back by
    // the view model and left to the button; so is any attempt that finds
    // the page not ready yet.
    var autoTried by remember(mode) { mutableStateOf(false) }
    var autoAttempt by remember(mode) { mutableStateOf(false) }
    LaunchedEffect(pageReady) {
        if (pageReady && mode == WebSessionMode.SIGN_IN && !autoTried) {
            autoTried = true
            autoAttempt = true
            confirmingProfile = true
            captureRequest++
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (mode) {
                            WebSessionMode.SIGN_IN -> stringResource(R.string.sign_in_youtube_music)
                            WebSessionMode.SWITCH_CHANNEL -> stringResource(R.string.choose_profile)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (pageReady) {
                        Text(
                            text = if (captureFailed) {
                                stringResource(R.string.profile_unavailable)
                            } else {
                                stringResource(R.string.switch_profile_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                if (pageReady) {
                    TextButton(onClick = {
                        captureFailed = false
                        autoAttempt = false
                        confirmingProfile = true
                        captureRequest++
                    }, enabled = !confirmingProfile) {
                        Text(
                            if (confirmingProfile) stringResource(R.string.checking)
                            else stringResource(R.string.use_this_profile),
                        )
                    }
                }
            }
            YtMusicLoginScreen(
                mode = mode,
                initialCookie = initialCookie,
                captureRequest = captureRequest,
                onPageReady = { pageReady = it },
                onCaptureUnavailable = {
                    confirmingProfile = false
                    if (autoAttempt) autoAttempt = false else captureFailed = true
                },
                onCaptured = { session ->
                    val auto = autoAttempt
                    autoAttempt = false
                    onSessionCaptured(
                        session,
                        auto,
                        { confirmingProfile = false },
                    ) { accepted ->
                        confirmingProfile = false
                        if (!accepted) captureFailed = true
                    }
                },
            )
        }
    }
}

@Composable
internal fun UpdateAvailableOverlay(
    visible: Boolean,
    updateNotice: AppUpdateChecker.UpdateInfo?,
    hazeState: HazeState,
    context: Context,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible && updateNotice != null,
        enter = fadeIn(tween(durationMillis = 200)),
        exit = fadeOut(tween(durationMillis = 180)),
    ) {
        updateNotice?.let { update ->
            UpdateAvailableDialog(
                version = update.version,
                notes = update.notes,
                hazeState = hazeState,
                // A download in progress keeps running behind the closed
                // sheet — only the sheet itself goes away. The top bar's
                // update icon reopens it onto whatever state it reached.
                onDismiss = onDismiss,
                onDownload = {
                    if (update.apkUrl != null) {
                        scope.launch { AppUpdateChecker.downloadApk(context) }
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                        onDismiss()
                    }
                },
                onCancelDownload = {
                    AppUpdateChecker.cancelDownload()
                },
                onInstall = {
                    val ready = AppUpdateChecker.download.value as? AppUpdateChecker.DownloadState.Ready
                    ready?.let { AppUpdateChecker.installApk(context, it.file) }
                },
                onOpenReleasePage = {
                    AppUpdateChecker.resetDownload()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                    onDismiss()
                },
            )
        }
    }
}

@Composable
internal fun DiscordSignInWebViewOverlay(
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    "Sign in to Discord",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            DiscordLoginScreen(
                onTokenCaptured = { token ->
                    AppSettings.setDiscordToken(token)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
internal fun SpotifySignInWebViewOverlay(
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    stringResource(R.string.spotify_sign_in),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            SpotifyLoginScreen(
                modifier = Modifier.navigationBarsPadding(),
                onConnected = { token ->
                    AppSettings.setSpotifySpdcToken(token)
                    onDismiss()
                },
            )
        }
    }
}
