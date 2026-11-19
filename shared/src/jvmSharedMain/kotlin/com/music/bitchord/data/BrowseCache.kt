package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SubscriptionState
import com.music.bitchord.data.model.WatchTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Pages that have been seen once, kept so they open at once next time — on this run and the next.
 *
 * YouTube Music's pages are borrowed: every one of them is a round trip (an artist's, several) to a
 * server that answers when it likes. So whatever was read is kept here, on disk as well as in
 * memory, and handed back at any age; the caller shows it straight away and asks again behind it
 * (see [Stored.ageMs]). Nothing here decides what is too old to show — only what is too old to keep.
 *
 * Two kinds of page: an artist's ([ArtistPage], possibly only its landing — see [Stored.complete])
 * and a collection's — an album or a playlist, every row of it ([YtMusicRepository.SongPage] with
 * no continuation). Both are filed under [Innertube.cacheScope], so a page read signed in to one
 * account never opens under another.
 *
 * Without [init] it still works, in memory only.
 */
object BrowseCache {

    /** A kept page, when it was read, and whether it is the whole of it. */
    data class Stored<T>(val value: T, val savedAtMs: Long, val complete: Boolean) {
        fun ageMs(now: Long = System.currentTimeMillis()): Long = now - savedAtMs
    }

    private const val TAG = "BitChord"
    private const val SCHEMA = 1
    private const val MEMORY_ENTRIES = 96
    /** Fewer collections than artists in memory: a long playlist is thousands of rows. */
    private const val MEMORY_COLLECTIONS = 24
    /** Files kept per kind on disk; the least recently written go first. */
    private const val DISK_ENTRIES = 500
    /** A page nobody has opened or refreshed in this long is not worth its disk. */
    private const val MAX_KEEP_MS = 60L * 24 * 60 * 60 * 1000

    private enum class Kind(val dir: String) { ARTIST("artists"), COLLECTION("collections") }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var baseDir: File? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private class Lru<V>(private val max: Int) : LinkedHashMap<String, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > max
    }

    private val artists = Lru<Stored<ArtistPage>>(MEMORY_ENTRIES)
    private val collections = Lru<Stored<YtMusicRepository.SongPage>>(MEMORY_COLLECTIONS)

    /** Opens the folder pages are kept in, and clears out what has outlived [MAX_KEEP_MS]. */
    fun init(dir: File) {
        baseDir = dir
        writer.launch {
            Kind.entries.forEach { kind ->
                val folder = File(dir, kind.dir)
                folder.mkdirs()
                prune(folder)
            }
        }
    }

    // ---- Artists -------------------------------------------------------------

    fun artist(browseId: String): Stored<ArtistPage>? =
        read(Kind.ARTIST, artists, browseId) { decodeArtist(it) }

    fun putArtist(browseId: String, page: ArtistPage, complete: Boolean, savedAtMs: Long = System.currentTimeMillis()) {
        // A landing never replaces a whole page: the whole one, older, is still the better thing to show.
        if (!complete && artist(browseId)?.complete == true) return
        write(Kind.ARTIST, artists, browseId, Stored(page, savedAtMs, complete)) { encodeArtist(it) }
    }

    /** Changes a kept artist page in place — a subscribe that went through, say — without re-reading it. */
    fun updateArtist(browseId: String, change: (ArtistPage) -> ArtistPage) {
        val stored = artist(browseId) ?: return
        write(Kind.ARTIST, artists, browseId, stored.copy(value = change(stored.value))) { encodeArtist(it) }
    }

    // ---- Collections ---------------------------------------------------------

    fun collection(browseId: String): Stored<YtMusicRepository.SongPage>? =
        read(Kind.COLLECTION, collections, browseId) { decodeCollection(it) }

    /** Keeps an album or playlist; [page] must hold every row (no continuation). */
    fun putCollection(browseId: String, page: YtMusicRepository.SongPage) {
        if (page.songs.isEmpty()) return
        write(
            Kind.COLLECTION,
            collections,
            browseId,
            Stored(page.copy(continuation = null), System.currentTimeMillis(), complete = true),
        ) { encodeCollection(it) }
    }

    /** Forgets a collection, after an edit made it wrong — see the playlist writes in [YtMusicRepository]. */
    fun removeCollection(browseId: String) {
        val key = key(browseId)
        synchronized(collections) { collections.remove(key) }
        val dir = baseDir ?: return
        writer.launch { File(File(dir, Kind.COLLECTION.dir), fileName(key)).delete() }
    }

    /** Whether anything is kept for this collection — without reading it, which for a long playlist is not free. */
    fun hasCollection(browseId: String): Boolean {
        val key = key(browseId)
        if (synchronized(collections) { collections.containsKey(key) }) return true
        val dir = baseDir ?: return false
        return File(File(dir, Kind.COLLECTION.dir), fileName(key)).isFile
    }

    /** Forgets what is in memory, as a restart would — tests read the disk copy back with it. */
    internal fun clearMemory() {
        synchronized(artists) { artists.clear() }
        synchronized(collections) { collections.clear() }
    }

    /** Waits for the writes already asked for — tests only. */
    internal fun awaitWrites() {
        kotlinx.coroutines.runBlocking { writer.launch { }.join() }
    }

    // ---- Storage -------------------------------------------------------------

    private fun key(browseId: String) = "${Innertube.cacheScope()}:$browseId"

    private fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".json"
    }

    private fun <T> read(kind: Kind, memory: Lru<Stored<T>>, browseId: String, decode: (JsonObject) -> T): Stored<T>? {
        val key = key(browseId)
        synchronized(memory) { memory[key] }?.let { return it }
        val dir = baseDir ?: return null
        val file = File(File(dir, kind.dir), fileName(key))
        if (!file.isFile) return null
        val stored = runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            if (root.int("schema") != SCHEMA || root.str("key") != key) return null
            Stored(
                value = decode(root["value"]!!.jsonObject),
                savedAtMs = root.long("savedAt") ?: 0L,
                complete = root.bool("complete") ?: true,
            )
        }.onFailure { Log.w(TAG, "browse cache: unreadable ${kind.dir} entry dropped: ${it.message}") }
            .getOrNull()
        if (stored == null) {
            file.delete()
            return null
        }
        synchronized(memory) { memory.putIfAbsent(key, stored) }
        return stored
    }

    private fun <T> write(kind: Kind, memory: Lru<Stored<T>>, browseId: String, stored: Stored<T>, encode: (T) -> JsonObject) {
        val key = key(browseId)
        synchronized(memory) { memory[key] = stored }
        val dir = baseDir ?: return
        writer.launch {
            runCatching {
                val folder = File(dir, kind.dir).apply { mkdirs() }
                val root = buildJsonObject {
                    put("schema", SCHEMA)
                    put("key", key)
                    put("savedAt", stored.savedAtMs)
                    put("complete", stored.complete)
                    put("value", encode(stored.value))
                }
                val target = File(folder, fileName(key))
                val tmp = File(folder, target.name + ".tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }.onFailure { Log.w(TAG, "browse cache: could not keep ${kind.dir} page: ${it.message}") }
        }
    }

    private fun prune(folder: File) {
        val files = folder.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        files.filter { it.name.endsWith(".tmp") || now - it.lastModified() > MAX_KEEP_MS }.forEach { it.delete() }
        val live = files.filter { it.exists() }.sortedByDescending { it.lastModified() }
        live.drop(DISK_ENTRIES).forEach { it.delete() }
    }

    // ---- Codec ---------------------------------------------------------------

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.arr(name: String): List<JsonObject> =
        (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun MutableMap<String, JsonElement>.opt(name: String, value: String?) {
        if (value != null) this[name] = JsonPrimitive(value)
    }

    private fun obj(build: MutableMap<String, JsonElement>.() -> Unit): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>().apply(build))

    private fun list(items: List<JsonObject>): JsonArray = JsonArray(items)

    internal fun encodeSong(s: Song): JsonObject = obj {
        put("videoId", JsonPrimitive(s.videoId))
        put("title", JsonPrimitive(s.title))
        put("artist", JsonPrimitive(s.artist))
        opt("thumbnailUrl", s.thumbnailUrl)
        opt("durationText", s.durationText)
        opt("artistId", s.artistId)
        if (s.artists.isNotEmpty()) {
            put("artists", list(s.artists.map { a -> obj { put("name", JsonPrimitive(a.name)); opt("browseId", a.browseId) } }))
        }
        opt("albumId", s.albumId)
        opt("albumName", s.albumName)
        if (s.isVideo) put("isVideo", JsonPrimitive(true))
        if (s.isVideoOrigin != s.isVideo) put("isVideoOrigin", JsonPrimitive(s.isVideoOrigin))
        opt("setVideoId", s.setVideoId)
        if (s.queueTier != QueueTier.CONTEXT) put("queueTier", JsonPrimitive(s.queueTier.name))
        opt("radioName", s.radioName)
        s.trackNumber?.let { put("trackNumber", JsonPrimitive(it)) }
        s.isExplicit?.let { put("isExplicit", JsonPrimitive(it)) }
        opt("playbackSource", s.playbackSource)
        s.playbackSourceType?.let { put("playbackSourceType", JsonPrimitive(it.name)) }
        opt("playbackSourceId", s.playbackSourceId)
        opt("feedbackToken", s.feedbackToken)
    }

    internal fun decodeSong(o: JsonObject): Song {
        val isVideo = o.bool("isVideo") ?: false
        return Song(
            videoId = o.str("videoId")!!,
            title = o.str("title").orEmpty(),
            artist = o.str("artist").orEmpty(),
            thumbnailUrl = o.str("thumbnailUrl"),
            durationText = o.str("durationText"),
            artistId = o.str("artistId"),
            artists = o.arr("artists").map { ArtistRef(it.str("name").orEmpty(), it.str("browseId")) },
            albumId = o.str("albumId"),
            albumName = o.str("albumName"),
            isVideo = isVideo,
            isVideoOrigin = o.bool("isVideoOrigin") ?: isVideo,
            setVideoId = o.str("setVideoId"),
            queueTier = o.str("queueTier")?.let { runCatching { QueueTier.valueOf(it) }.getOrNull() } ?: QueueTier.CONTEXT,
            radioName = o.str("radioName"),
            trackNumber = o.int("trackNumber"),
            isExplicit = o.bool("isExplicit"),
            playbackSource = o.str("playbackSource"),
            playbackSourceType = o.str("playbackSourceType")?.let { runCatching { PlaybackSourceType.valueOf(it) }.getOrNull() },
            playbackSourceId = o.str("playbackSourceId"),
            feedbackToken = o.str("feedbackToken"),
        )
    }

    private fun encodeShelf(shelf: HomeShelf): JsonObject = obj {
        put("title", JsonPrimitive(shelf.title))
        put("subtitle", JsonPrimitive(shelf.subtitle))
        opt("moreBrowseId", shelf.moreBrowseId)
        opt("moreParams", shelf.moreParams)
        put("layout", JsonPrimitive(shelf.layout.name))
        put(
            "items",
            list(
                shelf.items.map { item ->
                    obj {
                        put("title", JsonPrimitive(item.title))
                        put("subtitle", JsonPrimitive(item.subtitle))
                        opt("thumbnailUrl", item.thumbnailUrl)
                        opt("videoId", item.videoId)
                        opt("browseId", item.browseId)
                        if (item.isVideo) put("isVideo", JsonPrimitive(true))
                    }
                },
            ),
        )
    }

    private fun decodeShelf(o: JsonObject): HomeShelf = HomeShelf(
        title = o.str("title").orEmpty(),
        items = o.arr("items").map {
            ShelfItem(
                title = it.str("title").orEmpty(),
                subtitle = it.str("subtitle").orEmpty(),
                thumbnailUrl = it.str("thumbnailUrl"),
                videoId = it.str("videoId"),
                browseId = it.str("browseId"),
                isVideo = it.bool("isVideo") ?: false,
            )
        },
        subtitle = o.str("subtitle").orEmpty(),
        moreBrowseId = o.str("moreBrowseId"),
        moreParams = o.str("moreParams"),
        layout = o.str("layout")?.let { runCatching { ShelfLayout.valueOf(it) }.getOrNull() } ?: ShelfLayout.CARDS,
    )

    private fun encodeWatch(t: WatchTarget): JsonObject = obj {
        put("playlistId", JsonPrimitive(t.playlistId))
        opt("videoId", t.videoId)
        opt("params", t.params)
    }

    private fun decodeWatch(o: JsonObject?): WatchTarget? =
        o?.str("playlistId")?.let { WatchTarget(it, o.str("videoId"), o.str("params")) }

    private fun encodeArtist(p: ArtistPage): JsonObject = obj {
        put("songs", list(p.songs.map(::encodeSong)))
        opt("moreSongsBrowseId", p.moreSongsBrowseId)
        put("sections", list(p.sections.map(::encodeShelf)))
        opt("thumbnailUrl", p.thumbnailUrl)
        opt("name", p.name)
        opt("description", p.description)
        opt("subscriberCountText", p.subscriberCountText)
        opt("monthlyListenerCount", p.monthlyListenerCount)
        p.subscription?.let {
            put("subscription", obj { put("channelId", JsonPrimitive(it.channelId)); put("subscribed", JsonPrimitive(it.subscribed)) })
        }
        p.radio?.let { put("radio", encodeWatch(it)) }
        p.shuffle?.let { put("shuffle", encodeWatch(it)) }
    }

    private fun decodeArtist(o: JsonObject): ArtistPage = ArtistPage(
        songs = o.arr("songs").map(::decodeSong),
        moreSongsBrowseId = o.str("moreSongsBrowseId"),
        sections = o.arr("sections").map(::decodeShelf),
        thumbnailUrl = o.str("thumbnailUrl"),
        name = o.str("name"),
        description = o.str("description"),
        subscriberCountText = o.str("subscriberCountText"),
        monthlyListenerCount = o.str("monthlyListenerCount"),
        subscription = (o["subscription"] as? JsonObject)?.let { s ->
            s.str("channelId")?.let { SubscriptionState(it, s.bool("subscribed") ?: false) }
        },
        radio = decodeWatch(o["radio"] as? JsonObject),
        shuffle = decodeWatch(o["shuffle"] as? JsonObject),
    )

    private fun encodeCollection(p: YtMusicRepository.SongPage): JsonObject = obj {
        put("songs", list(p.songs.map(::encodeSong)))
        if (p.suggested.isNotEmpty()) put("suggested", list(p.suggested.map(::encodeSong)))
        p.library?.let {
            put("library", obj { put("playlistId", JsonPrimitive(it.playlistId)); put("saved", JsonPrimitive(it.saved)) })
        }
        p.owned?.let { put("owned", JsonPrimitive(it)) }
        p.header?.let { h ->
            put(
                "header",
                obj {
                    put("title", JsonPrimitive(h.title))
                    put("subtitle", JsonPrimitive(h.subtitle))
                    opt("thumbnailUrl", h.thumbnailUrl)
                },
            )
        }
        opt("description", p.description)
        if (p.sections.isNotEmpty()) put("sections", list(p.sections.map(::encodeShelf)))
    }

    private fun decodeCollection(o: JsonObject): YtMusicRepository.SongPage = YtMusicRepository.SongPage(
        songs = o.arr("songs").map(::decodeSong),
        continuation = null,
        suggested = o.arr("suggested").map(::decodeSong),
        library = (o["library"] as? JsonObject)?.let { l ->
            l.str("playlistId")?.let { LibraryState(it, l.bool("saved") ?: false) }
        },
        owned = o.bool("owned"),
        header = (o["header"] as? JsonObject)?.let { h ->
            InnertubeParser.BrowseHeader(h.str("title").orEmpty(), h.str("subtitle").orEmpty(), h.str("thumbnailUrl"))
        },
        description = o.str("description"),
        sections = o.arr("sections").map(::decodeShelf),
    )
}
