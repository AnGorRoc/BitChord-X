package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** How long the outgoing track takes to reach its fade, through a tempo ramp or not. */
class WallMsUntilTest {

    @Test
    fun `without a ramp it is the distance at the player's speed`() {
        assertEquals(2_000.0, wallMsUntil(8_000.0, 10_000.0, 1.0, 1.0, 0.0, 0.0, 0), 1e-9)
        assertEquals(1_600.0, wallMsUntil(8_000.0, 10_000.0, 1.25, 1.0, 0.0, 0.0, 0), 1e-9)
        assertEquals(-500.0, wallMsUntil(10_500.0, 10_000.0, 1.0, 1.0, 0.0, 0.0, 0), 1e-9)
    }

    @Test
    fun `through a ramp each step runs at its own speed`() {
        // Two steps of 500 media ms ending on the fade at 10 s, ramping to 1.04.
        val start = 9_000.0
        val wall = wallMsUntil(8_000.0, 10_000.0, 1.0, 1.04, start, 500.0, 2)
        val expected = 1_000.0 + 500.0 / 1.02 + 500.0 / 1.04
        assertEquals(expected, wall, 1e-9)
    }

    @Test
    fun `from inside a ramp only what is left counts`() {
        val wall = wallMsUntil(9_750.0, 10_000.0, 1.0, 1.04, 9_000.0, 500.0, 2)
        assertEquals(250.0 / 1.04, wall, 1e-9)
    }

    @Test
    fun `past the fade it runs at the final speed`() {
        val wall = wallMsUntil(10_104.0, 10_000.0, 1.0, 1.04, 9_000.0, 500.0, 2)
        assertEquals(-100.0, wall, 1e-9)
    }
}
