package com.music.bitchord

import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifySyncState
import com.music.bitchord.data.spotify.SyncErrorKind
import com.music.bitchord.data.spotify.SyncPhase
import com.music.bitchord.data.spotify.SyncTarget
import com.music.bitchord.data.spotify.YouTubeWriteException
import com.music.bitchord.data.spotify.syncErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class SpotifySyncTest {
    private val playlist = SpotifyPlaylist(id = "p", name = "p", owner = null, imageUrl = null)

    @Test
    fun expiredSignInIsTheUsersToFix() {
        assertEquals(SyncErrorKind.SIGNED_OUT, syncErrorKind(IllegalStateException("Spotify sign-in expired")))
        assertEquals(SyncErrorKind.SIGNED_OUT, syncErrorKind(IllegalStateException("Spotify fetchPlaylist failed (401)")))
    }

    @Test
    fun droppedConnectionIsANetworkError() {
        assertEquals(SyncErrorKind.NETWORK, syncErrorKind(UnknownHostException("api-partner.spotify.com")))
        assertEquals(SyncErrorKind.NETWORK, syncErrorKind(IOException("timeout")))
    }

    @Test
    fun anythingElseIsOther() {
        assertEquals(SyncErrorKind.OTHER, syncErrorKind(IllegalStateException("Spotify playlist response was empty")))
        assertEquals(SyncErrorKind.OTHER, syncErrorKind(IllegalStateException("Spotify fetchPlaylist failed (500)")))
    }

    @Test
    fun progressCountsFinishedPlaylistsAndTheSongsOfTheCurrentOne() {
        val state = SpotifySyncState.Running(
            index = 3, total = 4, playlist = playlist, songsDone = 50, songsTotal = 100, failures = emptyList(),
        )
        assertEquals(0.625f, state.fraction, 0.0001f)
    }

    @Test
    fun progressIsZeroWhileTheFirstPlaylistIsBeingRead() {
        val state = SpotifySyncState.Running(
            index = 1, total = 4, playlist = playlist, songsDone = 0, songsTotal = 0, failures = emptyList(),
        )
        assertEquals(0f, state.fraction, 0.0001f)
    }

    @Test
    fun anImportToYouTubeSpendsHalfAPlaylistMatchingAndHalfWriting() {
        val matching = SpotifySyncState.Running(
            index = 1, total = 2, playlist = playlist, songsDone = 50, songsTotal = 100, failures = emptyList(),
            target = SyncTarget.YouTube(), phase = SyncPhase.MATCHING,
        )
        assertEquals(0.125f, matching.fraction, 0.0001f)
        val writing = matching.copy(phase = SyncPhase.UPLOADING, songsDone = 50, songsTotal = 100)
        assertEquals(0.375f, writing.fraction, 0.0001f)
    }

    @Test
    fun aRefusedYouTubeWriteKeepsItsKind() {
        assertEquals(
            SyncErrorKind.YOUTUBE_SIGNED_OUT,
            syncErrorKind(YouTubeWriteException(SyncErrorKind.YOUTUBE_SIGNED_OUT, "401")),
        )
        assertEquals(
            SyncErrorKind.RATE_LIMITED,
            syncErrorKind(YouTubeWriteException(SyncErrorKind.RATE_LIMITED, "429")),
        )
    }
}
