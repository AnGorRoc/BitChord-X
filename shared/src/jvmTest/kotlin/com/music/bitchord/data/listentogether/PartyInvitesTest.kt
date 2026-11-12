package com.music.bitchord.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What counts as an invite, and which server it points at. Phone and PC both read links through
 * [PartyInvites], so a link from either — or from the original BitChord — lands on the same party.
 */
class PartyInvitesTest {

    @Test
    fun `the queue limit is read from the server and falls back to the original's 25`() {
        assertEquals(500, PartyServers.parseMaxUpcoming("""{"limits":{"maxUpcomingQueue":500}}"""))
        assertEquals(25, PartyServers.parseMaxUpcoming("""{"limits":{}}"""))
        assertEquals(25, PartyServers.parseMaxUpcoming("<html>404</html>"))
        assertEquals(25, PartyServers.parseMaxUpcoming("""{"limits":{"maxUpcomingQueue":0}}"""))
        assertEquals(1000, PartyServers.parseMaxUpcoming("""{"limits":{"maxUpcomingQueue":999999}}"""))
    }


    private val own = "https://bitchord.pymp.net"

    @Test
    fun `an original web link without a server means the original official server`() {
        val invite = PartyInvites.parse("https://bitchord.kushagrasingh.in/invite/abc123")
        assertEquals("ABC123", invite?.code)
        assertEquals(PartyServers.ORIGINAL_OFFICIAL, invite?.serverUrl)
    }

    @Test
    fun `scheme and host may be in any case`() {
        // QR codes often carry upper-cased URLs; the PC used to keep "HTTPSB" as the code.
        assertEquals("ABC123", PartyInvites.parse("HTTPS://BITCHORD.KUSHAGRASINGH.IN/INVITE/ABC123")?.code)
    }

    @Test
    fun `a named server wins over the link's host`() {
        val invite = PartyInvites.parse(
            "https://bitchord.kushagrasingh.in/invite/ABC123?server=https%3A%2F%2Fbitchord.pymp.net%2F",
        )
        assertEquals("ABC123", invite?.code)
        assertEquals(own, invite?.serverUrl)
    }

    @Test
    fun `a landing page link points at its own server`() {
        assertEquals(own, PartyInvites.parse("$own/invite/ZZ99QQ")?.serverUrl)
        assertEquals("http://192.168.1.10:8000", PartyInvites.parse("http://192.168.1.10:8000/invite/ZZ99QQ")?.serverUrl)
        assertEquals("https://host.example/jam", PartyInvites.parse("https://Host.example/jam/invite/ZZ99QQ")?.serverUrl)
    }

    @Test
    fun `app scheme links read with and without a server`() {
        val named = PartyInvites.parse("bitchord://party/a1b2c3?server=https%3A%2F%2Fbitchord.pymp.net")
        assertEquals("A1B2C3", named?.code)
        assertEquals(own, named?.serverUrl)
        // The original app writes no server for its own parties.
        assertEquals(PartyServers.ORIGINAL_OFFICIAL, PartyInvites.parse("bitchord://party/XYZ789")?.serverUrl)
        assertEquals("XYZ789", PartyInvites.parse("bitchord://party?code=xyz789")?.code)
    }

    @Test
    fun `malformed links are refused rather than guessed at`() {
        assertNull(PartyInvites.parse(null))
        assertNull(PartyInvites.parse(""))
        assertNull(PartyInvites.parse("ABC123"))
        assertNull(PartyInvites.parse("http://bitchord.kushagrasingh.in/invite/ABC123"))
        assertNull(PartyInvites.parse("https://bitchord.kushagrasingh.in/invite/ABC123/"))
        assertNull(PartyInvites.parse("https://bitchord.kushagrasingh.in/invite/ABC12"))
        assertNull(PartyInvites.parse("https://bitchord.kushagrasingh.in/download"))
        assertNull(PartyInvites.parse("ftp://example.com/invite/ABC123"))
    }

    @Test
    fun `input accepts a link or a bare code`() {
        assertEquals(own, PartyInvites.fromInput("$own/invite/ABC123")?.serverUrl)
        val typed = PartyInvites.fromInput(" abc-123 ")
        assertEquals("ABC123", typed?.code)
        assertNull(typed?.serverUrl)
        assertNull(PartyInvites.fromInput("abc"))
    }

    @Test
    fun `spotify jam links are recognised`() {
        val invite = PartyInvites.parse("https://open.spotify.com/socialsession/abcdef123456?si=x")
        assertTrue(invite?.isSpotifyJam == true)
        assertEquals("abcdef123456", invite?.spotifyJamToken)
    }

    @Test
    fun `share links name their server`() {
        assertEquals("https://bitchord.kushagrasingh.in/invite/ABC123", PartyInvites.url("abc123", PartyServers.ORIGINAL_OFFICIAL))
        assertEquals("https://bitchord.kushagrasingh.in/invite/ABC123", PartyInvites.url("abc123", null))
        assertEquals("$own/invite/ABC123", PartyInvites.url("abc123", "$own/"))
        assertEquals(
            "bitchord://party/ABC123?server=https%3A%2F%2Fbitchord.pymp.net",
            PartyInvites.schemeUrl("abc123", own),
        )
    }

    @Test
    fun `every link built round-trips to the same party and server`() {
        for (server in listOf(own, PartyServers.ORIGINAL_OFFICIAL, "http://10.0.0.5:8000")) {
            for (link in listOf(PartyInvites.url("q1w2e3", server), PartyInvites.schemeUrl("q1w2e3", server))) {
                val invite = PartyInvites.parse(link)
                assertEquals("Q1W2E3", invite?.code, link)
                assertEquals(server, invite?.serverUrl, link)
            }
        }
    }

    @Test
    fun `server features are read from info and default to none`() {
        val info = """{"flavor":"bitchord-x","features":["preview","listeningMode"]}"""
        assertTrue(PartyServers.FEATURE_LISTENING_MODE in PartyServers.parseFeatures(info))
        // The original server answers 404 with plain text or an error body: baseline only.
        assertEquals(emptySet(), PartyServers.parseFeatures("not found"))
        assertEquals(emptySet(), PartyServers.parseFeatures("""{"error":"no_such_party"}"""))
    }
}
