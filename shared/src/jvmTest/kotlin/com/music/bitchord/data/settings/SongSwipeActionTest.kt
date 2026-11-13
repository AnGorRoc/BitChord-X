package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongSwipeActionTest {

    @Test
    fun resolveSwipeAction_mapsDirectionsCorrectly() {
        assertEquals(
            SongSwipeAction.PLAY_NEXT,
            resolveSwipeAction(SongSwipeDirection.START_TO_END, SongSwipeAction.PLAY_NEXT, SongSwipeAction.ADD_TO_QUEUE),
        )
        assertEquals(
            SongSwipeAction.ADD_TO_QUEUE,
            resolveSwipeAction(SongSwipeDirection.END_TO_START, SongSwipeAction.PLAY_NEXT, SongSwipeAction.ADD_TO_QUEUE),
        )
    }

    @Test
    fun rubberBandSwipeOffset_tracksOneToOneBelowArmThreshold_andDampsAboveIt() {
        val width = 1000f
        // Below 380px: 1:1
        assertEquals(200f, rubberBandSwipeOffset(200f, width, enabled = true), 0.01f)
        assertEquals(-200f, rubberBandSwipeOffset(-200f, width, enabled = true), 0.01f)

        // Above 380px: damped and never exceeds 620px (0.62f * width)
        val damped = rubberBandSwipeOffset(600f, width, enabled = true)
        assertTrue(damped > 380f && damped < 600f)
        assertEquals(620f, rubberBandSwipeOffset(5000f, width, enabled = true), 0.01f)
        assertEquals(-620f, rubberBandSwipeOffset(-5000f, width, enabled = true), 0.01f)
    }

    @Test
    fun rubberBandSwipeOffset_whenDisabled_appliesHeavyResistanceAndNeverReachesArmThreshold() {
        val width = 1000f
        val offset = rubberBandSwipeOffset(800f, width, enabled = false)
        assertTrue(offset < width * SWIPE_ARM_FRACTION)
        assertFalse(updateSwipeArmedState(false, offset, width, enabled = false))
    }

    @Test
    fun updateSwipeArmedState_usesHysteresisBetweenArmAndDisarm() {
        val width = 1000f
        // Arms at 380px (0.38f)
        assertFalse(updateSwipeArmedState(currentlyArmed = false, visualOffsetPx = 370f, widthPx = width))
        assertTrue(updateSwipeArmedState(currentlyArmed = false, visualOffsetPx = 380f, widthPx = width))

        // Stays armed at 320px (above 0.30f = 300px)
        assertTrue(updateSwipeArmedState(currentlyArmed = true, visualOffsetPx = 320f, widthPx = width))
        // Disarms below 300px
        assertFalse(updateSwipeArmedState(currentlyArmed = true, visualOffsetPx = 295f, widthPx = width))
    }

    @Test
    fun holdRingProgress_waitsForLeadInAndReachesOneAtHoldDuration() {
        assertEquals(0f, holdRingProgress(0L))
        assertEquals(0f, holdRingProgress(SWIPE_HOLD_RING_LEAD_IN_MS))
        val mid = holdRingProgress((SWIPE_HOLD_RING_LEAD_IN_MS + SWIPE_HOLD_DURATION_MS) / 2)
        assertTrue(mid in 0.45f..0.55f)
        assertEquals(1f, holdRingProgress(SWIPE_HOLD_DURATION_MS))
    }
}
