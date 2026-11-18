package com.music.bitchord.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionVerdictTest {

    @Test
    fun noNetworkIsNone() {
        assertEquals(NetworkReach.NONE, networkReach(hasNetwork = false, hasInternet = false, validated = false))
    }

    @Test
    fun aNetworkThatCannotCarryInternetIsNoneEvenIfValidated() {
        assertEquals(NetworkReach.NONE, networkReach(hasNetwork = true, hasInternet = false, validated = true))
    }

    @Test
    fun aValidatedNetworkIsValidated() {
        assertEquals(NetworkReach.VALIDATED, networkReach(hasNetwork = true, hasInternet = true, validated = true))
    }

    @Test
    fun anUnvalidatedNetworkThatClaimsInternetIsUnconfirmedNotOffline() {
        assertEquals(NetworkReach.UNCONFIRMED, networkReach(hasNetwork = true, hasInternet = true, validated = false))
    }

    @Test
    fun noneNeverStreamsAndValidatedAlwaysDoes() {
        assertFalse(streamingAvailable(NetworkReach.NONE, probeReached = true))
        assertTrue(streamingAvailable(NetworkReach.VALIDATED, probeReached = false))
    }

    @Test
    fun anUnconfirmedNetworkStreamsOnlyOnceOurOwnRequestGotThrough() {
        assertFalse(streamingAvailable(NetworkReach.UNCONFIRMED, probeReached = false))
        assertTrue(streamingAvailable(NetworkReach.UNCONFIRMED, probeReached = true))
    }
}
