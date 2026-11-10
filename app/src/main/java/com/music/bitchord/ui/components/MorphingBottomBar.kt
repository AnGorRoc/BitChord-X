package com.music.bitchord.ui.components

import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.Song
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import com.music.bitchord.ui.components.rememberRemoteArtworkUrl
import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clipToBounds

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.delay
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.clear
import com.music.bitchord.sharedui.resources.search
import com.music.bitchord.sharedui.resources.search_hint
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

private val GESTURE_BAR_FLOOR = 15.dp

/** How far above the keyboard the bar floats. */
private val KEYBOARD_GAP = 10.dp
private const val TAB_SEARCH = 3

/**
 * Morphing navigation bar:
 * In normal mode, displays the navigation tabs (Home, Explore, Library) on the left
 * with hold-and-slide scrubbing gesture and liquid selection indicator,
 * and an exact circular Search button (58x58dp) on the right.
 * When Search is tapped, the Search circle expands towards the left to become the
 * full search mega-pill, while the navigation tabs collapse towards the left and disappear.
 * When the keyboard opens, it floats directly above the keyboard keys.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MorphingBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismissSearch: () -> Unit,
    searchFocusRequester: FocusRequester,
    hazeState: HazeState,
    isInline: Boolean = false,
    onExpandInline: () -> Unit = {},
    inlineSong: Song? = null,
    isPlaying: Boolean = false,
    isLoading: Boolean = false,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onExpandPlayer: () -> Unit = {},
    pull: MiniPlayerPull? = null,
    controlsLocked: Boolean = false,
    onBlockedControl: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pillShape = remember { RoundedCornerShape(percent = 50) }
    val container = MaterialTheme.colorScheme.surface
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val barTint = glassBarTint()
    val contentColor = if (useGlass) glassContentColor() else MaterialTheme.colorScheme.onSurface
    val haptics = rememberHaptics()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val isSearching = selectedIndex == TAB_SEARCH
    val searchProgress by animateFloatAsState(
        targetValue = if (isSearching) 1f else 0f,
        animationSpec = if (reduceAnimation) snap() else spring(
            dampingRatio = 0.82f,
            stiffness = 380f,
        ),
        label = "searchMorphProgress",
    )

    val isInlineEffective = isInline && !isSearching
    val inlineProgress by animateFloatAsState(
        targetValue = if (isInlineEffective) 1f else 0f,
        animationSpec = if (reduceAnimation) snap() else tween(
            durationMillis = 220,
            easing = FastOutSlowInEasing,
        ),
        label = "inlineProgress",
    )

    // Keyboard and bottom padding.
    //
    // The bar rests above the gesture area, and while the keyboard is up it
    // rides a [KEYBOARD_GAP] above the keys. That is one continuous figure —
    // the larger of the two — read where the bar is laid out rather than here,
    // so it follows the keyboard's own animation frame for frame instead of
    // stepping to a fixed offset and recomposing on every frame of it.
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val restingBottomPx = with(density) { (max(navBottom, GESTURE_BAR_FLOOR) + 2.dp).roundToPx() }
    val keyboardGapPx = with(density) { KEYBOARD_GAP.roundToPx() }

    // Nav tabs setup (Home, Explore, Library)
    val navTabs = remember(tabs) { tabs.take(3) }
    val n = navTabs.size
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 6.dp.toPx() }

    val tabWidthPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width - gapPx * (n - 1)) / n
    } else 0f
    val tabStepPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width + gapPx) / n
    } else 0f

    var lastSelectedNavTab by remember {
        mutableIntStateOf(if (selectedIndex < 3) selectedIndex else 0)
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 3) {
            lastSelectedNavTab = selectedIndex
        }
        dragOffset = 0f
    }

    val glassSpec: AnimationSpec<Float> = if (reduceAnimation) snap() else GlassSpring

    val pillTargetPx = if (tabStepPx > 0f) {
        lastSelectedNavTab * tabStepPx + dragOffset
    } else 0f

    val animatedPillOffset by animateFloatAsState(
        targetValue = pillTargetPx,
        animationSpec = glassSpec,
        label = "pillOffset",
    )

    val lag = if (tabStepPx > 0f) {
        (abs(pillTargetPx - animatedPillOffset) / tabStepPx).coerceIn(0f, 1f)
    } else {
        0f
    }

    var lastHapticTab by remember { mutableIntStateOf(lastSelectedNavTab) }
    val currentSelectedNavTab by rememberUpdatedState(lastSelectedNavTab)
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)

    LaunchedEffect(isSearching) {
        if (isSearching) {
            delay(100)
            try {
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {
                delay(120)
                try {
                    searchFocusRequester.requestFocus()
                    keyboardController?.show()
                } catch (_: Exception) {}
            }
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    val submitSearch: () -> Unit = {
        onSubmit()
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    val dismissSearch: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDismissSearch()
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

    val barHeight = 58.dp
    val restingCircleSize = 58.dp

    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = BAR_GUTTER)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val bottom = maxOf(restingBottomPx, imeInsets.getBottom(this) + keyboardGapPx)
                layout(placeable.width, placeable.height + bottom) { placeable.place(0, 0) }
            }
            .fillMaxWidth(),
    ) {
        val totalWidth = maxWidth
        val safeSearchProgress = searchProgress.coerceIn(0f, 1f)
        val safeInlineProgress = inlineProgress.coerceIn(0f, 1f)
        val spacing = (8.dp * (1f - safeSearchProgress)).coerceAtLeast(0.dp)
        val searchWidth = (restingCircleSize + (totalWidth - restingCircleSize) * safeSearchProgress).coerceAtLeast(0.dp)
        val fullTabsWidth = (totalWidth - searchWidth - spacing).coerceAtLeast(0.dp)
        val showInline = safeInlineProgress > 0.001f && inlineSong != null && safeSearchProgress < 0.1f
        val effectiveTabsWidth = if (showInline) {
            lerp(fullTabsWidth, restingCircleSize, safeInlineProgress).coerceAtLeast(0.dp)
        } else {
            fullTabsWidth
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. LEFT PILL: Navigation tabs (Home, Explore, Library) or Active Tab Circle when inline
            // Collapses towards the left as search expands, or compacts to active tab when inline
            if (searchProgress < 0.99f && effectiveTabsWidth > 1.dp) {
                val tabsAlpha = (1f - searchProgress * 2.2f).coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .width(effectiveTabsWidth.coerceAtLeast(0.dp))
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = tabsAlpha
                            translationX = -searchProgress * 50f
                        }
                        .clip(pillShape)
                        .then(surfaceModifier())
                        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, pillShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = inlineProgress > 0.6f && showInline,
                            onClick = {
                                haptics.play(Haptic.Select)
                                onExpandInline()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (safeInlineProgress < 0.85f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .graphicsLayer {
                                    alpha = (1f - safeInlineProgress * 1.4f).coerceIn(0f, 1f)
                                }
                                .padding(horizontal = PILL_INSET, vertical = 4.dp),
                        ) {
                    // Liquid sliding indicator pill
                    if (tabWidthPx > 0f) {
                        Box(
                            modifier = Modifier
                                .width(with(density) { tabWidthPx.toDp() })
                                .fillMaxHeight()
                                .graphicsLayer {
                                    translationX = animatedPillOffset
                                    scaleX = 1f + lag * STRETCH
                                    scaleY = 1f - lag * STRETCH * SQUASH
                                }
                                .clip(pillShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { rowSize = it }
                            .pointerInput(Unit) {
                                var totalDrag = 0f
                                var dragStartIndex = currentSelectedNavTab
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        totalDrag = 0f
                                        dragStartIndex = currentSelectedNavTab
                                    },
                                    onDragCancel = { dragOffset = 0f },
                                    onDragEnd = {
                                        if (tabStepPx > 0f) {
                                            val absoluteIndexFloat = (dragStartIndex + totalDrag / tabStepPx)
                                                .coerceIn(0f, (n - 1).toFloat())
                                            val newIndex = absoluteIndexFloat.roundToInt()
                                            if (newIndex != currentSelectedNavTab) {
                                                currentOnTabSelected(newIndex)
                                            }
                                        }
                                        dragOffset = 0f
                                    },
                                    onHorizontalDrag = { _, delta ->
                                        totalDrag += delta
                                        val absoluteIndexFloat = (dragStartIndex + totalDrag / tabStepPx)
                                            .coerceIn(0f, (n - 1).toFloat())
                                        dragOffset = absoluteIndexFloat * tabStepPx - dragStartIndex * tabStepPx

                                        val approxTab = absoluteIndexFloat.roundToInt()
                                        if (approxTab != lastHapticTab) {
                                            haptics.play(Haptic.Tick)
                                            lastHapticTab = approxTab
                                        }
                                    },
                                )
                            },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        navTabs.forEachIndexed { index, tab ->
                            val isSelected = index == lastSelectedNavTab && selectedIndex < 3
                            val scale by animateFloatAsState(
                                targetValue = if (isSelected) 1.08f else 1f,
                                animationSpec = glassSpec,
                                label = "tabScale",
                            )
                            val tint by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    contentColor.copy(alpha = 0.65f)
                                },
                                animationSpec = tween(200),
                                label = "tabTint",
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(pillShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        // The active tab is forwarded too: the host turns
                                        // that tap into "back to the root, or to the top".
                                        haptics.play(Haptic.Select)
                                        onTabSelected(index)
                                    },
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = tint,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        },
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // Inline active tab circle view (shows only the selected tab's icon)
            if (showInline && inlineProgress > 0.15f) {
                val activeTab = navTabs.getOrNull(lastSelectedNavTab)
                if (activeTab != null) {
                    val iconProgress = ((inlineProgress - 0.20f) / 0.80f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = iconProgress
                                scaleX = 0.70f + 0.30f * iconProgress
                                scaleY = 0.70f + 0.30f * iconProgress
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = activeTab.icon,
                            contentDescription = activeTab.label,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }
    }

            // 2. MIDDLE PILL: Inline MiniPlayer (between Active Tab and Search)
            if (showInline) {
                Spacer(Modifier.width((8.dp * safeInlineProgress).coerceAtLeast(0.dp)))

                val middleAlpha = ((safeInlineProgress - 0.05f) / 0.95f).coerceIn(0f, 1f)
                val middleScale = 0.85f + 0.15f * safeInlineProgress

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = middleAlpha
                            scaleX = middleScale
                            scaleY = middleScale
                        }
                        .clip(pillShape)
                        .then(surfaceModifier())
                        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, pillShape)
                        .miniPlayerGestures(
                            pull = pull,
                            onNext = {
                                haptics.play(Haptic.SkipNext)
                                onNext()
                            },
                            onPrevious = {
                                haptics.play(Haptic.SkipPrevious)
                                onPrevious()
                            },
                            locked = controlsLocked,
                            onBlocked = onBlockedControl,
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onExpandPlayer,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (inlineProgress > 0.10f) {
                        val contentProgress = ((inlineProgress - 0.10f) / 0.90f).coerceIn(0f, 1f)
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = contentProgress
                                    scaleX = 0.88f + 0.12f * contentProgress
                                    scaleY = 0.88f + 0.12f * contentProgress
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val artUrl = rememberRemoteArtworkUrl(inlineSong)?.artworkAt(ROW_ART_PX)
                            AsyncImage(
                                model = artUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )

                            Spacer(Modifier.width(8.dp))

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = inlineSong.title,
                                    style = TextStyle(
                                        fontSize = 12.5f.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = contentColor,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = inlineSong.artist,
                                    style = TextStyle(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = contentColor.copy(alpha = 0.65f),
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Spacer(Modifier.width(4.dp))

                            if (isLoading) {
                                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        color = contentColor,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            if (controlsLocked) {
                                                onBlockedControl()
                                            } else {
                                                haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                                                onPlayPause()
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = contentColor,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width((8.dp * (1f - safeSearchProgress)).coerceAtLeast(0.dp)))
            } else if (spacing > 0.dp && searchProgress < 0.99f) {
                Spacer(Modifier.width(spacing.coerceAtLeast(0.dp)))
            }

            // 2. RIGHT PILL: Search button -> Expanding Mega-Pill
            // Starts on the right as a circle (58x58dp) and expands leftward to take the full bar
            val searchBoxModifier = if (safeSearchProgress >= 0.99f) {
                Modifier.fillMaxWidth().fillMaxHeight()
            } else {
                Modifier.width(searchWidth.coerceAtLeast(0.dp)).fillMaxHeight()
            }

            Box(
                modifier = Modifier
                    .then(searchBoxModifier)
                    .clip(pillShape)
                    .then(surfaceModifier())
                    .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, pillShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (!isSearching) {
                            haptics.play(Haptic.Select)
                            onTabSelected(TAB_SEARCH)
                        } else {
                            try {
                                searchFocusRequester.requestFocus()
                                keyboardController?.show()
                            } catch (_: Exception) {}
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (searchProgress < 0.4f) {
                    val iconAlpha = (1f - searchProgress * 2.5f).coerceIn(0f, 1f)
                    val iconScale = 1f - searchProgress * 0.25f
                    // Closed state: round search button with magnifying glass
                    Icon(
                        imageVector = tabs.getOrNull(TAB_SEARCH)?.icon ?: Icons.Rounded.Search,
                        contentDescription = stringResource(Res.string.search),
                        tint = if (isSearching) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(25.dp)
                            .graphicsLayer {
                                alpha = iconAlpha
                                scaleX = iconScale
                                scaleY = iconScale
                            },
                    )
                }

                if (isSearching || searchProgress > 0.05f) {
                    // Expanding / Expanded state: full search field
                    val contentAlpha = ((searchProgress - 0.10f) / 0.90f).coerceIn(0f, 1f)
                    val contentScale = 0.90f + 0.10f * contentAlpha
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp)
                            .graphicsLayer {
                                alpha = contentAlpha
                                scaleX = contentScale
                                scaleY = contentScale
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(Res.string.search),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable(enabled = query.isNotBlank(), onClick = submitSearch),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = contentColor.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = onQueryChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = contentColor,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                            )
                        }
                        if (query.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(Res.string.clear),
                                tint = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .clickable { onQueryChange("") }
                                    .padding(3.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.back),
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable(onClick = dismissSearch)
                                .padding(3.dp),
                        )
                    }
                }
            }
        }
    }
}
