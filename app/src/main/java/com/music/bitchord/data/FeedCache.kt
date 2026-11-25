package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap

/** Cached representation of a Home feed, keyed by listener identity. */
data class CachedHomeFeed(
    val listenerKey: String?,
    val shelves: List<HomeShelf>,
    val continuation: String?,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Caches Home shelves and Explore sections in memory and persistently in disk
 * so that both tabs open instantly upon app launch or navigation, and revalidates
 * in the background updating the view smoothly when content changes.
 *
 * A Home feed older than [MAX_AGE_MS] is not shown: stale-while-revalidate is
 * for a feed that is a little behind, not one from last month. [touchHome]
 * renews a feed the network has just confirmed unchanged, which is what keeps
 * an unchanged feed young. What is written is versioned by [SCHEMA_VERSION]
 * and bounded: mood and genre pages are kept for the last [MAX_MOOD_GENRES]
 * opened.
 *
 * Nothing is read at [init]: the file is opened, its schema checked and its
 * contents parsed the first time something asks, which for Home is a read off
 * the main thread. Mood and genre pages live in a file of their own, so the one
 * read at launch holds only Home and Explore rather than every page opened.
 */
object FeedCache {
    private const val PREFS_NAME = "bitchord_feed_cache"
    private const val MOOD_GENRE_PREFS_NAME = "bitchord_mood_genre_cache"
    private const val KEY_HOME_PREFIX = "home_"
    private const val KEY_EXPLORE = "explore_sections"
    private const val KEY_MOOD_GENRE_PREFIX = "mood_genre_"
    private const val KEY_MOOD_GENRE_INDEX = "mood_genre_index"

    /** Bumped whenever what is written here changes shape; see [CacheSchema]. */
    const val SCHEMA_VERSION = 1

    /** Older than this and a Home feed is treated as absent. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    const val MAX_MOOD_GENRES = 24

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var writer: PrefsWriter? = null
    @Volatile private var moodPrefs: SharedPreferences? = null
    @Volatile private var moodWriter: PrefsWriter? = null

    /** Set by [init] and opened by [ready] on first use. */
    @Volatile private var pendingContext: Context? = null
    private val openLock = Any()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The mood and genre pages held, oldest first. */
    private val moodGenreIndex = mutableListOf<String>()

    /** The clock the age of a feed is read on; replaceable so a test can move it. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val homeMemoryCache = ConcurrentHashMap<String, CachedHomeFeed>()
    @Volatile
    private var exploreMemoryCache: Pair<String, List<MoodGenreSection>>? = null
    private val moodGenreMemoryCache = ConcurrentHashMap<String, List<HomeShelf>>()

    /** Remembers where the cache lives; nothing is read until it is first asked for. */
    fun init(context: Context) {
        if (prefs != null || pendingContext != null) return
        pendingContext = context.applicationContext
    }

    /** The cache's files, opened on first use; null before [init]. */
    private fun ready(): SharedPreferences? {
        prefs?.let { return it }
        val context = pendingContext ?: return null
        synchronized(openLock) {
            prefs?.let { return it }
            initWith(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                moodSp = context.getSharedPreferences(MOOD_GENRE_PREFS_NAME, Context.MODE_PRIVATE),
            )
            return prefs
        }
    }

    /**
     * [moodSp] holds the mood and genre pages; a test may pass the same file
     * for both. [prefs] is set last, so a reader that sees it sees the rest.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun initWith(
        sp: SharedPreferences,
        lane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
        moodSp: SharedPreferences = sp,
    ) {
        CacheSchema.ensure(sp, SCHEMA_VERSION)
        if (moodSp !== sp) CacheSchema.ensure(moodSp, SCHEMA_VERSION)
        homeMemoryCache.clear()
        exploreMemoryCache = null
        moodGenreMemoryCache.clear()
        synchronized(moodGenreIndex) { moodGenreIndex.clear() }
        val homeWriter = PrefsWriter(sp, lane)
        writer = homeWriter
        moodPrefs = moodSp
        moodWriter = if (moodSp === sp) homeWriter else PrefsWriter(moodSp, lane)
        loadMoodGenreIndex(moodSp)
        // Pages written before they had a file of their own: a cache, so they
        // are dropped rather than moved, and the launch file sheds their weight.
        if (moodSp !== sp && sp.contains(KEY_MOOD_GENRE_INDEX)) {
            homeWriter.write {
                sp.all.keys
                    .filter { it == KEY_MOOD_GENRE_INDEX || it.startsWith(KEY_MOOD_GENRE_PREFIX) }
                    .forEach(::remove)
            }
        }
        // Explore saved before it was kept per language; no key reads it any more.
        if (sp.contains(KEY_EXPLORE)) homeWriter.write { remove(KEY_EXPLORE) }
        pendingContext = null
        prefs = sp
    }

    private fun loadMoodGenreIndex(sp: SharedPreferences) {
        runCatching {
            val arr = sp.getString(KEY_MOOD_GENRE_INDEX, null)?.let { json.parseToJsonElement(it).jsonArray } ?: return
            synchronized(moodGenreIndex) {
                moodGenreIndex.clear()
                arr.forEach { el -> el.jsonPrimitive.contentOrNull?.let(moodGenreIndex::add) }
            }
        }.onFailure { TrackLog.w(TAG, "feed cache: unreadable mood index: ${it.message}") }
    }

    private fun isFresh(feed: CachedHomeFeed) = clock() - feed.timestamp <= MAX_AGE_MS

    /**
     * Per listener and per app language, like Explore's (see [exploreKey]):
     * YouTube names Home's shelves in the language it is asked in, so a feed
     * kept from before a language change opened with its titles in the old one
     * until the network caught up.
     */
    private fun homePrefKey(listenerKey: String?): String {
        val lang = runCatching { com.music.bitchord.data.innertube.Innertube.currentLanguage }.getOrDefault("en")
        return KEY_HOME_PREFIX + (listenerKey?.replace(":", "_") ?: "signed_out") + "_$lang"
    }

    fun getHome(listenerKey: String?): CachedHomeFeed? {
        val key = homePrefKey(listenerKey)
        homeMemoryCache[key]?.let { return it.takeIf(::isFresh) }

        val sp = ready() ?: return null
        val raw = sp.getString(key, null) ?: return null
        return runCatching {
            val parsed = cachedHomeFeedFromJson(json.parseToJsonElement(raw).jsonObject)
            homeMemoryCache[key] = parsed
            parsed.takeIf(::isFresh)
        }.onFailure { TrackLog.w(TAG, "feed cache: unreadable home feed $key: ${it.message}") }.getOrNull()
    }

    fun saveHome(listenerKey: String?, shelves: List<HomeShelf>, continuation: String?) {
        val key = homePrefKey(listenerKey)
        val feed = CachedHomeFeed(
            listenerKey = listenerKey,
            shelves = shelves,
            continuation = continuation,
            timestamp = clock(),
        )
        homeMemoryCache[key] = feed
        ready()
        writer?.put(key, feed.toJsonObject().toString())
    }

    fun touchHome(listenerKey: String?) {
        val key = homePrefKey(listenerKey)
        val existing = homeMemoryCache[key] ?: return
        val updated = existing.copy(timestamp = clock())
        homeMemoryCache[key] = updated
        ready()
        writer?.put(key, updated.toJsonObject().toString())
    }

    private fun exploreKey(): String {
        val lang = runCatching { com.music.bitchord.data.innertube.Innertube.currentLanguage }.getOrDefault("en")
        return "${KEY_EXPLORE}_$lang"
    }

    fun getExplore(): List<MoodGenreSection>? {
        val lang = runCatching { com.music.bitchord.data.innertube.Innertube.currentLanguage }.getOrDefault("en")
        exploreMemoryCache?.let { (cachedLang, data) ->
            if (cachedLang == lang) return data
        }
        val sp = ready() ?: return null
        val raw = sp.getString(exploreKey(), null) ?: return null
        return runCatching {
            val arr = json.parseToJsonElement(raw).jsonArray
            val list = arr.mapNotNull { el ->
                el.takeIf { it !is JsonNull }?.let { runCatching { moodGenreSectionFromJson(it.jsonObject) }.getOrNull() }
            }
            exploreMemoryCache = lang to list
            list
        }.getOrNull()
    }

    fun saveExplore(sections: List<MoodGenreSection>) {
        val lang = runCatching { com.music.bitchord.data.innertube.Innertube.currentLanguage }.getOrDefault("en")
        exploreMemoryCache = lang to sections
        ready()
        val w = writer ?: return
        val array = buildJsonArray {
            sections.forEach { add(it.toJsonObject()) }
        }
        w.put(exploreKey(), array.toString())
    }

    fun getMoodGenreShelves(key: String): List<HomeShelf>? {
        moodGenreMemoryCache[key]?.let { return it }
        ready() ?: return null
        val sp = moodPrefs ?: return null
        val raw = sp.getString(KEY_MOOD_GENRE_PREFIX + key, null) ?: return null
        return runCatching {
            val arr = json.parseToJsonElement(raw).jsonArray
            val list = arr.mapNotNull { el ->
                el.takeIf { it !is JsonNull }?.let { runCatching { homeShelfFromJson(it.jsonObject) }.getOrNull() }
            }
            moodGenreMemoryCache[key] = list
            list
        }.getOrNull()
    }

    fun saveMoodGenreShelves(key: String, shelves: List<HomeShelf>) {
        moodGenreMemoryCache[key] = shelves
        ready()
        val w = moodWriter ?: return
        val array = buildJsonArray {
            shelves.forEach { add(it.toJsonObject()) }
        }
        // Only the last few are kept: every genre ever opened would otherwise
        // stay on disk for good.
        val dropped = synchronized(moodGenreIndex) {
            moodGenreIndex.remove(key)
            moodGenreIndex.add(key)
            buildList { while (moodGenreIndex.size > MAX_MOOD_GENRES) add(moodGenreIndex.removeAt(0)) }
        }
        dropped.forEach { moodGenreMemoryCache.remove(it) }
        val index = buildJsonArray { synchronized(moodGenreIndex) { moodGenreIndex.forEach { add(it) } } }
        w.write {
            putString(KEY_MOOD_GENRE_PREFIX + key, array.toString())
            dropped.forEach { remove(KEY_MOOD_GENRE_PREFIX + it) }
            putString(KEY_MOOD_GENRE_INDEX, index.toString())
        }
    }

    fun areShelvesEqual(a: List<HomeShelf>, b: List<HomeShelf>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val s1 = a[i]
            val s2 = b[i]
            if (s1.title != s2.title || s1.subtitle != s2.subtitle || s1.layout != s2.layout) return false
            if (s1.items.size != s2.items.size) return false
            for (j in s1.items.indices) {
                val it1 = s1.items[j]
                val it2 = s2.items[j]
                if (it1.videoId != it2.videoId ||
                    it1.browseId != it2.browseId ||
                    it1.title != it2.title ||
                    it1.subtitle != it2.subtitle
                ) {
                    return false
                }
            }
        }
        return true
    }

    fun areExploreSectionsEqual(a: List<MoodGenreSection>, b: List<MoodGenreSection>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val sec1 = a[i]
            val sec2 = b[i]
            if (sec1.title != sec2.title) return false
            if (sec1.items.size != sec2.items.size) return false
            for (j in sec1.items.indices) {
                val it1 = sec1.items[j]
                val it2 = sec2.items[j]
                if (it1.browseId != it2.browseId ||
                    it1.params != it2.params ||
                    it1.title != it2.title
                ) {
                    return false
                }
            }
        }
        return true
    }

    fun clearMemory() {
        homeMemoryCache.clear()
        exploreMemoryCache = null
        moodGenreMemoryCache.clear()
    }

    fun clear() {
        homeMemoryCache.clear()
        exploreMemoryCache = null
        moodGenreMemoryCache.clear()
        synchronized(moodGenreIndex) { moodGenreIndex.clear() }
        ready()
        val home = writer
        home?.write {
            clear()
            putInt(CacheSchema.KEY, SCHEMA_VERSION)
        }
        moodWriter?.takeIf { it !== home }?.write {
            clear()
            putInt(CacheSchema.KEY, SCHEMA_VERSION)
        }
    }

    private const val TAG = "BitChord"

    // JSON serialization functions
    fun ShelfItem.toJsonObject(): JsonObject = buildJsonObject {
        put("title", title)
        put("subtitle", subtitle)
        thumbnailUrl?.let { put("thumbnailUrl", it) }
        videoId?.let { put("videoId", it) }
        browseId?.let { put("browseId", it) }
        put("isVideo", isVideo)
    }

    fun shelfItemFromJson(obj: JsonObject): ShelfItem = ShelfItem(
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        subtitle = obj["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        thumbnailUrl = obj["thumbnailUrl"]?.jsonPrimitive?.contentOrNull,
        videoId = obj["videoId"]?.jsonPrimitive?.contentOrNull,
        browseId = obj["browseId"]?.jsonPrimitive?.contentOrNull,
        isVideo = obj["isVideo"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    fun HomeShelf.toJsonObject(): JsonObject = buildJsonObject {
        put("title", title)
        put("subtitle", subtitle)
        moreBrowseId?.let { put("moreBrowseId", it) }
        moreParams?.let { put("moreParams", it) }
        put("layout", layout.name)
        if (tail) put("tail", true)
        putJsonArray("items") {
            items.forEach { add(it.toJsonObject()) }
        }
    }

    fun homeShelfFromJson(obj: JsonObject): HomeShelf = HomeShelf(
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        subtitle = obj["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        moreBrowseId = obj["moreBrowseId"]?.jsonPrimitive?.contentOrNull,
        moreParams = obj["moreParams"]?.jsonPrimitive?.contentOrNull,
        layout = runCatching { ShelfLayout.valueOf(obj["layout"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
            .getOrDefault(ShelfLayout.CARDS),
        tail = obj["tail"]?.jsonPrimitive?.booleanOrNull ?: false,
        items = obj["items"]?.jsonArray?.mapNotNull { el ->
            el.takeIf { it !is JsonNull }?.let { runCatching { shelfItemFromJson(it.jsonObject) }.getOrNull() }
        }.orEmpty(),
    )

    fun MoodGenre.toJsonObject(): JsonObject = buildJsonObject {
        put("title", title)
        put("browseId", browseId)
        params?.let { put("params", it) }
        thumbnailUrl?.let { put("thumbnailUrl", it) }
        stripeColor?.let { put("stripeColor", it) }
    }

    fun moodGenreFromJson(obj: JsonObject): MoodGenre = MoodGenre(
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        browseId = obj["browseId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        params = obj["params"]?.jsonPrimitive?.contentOrNull,
        thumbnailUrl = obj["thumbnailUrl"]?.jsonPrimitive?.contentOrNull,
        stripeColor = obj["stripeColor"]?.jsonPrimitive?.longOrNull,
    )

    fun MoodGenreSection.toJsonObject(): JsonObject = buildJsonObject {
        put("title", title)
        putJsonArray("items") {
            items.forEach { add(it.toJsonObject()) }
        }
    }

    fun moodGenreSectionFromJson(obj: JsonObject): MoodGenreSection = MoodGenreSection(
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        items = obj["items"]?.jsonArray?.mapNotNull { el ->
            el.takeIf { it !is JsonNull }?.let { runCatching { moodGenreFromJson(it.jsonObject) }.getOrNull() }
        }.orEmpty(),
    )

    fun CachedHomeFeed.toJsonObject(): JsonObject = buildJsonObject {
        listenerKey?.let { put("listenerKey", it) }
        continuation?.let { put("continuation", it) }
        put("timestamp", timestamp)
        putJsonArray("shelves") {
            shelves.forEach { add(it.toJsonObject()) }
        }
    }

    fun cachedHomeFeedFromJson(obj: JsonObject): CachedHomeFeed = CachedHomeFeed(
        listenerKey = obj["listenerKey"]?.jsonPrimitive?.contentOrNull,
        continuation = obj["continuation"]?.jsonPrimitive?.contentOrNull,
        timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
        shelves = obj["shelves"]?.jsonArray?.mapNotNull { el ->
            el.takeIf { it !is JsonNull }?.let { runCatching { homeShelfFromJson(it.jsonObject) }.getOrNull() }
        }.orEmpty(),
    )
}
