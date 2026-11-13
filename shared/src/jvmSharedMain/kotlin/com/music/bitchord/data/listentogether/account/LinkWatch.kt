package com.music.bitchord.data.listentogether.account

/**
 * Tells a remote that its link is gone after a break in its own connection.
 *
 * The server keeps a link for a short grace when either end drops (see account.LinkGrace). When this
 * device is the one that was offline for longer, the server has freed the link by the time it
 * comes back, but the phone still believes it is a remote and shows a device it no longer controls.
 * The first state after reconnecting is the moment the two can be compared. Comparing on every state
 * would misread the instant after a tap, when the attach is on its way and the server's list does
 * not show it yet.
 */
class LinkWatch {
    private var wasOffline = false

    /** True once, on the first live state after the connection had been down. */
    fun reconnected(connection: AccountConnection): Boolean {
        if (connection != AccountConnection.LIVE) {
            wasOffline = true
            return false
        }
        val back = wasOffline
        wasOffline = false
        return back
    }

    companion object {
        /** Whether the server no longer has [self] linked to [targetId]. */
        fun lost(self: AccountDevice?, targetId: String?): Boolean =
            targetId != null && self?.controlling != targetId
    }
}
