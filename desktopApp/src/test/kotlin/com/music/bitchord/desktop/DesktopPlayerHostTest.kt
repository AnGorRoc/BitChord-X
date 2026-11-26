package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlayerHostTest {

    @Test
    fun testResetDeviceCallbacksRestoresNoopsAndClearsConnectingDevice() {
        var pickCalled = false
        var comeBackCalled = false
        var setVolumeCalled = false

        DesktopPlayerHost.setConnectingDevice("device-123")
        assertEquals("device-123", DesktopPlayerHost.connectingDevice.value)

        DesktopPlayerHost.onPickDevice = { _, _ -> pickCalled = true }
        DesktopPlayerHost.onComeBack = { comeBackCalled = true }
        DesktopPlayerHost.onSetDeviceVolume = { _, _ -> setVolumeCalled = true }

        DesktopPlayerHost.pickDevice("test", null)
        DesktopPlayerHost.comeBackToThisDevice()
        DesktopPlayerHost.setDeviceVolume("test", 0.5f)

        assertTrue(pickCalled)
        assertTrue(comeBackCalled)
        assertTrue(setVolumeCalled)

        DesktopPlayerHost.resetDeviceCallbacks()

        assertNull(DesktopPlayerHost.connectingDevice.value)

        pickCalled = false
        comeBackCalled = false
        setVolumeCalled = false

        DesktopPlayerHost.pickDevice("test", null)
        DesktopPlayerHost.comeBackToThisDevice()
        DesktopPlayerHost.setDeviceVolume("test", 0.8f)

        assertTrue(!pickCalled, "pickDevice must be a no-op after resetDeviceCallbacks")
        assertTrue(!comeBackCalled, "comeBack must be a no-op after resetDeviceCallbacks")
        assertTrue(!setVolumeCalled, "setDeviceVolume must be a no-op after resetDeviceCallbacks")
    }
}
