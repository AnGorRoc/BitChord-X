package com.music.bitchord.data.settings

/**
 * What the system says about the network the phone is using, before the app checks anything itself.
 *
 * Android marks a network validated once it has reached its own connectivity server. That mark can
 * lag behind a connection that already carries traffic, and on some networks (a VPN, a private DNS,
 * a slow hand-over from Wi-Fi to mobile data) it can stay off for minutes. Treating "not validated"
 * as "offline" left the app stuck in offline mode with a working connection.
 */
internal enum class NetworkReach {
    /** No network, or one that cannot carry internet traffic. */
    NONE,

    /** The system has checked it and found the internet. */
    VALIDATED,

    /**
     * It claims to carry internet traffic, but the system has not confirmed that. Only a request of
     * our own can settle it.
     */
    UNCONFIRMED,
}

internal fun networkReach(hasNetwork: Boolean, hasInternet: Boolean, validated: Boolean): NetworkReach = when {
    !hasNetwork || !hasInternet -> NetworkReach.NONE
    validated -> NetworkReach.VALIDATED
    else -> NetworkReach.UNCONFIRMED
}

/**
 * Whether streaming can be tried. [probeReached] is the answer of our own request and only matters
 * for an [NetworkReach.UNCONFIRMED] network.
 */
internal fun streamingAvailable(reach: NetworkReach, probeReached: Boolean): Boolean = when (reach) {
    NetworkReach.NONE -> false
    NetworkReach.VALIDATED -> true
    NetworkReach.UNCONFIRMED -> probeReached
}
