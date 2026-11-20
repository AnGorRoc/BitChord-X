package com.music.bitchord.playback

import android.content.Context
import androidx.annotation.StringRes
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceTrackKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Report of an individual audio source resolution attempt for a track.
 */
data class SourceAttemptReport(
    val sourceId: String,
    val displayName: String,
    val kind: SourceKind,
    val status: String,
    val detail: String? = null,
    val isWinner: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Live state of all source resolution reports for one track.
 */
data class TrackResolutionState(
    val mediaId: String = "",
    val title: String = "",
    val reports: List<SourceAttemptReport> = emptyList(),
    /** Where the audio is coming from without a lookup, such as the disk cache or a local file. */
    val storage: String? = null,
)

/**
 * What the player is actually sounding for a track, as the service reads it
 * off the item it is playing — see [SourceResolutionTracker.markPlaying].
 */
sealed interface PlayingSource {
    /** A configured provider is serving the bytes. */
    data class Provider(
        val sourceId: String,
        val displayName: String,
        val kind: SourceKind,
        /** The stream's own description, when it gave one. */
        val summary: String? = null,
    ) : PlayingSource

    /** The bytes come off the device (the disk cache or a local file), from no provider known to this process. */
    data class Storage(val label: String) : PlayingSource
}

/**
 * Tracks what every source returned during track resolution and quality upgrade passes.
 *
 * Exposes plain-language statuses for the Audio Pipeline UI so listeners can see
 * exactly why a track played from one source rather than another (e.g. not in catalogue,
 * length mismatch, network error, data ceiling, or superseded by lossless).
 *
 * ### One state per track
 *
 * The player opens the *next* track — a gapless preload, the crossfade's
 * standby — while the current one is still playing, and that open is a
 * resolve. With a single state the next track's resolve wiped the playing
 * one's, and for the last stretch of every song the inspection listed every
 * provider as never asked. So each track keeps its own state ([MAX_TRACKS] of
 * them), a report lands on the track whose work produced it (see
 * [TrackLog.workingOn]), and [playing] is the state of whatever the service
 * last said is sounding.
 */
object SourceResolutionTracker {

    private const val MAX_TRACKS = 8

    private class Track(val mediaId: String, var title: String, var storage: String?) {
        val attempts = LinkedHashMap<String, SourceAttemptReport>()

        fun state() = TrackResolutionState(
            mediaId = mediaId,
            title = title,
            reports = attempts.values.sortedBy { it.kind.rank },
            storage = storage,
        )
    }

    private val lock = Any()
    private val tracks = LinkedHashMap<String, Track>()

    /** The track last reset — where a report naming no track goes. */
    private var focusKey: String? = null

    /** The track the service says is sounding. */
    private var playingKey: String? = null

    private val _current = MutableStateFlow(TrackResolutionState())

    /** The state of the track most recently looked up. */
    val current: StateFlow<TrackResolutionState> = _current.asStateFlow()

    private val _playing = MutableStateFlow(TrackResolutionState())

    /** The state of the track that is sounding now — what the inspection shows. */
    val playing: StateFlow<TrackResolutionState> = _playing.asStateFlow()

    private var appContext: Context? = null

    /** Gives [text] the strings to read; called once from the application. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * A status line from the string table. The statuses are stored as text, so
     * one is written in the language in force when it happened. Before [init]
     * — plain unit tests — it reads as the resource id, which is still stable
     * to compare against.
     */
    fun text(@StringRes id: Int, vararg args: Any): String =
        appContext?.getString(id, *args) ?: "#$id"

    /** Source-backed queue items carry `src:<config>::<track>`; a track is keyed on the bare id. */
    private fun keyOf(mediaId: String) = SourceTrackKeys.keyOf(mediaId)

    /** The state of [mediaId], or null if nothing was recorded for it lately. */
    fun stateFor(mediaId: String?): TrackResolutionState? {
        if (mediaId == null) return null
        return synchronized(lock) { tracks[keyOf(mediaId)]?.state() }
    }

    /**
     * Starts [mediaId]'s state afresh: a lookup for it is beginning.
     *
     * [storage] names where the audio comes from when it is not looked up at
     * all — the disk cache or a local file — so that the stage can say so.
     */
    fun resetForTrack(mediaId: String, title: String, storage: String? = null) {
        synchronized(lock) {
            val key = keyOf(mediaId)
            tracks.remove(key)
            tracks[key] = Track(mediaId, title, storage)
            while (tracks.size > MAX_TRACKS) {
                val eldest = tracks.keys.first { it != key && it != playingKey }
                tracks.remove(eldest)
            }
            focusKey = key
        }
        TrackLog.d("SourceTracker", "Reset for track '$title' ($mediaId)", about = mediaId)
        publish()
    }

    /**
     * The track a report is about: [mediaId] if named, else the track the
     * calling coroutine works for, else the last one reset. A track with no
     * state gets one when [create] — a lookup's own report — and is null
     * otherwise: a winner declared for a track long gone.
     */
    private fun targetOf(mediaId: String?, create: Boolean): Track? {
        val named = mediaId ?: TrackLog.workingOn()
        if (named != null) {
            tracks[keyOf(named)]?.let { return it }
            // A lookup for a track nothing reset yet — a read-ahead, a second
            // look for a track evicted from the map: it starts its own state
            // rather than writing into another track's.
            if (create) {
                val track = Track(named, "", null)
                tracks[keyOf(named)] = track
                while (tracks.size > MAX_TRACKS) {
                    val eldest = tracks.keys.first { it != keyOf(named) && it != playingKey }
                    tracks.remove(eldest)
                }
                return track
            }
            return null
        }
        return focusKey?.let { tracks[it] }
    }

    /**
     * Records or updates the resolution outcome for [sourceId].
     *
     * A later lookup that comes back empty or failing for the source that is
     * currently serving the audio does not demote it: the stream is still
     * playing, so the row keeps saying so. Only [setWinner] and [markPlaying]
     * change who wins.
     */
    fun record(
        sourceId: String,
        displayName: String,
        kind: SourceKind,
        status: String,
        detail: String? = null,
        isWinner: Boolean = false,
        mediaId: String? = null,
    ) {
        synchronized(lock) {
            val track = targetOf(mediaId, create = true) ?: return
            val existing = track.attempts[sourceId]
            if (existing?.isWinner == true && !isWinner) return
            track.attempts[sourceId] = SourceAttemptReport(
                sourceId = sourceId,
                displayName = displayName,
                kind = kind,
                status = status,
                detail = detail,
                isWinner = isWinner,
            )
        }
        TrackLog.d("SourceTracker", "[$displayName] -> $status${detail?.let { " ($it)" } ?: ""}${if (isWinner) " [WINNER]" else ""}")
        publish()
    }

    /**
     * Declares [sourceId] as the active winning source that is currently serving the audio.
     * Any previously active winner is demoted (e.g. when upgraded to lossless).
     *
     * A winner nobody recorded a lookup for (a module or upgrade stream that
     * was never asked about by name) gets a row of its own, so the playing
     * source is always listed. When [mediaId] names a track with no state,
     * the call is a late report from a track long gone and is dropped.
     */
    fun setWinner(sourceId: String, winnerStatus: String? = null, mediaId: String? = null) {
        synchronized(lock) {
            val track = targetOf(mediaId, create = false) ?: run {
                TrackLog.d("SourceTracker", "ignored winner '$sourceId' for $mediaId; no state for it")
                return
            }
            promote(track, sourceId, winnerStatus, demotedTo = { text(R.string.pipeline_status_superseded) })
        }
        publish()
    }

    /** Makes [sourceId] the only winner of [track]. Caller holds [lock]. */
    private fun promote(track: Track, sourceId: String, winnerStatus: String?, demotedTo: (SourceAttemptReport) -> String) {
        val config = runCatching { SourceRegistry.config(sourceId) }.getOrNull()
        val base = track.attempts[sourceId] ?: SourceAttemptReport(
            sourceId = sourceId,
            displayName = config?.displayName ?: sourceId,
            kind = config?.kind ?: SourceKind.MODULE,
            status = winnerStatus ?: text(R.string.pipeline_status_playing),
        )
        for ((id, old) in track.attempts.entries.toList()) {
            if (id != sourceId && old.isWinner) {
                track.attempts[id] = old.copy(isWinner = false, status = demotedTo(old), detail = null)
                TrackLog.d("SourceTracker", "[${old.displayName}] demoted from winner")
            }
        }
        // Queued behind the winner: the walk stops there, so they will not be
        // asked. Queued ahead of it (YouTube starting while the walk is still
        // going) they still may be, and are left as they are.
        settleQueued(track, text(R.string.pipeline_status_not_needed_winner, base.displayName)) {
            it.kind.rank >= base.kind.rank
        }
        track.attempts[sourceId] = base.copy(isWinner = true, status = winnerStatus ?: base.status)
        // Whatever is playing now comes from this source, not from the store.
        track.storage = null
        TrackLog.d("SourceTracker", "[${base.displayName}] promoted to WINNER: ${winnerStatus ?: base.status}")
    }

    /**
     * What is sounding for [mediaId] right now, read by the service off the
     * item the player holds — the authority on the green mark.
     *
     * The lookups say what they found and [setWinner] what they swapped in,
     * but a track can change hands on paths that never report — a read-ahead
     * pinning its copy before the track is reached, a lower rendition taken
     * to keep up, a revert, a restart into the cache. This is called on every
     * track change and every new decoder format, so whichever path moved the
     * audio, the mark follows it.
     */
    fun markPlaying(mediaId: String, title: String, source: PlayingSource?) {
        synchronized(lock) {
            val key = keyOf(mediaId)
            val track = tracks[key] ?: Track(mediaId, title, null).also {
                tracks[key] = it
                while (tracks.size > MAX_TRACKS) {
                    val eldest = tracks.keys.first { k -> k != key }
                    tracks.remove(eldest)
                }
            }
            if (track.title.isBlank()) track.title = title
            playingKey = key
            when (source) {
                is PlayingSource.Provider -> {
                    val current = track.attempts[source.sourceId]
                    if (current?.isWinner != true) {
                        val status = source.summary?.takeIf { it.isNotBlank() }
                            ?.let { text(R.string.pipeline_status_playing_with, it) }
                            ?: text(R.string.pipeline_status_playing)
                        if (current == null) {
                            track.attempts[source.sourceId] = SourceAttemptReport(
                                sourceId = source.sourceId,
                                displayName = source.displayName,
                                kind = source.kind,
                                status = status,
                            )
                        }
                        promote(track, source.sourceId, status, demotedTo = {
                            text(R.string.pipeline_status_replaced_by, source.displayName)
                        })
                    }
                }
                is PlayingSource.Storage -> {
                    // From the device, by a provider this process never saw
                    // chosen: the store is what is sounding.
                    if (track.attempts.values.none { it.isWinner }) track.storage = source.label
                }
                null -> Unit
            }
        }
        publish()
    }

    /**
     * Ends the search for [mediaId]: a source still queued was never asked,
     * and one still looking up was cut off. Called when the lookup that queued
     * them has completed, however it ended.
     */
    fun finishSearch(mediaId: String) {
        synchronized(lock) {
            val track = tracks[keyOf(mediaId)] ?: return
            settleQueued(track, text(R.string.pipeline_status_not_asked))
            val querying = text(R.string.pipeline_status_querying)
            val interrupted = text(R.string.pipeline_status_interrupted)
            for ((id, old) in track.attempts.entries.toList()) {
                if (old.status == querying) track.attempts[id] = old.copy(status = interrupted)
            }
        }
        publish()
    }

    /** Turns every queued row of [track] into [to]. Asked rows keep their status. */
    private fun settleQueued(track: Track, to: String, which: (SourceAttemptReport) -> Boolean = { true }) {
        val queued = text(R.string.pipeline_status_queued)
        for ((id, old) in track.attempts.entries.toList()) {
            if (old.status == queued && which(old)) track.attempts[id] = old.copy(status = to)
        }
    }

    private fun publish() {
        val (focus, playing) = synchronized(lock) {
            focusKey?.let { tracks[it]?.state() } to playingKey?.let { tracks[it]?.state() }
        }
        _current.value = focus ?: TrackResolutionState()
        _playing.value = playing ?: TrackResolutionState()
    }

    /**
     * Whether two track ids name the same track. Source-backed queue items
     * carry `src:<config>::<track>`; the resolver keys its reports by the
     * bare track id, so the two are compared on that part.
     */
    fun sameTrack(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        if (a == b) return true
        return SourceTrackKeys.parse(a)?.second == b || SourceTrackKeys.parse(b)?.second == a
    }

    /** Forgets everything; for tests. */
    internal fun clearForTest() {
        synchronized(lock) {
            tracks.clear()
            focusKey = null
            playingKey = null
        }
        publish()
    }
}
