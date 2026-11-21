package com.music.bitchord

import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.ProbeResult
import com.music.bitchord.data.listentogether.ServerConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherServerRecoveryTest {
    private val mine = "https://mine.example.org"
    private val original = "https://original.example.org"
    private val up = ProbeResult(isOnline = true, latencyMs = 40L)
    private val down = ProbeResult(isOnline = false)

    private fun resolve(
        custom: String = "",
        primary: ProbeResult,
        fallback: ProbeResult,
        customProbe: ProbeResult = down,
    ) = runBlocking {
        ListenTogether.resolveServerConnection(
            customServer = custom,
            defaultServer = mine,
            probeCustom = { customProbe },
            probeDefault = { primary },
            fallbackServer = original,
            probeFallback = { fallback },
        )
    }

    @Test
    fun `preferred server answers, it is used`() {
        val (server, state) = resolve(primary = up, fallback = up)
        assertEquals(mine, server)
        assertTrue(state is ServerConnectionState.DefaultOnline)
    }

    @Test
    fun `preferred server down, original used as fallback`() {
        val (server, state) = resolve(primary = down, fallback = up)
        assertEquals(original, server)
        assertTrue(state is ServerConnectionState.DefaultOnline)
    }

    @Test
    fun `preferred server back after an outage is used again`() {
        resolve(primary = down, fallback = up)
        val (server, _) = resolve(primary = up, fallback = up)
        assertEquals(mine, server)
    }

    @Test
    fun `both down reports offline on the preferred address`() {
        val (server, state) = resolve(primary = down, fallback = down)
        assertEquals(mine, server)
        assertEquals(ServerConnectionState.Offline, state)
    }

    @Test
    fun `custom server down falls back to built in, then returns to custom`() {
        val custom = "https://custom.example.org"
        val (fallbackServer, fallbackState) = resolve(
            custom = custom,
            primary = up,
            fallback = up,
            customProbe = down,
        )
        assertEquals(mine, fallbackServer)
        assertTrue(fallbackState is ServerConnectionState.CustomFallback)

        val (back, backState) = resolve(custom = custom, primary = up, fallback = up, customProbe = up)
        assertEquals(custom, back)
        assertTrue(backState is ServerConnectionState.CustomOnline)
    }

    @Test
    fun `fallback is considered while the screen is closed`() {
        assertFalse(
            ListenTogether.shouldSkipIdleProbe(
                screenActive = false,
                connectionLive = false,
                inParty = false,
                metered = false,
                onFallback = true,
            ),
        )
    }

    @Test
    fun `closed screen off fallback still never probes`() {
        assertTrue(
            ListenTogether.shouldSkipIdleProbe(
                screenActive = false,
                connectionLive = false,
                inParty = false,
                metered = false,
                onFallback = false,
            ),
        )
    }

    @Test
    fun `fallback recheck respects live socket and metered rules`() {
        assertTrue(
            ListenTogether.shouldSkipIdleProbe(
                screenActive = false,
                connectionLive = true,
                inParty = true,
                metered = false,
                onFallback = true,
            ),
        )
        assertTrue(
            ListenTogether.shouldSkipIdleProbe(
                screenActive = false,
                connectionLive = false,
                inParty = false,
                metered = true,
                onFallback = true,
            ),
        )
    }
}
