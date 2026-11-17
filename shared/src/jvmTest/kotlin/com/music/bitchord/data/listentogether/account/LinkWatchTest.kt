package com.music.bitchord.data.listentogether.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkWatchTest {

    @Test
    fun `only the first live state after a break counts as a return`() {
        val watch = LinkWatch()
        assertFalse(watch.reconnected(AccountConnection.LIVE))
        assertFalse(watch.reconnected(AccountConnection.CONNECTING))
        assertTrue(watch.reconnected(AccountConnection.LIVE))
        assertFalse(watch.reconnected(AccountConnection.LIVE))
    }

    @Test
    fun `a link the server no longer holds is lost`() {
        val linked = AccountDevice(deviceId = "me", name = "Me", controlling = "tv")
        val freed = AccountDevice(deviceId = "me", name = "Me")
        assertFalse(LinkWatch.lost(linked, "tv"))
        assertTrue(LinkWatch.lost(freed, "tv"))
        assertTrue(LinkWatch.lost(null, "tv"))
        assertTrue(LinkWatch.lost(linked, "other"))
        assertEquals(false, LinkWatch.lost(freed, null))
    }
}
