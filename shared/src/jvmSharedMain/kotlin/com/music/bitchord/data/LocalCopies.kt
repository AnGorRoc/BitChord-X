package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import java.io.File
import java.util.Locale

/**
 * The listener's own files, offered in place of a stream — on the phone and
 * on the desktop alike.
 *
 * A track found in search or a playlist used to play from YouTube (or a
 * lossless source) even when the same recording sat on the device as a file
 * the listener put there themselves: Local Music listed the file, but nothing
 * connected it to the catalogue row. This keeps a small index of what Local
 * Music scanned, fed by each platform's own scan, so building a queue item can
 * ask "is this one here?" without touching the disk — the phone's
 * `toMediaItem` runs for every track of a queue, on whatever thread builds it.
 *
 * Identity is [TrackMatcher.score], the same strict match a source has to pass
 * to stand in for a YouTube track, plus a shared artist and, where both sides
 * state one, a runtime within [DRIFT_SEC]: a copy on disk is the same master or
 * it is not used. Without a runtime on either side, only a single recording
 * by that name and artist is taken (see [find]).
 *
 * Any format counts — FLAC, WAV, DSD, ALAC, MP3, OGG — and which copy wins
 * is [shouldPlay]: the file whenever it is at least as good as what streaming
 * would bring. Lossless always; a lossy file when it matches the best lossy
 * source switched on (JioSaavn's 320, or YouTube's ~160) and no lossless one
 * is; anything poorer only with no connection.
 */
object LocalCopies {

    private const val TAG = "BitChord"

    /** How far a file's runtime may sit from the row's and still be the same recording. */
    const val DRIFT_SEC = 3

    /**
     * Below this a lossy file is "very low": kept for offline, but online the
     * track is streamed instead. YouTube's usual Opus is 160 kbps, so a 128 kbps
     * MP3 is worse than streaming and a 192 one is at least as good.
     */
    const val MIN_ONLINE_KBPS = 192

    /**
     * A lossy file this rich matches JioSaavn's 320 kbps tier. Read off the
     * file's size, which a 320 MP3 with its cover art clears and a V0 does not.
     */
    const val HIGH_LOSSY_KBPS = 300

    /** ALAC and AAC share the .m4a extension; nothing lossy runs this rich. */
    private const val ALAC_MIN_KBPS = 500

    /** How good a copy on the phone is, best first. */
    enum class Quality { LOSSLESS, HIGH_LOSSY, GOOD_LOSSY, LOW }

    /** The best streaming could bring for a track right now. */
    enum class Streaming {
        /** Lossless asked for and a source that serves it switched on. */
        LOSSLESS,

        /** JioSaavn on: up to 320 kbps. */
        HIGH,

        /** YouTube alone: Opus around 160 kbps. */
        STANDARD,
    }

    data class Copy(
        val uri: String,
        val path: String?,
        /** Null where the scan does not read a runtime — the desktop's. */
        val durationSec: Int?,
        val format: StreamFormat,
        val song: Song,
    ) {
        val quality: Quality get() = qualityOf(format)
    }

    /** Every indexed file, filed under [titleKey] of its title. */
    @Volatile
    private var byTitle: Map<String, List<Copy>> = emptyMap()

    /**
     * A coarse bucket for a title: lowercased, accents folded, bracketed and
     * dashed packaging dropped, letters and digits only. Only narrows the
     * search — [TrackMatcher.score] still decides, and it tells a remix or a
     * live take from the album cut by the very markers dropped here.
     */
    internal fun titleKey(title: String): String {
        val bare = title.substringBefore(" (").substringBefore(" [").substringBefore(" - ")
        return TrackMatcher.foldLatinDiacritics(bare.lowercase(Locale.ROOT)).filter { it.isLetterOrDigit() }
    }

    /**
     * Replace the index with what Local Music just scanned. Rows carry their
     * file's own id — `content://` on the phone, `local:<path>` on the
     * desktop; a file this app downloaded comes back from the phone's scan
     * wearing its video id instead, and is already found by it.
     */
    fun update(songs: List<Song>) {
        val copies = songs.mapNotNull { song ->
            val uri = song.localUri ?: return@mapNotNull null
            if (!isOwnFileId(song.videoId)) return@mapNotNull null
            val seconds = TrackMatcher.secondsOf(song.durationText)
            val path = song.localPath
            val size = path?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
            Copy(uri, path, seconds, formatOf(path, size, (seconds ?: 0) * 1_000L), song)
        }
        byTitle = copies.groupBy { titleKey(it.song.title) }
        Log.d(TAG, "local copies: ${copies.size} files indexed")
    }

    private fun isOwnFileId(id: String) =
        id.startsWith("content://") || id.startsWith("file://") || id.startsWith("local:")

    /**
     * The file on the phone that is [song], or null. Never the song's own file:
     * a row from Local Music already plays itself.
     */
    fun find(song: Song): Copy? {
        if (song.isVideo || song.localUri != null || isOwnFileId(song.videoId)) return null
        val index = byTitle
        if (index.isEmpty()) return null
        val target = TrackMatcher.targetOf(song)
        val seconds = target.durationSec
        val sameTitle = index[titleKey(song.title)].orEmpty()
            // [TrackMatcher.score] accepts an exact runtime in place of a
            // shared credit; on a phone full of files that is too loose, so
            // the artist has to agree as well.
            .filter { TrackMatcher.sharesArtist(song.artist, it.song.artist) }
        // With a runtime, it has to agree. A search row often comes without
        // one (the top result card never carries it), and then title, version
        // and artist are what there is — but only when they point at a single
        // recording: two same-named files of different lengths are an album
        // cut and an edit, and nothing here can say which was asked for.
        // A file whose runtime the scan never read (the desktop's) can't be
        // ruled out by it; it stays in, and the single-recording rule below
        // is what guards it.
        val nearby = if (seconds != null) {
            sameTitle.filter { copy -> copy.durationSec?.let { kotlin.math.abs(it - seconds) <= DRIFT_SEC } ?: true }
        } else {
            sameTitle
        }
        if (nearby.isEmpty()) return null
        val best = TrackMatcher.best(nearby.map { it.song }, target) ?: return null
        val chosen = nearby.firstOrNull { it.song === best } ?: return null
        val unvetted = seconds == null || chosen.durationSec == null
        if (unvetted) {
            val accepted = nearby.filter { TrackMatcher.score(it.song, target) != null }
            val lengths = accepted.mapNotNull { it.durationSec }
            // Two same-named files: their lengths must agree, and where they
            // are unknown, there has to be only the one file.
            if (lengths.size < accepted.size && accepted.size > 1) return null
            if (lengths.isNotEmpty() && lengths.max() - lengths.min() > DRIFT_SEC) return null
        }
        // A lossy file read without a runtime has no bitrate yet: the row's own
        // runtime stands in for it, which is the same recording's.
        if (chosen.format.kbps == null && chosen.format.isLossless != true && seconds != null) {
            val size = chosen.path?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
            return chosen.copy(format = formatOf(chosen.path, size, seconds * 1_000L))
        }
        return chosen
    }

    /** What a file is, read off its extension and how many bytes a second it takes. */
    fun formatOf(path: String?, sizeBytes: Long, durationMs: Long): StreamFormat {
        val ext = path?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val kbps = if (sizeBytes > 0 && durationMs > 0) (sizeBytes * 8 / durationMs).toInt() else null
        val codec = when (ext) {
            "flac" -> "flac"
            "wav" -> "wav"
            "aif", "aiff" -> "aiff"
            "ape" -> "ape"
            "wv" -> "wv"
            "dsf" -> "dsf"
            "dff" -> "dff"
            "alac" -> "alac"
            "m4a", "mp4" -> if ((kbps ?: 0) >= ALAC_MIN_KBPS) "alac" else "aac"
            "mp3" -> "mp3"
            "ogg", "oga" -> "vorbis"
            "opus", "webm" -> "opus"
            "aac" -> "aac"
            else -> ext.ifEmpty { null }
        }
        return StreamFormat(codec = codec, kbps = kbps)
    }

    fun qualityOf(format: StreamFormat): Quality = when {
        format.isLossless == true || format.isDolbyAtmos -> Quality.LOSSLESS
        (format.kbps ?: 0) >= HIGH_LOSSY_KBPS -> Quality.HIGH_LOSSY
        (format.kbps ?: 0) >= MIN_ONLINE_KBPS -> Quality.GOOD_LOSSY
        else -> Quality.LOW
    }

    /**
     * The quality of a download, from the label it was saved with ("FLAC",
     * "DOLBY", "OPUS · 160 kbps") or, failing that, from the file itself.
     */
    fun qualityOfDownload(label: String?, path: String?, durationSec: Int?): Quality {
        when {
            label.equals("FLAC", ignoreCase = true) || label.equals("DOLBY", ignoreCase = true) ->
                return Quality.LOSSLESS
            label != null -> Regex("""(\d+)\s*kbps""").find(label)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return qualityOf(StreamFormat(codec = "lossy", kbps = it))
            }
        }
        if (path == null || durationSec == null) return Quality.LOW
        val size = runCatching { File(path).length() }.getOrDefault(0L)
        return qualityOf(formatOf(path, size, durationSec * 1_000L))
    }

    /**
     * Whether to play a copy on the phone rather than stream.
     *
     * @param online whether streaming is possible at all.
     * @param streaming the best streaming could bring now, asked only for a
     *   lossy file — the one case where the answer depends on it.
     */
    fun shouldPlay(quality: Quality, online: Boolean, streaming: () -> Streaming): Boolean = when {
        !online -> true
        quality == Quality.LOSSLESS -> true
        quality == Quality.LOW -> false
        else -> when (streaming()) {
            Streaming.LOSSLESS -> false
            Streaming.HIGH -> quality == Quality.HIGH_LOSSY
            Streaming.STANDARD -> true
        }
    }
}
