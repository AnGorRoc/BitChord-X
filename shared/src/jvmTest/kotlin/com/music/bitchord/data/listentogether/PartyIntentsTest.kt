package com.music.bitchord.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyIntentsTest {

    private var now = 10_000L
    private val intents = PartyIntents<String> { now }

    @Test
    fun `a remote's seek is shown until the party is there`() {
        intents.expectSeek(90_000, "a")
        now += 300
        // The heartbeat from before the seek reached the server.
        intents.onPlayback("a", 12_300, playing = true)
        assertEquals(90_300, intents.shownPositionMs(12_300, partyPlaying = true))
        now += 200
        intents.onPlayback("a", 90_450, playing = true)
        assertEquals(12_345, intents.shownPositionMs(12_345, partyPlaying = true))
    }

    @Test
    fun `two quick presses show the second until the party agrees`() {
        intents.expectPress(false)
        intents.expectPress(true)
        intents.onPlayback("a", 1_000, playing = false) // the first press's echo
        assertTrue(intents.shownPlaying(partyPlaying = false))
        intents.onPlayback("a", 1_000, playing = true)
        assertFalse(intents.shownPlaying(partyPlaying = false))
    }

    @Test
    fun `a skip shows where it leads and ignores the song in between`() {
        intents.expectTrack("c")
        intents.onPlayback("b", 0, playing = true)
        assertEquals("c", intents.shownTrackId("b"))
        intents.onPlayback("c", 0, playing = true)
        assertEquals("b", intents.shownTrackId("b"))
    }

    @Test
    fun `a second skip leads on from where the first is heading`() {
        val queue = PartyQueue(seq = 1, index = 0, items = listOf("a", "b", "c", "d").map { PartyTrack(videoId = it) })
        val playback = PartyPlayback(seq = 4, track = PartyTrack(videoId = "a"), queueSeq = 1, queueIndex = 0)
        val first = partyStepTarget(queue, playback, intents.skippingTo, 1)
        intents.expectTrack(first?.videoId)
        val second = partyStepTarget(queue, playback, intents.skippingTo, 1)
        assertEquals("b", first?.videoId)
        assertEquals("c", second?.videoId)
    }

    @Test
    fun `an unanswered control is sent once more, and an answered one is not`() {
        intents.sent("seek", partySeq = 5)
        now += 1_000
        assertTrue(intents.dueAgain().isEmpty())
        now += 1_100
        assertEquals(listOf("seek" to 2_100L), intents.dueAgain())
        assertTrue(intents.dueAgain().isEmpty())

        intents.sent("pause", partySeq = 5)
        intents.answeredUpTo(6)
        now += 2_100
        assertTrue(intents.dueAgain().isEmpty())
    }

    @Test
    fun `controls sent while the socket was down go once it is back, unless the party applied them`() {
        intents.sent("pause", partySeq = 5)
        intents.sent("seek", partySeq = 5)
        now += 1_500
        // The party applied one of them before the socket died.
        assertEquals(listOf("seek"), intents.dueAfterReconnect(partySeq = 6).map { it.first })
        // Counted from the reconnect: not repeated again on a timer.
        now += 2_100
        assertTrue(intents.dueAgain().isEmpty())
    }

    @Test
    fun `controls too old to still be wanted are given up`() {
        intents.sent("seek", partySeq = 5)
        now += 6_000
        assertTrue(intents.dueAfterReconnect(partySeq = 5).isEmpty())
    }

    @Test
    fun `a control that cannot be repeated still lines the others up with their echoes`() {
        intents.sent(null, partySeq = 5)
        intents.sent("seek", partySeq = 5)
        intents.answeredUpTo(6)
        now += 2_100
        assertEquals(listOf("seek"), intents.dueAgain().map { it.first })
    }

    @Test
    fun `a refusal drops what was held and what was shown`() {
        intents.expectSeek(50_000, "a")
        intents.sent("seek", partySeq = 5)
        intents.refused()
        now += 2_100
        assertTrue(intents.dueAgain().isEmpty())
        assertEquals(1_000, intents.shownPositionMs(1_000, partyPlaying = false))
    }
}
