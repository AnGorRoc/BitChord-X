package com.music.bitchord.ui.components

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.theme.stableStatusBarInsets
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.abs
import kotlin.math.roundToInt

/** Width of the vertical navbar rail in landscape, matching the horizontal bar's height. */
internal val LANDSCAPE_NAV_RAIL_WIDTH = 58.dp

/** Wider ceiling for the centered MiniPlayer & Search pill in landscape. */
internal val LANDSCAPE_MINI_PLAYER_MAX_WIDTH = 560.dp

private val LANDSCAPE_RAIL_GUTTER = 12.dp
private val LANDSCAPE_TAB_SLOT_HEIGHT = 52.dp
private val LANDSCAPE_TAB_GAP = 6.dp
private const val TAB_SEARCH_INDEX = 3

/**
 * Observes the display's physical rotation ([Surface.ROTATION_0]..[Surface.ROTATION_270]),
 * updating immediately on 180-degree flips between [Surface.ROTATION_90] and [Surface.ROTATION_270].
 */
@Composable
fun rememberDisplayRotation(): Int {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    fun readRotation(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { context.display.rotation }.getOrDefault(Surface.ROTATION_0)
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

    var rotation by remember { mutableIntStateOf(readRotation()) }

    DisposableEffect(context, configuration) {
        rotation = readRotation()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val updated = readRotation()
                if (updated != rotation) rotation = updated
            }
        }
        displayManager?.registerDisplayListener(listener, null)
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }
    return rotation
}

/**
 * Computes horizontal edge margins for scrollable page content in camera-style
 * landscape mode so content sits cleanly between the two side rails.
 */
@Composable
fun rememberLandscapeSideContentPadding(topBarOnLeft: Boolean): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val safeLeft = safePadding.calculateLeftPadding(layoutDirection)
    val safeRight = safePadding.calculateRightPadding(layoutDirection)

    val topRailFootprint = max(safeLeft.takeIf { topBarOnLeft } ?: safeRight, LANDSCAPE_RAIL_GUTTER) + TOP_CONTROL_SIZE + 8.dp
    val navRailFootprint = max(safeRight.takeIf { topBarOnLeft } ?: safeLeft, LANDSCAPE_RAIL_GUTTER) + LANDSCAPE_NAV_RAIL_WIDTH + 8.dp

    return if (topBarOnLeft) {
        PaddingValues(start = topRailFootprint, end = navRailFootprint)
    } else {
        PaddingValues(start = navRailFootprint, end = topRailFootprint)
    }
}

/**
 * Physical top-bar controls anchored to their original short edge of the phone
 * (left edge in [Surface.ROTATION_90], right edge in [Surface.ROTATION_270]).
 * Only the icons themselves sit upright; the bar controls stay on the same hardware edge.
 */
@Composable
fun LandscapeTopBarRail(
    topBarOnLeft: Boolean,
    anchor: TopBarAnchor,
    auxiliary: List<TopBarActionItem>,
    actionsVisible: Boolean,
    onBack: (() -> Unit)?,
    leading: (@Composable () -> Unit)?,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val statusTop = stableStatusBarInsets().asPaddingValues().calculateTopPadding()
    val bottomSafe = max(safePadding.calculateBottomPadding(), 14.dp)
    val edgeInset = if (topBarOnLeft) {
        max(safePadding.calculateLeftPadding(layoutDirection), LANDSCAPE_RAIL_GUTTER)
    } else {
        max(safePadding.calculateRightPadding(layoutDirection), LANDSCAPE_RAIL_GUTTER)
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(TOP_CONTROL_SIZE + edgeInset)
            .padding(
                start = if (topBarOnLeft) edgeInset else 0.dp,
                end = if (topBarOnLeft) 0.dp else edgeInset,
                top = statusTop + 10.dp,
                bottom = bottomSafe,
            ),
    ) {
        val leadingOrBack: @Composable () -> Unit = {
            if (onBack != null) {
                ArtworkPageBackButton(
                    onClick = onBack,
                    hazeState = hazeState,
                )
            } else if (leading != null) {
                Box(
                    modifier = Modifier.size(TOP_CONTROL_SIZE),
                    contentAlignment = Alignment.Center,
                ) {
                    leading()
                }
            }
        }

        val actionsPill: @Composable () -> Unit = {
            LandscapeVerticalActionsPill(
                anchor = anchor,
                auxiliary = auxiliary,
                visible = actionsVisible,
                anchorAtTop = topBarOnLeft,
                hazeState = hazeState,
            )
        }

        if (topBarOnLeft) {
            // ROTATION_90 (CCW): portrait top-right rotated to top-left; portrait top-left to bottom-left.
            Box(Modifier.align(Alignment.TopCenter)) { actionsPill() }
            Box(Modifier.align(Alignment.BottomCenter)) { leadingOrBack() }
        } else {
            // ROTATION_270 (CW): portrait top-left rotated to top-right; portrait top-right to bottom-right.
            Box(Modifier.align(Alignment.TopCenter)) { leadingOrBack() }
            Box(Modifier.align(Alignment.BottomCenter)) { actionsPill() }
        }
    }
}

@Composable
private fun LandscapeVerticalActionsPill(
    anchor: TopBarAnchor,
    auxiliary: List<TopBarActionItem>,
    visible: Boolean,
    anchorAtTop: Boolean,
    hazeState: HazeState?,
) {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val presence by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (reduceAnimation) snap() else spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "landscapeTopBarActionsPresence",
    )
    if (presence <= 0.001f && !visible) return

    val orderedAux = remember(auxiliary, anchorAtTop) {
        val slots = (2 downTo 0).mapNotNull { auxiliary.getOrNull(it) }
        if (anchorAtTop) slots.reversed() else slots
    }

    Column(
        modifier = Modifier
            .graphicsLayer {
                val anchorCentreY = if (anchorAtTop) {
                    TOP_CONTROL_SIZE.toPx() / 2f
                } else {
                    size.height - TOP_CONTROL_SIZE.toPx() / 2f
                }
                transformOrigin = TransformOrigin(
                    pivotFractionX = 0.5f,
                    pivotFractionY = if (size.height > 0f) anchorCentreY / size.height else 0.5f,
                )
                scaleX = presence
                scaleY = presence
                alpha = presence.coerceIn(0f, 1f)
            }
            .defaultMinSize(minWidth = TOP_CONTROL_SIZE, minHeight = TOP_CONTROL_SIZE)
            .then(artworkPageSurface(shape = CircleShape, hazeState = hazeState))
            .padding(vertical = if (orderedAux.isNotEmpty()) ACTION_EDGE_INSET else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (orderedAux.isNotEmpty()) 4.dp else 0.dp),
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides TOP_CONTROL_SIZE) {
            if (anchorAtTop) {
                TopBarAnchorSlot(anchor = anchor)
                orderedAux.forEach { item ->
                    Box(
                        modifier = Modifier.size(TOP_CONTROL_SIZE),
                        contentAlignment = Alignment.Center,
                    ) {
                        item.content()
                    }
                }
            } else {
                orderedAux.forEach { item ->
                    Box(
                        modifier = Modifier.size(TOP_CONTROL_SIZE),
                        contentAlignment = Alignment.Center,
                    ) {
                        item.content()
                    }
                }
                TopBarAnchorSlot(anchor = anchor)
            }
        }
    }
}

/**
 * Physical bottom-bar navigation rail anchored to the opposite short edge of the phone
 * (right edge in [Surface.ROTATION_90], left edge in [Surface.ROTATION_270]).
 * Displays icons only, upright, with vertical hold-and-slide scrubbing and liquid pill motion.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LandscapeNavBarRail(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    onDismissSearch: () -> Unit,
    searchOnTop: Boolean,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val statusTop = stableStatusBarInsets().asPaddingValues().calculateTopPadding()
    val bottomSafe = max(safePadding.calculateBottomPadding(), 14.dp)
    val edgeInset = if (searchOnTop) {
        // Right edge in ROTATION_90
        max(safePadding.calculateRightPadding(layoutDirection), LANDSCAPE_RAIL_GUTTER)
    } else {
        // Left edge in ROTATION_270
        max(safePadding.calculateLeftPadding(layoutDirection), LANDSCAPE_RAIL_GUTTER)
    }

    val pillShape = remember { RoundedCornerShape(percent = 50) }
    val container = MaterialTheme.colorScheme.surface
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val barTint = glassBarTint()
    val contentColor = if (useGlass) glassContentColor() else MaterialTheme.colorScheme.onSurface
    val haptics = rememberHaptics()
    val density = LocalDensity.current

    val navTabs = remember(tabs) { tabs.take(3) }
    // ROTATION_90 (Right rail): physical Bottom-Right is Top -> Search above, then [Library(2), Explore(1), Home(0)].
    // ROTATION_270 (Left rail): physical Bottom-Left is Top -> [Home(0), Explore(1), Library(2)], then Search below.
    val displayIndices = remember(navTabs.size, searchOnTop) {
        val raw = navTabs.indices.toList()
        if (searchOnTop) raw.reversed() else raw
    }

    val isSearching = selectedIndex == TAB_SEARCH_INDEX
    var lastSelectedNavTab by remember {
        mutableIntStateOf(if (selectedIndex < 3) selectedIndex else 0)
    }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 3) lastSelectedNavTab = selectedIndex
    }

    val selectedDisplayPos = displayIndices.indexOf(lastSelectedNavTab).coerceAtLeast(0)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var colSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { LANDSCAPE_TAB_GAP.toPx() }
    val n = displayIndices.size

    val tabHeightPx = if (colSize.height > 0 && n > 0) {
        (colSize.height - gapPx * (n - 1)) / n
    } else 0f
    val tabStepPx = if (colSize.height > 0 && n > 0) {
        (colSize.height + gapPx) / n
    } else 0f

    val glassSpec: AnimationSpec<Float> = if (reduceAnimation) snap() else GlassSpring
    val pillTargetPx = if (tabStepPx > 0f) {
        selectedDisplayPos * tabStepPx + dragOffset
    } else 0f

    val animatedPillOffset by animateFloatAsState(
        targetValue = pillTargetPx,
        animationSpec = glassSpec,
        label = "landscapePillOffset",
    )

    val lag = if (tabStepPx > 0f) {
        (abs(pillTargetPx - animatedPillOffset) / tabStepPx).coerceIn(0f, 1f)
    } else 0f

    val currentSelectedDisplayPos by rememberUpdatedState(selectedDisplayPos)
    val currentDisplayIndices by rememberUpdatedState(displayIndices)
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    var lastHapticPos by remember { mutableIntStateOf(selectedDisplayPos) }

    LaunchedEffect(selectedDisplayPos) {
        dragOffset = 0f
        lastHapticPos = selectedDisplayPos
    }

    val surfaceModifier: @Composable () -> Modifier = {
        Modifier.then(
            if (reduceDynamicBlur) {
                Modifier.background(container)
            } else if (useGlass) {
                Modifier.liquidGlass(shape = pillShape, surfaceTint = barTint)
            } else {
                Modifier.optimizedHazeEffect(
                    state = hazeState,
                    style = HazeMaterials.regular(container),
                )
            },
        )
    }

    val searchCircle: @Composable () -> Unit = {
        val searchTint by animateColorAsState(
            targetValue = if (isSearching) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.85f),
            animationSpec = tween(200),
            label = "landscapeSearchTint",
        )
        Box(
            modifier = Modifier
                .size(LANDSCAPE_NAV_RAIL_WIDTH)
                .clip(CircleShape)
                .then(surfaceModifier())
                .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.play(Haptic.Select)
                    if (isSearching) {
                        onDismissSearch()
                    } else {
                        onTabSelected(TAB_SEARCH_INDEX)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isSearching) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = tabs.getOrNull(TAB_SEARCH_INDEX)?.label ?: "Search",
                tint = searchTint,
                modifier = Modifier.size(26.dp),
            )
        }
    }

    val navPillHeight = PILL_INSET * 2 +
        LANDSCAPE_TAB_SLOT_HEIGHT * n +
        LANDSCAPE_TAB_GAP * (n - 1).coerceAtLeast(0)

    val tabsPill: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .width(LANDSCAPE_NAV_RAIL_WIDTH)
                .height(navPillHeight)
                .clip(pillShape)
                .then(surfaceModifier())
                .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, pillShape)
                .padding(horizontal = 4.dp, vertical = PILL_INSET),
        ) {
            if (tabHeightPx > 0f && !isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { tabHeightPx.toDp() })
                        .graphicsLayer {
                            translationY = animatedPillOffset
                            scaleY = 1f + lag * STRETCH
                            scaleX = 1f - lag * STRETCH * SQUASH
                        }
                        .clip(pillShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { colSize = it }
                    .pointerInput(n) {
                        var totalDrag = 0f
                        var dragStartPos = currentSelectedDisplayPos
                        detectVerticalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                dragStartPos = currentSelectedDisplayPos
                            },
                            onDragCancel = { dragOffset = 0f },
                            onDragEnd = {
                                if (tabStepPx > 0f && n > 0) {
                                    val absFloat = (dragStartPos + totalDrag / tabStepPx)
                                        .coerceIn(0f, (n - 1).toFloat())
                                    val newPos = absFloat.roundToInt()
                                    val targetTabIndex = currentDisplayIndices.getOrNull(newPos)
                                    if (targetTabIndex != null) {
                                        currentOnTabSelected(targetTabIndex)
                                    }
                                }
                                dragOffset = 0f
                            },
                            onVerticalDrag = { _, delta ->
                                if (tabStepPx <= 0f || n <= 0) return@detectVerticalDragGestures
                                totalDrag += delta
                                val absFloat = (dragStartPos + totalDrag / tabStepPx)
                                    .coerceIn(0f, (n - 1).toFloat())
                                dragOffset = (absFloat - currentSelectedDisplayPos) * tabStepPx
                                val approx = absFloat.roundToInt()
                                if (approx != lastHapticPos) {
                                    haptics.play(Haptic.Tick)
                                    lastHapticPos = approx
                                }
                            },
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(LANDSCAPE_TAB_GAP),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                displayIndices.forEach { realIndex ->
                    val tab = navTabs[realIndex]
                    val isSelected = realIndex == lastSelectedNavTab && !isSearching
                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = glassSpec,
                        label = "landscapeTabScale",
                    )
                    val tint by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            contentColor.copy(alpha = 0.65f)
                        },
                        animationSpec = tween(200),
                        label = "landscapeTabTint",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(pillShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                haptics.play(Haptic.Select)
                                onTabSelected(realIndex)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier
                                .size(25.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                },
                        )
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(LANDSCAPE_NAV_RAIL_WIDTH + edgeInset)
            .padding(
                start = if (searchOnTop) 0.dp else edgeInset,
                end = if (searchOnTop) edgeInset else 0.dp,
                top = statusTop + 10.dp,
                bottom = bottomSafe,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (searchOnTop) {
                searchCircle()
                tabsPill()
            } else {
                tabsPill()
                searchCircle()
            }
        }
    }
}
