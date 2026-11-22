package com.music.bitchord.data.listentogether.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteIntentsTest {

    private var now = 10_000L
    private val clock = { now }

    @Test
    fun `a report from before the seek does not pull the bar back`() {
        val seek = SeekIntent(clock)
        seek.expect(90_000, "a", resendable = true)
        now += 300
        // Still the old place: sent before the device heard the seek.
        assertFalse(seek.accepts("a", 12_000, playing = true))
        assertTrue(seek.pending)
        assertEquals(90_300, seek.positionMs(playing = true))
    }

    @Test
    fun `a report where the seek landed ends it`() {
        val seek = SeekIntent(clock)
        seek.expect(90_000, "a", resendable = true)
        now += 800
        assertTrue(seek.accepts("a", 90_500, playing = true))
        assertFalse(seek.pending)
    }

    @Test
    fun `a seek that lands while the song is still opening is kept until it plays there`() {
        val seek = SeekIntent(clock)
        seek.expect(90_000, "b", resendable = true)
        now += 400
        // Opening: at the place, but not playing yet. Believed, not let go of.
        assertTrue(seek.accepts("b", 90_000, playing = false, settled = false))
        assertTrue(seek.pending)
        now += 800
        // The song starts and throws the seek away: held against, and sent again.
        assertFalse(seek.accepts("b", 0, playing = true))
        now += SeekIntent.RESEND_MS
        assertEquals(93_200, seek.resendPositionMs(playing = true))
        assertTrue(seek.accepts("b", 93_300, playing = true))
        assertFalse(seek.pending)
    }

    @Test
    fun `another song means the seek no longer matters`() {
        val seek = SeekIntent(clock)
        seek.expect(90_000, "a", resendable = true)
        assertTrue(seek.accepts("b", 0, playing = true))
        assertFalse(seek.pending)
    }

    @Test
    fun `an unanswered seek is sent once more, then given up`() {
        val seek = SeekIntent(clock)
        seek.expect(90_000, "a", resendable = true)
        assertNull(seek.resendPositionMs(playing = true))
        now += SeekIntent.RESEND_MS
        assertEquals(90_000 + SeekIntent.RESEND_MS, seek.resendPositionMs(playing = true))
        assertNull(seek.resendPositionMs(playing = true))
        now += SeekIntent.GIVE_UP_MS
        assertFalse(seek.pending)
        assertTrue(seek.accepts("a", 12_000, playing = true))
    }

    @Test
    fun `a skip's start is expected but never re-sent as a seek`() {
        val seek = SeekIntent(clock)
        seek.expect(0, "b", resendable = false)
        now += SeekIntent.RESEND_MS + 1
        assertNull(seek.resendPositionMs(playing = true))
    }

    @Test
    fun `a pause holds against reports still playing until one agrees`() {
        val play = PlayIntent(clock)
        play.expect(false)
        assertFalse(play.accepts(true))
        assertTrue(play.pending)
        now += SeekIntent.RESEND_MS
        assertTrue(play.shouldResend())
        assertFalse(play.shouldResend())
        assertTrue(play.accepts(false))
        assertFalse(play.pending)
    }

    @Test
    fun `songs passed on the way to a skip are not news`() {
        val skip = SkipIntent(clock)
        skip.expect("c")
        assertFalse(skip.accepts("a"))
        assertFalse(skip.accepts("b"))
        assertTrue(skip.accepts("c"))
        assertNull(skip.expectedId)
    }

    @Test
    fun `a skip that never arrives stops filtering`() {
        val skip = SkipIntent(clock)
        skip.expect("c")
        now += SkipIntent.WINDOW_MS
        assertTrue(skip.accepts("a"))
    }
}
