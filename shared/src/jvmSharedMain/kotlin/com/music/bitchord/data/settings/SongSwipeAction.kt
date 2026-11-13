package com.music.bitchord.data.settings

import kotlin.math.abs
import kotlin.math.sign

enum class SongSwipeAction {
    PLAY_NEXT,
    ADD_TO_QUEUE,
    DISABLED,
}

enum class SongSwipeDirection {
    START_TO_END,
    END_TO_START,
}

const val SWIPE_ARM_FRACTION = 0.38f
const val SWIPE_DISARM_FRACTION = 0.30f
const val SWIPE_MAX_VISUAL_FRACTION = 0.62f
const val SWIPE_HOLD_DURATION_MS = 1_200L
const val SWIPE_HOLD_RING_LEAD_IN_MS = 220L

private const val SWIPE_RUBBER_BAND_SLOPE = 0.28f
private const val SWIPE_DISABLED_SLOPE = 0.12f

fun resolveSwipeAction(
    direction: SongSwipeDirection,
    rightAction: SongSwipeAction,
    leftAction: SongSwipeAction,
): SongSwipeAction = when (direction) {
    SongSwipeDirection.START_TO_END -> rightAction
    SongSwipeDirection.END_TO_START -> leftAction
}

fun rubberBandSwipeOffset(
    rawDragPx: Float,
    widthPx: Float,
    enabled: Boolean = true,
): Float {
    if (widthPx <= 0f || rawDragPx == 0f) return 0f
    if (!enabled) {
        val maxDisabledPx = widthPx * SWIPE_DISABLED_SLOPE
        return (rawDragPx * SWIPE_DISABLED_SLOPE).coerceIn(-maxDisabledPx, maxDisabledPx)
    }
    val armPx = widthPx * SWIPE_ARM_FRACTION
    val maxPx = widthPx * SWIPE_MAX_VISUAL_FRACTION
    val absRaw = abs(rawDragPx)
    if (absRaw <= armPx) return rawDragPx
    val damped = (armPx + (absRaw - armPx) * SWIPE_RUBBER_BAND_SLOPE).coerceAtMost(maxPx)
    return sign(rawDragPx) * damped
}

fun updateSwipeArmedState(
    currentlyArmed: Boolean,
    visualOffsetPx: Float,
    widthPx: Float,
    enabled: Boolean = true,
): Boolean {
    if (!enabled || widthPx <= 0f) return false
    val fraction = abs(visualOffsetPx) / widthPx
    return if (!currentlyArmed) {
        fraction >= SWIPE_ARM_FRACTION
    } else {
        fraction >= SWIPE_DISARM_FRACTION
    }
}

fun holdRingProgress(elapsedArmedMs: Long): Float {
    if (elapsedArmedMs <= SWIPE_HOLD_RING_LEAD_IN_MS) return 0f
    val span = (SWIPE_HOLD_DURATION_MS - SWIPE_HOLD_RING_LEAD_IN_MS).toFloat()
    if (span <= 0f) return 1f
    return ((elapsedArmedMs - SWIPE_HOLD_RING_LEAD_IN_MS).toFloat() / span).coerceIn(0f, 1f)
}
