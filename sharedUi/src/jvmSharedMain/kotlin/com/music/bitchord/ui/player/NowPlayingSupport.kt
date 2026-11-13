package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.LyricsProviderState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * Comfortably over the sleeve's drawn size on a phone, without wasting bytes.
 *
 * A rung on the app-wide ladder rather than a number of the player's own, so a
 * large home-screen widget asks for the same copy — see [PLAYER_ART_PX].
 */
internal const val ART_PX = PLAYER_ART_PX

/**
 * How many further goes a cover that failed to load gets.
 *
 * Small on purpose. This is here for the connection that drops for a moment or
 * the request that loses a race with the app coming back to the foreground, not
 * for a track whose artwork has genuinely gone: past a few tries the answer is
 * not going to change, and the placeholder tile is the honest thing to draw.
 */
internal const val ART_RETRIES = 3

/** How long to leave it before trying a failed cover again. */
internal const val ART_RETRY_DELAY_MS = 1_500L

/**
 * How long a canvas lookup waits for the track's album name before giving up
 * on it. Long enough to cover the album lookup on a normal connection, short
 * enough not to be noticed on a track that has no album to find.
 */
internal const val ALBUM_SETTLE_MS = 700L

/**
 * How close the player's reported position has to get to a released scrub
 * handle before the handle stops being drawn where it was dropped. Wide enough
 * to swallow a coarse progress tick, tight enough that the handle doesn't hand
 * over while it is still visibly wrong.
 */
internal const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

/**
 * How long that handle is held at the drop point regardless. A backstop, not a
 * schedule: a seek normally settles in a tick or two, and this only decides how
 * long a seek that never settles can freeze the bar for. Generous enough that a
 * slow buffer still hands over smoothly rather than snapping back.
 */
internal const val SEEK_SETTLE_TIMEOUT_MS = 4_000L

internal val THUMB_SIZE = 54.dp
internal val HEADER_HEIGHT = 60.dp
internal val ART_TITLE_GAP = 20.dp
internal val TITLE_EXTRA_DROP = 6.dp

/**
 * How long the sleeve takes to travel the whole way between the full player and
 * the queue's header.
 *
 * Spent in proportion rather than in full: a drag released four fifths of the
 * way up has a fifth of the journey left and gets a fifth of the time for it.
 * Only the toggle, which travels end to end, ever spends all of it.
 */
internal const val QUEUE_TRAVEL_MS = 420

/**
 * How far up the sleeve has to have been dragged for a release to carry on
 * opening the queue rather than falling back, as a share of the sleeve's travel.
 *
 * Well under half, because the gesture is only ever *started* deliberately —
 * there is nothing else an upward drag on the artwork could have meant — so the
 * doubt a halfway line exists to settle isn't there.
 */
internal const val QUEUE_CARRY_FRACTION = 0.3f

/**
 * How fast a release has to be moving, in pixels a second, to decide the queue
 * on its own and overrule [QUEUE_CARRY_FRACTION].
 *
 * A flick is a whole gesture in its own right: it says "open" without ever
 * asking the finger to travel, and the distance it covered is beside the point.
 */
internal const val QUEUE_FLICK_VELOCITY = 450f

/**
 * The handle strip above the artwork, which always hands drags to the sheet.
 *
 * It isn't the only place that does — the artwork and the credits under it pass
 * theirs on as well, which is what makes the whole top of the player closable
 * rather than just its topmost 32dp. See the dismiss band in `NowPlayingScreen`.
 */
internal val DISMISS_STRIP_HEIGHT = 32.dp

/** The breathing room above the sleeve, needed twice: once to apply, once to measure past. */
internal val ART_BOX_TOP_PAD = 8.dp

/**
 * How far below the sleeve's top edge the video/audio pill floats.
 *
 * Far enough to clear the artwork's rounded corners, so the pill reads as
 * something laid on the cover rather than something clipped by it.
 */
internal val VERSION_PILL_ART_INSET = 12.dp

/**
 * Share of the motion-artwork banner's height given over to its dissolve.
 *
 * Generous on purpose: the banner has no card edge to stop at, so anything
 * short enough to still be reading as artwork where it ends reads as a picture
 * that was cut off rather than one that ran out.
 */
internal const val HERO_FADE_FRACTION = 0.18f

/** A modest floor while a subview replaces the hero with its blurred artwork backdrop. */
internal const val SUBVIEW_STATUS_SCRIM_MIN_ALPHA = 0.40f

/**
 * How often the legacy mesh re-reads the colours of a playing Canvas clip.
 *
 * Every three seconds, with the mesh's own fade easing each read into the last
 * so the backdrop arrives at its new colour rather than cutting to it. The read is
 * the expensive half — a texture readback off the GPU — and this is the number
 * that decides how many of them there are; the fade is the cheap half and is
 * over well inside the gap, which leaves the backdrop still for most of it.
 */
internal const val MESH_REFRESH_MS = 3_000L

/** How long the mesh's seam takes to reach a clip's edge, in step with the clip's own fade. */
internal const val MESH_SEAM_MOVE_MS = 320

/**
 * The widest portrait clip that still gets [PortraitClipScrim]. Narrower than a
 * 3:4 cover, which stops above the controls and has the mesh to stand on there.
 */
internal const val PORTRAIT_SCRIM_MAX_ASPECT = 0.7f

internal val PortraitClipScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    0.40f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.70f),
)

/**
 * The default theme's backdrop — see [ArtworkMeshBackdrop] — in a scope of its
 * own: a clip's new frame every few seconds, and the seam moving, reach only
 * this rather than the whole of the player.
 */
@Composable
internal fun PlayerMeshBackdrop(
    trackKey: String,
    imageUrl: String?,
    canvasFrame: () -> ImageBitmap?,
    seamTarget: () -> Dp,
    alpha: () -> Float,
) {
    val mesh = key(trackKey) { rememberArtworkMesh(imageUrl, canvasFrame(), ART_PX) }
    val currentSeamTarget by rememberUpdatedState(seamTarget)
    val seam = remember { Animatable(Snapshot.withoutReadObservation { seamTarget() }, Dp.VectorConverter) }
    // Glides to a clip's edge as the clip fades in, and is put straight where
    // the first measure says: nothing should be seen settling as the player opens.
    LaunchedEffect(seam) {
        snapshotFlow { currentSeamTarget() }.collectLatest { target ->
            if (seam.value == 0.dp || target == 0.dp) {
                seam.snapTo(target)
            } else {
                seam.animateTo(target, tween(MESH_SEAM_MOVE_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    ArtworkMeshBackdrop(
        mesh = mesh,
        seam = { seam.value },
        modifier = Modifier.graphicsLayer { this.alpha = alpha() },
    )
}

/** The legacy theme's backdrop, in a scope of its own for the same reason. */
@Composable
internal fun LegacyMeshBackdrop(
    imageUrl: String?,
    canvasFrame: () -> ImageBitmap?,
    trackKey: String,
    modifier: Modifier,
) {
    MeshGradientBackground(
        palette = rememberArtworkColors(imageUrl, canvasFrame()),
        trackKey = trackKey,
        modifier = modifier,
    )
}

/** How long a full-screen Spotify Canvas holds still after the player has opened. */
internal const val SPOTIFY_CANVAS_RESUME_DELAY_MS = 1_000L

/** [image] filling this scope, centred and cropped as [ContentScale.Crop] would. */
internal fun DrawScope.drawCropped(image: ImageBitmap) {
    if (size.width <= 0f || size.height <= 0f) return
    val scale = maxOf(size.width / image.width, size.height / image.height)
    val srcWidth = (size.width / scale).roundToInt().coerceIn(1, image.width)
    val srcHeight = (size.height / scale).roundToInt().coerceIn(1, image.height)
    drawImage(
        image = image,
        srcOffset = IntOffset((image.width - srcWidth) / 2, (image.height - srcHeight) / 2),
        srcSize = IntSize(srcWidth, srcHeight),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}

/** An animated cover's frame, and where in its clip it was taken. */
@Immutable
internal class CanvasStill(val url: String, val image: ImageBitmap, val positionMs: Long)

/**
 * The animated cover's frame from where it last held still for the player
 * moving, kept outside the player so it outlives the player closing. One at a
 * time: it is only ever wanted for the clip that is on screen.
 */
internal object CanvasStills {
    var current by mutableStateOf<CanvasStill?>(null)

    /** Whether [current] is still the clip's frame, rather than one it has played on from. */
    var fresh by mutableStateOf(false)
}

/** The player's side margin. Scrollable panels reach back across it. */
internal val PLAYER_GUTTER = 20.dp

/**
 * How wide the player's content is ever allowed to get. A sleeve and a volume
 * slider stretched right across a tablet aren't a bigger player, just a coarser
 * one; past this the column stops growing and centres itself instead. Phones
 * are narrower than this, so for them it does nothing.
 */
internal val PLAYER_MAX_WIDTH = 560.dp

/**
 * The width from which the player counts as tablet-sized: its backdrop is
 * the full-cover blur in every state, legacy mesh or not, and Settings keeps
 * the full-bleed artwork switch listed — see
 * [fullBleedArtworkAvailable].
 *
 * 700dp is the figure the docked-player layout used to call "tablet sized"
 * (a 360dp page beside a 340dp pane). That layout is gone; the number stays,
 * so removing it moved no breakpoint.
 */
private val TABLET_PLAYER_MIN_WIDTH = 700.dp

/**
 * The least a landscape window has to offer before the player splits into its
 * two columns: enough that each half still holds what it is given — the sleeve
 * above the lyrics / output / queue row on the left, the credits and transport
 * on the right — with the bottom row's three-up capsule still fitting across
 * the narrower half.
 *
 * Low enough to take in a phone turned sideways, which is the point: a phone in
 * landscape and a tablet in landscape are the same shape, and they get the same
 * layout rather than the portrait player squashed into a window it was never
 * drawn for.
 */
private val LANDSCAPE_PLAYER_MIN_WIDTH = 560.dp

/**
 * The artwork's own play/pause/scrub pose in the landscape layout. Three flat
 * scales and one priority rule: paused always wins outright over a scrub in
 * progress, rather than the two combining — there is one artwork, in one of
 * three settled poses, never a blend of two.
 */
internal const val ARTWORK_EXPANDED_SCALE = 1f
internal const val ARTWORK_PAUSE_SHRINK_SCALE = 0.88f
internal const val ARTWORK_DRAG_SHRINK_SCALE = 0.94f

/**
 * The curve and duration those three poses move between — an ease-out cubic
 * over 500ms rather than a spring. A spring reads wrong for a press-and-release
 * gesture specifically: it visibly lags a quick scrub and keeps settling after
 * the finger has already lifted.
 *
 * The phone layout keeps its own bouncy spring (`artScale`) — it is answering a
 * different thing there, a sleeve that also collapses into a header, and the
 * bounce is the signature.
 */
internal val ArtworkScaleEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
internal const val ARTWORK_SCALE_DURATION_MS = 500

/**
 * How far a tall screen is allowed to push the transport from the blocks either
 * side of it.
 *
 * The spare height has to land somewhere, and above and below the play button is
 * where it reads as room rather than as a hole. Past this it stops reading as one
 * group of controls, so the rest goes back to the artwork block.
 */
internal val CONTROL_GAP_SPREAD_MAX = 24.dp

/**
 * How much of the sheet's travel the rest of the player has gone by before
 * the artwork arrives at the mini player: it is fully faded with this share of
 * the way still to go.
 */
internal const val DOCK_FADE_LEAD = 0.15f

/** The least the faded player is drawn at while docking — see the player's fade. */
internal const val DOCK_FADE_FLOOR = 1f / 255f

/**
 * What one frame of docking into the mini player worked out — see
 * [PlayerDock]. Written while the sleeve is laid out and read further down
 * the same frame, by the tile inside the sleeve and by the player's draw; plain
 * fields rather than state, because nothing should recompose or re-lay out for
 * them — the sheet's offset already drives every frame that reads them.
 */
internal class DockFrame {
    /**
     * Where the player sits on screen with the sheet fully open. State, the
     * one field here that is: it arrives a frame after the sheet first lays
     * out, and the frame it arrives in has to hear about it.
     */
    var anchor: Offset? by mutableStateOf(null)
    /** The player's size [anchor] was measured at. */
    var anchorFor = IntSize.Zero
    /** The mini player's cover, in the sleeve's box. */
    var miniInBox: Rect? = null
    /** The sheet's offset this frame, in the whole pixels it is placed at. */
    var offset = 0f
    /** How much of that the artwork rides — see `dockRide`. */
    var ride = 0f
    /** The sleeve as laid out, in the same box. */
    var sleeve: Rect = Rect.Zero
    /**
     * The sleeve's corner as a shape, made again only when its radius moves:
     * both of the sleeve's clipping layers ask for it, on every frame of any
     * transition, and an unchanged radius needs no new shape.
     */
    private var cornerRadius = Dp.Unspecified
    private var corner: Shape = RectangleShape
    fun cornerShape(radius: Dp): Shape {
        if (radius != cornerRadius) {
            cornerRadius = radius
            corner = RoundedCornerShape(radius)
        }
        return corner
    }
    /** Where the sleeve's pixels land in the player, for drawing it on top. */
    var portalLeft = 0f
    var portalTop = 0f
    /**
     * How much smaller than laid out the sleeve is drawn there. Always 1 for
     * the portrait sleeve, which is re-laid out at its size each frame; the
     * landscape one keeps its layout and is scaled down onto the cover.
     */
    var portalScale = 1f
    /** The player itself, for placing the landscape sleeve within it. */
    var host: LayoutCoordinates? = null
    /** The landscape sleeve, as last placed. */
    var landscapeArt: LayoutCoordinates? = null
}

/**
 * One frame of the landscape sleeve's trip to the mini player: where it is
 * drawn in the player, and how small. See `landscapeDockPose`.
 */
internal class LandscapeDockPose(val left: Float, val top: Float, val scale: Float)

/** The placeholder tile's corners — one shape, not a new one every frame it moves. */
internal val TileShape = RoundedCornerShape(6.dp)

/**
 * [content], for as long as [shown] says so.
 *
 * Asked in a scope of its own, which is the whole point: the player's
 * collapse thresholds flip partway through a moving sleeve, and read where the
 * gated thing sits — in the player's own body, or in the box around the
 * artwork — each flip recomposed all of that on a frame of the movement, for
 * one caption or one line of stats coming or going.
 */
@Composable
internal fun WhileShown(shown: () -> Boolean, content: @Composable () -> Unit) {
    if (shown()) content()
}

/**
 * Whether the full-bleed artwork switch is worth listing in Settings for a
 * window this wide. Public so the settings sheet can leave the switch out
 * entirely where it would do nothing.
 *
 * A window narrow enough that the player fills it is where the setting
 * acts: edge to edge there means the artwork *is* the screen. The tablet
 * half is left from the docked player, whose phone-width pane ran the
 * artwork edge to edge too; with the pane gone the portrait player no
 * longer goes full bleed at these widths, but the switch is still listed
 * there, unchanged, until that is decided on its own.
 */
fun fullBleedArtworkAvailable(windowWidth: Dp): Boolean =
    playerFillsWindow(windowWidth) || tabletSizedPlayer(windowWidth)

/**
 * Whether a player given the whole of a window this wide is still narrow enough
 * to run its artwork edge to edge.
 */
internal fun playerFillsWindow(windowWidth: Dp): Boolean =
    windowWidth <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2

/**
 * Whether the player is tablet-sized — see [TABLET_PLAYER_MIN_WIDTH].
 *
 * [windowWidth] is the width of the *window*, measured rather than read off
 * `Configuration.screenWidthDp`: in a freeform or desktop window that can
 * report the display instead of the window, and it lands a beat late when
 * the window is dragged.
 */
internal fun tabletSizedPlayer(windowWidth: Dp): Boolean =
    windowWidth >= TABLET_PLAYER_MIN_WIDTH

/**
 * Whether the player takes its landscape shape: the sleeve and the lyrics /
 * output / queue row in the left column, and the right column showing the
 * credits and transport, the lyrics or the queue — one of the three at a time.
 *
 * The same answer for a tablet and a phone on its side. Both are asked the
 * same question, the window's own proportions, rather than what kind of
 * device this is.
 *
 * Width alone isn't enough: a tablet held upright can be as wide as a phone
 * held sideways, and the two-column layout is a landscape shape, not a "wide
 * enough" one. Upright, every device gets the portrait player — a tall window
 * is the shape it was drawn for.
 */
fun landscapePlayerAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    windowWidth > windowHeight && windowWidth >= LANDSCAPE_PLAYER_MIN_WIDTH

/** How long the player stands under the lyrics untouched before standing down. */
internal const val LYRICS_CONTROLS_IDLE_MS = 5_000L

/** Default Spotify Canvas delay before its optional automatic collapse. */
internal const val SPOTIFY_CANVAS_CONTROLS_IDLE_MS = 5_000L

/** One shared travel time keeps the deck, credits and stats moving as a unit. */
internal const val SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS = 420

/** The deck's fade back in on returning from lyrics or the queue. */
internal const val PLAYER_DECK_FADE_IN_MS = 700

/** Shared top-only scrim transition for the Canvas lower deck. */
internal const val SPOTIFY_DECK_TOP_FADE_FRACTION = 0.28f

// The lower glass and the controls use one piece of motion. Keeping the slide
// specification here prevents a busy frame from exposing slightly different
// timings between the surface and the player drawn over it.
internal fun playerDeckSlideIn() = slideInVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    initialOffsetY = { it },
)

internal fun playerDeckSlideOut() = slideOutVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    targetOffsetY = { it },
)

/**
 * One lower-deck component, with one animation value owning both its pixels
 * and the space it occupies.
 *
 * A slide-only AnimatedVisibility kept the full height until its last frame;
 * combining slide and size transitions fixed that but let two transition
 * layers overlap when the presentation mode changed while returning to main.
 * This layout reports a fraction of the deck's real height instead. Since the
 * weighted player/panel above it consumes the remainder, the deck's top and
 * everything drawn from it move together. The subtree is removed only after
 * the exit reaches zero, retaining neither an invisible player nor a duplicate.
 *
 * [fadeIn], read at the moment [visible] turns true, reveals the deck at its
 * full height at once and fades it up instead. Returning to the main player
 * with the deck stood down, the sleeve sized itself against the deck's missing
 * height and was then squeezed as the deck slid up under it — the artwork
 * swelling to fill the screen and shrinking back over one 420ms trip.
 */
@Composable
internal fun SlidingPlayerDeck(
    visible: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    fadeIn: () -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        if (visible) {
            if (fadeIn() && reveal.value < 1f) {
                alpha.snapTo(0f)
                reveal.snapTo(1f)
                mounted = true
                // Slower than the slide, and linear: on FastOutSlowIn the deck
                // was most of the way up within a few frames and read as
                // simply being there.
                alpha.animateTo(
                    1f,
                    tween(PLAYER_DECK_FADE_IN_MS, easing = LinearEasing),
                )
            } else {
                // Hidden part-way through a fade: the slide starts from
                // wherever the height is, with the pixels whole again.
                alpha.snapTo(1f)
                mounted = true
                reveal.animateTo(
                    1f,
                    tween(
                        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        } else {
            reveal.animateTo(
                0f,
                tween(
                    SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
            mounted = false
        }
    }

    if (mounted) {
        BoxCompatDeck(modifier = modifier, alpha = alpha, reveal = reveal, content = content)
    }
}

@Composable
private fun BoxCompatDeck(
    modifier: Modifier,
    alpha: Animatable<Float, AnimationVector1D>,
    reveal: Animatable<Float, AnimationVector1D>,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha.value
                // No buffer of its own to fade through: one would cut the
                // glyphs' blend off from the backdrop, and they would jump
                // brighter on the fade's last frame.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val animatedHeight = (placeable.height * reveal.value)
                    .roundToInt()
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
                layout(placeable.width, animatedHeight) {
                    // No second translation: the weighted sibling above moves
                    // this component's origin as its reported height changes.
                    placeable.placeRelative(0, 0)
                }
            },
    ) {
        content()
    }
}

/**
 * [PlayerScrubber] in a recomposition scope of its own so updates to
 * `versionAlignmentInProgress` and `smartTransitionWindow` only invalidate the
 * scrubber row instead of recomposing the portrait deck or landscape column.
 */
@Composable
internal fun IsolatedPlayerScrubber(
    song: Song,
    isLoading: Boolean,
    shown: () -> Float,
    durationMs: Long,
    audioVersionSwitching: Boolean,
    mixing: Boolean,
    scrubbing: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val versionAligning by PlayerSettings.versionAlignmentInProgress.collectAsStateWithLifecycle()
    val transitionWindow by PlayerSettings.smartTransitionWindow.collectAsStateWithLifecycle()
    PlayerScrubber(
        shown = shown,
        durationMs = durationMs,
        loading = versionAligning || audioVersionSwitching,
        mixing = mixing && !scrubbing,
        // Hidden while scrubbing: the planner is still describing
        // where the transition *would* be, and a marker sitting under
        // a finger that is moving the playhead invites reading it as
        // a drag target.
        transitionWindow = transitionWindow
            ?.takeIf { !scrubbing && it.end > it.start }
            ?.let { it.start..it.end },
        onScrub = onScrub,
        onScrubFinished = onScrubFinished,
    ) {
        PlaybackQualityLabel(
            song = song,
            isLoading = isLoading,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 8.dp),
        )
    }
}

@Composable
internal fun NowPlayingOverlays(
    playerHaze: HazeState,
    accountName: String?,
    isPlaying: Boolean,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    showVideoQuality: Boolean,
    onDismissVideoQuality: () -> Unit,
    showAudioOutput: Boolean,
    onDismissAudioOutput: () -> Unit,
    onOpenAudioPipeline: () -> Unit,
    onOpenCast: () -> Unit,
    showLyricsProviders: Boolean,
    onDismissLyricsProviders: () -> Unit,
    showAudioPipeline: Boolean,
    onDismissAudioPipeline: () -> Unit,
    showCast: Boolean,
    onDismissCast: () -> Unit,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    lyricsShare: LyricsShareRequest?,
    onDismissLyricsShare: () -> Unit,
) {
    if (showVideoQuality) {
        VideoQualitySheet(
            hazeState = playerHaze,
            onDismiss = onDismissVideoQuality,
        )
    }
    if (showAudioOutput) {
        AudioOutputSheet(
            hazeState = playerHaze,
            accountName = accountName,
            onDismiss = onDismissAudioOutput,
            onOpenPipeline = onOpenAudioPipeline,
            onOpenCast = onOpenCast,
        )
    }
    if (showLyricsProviders) {
        LyricsProviderSheet(
            hazeState = playerHaze,
            currentSource = lyricsSource,
            states = lyricsProviderStates,
            onSelect = onSelectLyricsProvider,
            onDismiss = onDismissLyricsProviders,
        )
    }
    if (showAudioPipeline) {
        AudioPipelineDialog(
            hazeState = playerHaze,
            isPlaying = isPlaying,
            onDismiss = onDismissAudioPipeline,
        )
    }
    if (showCast) {
        PlayerPlatform.host.CastDialog(
            hazeState = playerHaze,
            onDismiss = onDismissCast,
        )
    }
    if (lyricsOffsetOpen) {
        LyricsOffsetSheet(
            hazeState = playerHaze,
            onDismiss = onDismissLyricsOffset,
        )
    }
    lyricsShare?.let { request ->
        PlayerPlatform.host.LyricsShareSheet(
            hazeState = playerHaze,
            request = request,
            onDismiss = onDismissLyricsShare,
        )
    }
    MixViewOverlay(hazeState = playerHaze)
}

/**
 * The upward half of the sleeve's vertical gesture: dragged up, the artwork
 * block pulls the queue in behind it, following the finger the whole way and
 * settling to whichever end it was nearer on release.
 *
 * Downward is deliberately not ours. The sheet the player sits in is what closes
 * when the sleeve is dragged that way, and it can only read a drag it was
 * allowed to see — so a downward crossing of the touch slop is left entirely
 * alone and this returns having consumed nothing at all.
 *
 * Which of the two it is can only be known at the crossing, which is why the
 * decision is made there rather than at the press. A pointer event reaches a
 * child before its parent, so consuming the very event that crossed the slop is
 * enough to keep the sheet out of an upward drag, and letting that one event
 * through is enough to hand it a downward one — the sheet's own slop detector
 * gives up the moment it sees a change already spoken for.
 *
 * @param travel how far the sleeve has to be dragged for the queue to arrive.
 * @param slide the 0..1 the player's whole layout reads off.
 * @param onHold true while the finger owns [slide] and false when it hands it
 *   back; the settling animation is parked in between so the two never write the
 *   same value on alternate frames.
 * @param onSettle the state the release decided on, which that animation then
 *   finishes reaching from wherever the finger left off.
 */
internal suspend fun AwaitPointerEventScope.dragQueueIn(
    down: PointerInputChange,
    travel: Float,
    slide: MutableFloatState,
    onHold: (Boolean) -> Unit,
    onSettle: (Boolean) -> Unit,
) {
    // A block with nowhere to travel — a player not yet measured — would divide
    // by nothing and snap the queue open on the first pixel of movement.
    if (travel < 1f) return

    var pulled = 0f
    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
        if (overSlop < 0f) {
            pulled = -overSlop
            change.consume()
        }
    }
    if (drag == null || pulled <= 0f) return

    onHold(true)
    val velocity = VelocityTracker()
    velocity.addPointerInputChange(drag)
    slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
    verticalDrag(drag.id) { change ->
        velocity.addPointerInputChange(change)
        pulled -= change.positionChange().y
        slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
        change.consume()
    }

    // A flick decides on its own — it says "open" without asking the finger to
    // travel at all. Anything slower goes to whichever end it got nearer to.
    val flick = -velocity.calculateVelocity().y
    val open = when {
        flick >= QUEUE_FLICK_VELOCITY -> true
        flick <= -QUEUE_FLICK_VELOCITY -> false
        else -> slide.floatValue >= QUEUE_CARRY_FRACTION
    }
    onHold(false)
    onSettle(open)
}
