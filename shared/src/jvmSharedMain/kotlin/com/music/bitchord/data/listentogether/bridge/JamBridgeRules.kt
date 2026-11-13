package com.music.bitchord.data.listentogether.bridge

/**
 * The decisions of the Spotify Jam bridge, with no network and no player in them.
 *
 * Two catalogues are involved (YouTube Music for BitChord, Spotify for the Jam), and a track can
 * exist in one, both or neither of the places a listener expects it. Every rule below answers one
 * question — "what do we do with this track, given who is playing right now?" — so the
 * integration layer only has to carry the answer out, and the rules can be tested without a
 * Spotify account.
 *
 * The principle, from the person who owns the app: **whoever is playing decides.**
 */

/** Where the sound comes out of for the Jam's host right now. */
enum class Authority {
    /** BitChord's own player is the source; the Jam follows it. */
    BITCHORD,

    /** A Spotify device is the source; BitChord follows the Jam. */
    SPOTIFY,
}

/** One song in the shared queue, with whatever each catalogue knows about it. */
data class BridgeEntry(
    /** Stable id used to recognise the same entry on both sides. */
    val key: String,
    /** YouTube Music id, or null when BitChord has no match for it. */
    val videoId: String?,
    /** `spotify:track:…`, or null when Spotify has no match for it. */
    val spotifyUri: String?,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
) {
    val onBitchord: Boolean get() = videoId != null
    val onSpotify: Boolean get() = spotifyUri != null
}

/** Why something was not carried across, in words the UI can turn into a notice. */
enum class BridgeNotice {
    /** A BitChord listener asked for a song Spotify does not have, while Spotify plays. */
    NOT_ON_SPOTIFY,

    /** Queue edits other than adding are not possible while Spotify drives. */
    QUEUE_LOCKED_BY_SPOTIFY,
}

/** What to do with a song somebody wants to add. */
sealed interface AddDecision {
    /**
     * Add it to the shared queue.
     * @param toSpotify also append it to the Jam's own queue.
     * @param dimmed show it greyed out, because BitChord will not be able to play it.
     */
    data class Accept(
        val toSpotify: Boolean,
        val dimmed: Boolean = false,
    ) : AddDecision

    /** Change nothing anywhere; tell the person who asked. */
    data class Reject(val notice: BridgeNotice) : AddDecision
}

/** What to do when playback reaches an entry. */
sealed interface PlayDecision {
    /** Both sides can have it: BitChord plays it and the Jam is moved to it. */
    data class PlayBoth(val spotifyUri: String) : PlayDecision

    /** Only BitChord has it: play it here and keep the Jam paused until it ends. */
    data object PlayBitchordHoldJam : PlayDecision

    /** Only Spotify has it and BitChord is the source: skip it, in the queue and in the Jam. */
    data class SkipEverywhere(val spotifyUri: String) : PlayDecision

    /** Spotify is the source and plays it by itself. */
    data object SpotifyPlays : PlayDecision
}

object JamBridgeRules {

    /** How many upcoming Spotify-capable songs are kept in the Jam's queue ahead of the current. */
    const val SPOTIFY_WINDOW = 10

    /** A song asked for from BitChord. */
    fun addFromBitchord(authority: Authority, entry: BridgeEntry): AddDecision = when {
        entry.onSpotify -> AddDecision.Accept(toSpotify = true)
        authority == Authority.SPOTIFY -> AddDecision.Reject(BridgeNotice.NOT_ON_SPOTIFY)
        // BitChord is playing: the Jam simply never hears about it.
        else -> AddDecision.Accept(toSpotify = false)
    }

    /** A song that somebody added inside the Spotify Jam. */
    fun addFromSpotify(authority: Authority, entry: BridgeEntry): AddDecision =
        AddDecision.Accept(
            toSpotify = false, // it is already there
            // Only matters while BitChord is the one that would have to play it.
            dimmed = !entry.onBitchord && authority == Authority.BITCHORD,
        )

    /**
     * The reason a queue edit other than "add" (remove, reorder, clear) must not go ahead, or null
     * when it may. Spotify's queue cannot be edited from outside, so while it drives the order is
     * not ours to change.
     */
    fun editBlockedBy(authority: Authority): BridgeNotice? =
        if (authority == Authority.SPOTIFY) BridgeNotice.QUEUE_LOCKED_BY_SPOTIFY else null

    /** What to do as playback reaches [entry]. */
    fun onReach(authority: Authority, entry: BridgeEntry): PlayDecision = when (authority) {
        Authority.SPOTIFY -> PlayDecision.SpotifyPlays
        Authority.BITCHORD -> when {
            !entry.onBitchord -> PlayDecision.SkipEverywhere(entry.spotifyUri.orEmpty())
            !entry.onSpotify -> PlayDecision.PlayBitchordHoldJam
            else -> PlayDecision.PlayBoth(entry.spotifyUri.orEmpty())
        }
    }

    /**
     * Who is the source after something happened. The one already playing keeps it; a source that
     * starts while the other is silent takes it; if nothing plays, the last one stays.
     */
    fun authorityAfter(
        current: Authority,
        bitchordPlaying: Boolean,
        spotifyPlaying: Boolean,
        startedLast: Authority? = null,
    ): Authority = when {
        bitchordPlaying && !spotifyPlaying -> Authority.BITCHORD
        spotifyPlaying && !bitchordPlaying -> Authority.SPOTIFY
        // Both sounding, which should be transient: the one that started last yields to the
        // one that was first, so the first is the one that stays.
        bitchordPlaying && spotifyPlaying -> when (startedLast) {
            Authority.BITCHORD -> Authority.SPOTIFY
            Authority.SPOTIFY -> Authority.BITCHORD
            null -> current
        }
        else -> current
    }

    /**
     * The songs the Jam's own queue should show after [currentIndex], in order: only those Spotify
     * has, at most [SPOTIFY_WINDOW]. Spotify's queue can be appended to but not edited, so it is
     * kept short and treated as a view; only the current song is ever enforced.
     */
    fun spotifyWindow(queue: List<BridgeEntry>, currentIndex: Int): List<String> =
        queue.drop(currentIndex + 1)
            .mapNotNull { it.spotifyUri }
            .take(SPOTIFY_WINDOW)

    /**
     * Songs the Jam lists that the shared queue does not have yet, in the Jam's order. [known]
     * holds the Spotify uris already represented, so a repeated update adds nothing twice.
     */
    fun newFromSpotify(jamUpcoming: List<String>, known: Set<String>): List<String> =
        jamUpcoming.filter { it !in known }.distinct()

    /**
     * Songs a Spotify member removed from the Jam while Spotify drives: they were upcoming in the
     * shared queue and are no longer in the Jam, and were not simply played.
     */
    fun removedFromSpotify(
        queueUris: List<String>,
        jamUpcoming: List<String>,
        playedOrCurrent: Set<String>,
    ): List<String> = queueUris.filter { it !in jamUpcoming && it !in playedOrCurrent }
}
