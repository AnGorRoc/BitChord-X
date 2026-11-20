@file:OptIn(UnstableApi::class)

package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * How a live broadcast is played — the one kind of item that is not a track.
 *
 * A broadcast is a window of a few seconds that slides forward forever, served
 * as an HLS playlist that has to be re-read every few seconds. Every part of
 * the track pipeline assumes the opposite — a file with a start and an end that
 * can be cached, read ahead, resolved again on any re-open, upgraded to a
 * better copy, crossfaded at its end — and each of those assumptions breaks a
 * broadcast in its own way. A cached media playlist is a stale one, which is
 * what `PlaylistStuckException` was; a progressive read of the master playlist
 * is what `UnrecognizedInputFormatException` was; and the thirty-second window
 * joined at the live edge is what looked like a song starting at 0:15.
 *
 * So a broadcast never enters that pipeline. Its item says so in its URI
 * ([uriFor]), and [Factory] gives it an [HlsMediaSource] over plain HTTP that
 * knows nothing about the cache or the track resolver. The service treats it as
 * a station: alone in the queue, no AutoPlay, no quality hunt, no scrobble, and
 * a stall or an expired playlist is answered by rejoining the live edge rather
 * than by skipping (see [PlaybackService]).
 */
object LiveBroadcast {

    private const val TAG = "BitChord"

    /** Marks a queue entry as a broadcast; read back by [MediaItem.isLiveBroadcast]. */
    const val EXTRA_LIVE = "bitchord.live"

    private const val HOST = "live"
    private const val PARAM = "broadcast"

    /**
     * Deliberately not `bitchord://watch?v=…`: every helper that recognises a
     * track by that shape (read-ahead, the disk cache, the download scheduler)
     * then leaves a broadcast alone without having to be told.
     */
    fun uriFor(videoId: String): String = "bitchord://$HOST?$PARAM=$videoId"

    fun videoIdIn(uri: Uri?): String? =
        uri?.takeIf { it.scheme == "bitchord" && it.host == HOST }?.getQueryParameter(PARAM)

    /** Signed playlist URLs, kept until shortly before googlevideo says they expire. */
    private val manifests = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * The HLS master playlist for [videoId]. Blocking: called on the loader
     * thread by [Factory]'s resolver, which is where Media3 expects the wait.
     */
    fun manifestFor(videoId: String): String {
        val now = System.currentTimeMillis()
        manifests[videoId]?.takeIf { now < it.second }?.let { return it.first }
        val url = runBlocking { StreamResolver.liveManifestUrl(videoId) }
            ?: throw IOException("$videoId is not on the air")
        manifests[videoId] = url to expiryOf(url, now)
        TrackLog.d(TAG, "live: playlist for $videoId refreshed", about = videoId)
        return url
    }

    /** Forgets [videoId]'s playlist, so the next open signs a fresh one. */
    fun forget(videoId: String) {
        manifests.remove(videoId)
    }

    /** `…/expire/<epoch seconds>/…`, less a margin; ten minutes when it can't be read. */
    private fun expiryOf(url: String, now: Long): Long {
        val seconds = Regex("""/expire/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return seconds?.let { it * 1000 - 5 * 60_000L }?.coerceAtLeast(now + 60_000L)
            ?: (now + 10 * 60_000L)
    }

    /**
     * Routes broadcasts to their own [HlsMediaSource] and everything else to
     * [delegate], untouched.
     */
    class Factory(private val delegate: MediaSource.Factory) : MediaSource.Factory {

        private val live = HlsMediaSource.Factory(
            ResolvingDataSource.Factory(OkHttpDataSource.Factory(Http.client)) { spec ->
                videoIdIn(spec.uri)?.let { spec.withUri(Uri.parse(manifestFor(it))) } ?: spec
            },
        ).setAllowChunklessPreparation(true)

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
            delegate.setDrmSessionManagerProvider(provider)
            live.setDrmSessionManagerProvider(provider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
            delegate.setLoadErrorHandlingPolicy(policy)
            live.setLoadErrorHandlingPolicy(policy)
            return this
        }

        override fun getSupportedTypes(): IntArray = delegate.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            if (mediaItem.isLiveBroadcast) live.createMediaSource(mediaItem) else delegate.createMediaSource(mediaItem)
    }
}

/** A broadcast rather than a track — see [LiveBroadcast]. */
val MediaItem.isLiveBroadcast: Boolean
    get() = mediaMetadata.extras?.getBoolean(LiveBroadcast.EXTRA_LIVE) == true ||
        LiveBroadcast.videoIdIn(localConfiguration?.uri) != null

/** The MIME a broadcast item declares, so nothing downstream sniffs it as a file. */
internal const val LIVE_MIME = MimeTypes.APPLICATION_M3U8
