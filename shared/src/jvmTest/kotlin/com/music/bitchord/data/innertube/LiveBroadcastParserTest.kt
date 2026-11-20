package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Broadcasts never reach a list of tracks, and reach Explore's live section
 * only. Shapes are the ones YouTube Music sent on 2026-11-20: `liveBadge` on a
 * watch-queue row, `liveBadgeRenderer` on a search row.
 */
class LiveBroadcastParserTest {

    private fun panel(videoId: String, live: Boolean): String = """
        { "playlistPanelVideoRenderer": {
            "videoId": "$videoId",
            "title": { "runs": [ { "text": "Title $videoId" } ] },
            "longBylineText": { "runs": [ { "text": "Artist" } ] },
            ${if (live) """"badges": [ { "liveBadge": { "label": { "runs": [ { "text": "LIVE" } ] } } } ],""" else ""}
            "lengthText": { "runs": [ { "text": "3:30" } ] }
        } }"""

    private fun searchRow(videoId: String, live: Boolean): String = """
        { "musicResponsiveListItemRenderer": {
            "playlistItemData": { "videoId": "$videoId" },
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Title $videoId" } ] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Channel" }, { "text": " • " }, { "text": "1M views" } ] } } }
            ],
            ${if (live) """"badges": [ { "liveBadgeRenderer": { "label": { "runs": [ { "text": "LIVE" } ] } } } ],""" else ""}
            "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [ { "url": "u", "width": 400, "height": 225 } ] } } }
        } }"""

    private fun list(vararg rows: String): JsonObject =
        Json.parseToJsonElement("""{ "contents": [ ${rows.joinToString(",")} ] }""").jsonObject

    @Test
    fun `a radio queue keeps its songs and drops the broadcast in it`() {
        val queue = InnertubeParser.parseWatchQueue(list(panel("song1", false), panel("live1", true), panel("song2", false)))
        assertEquals(listOf("song1", "song2"), queue.map { it.videoId })
    }

    @Test
    fun `search results drop broadcasts`() {
        val page = InnertubeParser.parseSearchPage(list(searchRow("rec1", false), searchRow("live1", true)), includeVideos = true)
        assertEquals(listOf("rec1"), page.rows.map { (it as com.music.bitchord.data.model.SearchResult.Track).song.videoId })
    }

    @Test
    fun `the live section keeps only broadcasts, marked as such`() {
        val live = InnertubeParser.parseLiveBroadcasts(list(searchRow("rec1", false), searchRow("live1", true)))
        assertEquals(listOf("live1"), live.map { it.videoId })
        assertTrue(live.single().isLive)
    }
}
