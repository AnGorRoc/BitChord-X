package com.music.bitchord.data.model

/**
 * Classification of a track's origin and authenticity for search ranking and filtering.
 */
enum class TrackAuthenticityTier(val rank: Int) {
    /** Official label/distributor catalogue release (ATV or catalogue album track). */
    CATALOG(0),

    /**
     * Legitimate upload from the original creator (OMV, linked official artist channel,
     * or independent artist upload with clean metadata and no third-party reupload markers).
     */
    ORIGINAL_CREATOR(1),

    /**
     * Third-party reupload, fan lyric/karaoke video, speed/pitch edit (slowed, sped up,
     * nightcore, bass boosted, 8D, 1-hour loop), or unofficial fan bootleg/cover.
     */
    UNOFFICIAL_UGC(2),
}

/**
 * Classification of a channel/artist [BrowseItem] returned by search.
 */
enum class BrowseChannelTier {
    /** Official YouTube Music registered artist (`MUSIC_PAGE_TYPE_ARTIST`). */
    REGISTERED_ARTIST,

    /**
     * A YouTube channel not officially registered as a YouTube Music Artist,
     * but showing clear music-creator signals (credited on tracks in the results,
     * musician/label/DJ/music-channel identity, or subscriber-backed music channel).
     * Included ONLY as a fallback when valuable results are scarce.
     */
    UNREGISTERED_MUSIC_CHANNEL,

    /**
     * A normal personal YouTube user account/profile with no songs or music-creator
     * signals, or a third-party fan/lyrics/reupload channel. Always excluded from search.
     */
    NORMAL_USER_ACCOUNT,

    /** Albums, playlists, and other non-channel browse items. */
    NON_CHANNEL,
}

/**
 * Pure, deterministic filtering and ranking rules for:
 * 1. Prioritizing official catalogue releases over UGC/reuploads while preserving
 *    direct uploads from independent/original artists without a record label.
 * 2. Identifying continuous DJ sets and enganchados (strictly excluding individual
 *    remixes, full-album uploads, podcasts, and 1-hour single-song loops).
 * 3. Detecting explicit multilingual user query intent (lyrics, alterations, remixes,
 *    or sets/enganchados/mixes) so explicitly requested content is never hidden or
 *    unnecessarily split out of the main All tab.
 */
object TrackFilter {

    const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"
    const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
    const val MUSIC_VIDEO_TYPE_UGC = "MUSIC_VIDEO_TYPE_UGC"
    const val PAGE_TYPE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
    const val PAGE_TYPE_USER_CHANNEL = "MUSIC_PAGE_TYPE_USER_CHANNEL"

    /** Minimum runtime (12 min) for explicit enganchado / DJ set titles. */
    const val MIN_EXPLICIT_DJ_SET_SECONDS = 12 * 60

    /** Minimum runtime (20 min) for broader live-at / festival / mix titles. */
    const val MIN_BROAD_DJ_SET_SECONDS = 20 * 60

    // --- Multilingual lyrics & subtitle patterns ---
    // Latin-script markers are scoped to parenthetical, bracketed, separator-delimited,
    // or multi-word phrases so legitimate songs like "Lyrics to Go" are never matched.
    private val LYRICS_TITLE_REGEX = Regex(
        pattern = """(?:\(|\[|\s[-|/+]\s)\s*(?:(?:con|com|avec|mit|z)\s+)?(?:letra|letras|lyrics?|lyric\s+video|video\s+lyric|paroles|songtext|liedtext|testo|legenda|legendad[oa]|lirik|sozleri|sarki\s+sozleri|tekst|sub(?:titulad[oa]s?|titles?|titulos)?(?:\s+(?:al\s+|en\s+)?(?:espanol|ingles|portugues|francais|english|spanish))?|eng\s+sub|sub\s+esp|sub\s+es|sous[\s-]titres?|untertitel|sottotitoli|traducid[oa]|traducao|traduction|ubersetzung|traduzione|terjemahan|tlumaczenie|karaoke)\b""" +
            """|\b(?:lyric\s+video|video\s+lyric|video\s+con\s+letra|con\s+letra|com\s+letra|letra\s+oficial|avec\s+paroles|con\s+testo|z\s+tekstem|lirik\s+lagu|sarki\s+sozleri|sub\s+espanol|subtitulad[oa]\s+al\s+espanol|traducid[oa]\s+al\s+espanol|legendad[oa]\s+em\s+portugues|karaoke\s+version|version\s+karaoke)\b""" +
            """|\s+(?:letra|lyrics|paroles|songtext|testo|legendado|lirik|sozleri)\s*$""" +
            """|(?:текст\s+песни|(?:\(|\[|\s-\s)\s*(?:текст|слова|караоке|перевод|субтитры))""" +
            """|(?:歌詞|和訳|日本語字幕|カラオケ|가사|한국어\s*자막|노래방|中字|字幕)""",
        option = RegexOption.IGNORE_CASE,
    )

    // --- Multilingual audio alteration / reupload patterns ---
    private val ALTERATION_TITLE_REGEX = Regex(
        pattern = """\b(?:slowed\s*(?:\+|&|and|y|e|et|und)?\s*reverb|slowed\s+down|sped\s+up|speed\s+up|nightcore|bass\s+boosted|8d\s+audio|8d\s+music|16d\s+audio|pitch\s+shifted|1\s+hour|1\s+hora|1\s+heure|1\s+stunde|1\s+ora|10\s+hours|10\s+horas|10\s+heures|10\s+stunden|10\s+ore|loop\s+version|reverb\s+version|tiktok\s+(?:version|edit|remix))\b""" +
            """|(?:\(|\[)\s*(?:slowed|sped\s+up|reverb|bass\s+boosted|nightcore|8d|loop)\s*(?:\)|\])""" +
            """|(?:1時間|1시간|1小时|1小時|1\s*час)""",
        option = RegexOption.IGNORE_CASE,
    )

    // --- Unofficial fan remix / cover / bootleg patterns (only applied to non-catalogue tracks) ---
    private val UNOFFICIAL_REMIX_TITLE_REGEX = Regex(
        pattern = """\b(?:fan\s*made|unofficial\s+remix|bootleg|mashup|tribute\s+to|cover\s+by|acoustic\s+cover|piano\s+cover|guitar\s+cover|remix\s+by|reprise\s+par)\b""" +
            """|(?:\(|\[)\s*(?:unofficial|fan\s*made|bootleg|cover)\b""",
        option = RegexOption.IGNORE_CASE,
    )

    // --- Multilingual third-party fan/lyrics/reupload channel name patterns ---
    private val UNOFFICIAL_CHANNEL_REGEX = Regex(
        pattern = """\b(?:lyrics?|letras?|lyric\s+videos?|paroles|songtext|testi|lirik|sozleri|legendas?|subtitulos|sub\s+espanol|karaoke|nightcore|slowed|sped\s+up|8d\s+audio|bass\s+boosted|reuploads?|fan\s+club|fan\s+page|fanpage|edits?)\b""" +
            """|(?:текст\s+песни|караоке|歌詞|가사|カラオケ|노래방)""",
        option = RegexOption.IGNORE_CASE,
    )

    // --- Explicit multilingual user query intent detectors ---
    private val QUERY_LYRICS_INTENT = Regex(
        pattern = """\b(?:letra|letras|lyrics?|lyric|karaoke|subtitulad[oa]s?|sub\s+espanol|paroles|songtext|liedtext|testo|legenda|legendad[oa]|lirik|sozleri|tekst|traducao|traduction|traduzione|subtitles?)\b""" +
            """|(?:текст|слова|караоке|перевод|субтитры|歌詞|和訳|カラオケ|가사|노래방|中字|字幕)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val QUERY_ALTERATION_INTENT = Regex(
        pattern = """\b(?:slowed|reverb|sped\s+up|speed\s+up|nightcore|bass\s+boosted|8d|16d|1\s+hour|1\s+hora|1\s+heure|1\s+stunde|1\s+ora|loop)\b""" +
            """|(?:1時間|1시간|1小时|1小時|1\s*час)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val QUERY_REMIX_COVER_INTENT = Regex(
        pattern = """\b(?:remix|bootleg|mashup|cover|edit|tribute|reprise)\b""",
        option = RegexOption.IGNORE_CASE,
    )

    /**
     * Explicit user intent for DJ sets / enganchados / mixes across common languages.
     * When this matches, matching sets stay directly in SearchFilter.ALL instead of being
     * separated into a separate DJ_SETS chip.
     */
    private val QUERY_DJ_SET_INTENT = Regex(
        pattern = """\b(?:sets?|dj\s+sets?|live\s+sets?|enganchados?|mix|mixes|megamix|mega\s+mix|mixtape|mezclas?|mixagem|enchaine|non[\s-]?stop|b2b|boiler\s+room|cercle|essential\s+mix|dj\s+session|sesion\s+en\s+vivo|set\s+ao\s+vivo)\b""" +
            """|(?:микс|сет|диджей\s+сет|メドレー|ノンストップ|ミックス|믹스|디제이\s*셋|串烧|串燒|混音)""",
        option = RegexOption.IGNORE_CASE,
    )

    // --- Multilingual DJ Set / Enganchado detectors ---
    private val DJ_SET_EXPLICIT_REGEX = Regex(
        pattern = """\b(?:enganchados?|dj\s+sets?|live\s+sets?|megamix|mega\s+mix|continuous\s+mix|non[\s-]?stop\s+mix|boiler\s+room|cercle|essential\s+mix|b2b|hor\s+berlin|defected|tomorrowland|awakenings|time\s+warp|elrow|afterlife|club\s+set|rooftop\s+set|vinyl\s+set|techno\s+set|house\s+set|progressive\s+set|trance\s+set|set\s+bailable|set\s+ao\s+vivo|mezcla\s+continua|mix\s+continu|enchaine|surekli\s+mix|previa\s+mix|fiesta\s+mix|cachengue\s+mix|cumbia\s+mix|rkt\s+mix|cuarteto\s+mix|reggaeton\s+mix|turreo\s+mix|guaracha\s+mix|boliche|dugem\s+mix|jedag\s+jedug\s+mix|dj\s+session|session\s+set|club\s+mix\s+session|mixtape)\b""" +
            """|(?:диджей\s+сет|клубный\s+микс|нон[\s-]?стоп\s+микс|ノンストップ\s*ミックス|dj\s*ミックス|メドレー|논스톱\s*믹스|디제이\s*셋|電音串燒|电音串烧|连续混音|串烧|串燒)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val DJ_SET_BROAD_REGEX = Regex(
        pattern = """\b(?:live\s+at\s+\w+|live\s+from\s+\w+|set\s+en\s+vivo|sesion\s+en\s+vivo|sessao\s+ao\s+vivo|dj\s+mix|party\s+mix|summer\s+mix|festival\s+set|closing\s+set|opening\s+set|baile\s+mix|mix\s+20\d\d|\bset\s+20\d\d)\b""" +
            """|(?:микс|сет)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val DJ_SET_EXCLUDE_REGEX = Regex(
        pattern = """\b(?:full\s+album|album\s+completo|album\s+complet|ganzes\s+album|disco\s+completo|podcast|entrevista|interview|entretien|intervista|documental|documentary|reaccion|reaction|reacao|reaktion|reazione|1\s+hour|1\s+hora|1\s+heure|1\s+stunde|1\s+ora|10\s+hours|10\s+horas|10\s+heures|10\s+stunden|10\s+ore|loop|audiobook|audiolibro|sleep\s+music|study\s+music|rain\s+sounds|white\s+noise)\b""" +
            """|(?:полный\s+альбом|интервью|реакция|1\s*час|1時間|1시간|1小时|1小時|フルアルバム|전곡)""",
        option = RegexOption.IGNORE_CASE,
    )

    // --- Multilingual channel / artist / profile classifiers ---
    private val ARTIST_SUBTITLE_REGEX = Regex(
        pattern = """^\s*(?:artist|artista|artiste|kunstler|sanatci|wykonawca|artis)\b""" +
            """|^\s*(?:исполнитель|артист|アーティスト|아티스트|歌手|艺人)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val PROFILE_SUBTITLE_REGEX = Regex(
        pattern = """^\s*(?:profile|perfil|profil|profilo|user|usuario|utilisateur|benutzer|utente|kanal|channel|canal)\b""" +
            """|^\s*(?:профиль|канал|プロフィール|프로필|个人资料|個人資料)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val SUBSCRIBER_OR_LISTENER_REGEX = Regex(
        pattern = """\b(?:subscribers?|suscriptores?|inscritos?|abonnes?|abonnenten|iscritti|abone|subskrybentow|pelanggan|monthly\s+(?:audience|listeners?)|oyentes?\s+mensuales?|ouvintes?\s+mensais?)\b""" +
            """|(?:подписчик|слушател|登録者|구독자|订阅|訂閱)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val MUSIC_CHANNEL_CUE_REGEX = Regex(
        pattern = """\b(?:music|musica|musique|musik|muzyka|muzik|records?|recordings?|discos?|label|sound|sounds|audio|beats?|beatmaker|prod\.?|producer|dj|mc|band|banda|orchestra|orquesta|symphony|sinfonica|quartet|cuarteto|trio|choir|coro|studio|estudios?|sessions?|sesiones?|vevo|topic|radio|acoustic|acustico|hip[\s-]?hop|rap|trap|rock|jazz|techno|house|cumbia|rkt|reggaeton|salsa|punk|metal|indie|lo[\s-]?fi|phonk|dubstep|dnb|trance|blues|folk|flamenco|tango|bossa)\b""" +
            """|(?:музыка|рекордс|音楽|ミュージック|음악|뮤직|音乐|音樂)""",
        option = RegexOption.IGNORE_CASE,
    )

    /**
     * Folds common Latin diacritics (Spanish, Portuguese, French, German, Italian,
     * Turkish, Polish, Nordic) to plain ASCII lowercase while leaving non-Latin scripts
     * (Cyrillic, Japanese, Korean, Chinese) intact for multilingual matching.
     */
    internal fun fold(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (val lower = ch.lowercaseChar()) {
                'á', 'à', 'ä', 'â', 'ã', 'å', 'ą' -> sb.append('a')
                'é', 'è', 'ë', 'ê', 'ę' -> sb.append('e')
                'í', 'ì', 'ï', 'î', 'ı' -> sb.append('i')
                'ó', 'ò', 'ö', 'ô', 'õ', 'ø' -> sb.append('o')
                'ú', 'ù', 'ü', 'û' -> sb.append('u')
                'ñ', 'ń' -> sb.append('n')
                'ç', 'ć' -> sb.append('c')
                'ş', 'ś' -> sb.append('s')
                'ğ' -> sb.append('g')
                'ł' -> sb.append('l')
                'ź', 'ż' -> sb.append('z')
                'ß' -> sb.append("ss")
                'œ' -> sb.append("oe")
                'æ' -> sb.append("ae")
                else -> sb.append(lower)
            }
        }
        return sb.toString()
    }

    /**
     * Parses a YouTube duration label like `"3:42"`, `"58:10"`, or `"1:14:05"` into seconds.
     * Returns null when absent or unparseable.
     */
    fun parseDurationSeconds(durationText: String?): Int? {
        if (durationText.isNullOrBlank()) return null
        val parts = durationText.trim().split(':')
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return when (nums.size) {
            2 -> nums[0] * 60 + nums[1]
            3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            else -> null
        }
    }

    /**
     * Returns true when the user's search [query] explicitly asks for DJ sets,
     * enganchados, mixes, megamixes, etc. in any common language.
     *
     * When true, matching continuous sets stay directly in [SearchFilter.ALL]
     * instead of being separated into a separate [SearchFilter.DJ_SETS] chip.
     */
    fun queryRequestsDjSet(query: String): Boolean {
        if (query.isBlank()) return false
        return QUERY_DJ_SET_INTENT.containsMatchIn(fold(query))
    }

    /**
     * Classifies a [Song] into its authenticity tier.
     *
     * Key guarantees:
     * - Official catalogue tracks (`MUSIC_VIDEO_TYPE_ATV`, or non-video tracks with an
     *   album id and not marked UGC) are ALWAYS [TrackAuthenticityTier.CATALOG].
     * - Independent/original creator uploads without a record label (e.g., uploaded
     *   directly to the artist's own channel with a clean title and channel name)
     *   are classified as [TrackAuthenticityTier.ORIGINAL_CREATOR] and preserved.
     * - Fan lyric videos, audio-alteration reuploads, and third-party reupload channels
     *   are classified as [TrackAuthenticityTier.UNOFFICIAL_UGC].
     */
    fun classify(song: Song): TrackAuthenticityTier {
        val type = song.musicVideoType

        // Tier 1: Official distributor / label catalogue track (immune to title heuristics).
        if (type == MUSIC_VIDEO_TYPE_ATV) return TrackAuthenticityTier.CATALOG
        if (type != MUSIC_VIDEO_TYPE_UGC && !song.isVideo && !song.albumId.isNullOrBlank()) {
            return TrackAuthenticityTier.CATALOG
        }

        val foldedTitle = fold(song.title)
        val foldedArtist = fold(song.artist)

        val isLyrics = LYRICS_TITLE_REGEX.containsMatchIn(foldedTitle)
        val isAlteration = ALTERATION_TITLE_REGEX.containsMatchIn(foldedTitle)
        val isUnofficialRemix = UNOFFICIAL_REMIX_TITLE_REGEX.containsMatchIn(foldedTitle)
        val isUnofficialChannel = UNOFFICIAL_CHANNEL_REGEX.containsMatchIn(foldedArtist)

        // Third-party fan/lyrics/reupload channel is always unofficial UGC.
        if (isUnofficialChannel) return TrackAuthenticityTier.UNOFFICIAL_UGC

        // Explicit lyric video, speed/pitch alteration, or unofficial fan remix/bootleg.
        if (isLyrics || isAlteration || isUnofficialRemix) {
            return TrackAuthenticityTier.UNOFFICIAL_UGC
        }

        // Tier 2: Official Music Video (OMV) from Official Artist Channel.
        if (type == MUSIC_VIDEO_TYPE_OMV) return TrackAuthenticityTier.ORIGINAL_CREATOR

        // Tier 2: Non-video track with a linked artist channel (catalogue-like even without albumId).
        if (!song.isVideo && (!song.artistId.isNullOrBlank() || song.artists.any { !it.browseId.isNullOrBlank() })) {
            return TrackAuthenticityTier.ORIGINAL_CREATOR
        }

        // Tier 2: Independent creator upload directly to their channel with clean title & channel.
        // Even if YouTube marks an independent artist's upload as UGC or omits musicVideoType,
        // if it has no lyrics/alteration/bootleg/reupload markers we protect it as ORIGINAL_CREATOR.
        return TrackAuthenticityTier.ORIGINAL_CREATOR
    }

    /**
     * Checks whether a [Song] should be considered an unwanted UGC result for [query].
     * Respects explicit user intent: if the user searched for lyrics/letra/paroles/etc.,
     * slowed/nightcore, or remix/cover in any supported language, matching items are kept.
     */
    fun isUnwantedUgcForQuery(song: Song, query: String): Boolean {
        if (classify(song) != TrackAuthenticityTier.UNOFFICIAL_UGC) return false
        val foldedQuery = fold(query)
        val foldedTitle = fold(song.title)
        val foldedArtist = fold(song.artist)

        val wantsLyrics = QUERY_LYRICS_INTENT.containsMatchIn(foldedQuery)
        val wantsAlteration = QUERY_ALTERATION_INTENT.containsMatchIn(foldedQuery)
        val wantsRemixOrCover = QUERY_REMIX_COVER_INTENT.containsMatchIn(foldedQuery)

        val isLyrics = LYRICS_TITLE_REGEX.containsMatchIn(foldedTitle) ||
            QUERY_LYRICS_INTENT.containsMatchIn(foldedArtist)
        val isAlteration = ALTERATION_TITLE_REGEX.containsMatchIn(foldedTitle) ||
            QUERY_ALTERATION_INTENT.containsMatchIn(foldedArtist)
        val isUnofficialRemix = UNOFFICIAL_REMIX_TITLE_REGEX.containsMatchIn(foldedTitle)

        if (isLyrics && wantsLyrics) return false
        if (isAlteration && wantsAlteration) return false
        if (isUnofficialRemix && wantsRemixOrCover) return false

        return true
    }

    /**
     * Classifies a [BrowseItem] to separate registered YouTube Music artists from
     * normal user accounts without songs and unregistered music channels.
     *
     * @param trackArtistIds Artist browseIds credited on playable tracks in the same
     *   search response — proves an unregistered channel actually has songs.
     */
    fun classifyBrowseChannel(
        item: BrowseItem,
        trackArtistIds: Set<String> = emptySet(),
    ): BrowseChannelTier {
        if (item.type != BrowseType.ARTIST && !item.browseId.startsWith("UC")) {
            return BrowseChannelTier.NON_CHANNEL
        }

        val pageType = item.pageType.orEmpty()
        val foldedTitle = fold(item.title)
        val foldedSubtitle = fold(item.subtitle)

        // 1. Explicitly registered YouTube Music Artist.
        if ("ARTIST" in pageType) {
            return BrowseChannelTier.REGISTERED_ARTIST
        }

        val isExplicitUserChannel = "USER_CHANNEL" in pageType ||
            PROFILE_SUBTITLE_REGEX.containsMatchIn(foldedSubtitle)

        // 2. When pageType is omitted (e.g. legacy/cached rows), a localized "Artist"
        // subtitle without user-channel markers still identifies a registered artist.
        if (!isExplicitUserChannel && pageType.isBlank() && ARTIST_SUBTITLE_REGEX.containsMatchIn(foldedSubtitle)) {
            return BrowseChannelTier.REGISTERED_ARTIST
        }

        // 3. Fan/lyrics/reupload channels are never treated as legitimate musician channels.
        if (UNOFFICIAL_CHANNEL_REGEX.containsMatchIn(foldedTitle)) {
            return BrowseChannelTier.NORMAL_USER_ACCOUNT
        }

        // 4. Unregistered channel with clear proof or strong signals of being a music channel:
        //    - Credited as artistId on a playable track in the same search results, OR
        //    - Has musician/music-channel identity cues in its name or subtitle, OR
        //    - Has an active subscriber/listener count on a non-profile channel.
        val hasSongsInResults = item.browseId in trackArtistIds
        val hasMusicCue = MUSIC_CHANNEL_CUE_REGEX.containsMatchIn(foldedTitle) ||
            MUSIC_CHANNEL_CUE_REGEX.containsMatchIn(foldedSubtitle)
        val hasSubscribersOnNonProfile = !PROFILE_SUBTITLE_REGEX.containsMatchIn(foldedSubtitle) &&
            SUBSCRIBER_OR_LISTENER_REGEX.containsMatchIn(foldedSubtitle)

        if (hasSongsInResults || hasMusicCue || hasSubscribersOnNonProfile) {
            return BrowseChannelTier.UNREGISTERED_MUSIC_CHANNEL
        }

        // 5. Plain personal user account with no songs and no music-channel indicators.
        return BrowseChannelTier.NORMAL_USER_ACCOUNT
    }

    /**
     * Ranks and filters a search result list so:
     * 1. Official catalogue music is prioritized and independent creator uploads are preserved,
     *    while unwanted fan lyrics/UGC reuploads are filtered out (with an anti-empty fallback).
     * 2. Normal user accounts without songs are always excluded.
     * 3. Unregistered music channels are included ONLY as a fallback when it is difficult
     *    to find other valuable results (registered artists, clean songs, albums, playlists).
     */
    fun prioritizeAndFilterSearchResults(
        results: List<SearchResult>,
        query: String,
        filter: SearchFilter,
    ): List<SearchResult> {
        if (filter != SearchFilter.ALL && filter != SearchFilter.SONGS && filter != SearchFilter.ARTISTS) {
            return results
        }
        if (results.isEmpty()) return results

        val trackRows = results.filter { it is SearchResult.Track || it is SearchResult.TopTrack }
        val hasCleanTrack = trackRows.any { row ->
            val song = row.songOrNull() ?: return@any false
            !isUnwantedUgcForQuery(song, query)
        }

        // Collect artistIds from playable tracks so an unregistered channel that actually
        // authored songs in these results is recognized as a music channel.
        val trackArtistIds = buildSet {
            trackRows.forEach { row ->
                val song = row.songOrNull() ?: return@forEach
                song.artistId?.takeIf { it.isNotBlank() }?.let(::add)
                song.artists.forEach { ref -> ref.browseId?.takeIf { it.isNotBlank() }?.let(::add) }
            }
        }

        // Step A: Filter unwanted UGC tracks (with safety fallback so tracks never collapse to empty).
        val afterTrackFilter = if (trackRows.isNotEmpty() && hasCleanTrack) {
            results.filter { row ->
                val song = row.songOrNull() ?: return@filter true
                !isUnwantedUgcForQuery(song, query)
            }
        } else {
            results
        }

        // Step B: Filter normal user accounts and conditionally include unregistered music channels
        // ONLY when valuable results are scarce.
        val hasRegisteredArtist = afterTrackFilter.any { row ->
            row is SearchResult.Browse &&
                classifyBrowseChannel(row.item, trackArtistIds) == BrowseChannelTier.REGISTERED_ARTIST
        }
        val valuableNonChannelCount = afterTrackFilter.count { row ->
            when (row) {
                is SearchResult.TopTrack, is SearchResult.Track -> {
                    val song = row.songOrNull()
                    song != null && !isUnwantedUgcForQuery(song, query)
                }
                is SearchResult.Browse -> {
                    classifyBrowseChannel(row.item, trackArtistIds) == BrowseChannelTier.NON_CHANNEL
                }
            }
        }
        val allowUnregisteredMusicChannels = when (filter) {
            SearchFilter.ARTISTS -> !hasRegisteredArtist
            SearchFilter.ALL -> !hasRegisteredArtist && valuableNonChannelCount < 2
            else -> false
        }

        val kept = afterTrackFilter.filter { row ->
            if (row !is SearchResult.Browse) return@filter true
            when (classifyBrowseChannel(row.item, trackArtistIds)) {
                BrowseChannelTier.REGISTERED_ARTIST,
                BrowseChannelTier.NON_CHANNEL,
                -> true
                BrowseChannelTier.UNREGISTERED_MUSIC_CHANNEL -> allowUnregisteredMusicChannels
                BrowseChannelTier.NORMAL_USER_ACCOUNT -> false
            }
        }

        // For ARTISTS or explicit set/enganchado/mix queries in ALL, preserve relevance order.
        if (filter == SearchFilter.ARTISTS || (filter == SearchFilter.ALL && queryRequestsDjSet(query))) {
            return kept
        }

        // Stable re-ordering of Track rows so CATALOG ranks above ORIGINAL_CREATOR,
        // while keeping TopTrack and Browse items anchored in place.
        val sortedTracks = kept.filterIsInstance<SearchResult.Track>()
            .sortedBy { classify(it.song).rank }

        val trackIterator = sortedTracks.iterator()
        return kept.map { row ->
            if (row is SearchResult.Track) trackIterator.next() else row
        }
    }

    /**
     * Returns true if and only if [song] is a genuine continuous DJ set or enganchado.
     *
     * Strictly excludes:
     * - Individual remixes or songs (< 12 minutes)
     * - Live 24/7 radio streams (`song.isLive`)
     * - Full album uploads, podcasts, interviews, reactions, and 1-hour single-song loops
     */
    fun isDjSetOrEnganchado(song: Song): Boolean {
        if (song.isLive) return false
        val durationSec = parseDurationSeconds(song.durationText) ?: return false
        if (durationSec < MIN_EXPLICIT_DJ_SET_SECONDS) return false

        val foldedTitle = fold(song.title)
        val foldedArtist = fold(song.artist)
        val combined = "$foldedTitle $foldedArtist"

        if (DJ_SET_EXCLUDE_REGEX.containsMatchIn(foldedTitle)) return false

        if (DJ_SET_EXPLICIT_REGEX.containsMatchIn(combined)) {
            return true
        }

        if (durationSec >= MIN_BROAD_DJ_SET_SECONDS && DJ_SET_BROAD_REGEX.containsMatchIn(combined)) {
            return true
        }

        return false
    }

    /**
     * Builds the YouTube Music query used to check/fetch continuous DJ sets & enganchados
     * for a user's search [query].
     */
    fun djSetSearchQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (queryRequestsDjSet(trimmed)) {
            trimmed
        } else {
            "$trimmed dj set enganchado"
        }
    }

    private fun SearchResult.songOrNull(): Song? = when (this) {
        is SearchResult.TopTrack -> song
        is SearchResult.Track -> song
        is SearchResult.Browse -> null
    }
}
