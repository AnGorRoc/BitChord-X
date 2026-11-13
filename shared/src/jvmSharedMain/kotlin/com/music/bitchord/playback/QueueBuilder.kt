package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import java.util.Locale

/**
 * Builds the station that plays on after a one-off song.
 *
 * Shared: Android and the desktop assemble the same station from the same
 * watch queue, so the de-duplication and the per-artist caps are one
 * implementation rather than two that drift.
 *
 * Two problems this exists to solve:
 *
 *  - YouTube's watch queues routinely carry the same recording twice — the
 *    official audio and the music video are separate videoIds with near
 *    identical titles — so de-duping on id alone lets a track play, then play
 *    again as its video. Songs are matched on a normalised title plus lead
 *    artist instead.
 *
 *  - A radio mix leans hard on the seed's own artist. Capping how many tracks
 *    any one artist contributes keeps a station from turning into a single
 *    artist's discography.
 */
object QueueBuilder {

    /** No more than this many tracks by one artist in a single batch. */
    const val PER_ARTIST_LIMIT = 2

    /** The artist the station was seeded on gets more room, but not the run of it. */
    const val SEED_ARTIST_LIMIT = 4

    /** Fewer videos than this in a mix and a video station plays on with its songs too. */
    const val MIN_VIDEO_STATION = 3

    /**
     * The subset of [candidates] worth appending after [existing]: nothing
     * already queued, nothing repeated, at most [limit] tracks.
     */
    fun extend(
        existing: List<Song>,
        candidates: List<Song>,
        limit: Int,
        preferVideos: Boolean = false,
        allowVideoFallback: Boolean = true,
    ): List<Song> {
        val taken = existing.toMutableList()
        val perArtist = mutableMapOf<String, Int>()
        val seedArtists = existing.lastOrNull()?.artist?.let(::artistSet).orEmpty()
        val out = mutableListOf<Song>()

        // A mix pairs all but every track with its own music-video upload —
        // the same recording under a different id, titled far enough apart
        // ("Dildaara (Stand By Me)" against "Lyrical Video: Dildara Song")
        // that no title match will catch it. The catalogue cut is the one a
        // music app wants; videos only stand in when there is nothing else
        // and [allowVideoFallback] permits it.
        // [preferVideos] turns that round for a station playing on from a music
        // video: its videos, where the mix has enough of them to be one.
        val songsOnly = candidates.filterNot { it.isVideo }
        val videosOnly = candidates.filter { it.isVideo }
        val ordered = when {
            preferVideos && videosOnly.size >= MIN_VIDEO_STATION -> videosOnly
            preferVideos -> candidates
            allowVideoFallback -> songsOnly.ifEmpty { candidates }
            else -> songsOnly
        }

        for (candidate in ordered) {
            if (out.size >= limit) break
            if (taken.any { isSameRecording(it, candidate) }) continue

            val artists = artistSet(candidate.artist)
            val key = artists.minOrNull()
            if (key != null) {
                val cap = if (artists.any { it in seedArtists }) {
                    SEED_ARTIST_LIMIT
                } else {
                    PER_ARTIST_LIMIT
                }
                val played = perArtist[key] ?: 0
                if (played >= cap) continue
                perArtist[key] = played + 1
            }

            taken += candidate
            out += candidate
        }
        return out
    }

    /**
     * Whether two entries are the same recording. Ids differ between the audio
     * and video cuts of a track, which is exactly the case id equality misses.
     */
    fun isSameRecording(a: Song, b: Song): Boolean {
        if (a.videoId == b.videoId) return true
        // One shared name is enough. The two cuts of a track get billed in
        // whatever order the upload used — "Pritam, Arijit Singh & Shilpa Rao"
        // against "Shilpa Rao, Arijit Singh, & Pritam" is one song, twice.
        val left = artistSet(a.artist)
        val right = artistSet(b.artist)
        val sameArtist = left.isEmpty() || right.isEmpty() || left.any { it in right }
        if (!sameArtist) return false
        val knownArtists = left + right
        val titlesA = titleVariants(a.title, knownArtists)
        val titlesB = titleVariants(b.title, knownArtists)
        return titlesA.isNotEmpty() && titlesA.any { it in titlesB }
    }

    private fun titleVariants(raw: String, knownArtists: Set<String>): Set<String> {
        val base = normalisedTitle(raw)
        if (base.isEmpty()) return emptySet()
        if (knownArtists.isEmpty()) return setOf(base)
        val clean = raw.substringBefore(" | ")
        val sepIndex = clean.indexOf(" - ")
        if (sepIndex <= 0) return setOf(base)
        val out = linkedSetOf(base)
        val head = clean.substring(0, sepIndex)
        val tail = clean.substring(sepIndex + 3)
        if (artistSet(head).any { it in knownArtists }) {
            normalisedTitle(tail).takeIf { it.isNotEmpty() }?.let(out::add)
        }
        val tailClean = normalisedTitle(tail)
        if (tailClean.isNotEmpty() && artistSet(tailClean).any { it in knownArtists }) {
            normalisedTitle(head).takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }

    /**
     * `Kesariya (From "Brahmastra") | Official Video` and `Kesariya` are one
     * recording as far as a queue is concerned. Remix and cover markers are
     * deliberately left in — those really are different tracks.
     */
    fun normalisedTitle(raw: String): String = raw.lowercase(Locale.ROOT)
        .substringBefore(" | ")
        .replace(NOISE, " ")
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()

    /** The cast behind a credit, split out so billing order stops mattering. */
    fun artistSet(raw: String): Set<String> = raw.lowercase(Locale.ROOT)
        .replace(TOPIC, " ")
        .split(",", "&", "·", "•", ";", " feat", " ft.", " ft ", " x ", " with ")
        .map { it.replace(PUNCTUATION, " ").replace(SPACES, " ").trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val NOISE = Regex(
        """\((?:official|oficial|lyric|lyrics|lyrical|letra|audio|video|videoclip|video\s*clip|visuali[sz]er|full song|hd|4k)[^)]*\)""" +
            """|\(from[^)]*\)""" +
            """|\[[^]]*]""" +
            """|\b(?:official\s+(?:video|audio|music\s+video)|video\s+oficial|videoclip(?:\s+oficial)?|video\s+musical|video\s+letra|audio\s+oficial|lyrical?\s+video|full\s+video|4k\s+video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}
