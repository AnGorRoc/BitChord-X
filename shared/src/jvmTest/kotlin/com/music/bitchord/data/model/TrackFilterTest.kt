package com.music.bitchord.data.model

import com.music.bitchord.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFilterTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Artist",
        durationText: String? = "3:30",
        artistId: String? = "UC_artist",
        albumId: String? = null,
        isVideo: Boolean = false,
        isLive: Boolean = false,
        musicVideoType: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = "https://img/$id",
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        isVideo = isVideo,
        isLive = isLive,
        musicVideoType = musicVideoType,
    )

    // -------------------------------------------------------------------------
    // 1. Authenticity classification & edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `official ATV catalogue track is always CATALOG even with tricky titles`() {
        val trickyOfficialSongs = listOf(
            song("a1", "Lyrics to Go", "A Tribe Called Quest", albumId = "MPRE1", musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_ATV),
            song("a2", "Slowed Down", "Official Band", albumId = "MPRE2", musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_ATV),
            song("a3", "Nightcore", "Ken Carson", albumId = "MPRE3", musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_ATV),
            song("a4", "Blinding Lights (Major Lazer Remix)", "The Weeknd", albumId = "MPRE4", musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_ATV),
        )
        for (track in trickyOfficialSongs) {
            assertEquals("${track.title} must stay CATALOG", TrackAuthenticityTier.CATALOG, TrackFilter.classify(track))
            assertFalse("${track.title} must never be filtered", TrackFilter.isUnwantedUgcForQuery(track, "test"))
        }
    }

    @Test
    fun `independent creator upload without record label is preserved as ORIGINAL_CREATOR`() {
        // Case A: Independent artist uploads directly to YouTube (no albumId, marked UGC by YouTube, clean title & channel)
        val indieDirect = song(
            id = "indie1",
            title = "Mi Cancion Inedita",
            artist = "Artista Independiente",
            artistId = null,
            albumId = null,
            isVideo = false,
            musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC,
        )
        assertEquals(TrackAuthenticityTier.ORIGINAL_CREATOR, TrackFilter.classify(indieDirect))
        assertFalse(TrackFilter.isUnwantedUgcForQuery(indieDirect, "Mi Cancion Inedita"))

        // Case B: Official Music Video (OMV) uploaded by creator's channel without label release
        val indieOmv = song(
            id = "indie2",
            title = "Tema Nuevo (Video Oficial)",
            artist = "Artista Independiente",
            artistId = "UC_indie",
            albumId = null,
            isVideo = true,
            musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_OMV,
        )
        assertEquals(TrackAuthenticityTier.ORIGINAL_CREATOR, TrackFilter.classify(indieOmv))
        assertFalse(TrackFilter.isUnwantedUgcForQuery(indieOmv, "Tema Nuevo"))

        // Case C: SoundCloud-style creator with linked artist channel and no musicVideoType metadata
        val indieNoType = song(
            id = "indie3",
            title = "Freestyle Session 04",
            artist = "MC Underground",
            artistId = "UC_mc",
            albumId = null,
            isVideo = false,
            musicVideoType = null,
        )
        assertEquals(TrackAuthenticityTier.ORIGINAL_CREATOR, TrackFilter.classify(indieNoType))
    }

    @Test
    fun `fan lyrics, audio alterations, and reupload channels are classified as UNOFFICIAL_UGC`() {
        val unwanted = listOf(
            song("u1", "Monaco (Letra)", "Canal Random", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u2", "Monaco [Lyrics]", "Music Fan", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u3", "Monaco - Video con Letra", "Subtitulos HD", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u4", "Monaco Sub Español", "Traducciones", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u5", "Monaco (Slowed + Reverb)", "Aesthetic Vibes", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u6", "Monaco Sped Up", "TikTok Audios", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u7", "Monaco Nightcore", "Anime Music", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u8", "Monaco Bass Boosted", "Car Audio", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u9", "Monaco 8D Audio", "Spatial Club", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u10", "Monaco 1 Hora", "Loop Station", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u11", "Monaco", "Reggaeton Lyrics HD", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u12", "Monaco (Unofficial Remix)", "Fan Producer", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
            song("u13", "Monaco (Karaoke Version)", "Sing Along", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
        )
        for (track in unwanted) {
            assertEquals("${track.title} by ${track.artist} should be UNOFFICIAL_UGC", TrackAuthenticityTier.UNOFFICIAL_UGC, TrackFilter.classify(track))
            assertTrue("${track.title} should be filtered for normal query", TrackFilter.isUnwantedUgcForQuery(track, "Bad Bunny Monaco"))
        }
    }

    @Test
    fun `explicit query intent bypasses filtering for requested lyrics, slowed, or remix`() {
        val lyricTrack = song("l1", "Monaco (Letra)", "Canal Letras", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC)
        val slowedTrack = song("s1", "Monaco (Slowed + Reverb)", "Vibes", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC)
        val remixTrack = song("r1", "Monaco (Bootleg)", "DJ Fan", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC)

        assertFalse(TrackFilter.isUnwantedUgcForQuery(lyricTrack, "bad bunny monaco letra"))
        assertFalse(TrackFilter.isUnwantedUgcForQuery(slowedTrack, "bad bunny monaco slowed"))
        assertFalse(TrackFilter.isUnwantedUgcForQuery(remixTrack, "bad bunny monaco remix"))
    }

    // -------------------------------------------------------------------------
    // 2. Ranking, filtering & fallback comparisons
    // -------------------------------------------------------------------------

    @Test
    fun `prioritizeAndFilterSearchResults puts CATALOG first, keeps ORIGINAL_CREATOR, drops fan UGC`() {
        val fanLyrics = SearchResult.Track(song("ugc1", "Song (Letra)", "Letras Channel", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC))
        val indieCreator = SearchResult.Track(song("indie1", "Song (Acoustic Demo)", "Original Artist", artistId = "UC1", albumId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC))
        val slowedEdit = SearchResult.Track(song("ugc2", "Song (Slowed + Reverb)", "Edits", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC))
        val officialAtv = SearchResult.Track(song("atv1", "Song", "Original Artist", artistId = "UC1", albumId = "MPRE1", musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_ATV))
        val artistCard = SearchResult.Browse(BrowseItem("UC1", "Original Artist", "Artist", null, BrowseType.ARTIST))

        val input = listOf(artistCard, fanLyrics, indieCreator, slowedEdit, officialAtv)
        val output = TrackFilter.prioritizeAndFilterSearchResults(input, "Original Artist Song", SearchFilter.ALL)

        assertEquals(3, output.size)
        // Browse item remains anchored at index 0
        assertEquals(artistCard, output[0])
        // Official catalogue ATV promoted ahead of ORIGINAL_CREATOR
        assertEquals("atv1", (output[1] as SearchResult.Track).song.videoId)
        // Independent/creator upload is preserved right after catalogue
        assertEquals("indie1", (output[2] as SearchResult.Track).song.videoId)
    }

    @Test
    fun `safety fallback never returns empty when all tracks are UGC`() {
        val onlyLeak = SearchResult.Track(song("leak1", "Unreleased Track (Letra)", "Leaks Fan", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC))
        val output = TrackFilter.prioritizeAndFilterSearchResults(listOf(onlyLeak), "Unreleased Track", SearchFilter.SONGS)

        assertEquals(1, output.size)
        assertEquals("leak1", (output.single() as SearchResult.Track).song.videoId)
    }

    @Test
    fun `non-music filters such as VIDEOS are not altered by catalogue filter`() {
        val lyricVideo = SearchResult.Track(song("v1", "Song (Lyric Video)", "Fan", isVideo = true, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC))
        val output = TrackFilter.prioritizeAndFilterSearchResults(listOf(lyricVideo), "Song", SearchFilter.VIDEOS)
        assertEquals(listOf(lyricVideo), output)
    }

    // -------------------------------------------------------------------------
    // 3. Continuous DJ Sets & Enganchados detector
    // -------------------------------------------------------------------------

    @Test
    fun `isDjSetOrEnganchado accepts genuine continuous DJ sets and enganchados`() {
        val validSets = listOf(
            song("dj1", "Enganchado Cumbia RKT 2026 - DJ Tao", "DJ Tao", durationText = "42:15", isVideo = true),
            song("dj2", "Hernan Cattaneo | Cercle Live Set", "Cercle", durationText = "1:18:30", isVideo = true),
            song("dj3", "Fred again.. | Boiler Room London", "Boiler Room", durationText = "1:09:42", isVideo = true),
            song("dj4", "Megamix Reggaeton Old School", "DJ Mix", durationText = "18:40", isVideo = true),
            song("dj5", "Bizarrap Live at Tomorrowland 2025", "Tomorrowland", durationText = "55:00", isVideo = true),
            song("dj6", "Previa Mix Cachengue Boliche", "DJ Fer", durationText = "24:10", isVideo = true),
            song("dj7", "Solomun B2B Dixon - Continuous Mix", "Club TV", durationText = "1:45:00", isVideo = true),
            song("dj8", "Sesión en vivo Tech House", "Club Buenos Aires", durationText = "32:00", isVideo = true),
        )
        for (set in validSets) {
            assertTrue("${set.title} (${set.durationText}) should be recognized as DJ set", TrackFilter.isDjSetOrEnganchado(set))
        }
    }

    @Test
    fun `isDjSetOrEnganchado strictly rejects individual remixes, short clips, full albums, podcasts and loops`() {
        val rejected = listOf(
            song("r1", "Song (Official Remix)", "Artist", durationText = "3:45", isVideo = true),
            song("r2", "Song (Extended Club Mix)", "Artist", durationText = "6:20", isVideo = true),
            song("r3", "Song (DJ Remix)", "Artist", durationText = "4:10", isVideo = true),
            song("r4", "Enganchado Corto", "DJ", durationText = "8:30", isVideo = true),
            song("r5", "Un Verano Sin Ti (Full Album)", "Bad Bunny", durationText = "1:21:53", isVideo = true),
            song("r6", "Album Completo Cumbia", "Band", durationText = "48:00", isVideo = true),
            song("r7", "Podcast Entrevista sobre DJ Set", "Podcast", durationText = "1:10:00", isVideo = true),
            song("r8", "Song 1 Hour Loop DJ Mix", "Loop Channel", durationText = "1:00:00", isVideo = true),
            song("r9", "24/7 Live Radio DJ Set", "Live Radio", durationText = "1:00:00", isVideo = true, isLive = true),
            song("r10", "Documentary About Music", "Docs", durationText = "45:00", isVideo = true),
            song("r11", "Enganchado Sin Duracion", "DJ", durationText = null, isVideo = true),
        )
        for (track in rejected) {
            assertFalse("${track.title} (${track.durationText}) must NOT be classified as DJ set", TrackFilter.isDjSetOrEnganchado(track))
        }
    }

    @Test
    fun `djSetSearchQuery appends set keywords only when not already present`() {
        assertEquals("Hernan Cattaneo dj set enganchado", TrackFilter.djSetSearchQuery("Hernan Cattaneo"))
        assertEquals("Ke Personajes enganchado", TrackFilter.djSetSearchQuery("Ke Personajes enganchado"))
        assertEquals("Fred again boiler room", TrackFilter.djSetSearchQuery("Fred again boiler room"))
    }

    // -------------------------------------------------------------------------
    // 4. InnertubeParser integration: musicVideoType propagation
    // -------------------------------------------------------------------------

    @Test
    fun `InnertubeParser extracts musicVideoType from search rows for TrackFilter`() {
        val json = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicShelfRenderer": {
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "atv_01" },
                                "overlay": {
                                  "musicItemThumbnailOverlayRenderer": {
                                    "content": {
                                      "musicPlayButtonRenderer": {
                                        "playNavigationEndpoint": {
                                          "watchEndpoint": {
                                            "videoId": "atv_01",
                                            "watchEndpointMusicSupportedConfigs": {
                                              "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                },
                                "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://img/atv", "width": 120, "height": 120 }] } } },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Official Song" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Song" }, { "text": " • " }, { "text": "Real Artist" }, { "text": " • " }, { "text": "3:20" }] } } }
                                ]
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "ugc_01" },
                                "overlay": {
                                  "musicItemThumbnailOverlayRenderer": {
                                    "content": {
                                      "musicPlayButtonRenderer": {
                                        "playNavigationEndpoint": {
                                          "watchEndpoint": {
                                            "videoId": "ugc_01",
                                            "watchEndpointMusicSupportedConfigs": {
                                              "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_UGC" }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                },
                                "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://img/ugc", "width": 120, "height": 120 }] } } },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Official Song (Letra)" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Song" }, { "text": " • " }, { "text": "Letras Fan" }, { "text": " • " }, { "text": "3:22" }] } } }
                                ]
                              }
                            }
                          ]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val parsed = InnertubeParser.parseSearchPage(Json.parseToJsonElement(json).jsonObject)
        assertEquals(2, parsed.rows.size)
        val atvSong = (parsed.rows[0] as SearchResult.Track).song
        val ugcSong = (parsed.rows[1] as SearchResult.Track).song

        assertEquals(TrackFilter.MUSIC_VIDEO_TYPE_ATV, atvSong.musicVideoType)
        assertEquals(TrackFilter.MUSIC_VIDEO_TYPE_UGC, ugcSong.musicVideoType)

        val filtered = TrackFilter.prioritizeAndFilterSearchResults(parsed.rows, "Official Song", SearchFilter.SONGS)
        assertEquals(1, filtered.size)
        assertEquals("atv_01", (filtered.single() as SearchResult.Track).song.videoId)
    }

    // -------------------------------------------------------------------------
    // 5. Multilingual variants & keeping explicit set/mix queries directly in ALL
    // -------------------------------------------------------------------------

    @Test
    fun `multilingual lyrics and reupload variants are detected and respect explicit query intent`() {
        val multilingualLyrics = listOf(
            song("pt1", "Evidencias (Legendado)", "Canal Legendas", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "evidencias legendado",
            song("fr1", "Derniere Danse (Paroles)", "Musique Paroles", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "derniere danse paroles",
            song("de1", "99 Luftballons (Songtext)", "Deutsch Lyrics", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "99 luftballons songtext",
            song("it1", "Volare (Testo)", "Testi Italiani", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "volare testo",
            song("ru1", "Песня (Текст песни)", "Тексты", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "песня текст",
            song("ja1", "夜に駆ける 歌詞", "歌詞チャンネル", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "夜に駆ける 歌詞",
            song("ko1", "강남스타일 가사", "가사 채널", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "강남스타일 가사",
            song("zh1", "晴天 字幕 中字", "音乐频道", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "晴天 中字",
            song("tr1", "Şarkı (Şarkı Sözleri)", "Sözler", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "şarkı sözleri",
            song("pl1", "Piosenka (z tekstem)", "Muzyka", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "piosenka tekst",
            song("id1", "Lagu (Lirik Lagu)", "Lirik Channel", artistId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC) to "lagu lirik",
        )
        for ((track, explicitQuery) in multilingualLyrics) {
            assertEquals("${track.title} must classify as UNOFFICIAL_UGC", TrackAuthenticityTier.UNOFFICIAL_UGC, TrackFilter.classify(track))
            assertTrue("${track.title} must be filtered on normal query", TrackFilter.isUnwantedUgcForQuery(track, "normal song search"))
            assertFalse("${track.title} must be kept on explicit query '$explicitQuery'", TrackFilter.isUnwantedUgcForQuery(track, explicitQuery))
        }
    }

    @Test
    fun `queryRequestsDjSet detects explicit set, enganchado and mix queries in common languages`() {
        val setQueries = listOf(
            "hernan cattaneo set",
            "ke personajes enganchado",
            "cumbia enganchados 2026",
            "techno mix",
            "reggaeton megamix",
            "house dj set",
            "baile funk set ao vivo",
            "mix continu electro",
            "диджей сет техно",
            "ノンストップ ミックス",
            "하우스 디제이 셋",
            "電音串燒",
        )
        for (q in setQueries) {
            assertTrue("Query '$q' should be recognized as DJ set intent", TrackFilter.queryRequestsDjSet(q))
        }

        val normalQueries = listOf(
            "Bad Bunny",
            "Ke Personajes",
            "Hernan Cattaneo",
            "Duki Rockstar",
            "Soda Stereo De Musica Ligera",
        )
        for (q in normalQueries) {
            assertFalse("Query '$q' must NOT trigger DJ set intent", TrackFilter.queryRequestsDjSet(q))
        }
    }

    @Test
    fun `parseSearchPage with includeMatchingSetVideos keeps DJ sets directly in ALL without leaking normal videos`() {
        val json = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicShelfRenderer": {
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "set_01" },
                                "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://img/set", "width": 320, "height": 180 }] } } },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Enganchado Cumbia 2026 DJ Set" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Video" }, { "text": " • " }, { "text": "DJ Tao" }, { "text": " • " }, { "text": "45:20" }] } } }
                                ]
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "vid_01" },
                                "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://img/vid", "width": 320, "height": 180 }] } } },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Short Music Video" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Video" }, { "text": " • " }, { "text": "Artist" }, { "text": " • " }, { "text": "3:15" }] } } }
                                ]
                              }
                            }
                          ]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val root = Json.parseToJsonElement(json).jsonObject

        // Normal ALL search (includeMatchingSetVideos = false): drops both videos
        val normalAll = InnertubeParser.parseSearchPage(root, includeVideos = false, includeMatchingSetVideos = false)
        assertTrue(normalAll.rows.isEmpty())

        // Explicit set/enganchado ALL search (includeMatchingSetVideos = true):
        // keeps the 45-min DJ set directly in ALL, while still dropping the 3-min normal video!
        val setAll = InnertubeParser.parseSearchPage(root, includeVideos = false, includeMatchingSetVideos = true)
        assertEquals(1, setAll.rows.size)
        assertEquals("set_01", (setAll.rows.single() as SearchResult.Track).song.videoId)
    }

    // -------------------------------------------------------------------------
    // 6. Normal user accounts vs. unregistered music channels (scarce fallback)
    // -------------------------------------------------------------------------

    @Test
    fun `normal user accounts without songs are always excluded even when no other results exist`() {
        val profiles = listOf(
            SearchResult.Browse(BrowseItem("UC_u1", "Juan Perez", "Perfil • @juanperez", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL)),
            SearchResult.Browse(BrowseItem("UC_u2", "John Doe", "Profile • @johndoe", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL)),
            SearchResult.Browse(BrowseItem("UC_u3", "Marie Dupont", "Profil • @marie", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL)),
            SearchResult.Browse(BrowseItem("UC_u4", "Иван Иванов", "Профиль", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL)),
            SearchResult.Browse(BrowseItem("UC_u5", "山田太郎", "プロフィール", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL)),
            SearchResult.Browse(BrowseItem("UC_u6", "Reggaeton Lyrics HD", "Channel • 50K subscribers", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL)),
        )

        val outAll = TrackFilter.prioritizeAndFilterSearchResults(profiles, "juan perez", SearchFilter.ALL)
        val outArtists = TrackFilter.prioritizeAndFilterSearchResults(profiles, "juan perez", SearchFilter.ARTISTS)

        assertTrue("Normal profiles and lyrics channels must never appear in ALL", outAll.isEmpty())
        assertTrue("Normal profiles and lyrics channels must never appear in ARTISTS", outArtists.isEmpty())
    }

    @Test
    fun `unregistered music channels are omitted when valuable results exist`() {
        val registeredArtist = SearchResult.Browse(
            BrowseItem("UC_reg", "Bad Bunny", "Artist • 80M monthly listeners", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_ARTIST),
        )
        val unregisteredMusic = SearchResult.Browse(
            BrowseItem("UC_mus", "Bad Bunny Beats Studio", "Channel • 12K subscribers", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL),
        )
        val normalProfile = SearchResult.Browse(
            BrowseItem("UC_norm", "Bad Bunny Fan", "Profile • @bbfan", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL),
        )

        val outArtists = TrackFilter.prioritizeAndFilterSearchResults(
            listOf(registeredArtist, unregisteredMusic, normalProfile),
            "Bad Bunny",
            SearchFilter.ARTISTS,
        )
        assertEquals(listOf(registeredArtist), outArtists)

        // In ALL with 2+ valuable tracks (even without a registered artist), unregistered music channel is still omitted
        val song1 = SearchResult.Track(song("s1", "Track 1", "Artist", albumId = "MPRE1", musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_ATV))
        val song2 = SearchResult.Track(song("s2", "Track 2", "Artist", albumId = "MPRE1", musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_ATV))
        val outAll = TrackFilter.prioritizeAndFilterSearchResults(
            listOf(song1, song2, unregisteredMusic, normalProfile),
            "Track",
            SearchFilter.ALL,
        )
        assertEquals(listOf(song1, song2), outAll)
    }

    @Test
    fun `unregistered music channels are included as fallback when valuable results are scarce`() {
        val unregisteredDj = SearchResult.Browse(
            BrowseItem("UC_dj", "DJ Pepon Underground", "Perfil • @djpepon", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL),
        )
        val normalUser = SearchResult.Browse(
            BrowseItem("UC_plain", "Pepon Garcia", "Perfil • @pepongarcia", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL),
        )

        // ARTISTS tab with zero registered artists: keeps unregistered music channel, drops normal profile
        val outArtists = TrackFilter.prioritizeAndFilterSearchResults(
            listOf(normalUser, unregisteredDj),
            "DJ Pepon Underground",
            SearchFilter.ARTISTS,
        )
        assertEquals(listOf(unregisteredDj), outArtists)

        // ALL tab with zero registered artists and only 1 independent track credited to an unregistered channel:
        // keeps both the track and the credited channel, drops unrelated normal profile
        val indieTrack = SearchResult.Track(
            song("t1", "Mi Tema", "Lucas Mendoza", artistId = "UC_lucas", albumId = null, musicVideoType = TrackFilter.MUSIC_VIDEO_TYPE_UGC),
        )
        val lucasChannel = SearchResult.Browse(
            BrowseItem("UC_lucas", "Lucas Mendoza", "Perfil • @lucasmendoza", null, BrowseType.ARTIST, TrackFilter.PAGE_TYPE_USER_CHANNEL),
        )
        val outAll = TrackFilter.prioritizeAndFilterSearchResults(
            listOf(normalUser, lucasChannel, indieTrack),
            "Lucas Mendoza",
            SearchFilter.ALL,
        )
        assertEquals(listOf(lucasChannel, indieTrack), outAll)
    }

    @Test
    fun `InnertubeParser preserves pageType on BrowseItem for artist vs user channel filtering`() {
        val json = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicShelfRenderer": {
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "navigationEndpoint": {
                                  "browseEndpoint": {
                                    "browseId": "UC_official",
                                    "browseEndpointContextSupportedConfigs": {
                                      "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" }
                                    }
                                  }
                                },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Duki" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artista • 20 M de oyentes mensuales" }] } } }
                                ]
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "navigationEndpoint": {
                                  "browseEndpoint": {
                                    "browseId": "UC_random_user",
                                    "browseEndpointContextSupportedConfigs": {
                                      "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_USER_CHANNEL" }
                                    }
                                  }
                                },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Duki2026" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Perfil • @duki2026" }] } } }
                                ]
                              }
                            }
                          ]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val parsed = InnertubeParser.parseSearchPage(Json.parseToJsonElement(json).jsonObject)
        assertEquals(2, parsed.rows.size)
        val filtered = TrackFilter.prioritizeAndFilterSearchResults(parsed.rows, "Duki", SearchFilter.ARTISTS)
        assertEquals(1, filtered.size)
        assertEquals("UC_official", (filtered.single() as SearchResult.Browse).item.browseId)
    }
}
