package com.music.bitchord.data.listentogether.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteMixTest {

    private val ms = 1_000_000L

    @Test
    fun `a beat carried on the playhead lands on the same beat on the remote`() {
        val beat = 500.0
        // On the target: a beat landed 120 ms ago, the song at 61 000 ms.
        val targetNow = 900_000 * ms
        val at = blendBeatAt(61_000, beat, targetNow - 120 * ms, targetNow)
        val np = AccountNowPlaying(videoId = "a", isPlaying = true, positionMs = 61_000, blending = true, blendBeatMs = beat, blendBeatAtMs = at)
        // On the remote, on a clock of its own, the playhead brought to 61 300 ms: 420 ms since that beat.
        val remoteNow = 5_000 * ms
        val anchor = np.copy(positionMs = 61_300).blendAnchorNanos(61_300, remoteNow)
        assertEquals(420.0, ((remoteNow - anchor) / 1e6) % beat, 1.0)
    }

    @Test
    fun `a blend without a known beat still carries, with no beat`() {
        assertEquals(0L, blendBeatAt(10_000, 0.0, 0L, 50 * ms))
        val np = AccountNowPlaying(videoId = "a", blending = true)
        assertEquals(7L, np.blendAnchorNanos(10_000, 7L))
    }

    @Test
    fun `the same blend read a moment later is not news, a change of it is`() {
        val a = AccountNowPlaying(videoId = "a", blending = true, blendTo = "b", blendBeatMs = 500.0, blendBeatAtMs = 490)
        assertTrue(a.sameBlend(a.copy(blendBeatAtMs = 10))) // across the fold
        assertFalse(a.sameBlend(a.copy(blendBeatAtMs = 250)))
        assertFalse(a.sameBlend(a.copy(blending = false)))
        assertFalse(a.sameBlend(a.copy(mixing = true)))
    }

    @Test
    fun `a blend heading to the song skipped to is that skip under way`() {
        var now = 0L
        val skip = SkipIntent { now }
        skip.expect("b")
        now += 300
        assertFalse(skip.accepts("a"))
        assertTrue(skip.accepts("a", headingTo = "b"))
        assertFalse(skip.pending)
    }
}
