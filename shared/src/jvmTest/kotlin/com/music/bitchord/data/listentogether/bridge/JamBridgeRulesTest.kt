package com.music.bitchord.data.listentogether.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JamBridgeRulesTest {

    private fun entry(key: String, video: String? = "v$key", spotify: String? = "spotify:track:$key") =
        BridgeEntry(key, video, spotify, "Title $key", "Artist")

    private val both = entry("a")
    private val bitchordOnly = entry("b", spotify = null)
    private val spotifyOnly = entry("c", video = null)

    // ---- the four cases the owner described -----------------------------------------------

    @Test
    fun `spotify plays, bitchord adds a song spotify lacks, nothing changes and the user is told`() {
        assertEquals(
            AddDecision.Reject(BridgeNotice.NOT_ON_SPOTIFY),
            JamBridgeRules.addFromBitchord(Authority.SPOTIFY, bitchordOnly),
        )
    }

    @Test
    fun `spotify plays, a member adds a song bitchord lacks, it is queued normally and not dimmed`() {
        assertEquals(
            AddDecision.Accept(toSpotify = false, dimmed = false),
            JamBridgeRules.addFromSpotify(Authority.SPOTIFY, spotifyOnly),
        )
    }

    @Test
    fun `bitchord plays, a member adds a song bitchord lacks, it is kept but dimmed`() {
        assertEquals(
            AddDecision.Accept(toSpotify = false, dimmed = true),
            JamBridgeRules.addFromSpotify(Authority.BITCHORD, spotifyOnly),
        )
        // ...and when it comes up it is skipped, in the queue and in the Jam.
        assertEquals(
            PlayDecision.SkipEverywhere("spotify:track:c"),
            JamBridgeRules.onReach(Authority.BITCHORD, spotifyOnly),
        )
    }

    @Test
    fun `bitchord plays, bitchord adds a song spotify lacks, the jam never hears of it and waits paused`() {
        assertEquals(
            AddDecision.Accept(toSpotify = false),
            JamBridgeRules.addFromBitchord(Authority.BITCHORD, bitchordOnly),
        )
        assertEquals(PlayDecision.PlayBitchordHoldJam, JamBridgeRules.onReach(Authority.BITCHORD, bitchordOnly))
    }

    // ---- the ordinary cases ----------------------------------------------------------------

    @Test
    fun `a song both catalogues have goes to the jam whoever is playing`() {
        for (authority in Authority.entries) {
            assertEquals(AddDecision.Accept(toSpotify = true), JamBridgeRules.addFromBitchord(authority, both))
        }
    }

    @Test
    fun `a song from spotify that bitchord can play is never dimmed`() {
        for (authority in Authority.entries) {
            assertEquals(
                AddDecision.Accept(toSpotify = false, dimmed = false),
                JamBridgeRules.addFromSpotify(authority, both),
            )
        }
    }

    @Test
    fun `reaching a shared song while bitchord plays moves the jam to it`() {
        assertEquals(PlayDecision.PlayBoth("spotify:track:a"), JamBridgeRules.onReach(Authority.BITCHORD, both))
    }

    @Test
    fun `while spotify drives, reaching any song is spotify's business`() {
        for (e in listOf(both, bitchordOnly, spotifyOnly)) {
            assertEquals(PlayDecision.SpotifyPlays, JamBridgeRules.onReach(Authority.SPOTIFY, e))
        }
    }

    // ---- queue edits -----------------------------------------------------------------------

    @Test
    fun `reordering and removing are blocked only while spotify drives`() {
        assertEquals(BridgeNotice.QUEUE_LOCKED_BY_SPOTIFY, JamBridgeRules.editBlockedBy(Authority.SPOTIFY))
        assertNull(JamBridgeRules.editBlockedBy(Authority.BITCHORD))
    }

    @Test
    fun `the jam window lists only what spotify has, after the current song, in order`() {
        val queue = listOf(entry("1"), entry("2"), bitchordOnly, entry("4"), entry("5", video = null), entry("6"))
        assertEquals(
            listOf("spotify:track:2", "spotify:track:4", "spotify:track:5", "spotify:track:6"),
            JamBridgeRules.spotifyWindow(queue, currentIndex = 0),
        )
    }

    @Test
    fun `the jam window is capped`() {
        val queue = (0..40).map { entry("$it") }
        assertEquals(JamBridgeRules.SPOTIFY_WINDOW, JamBridgeRules.spotifyWindow(queue, 0).size)
        assertEquals("spotify:track:1", JamBridgeRules.spotifyWindow(queue, 0).first())
    }

    @Test
    fun `the jam window past the end is empty`() {
        assertEquals(emptyList(), JamBridgeRules.spotifyWindow(listOf(entry("1")), currentIndex = 0))
        assertEquals(emptyList(), JamBridgeRules.spotifyWindow(emptyList(), currentIndex = -1))
    }

    // ---- reading what the jam reports -------------------------------------------------------

    @Test
    fun `a repeated jam update adds nothing twice`() {
        val known = setOf("spotify:track:1")
        assertEquals(
            listOf("spotify:track:2"),
            JamBridgeRules.newFromSpotify(listOf("spotify:track:1", "spotify:track:2", "spotify:track:2"), known),
        )
        assertEquals(emptyList(), JamBridgeRules.newFromSpotify(listOf("spotify:track:1"), known))
    }

    @Test
    fun `a song a spotify member removed is found, but one that was simply played is not`() {
        val removed = JamBridgeRules.removedFromSpotify(
            queueUris = listOf("x", "y", "z"),
            jamUpcoming = listOf("z"),
            playedOrCurrent = setOf("y"),
        )
        assertEquals(listOf("x"), removed)
    }

    // ---- who is the source ------------------------------------------------------------------

    @Test
    fun `whoever is playing alone is the source`() {
        assertEquals(Authority.BITCHORD, JamBridgeRules.authorityAfter(Authority.SPOTIFY, true, false))
        assertEquals(Authority.SPOTIFY, JamBridgeRules.authorityAfter(Authority.BITCHORD, false, true))
    }

    @Test
    fun `when nothing plays the last source stays`() {
        assertEquals(Authority.SPOTIFY, JamBridgeRules.authorityAfter(Authority.SPOTIFY, false, false))
        assertEquals(Authority.BITCHORD, JamBridgeRules.authorityAfter(Authority.BITCHORD, false, false))
    }

    @Test
    fun `when both sound, the one that was first keeps priority over the one that started last`() {
        assertEquals(
            Authority.SPOTIFY,
            JamBridgeRules.authorityAfter(Authority.SPOTIFY, true, true, startedLast = Authority.BITCHORD),
        )
        assertEquals(
            Authority.BITCHORD,
            JamBridgeRules.authorityAfter(Authority.BITCHORD, true, true, startedLast = Authority.SPOTIFY),
        )
        assertEquals(Authority.BITCHORD, JamBridgeRules.authorityAfter(Authority.BITCHORD, true, true))
    }
}
