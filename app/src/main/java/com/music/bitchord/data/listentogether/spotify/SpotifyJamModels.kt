package com.music.bitchord.data.listentogether.spotify

import kotlinx.serialization.Serializable

/**
 * Account tier of the logged-in Spotify user.
 */
enum class SpotifyAccountTier {
    UNKNOWN,
    FREE,
    PREMIUM;

    val canHostJam: Boolean get() = this == PREMIUM
}

/**
 * A member participating in a Spotify Jam session.
 */
@Serializable
data class SpotifyJamMember(
    val username: String,
    val displayName: String = "",
    val avatarUrl: String? = null,
    val isHost: Boolean = false,
    val isConnected: Boolean = true,
)

/**
 * Active Spotify Jam session information.
 */
@Serializable
data class SpotifyJamSession(
    val sessionId: String,
    val joinSessionUri: String, // e.g. "spotify:socialsession:<token>"
    val joinSessionUrl: String, // e.g. "https://open.spotify.com/socialsession/<token>"
    val hostUsername: String = "",
    val isHost: Boolean = false,
    val members: List<SpotifyJamMember> = emptyList(),
    val activeDeviceId: String? = null,
)

/**
 * Playback state pushed via Dealer or returned by spclient connect-state.
 */
@Serializable
data class SpotifyJamPlaybackState(
    val trackUri: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val imageUrl: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val timestampMs: Long = 0L,
    val nextTrackUris: List<String> = emptyList(),
)

/**
 * Parsed payload from spotify.link or open.spotify.com/socialsession/<token>
 */
data class SpotifyJamInvite(
    val sessionToken: String,
    val originalUrl: String,
)
