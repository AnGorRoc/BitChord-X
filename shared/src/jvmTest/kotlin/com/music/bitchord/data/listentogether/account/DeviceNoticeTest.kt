package com.music.bitchord.data.listentogether.account

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceNoticeTest {
    @Test
    fun `a refusal is read by its reason, not by its English`() {
        assertEquals(DeviceNotice.Unavailable, DeviceNotice.fromServer("not_available", "that device is not available"))
        assertEquals(DeviceNotice.TargetIsRemote(null), DeviceNotice.fromServer("controlling_other", "x"))
        assertEquals(DeviceNotice.TooManySongs, DeviceNotice.fromServer("too_many_songs", null))
        assertEquals(DeviceNotice.NotLinked, DeviceNotice.fromServer("not_linked", "not controlling that device"))
        assertEquals(DeviceNotice.NotLinked, DeviceNotice.fromServer("unknown_command", null))
    }

    @Test
    fun `a device that refuses control or has to be opened is told apart`() {
        assertEquals(DeviceNotice.TargetNotAllowed(null), DeviceNotice.fromServer("not_allowed", null))
        assertEquals(DeviceNotice.TargetNotReachable(null), DeviceNotice.fromServer("not_reachable", null))
    }

    @Test
    fun `a reason this build does not know falls back to what the server said`() {
        assertEquals(DeviceNotice.Refused("slow down"), DeviceNotice.fromServer(null, "slow down"))
        assertEquals(DeviceNotice.Refused("x"), DeviceNotice.fromServer("some_future_reason", "x"))
    }
}
