package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AccountParserTest {

    @Test
    fun parseAccount_withStandardName() {
        val json = Json.parseToJsonElement(
            """
            {
              "activeAccountHeaderRenderer": {
                "accountName": { "runs": [{ "text": "Alice" }] },
                "email": { "runs": [{ "text": "alice@example.com" }] },
                "channelHandle": { "runs": [{ "text": "@alice" }] }
              }
            }
            """
        )
        val account = InnertubeParser.parseAccount(json)
        assertNotNull(account)
        assertEquals("Alice", account.name)
        assertEquals("alice@example.com", account.email)
    }

    @Test
    fun parseAccount_withBlankNameTrick_fallsBackToHandleOrEmail() {
        val invisibleNameJson = Json.parseToJsonElement(
            """
            {
              "activeAccountHeaderRenderer": {
                "accountName": { "runs": [{ "text": "\u200B\u200C\u3164  " }] },
                "email": { "runs": [{ "text": "user@example.com" }] },
                "channelHandle": { "runs": [{ "text": "@invisible_user" }] }
              }
            }
            """
        )
        val account = InnertubeParser.parseAccount(invisibleNameJson)
        assertNotNull(account)
        assertEquals("@invisible_user", account.name)
        assertEquals("user@example.com", account.email)
    }

    @Test
    fun parseAccountChannels_withBlankOrInvisibleName_retainsChannel() {
        val json = Json.parseToJsonElement(
            """
            {
              "accountItem": {
                "accountName": { "runs": [{ "text": "\u2800\uFFA0" }] },
                "channelHandle": { "runs": [{ "text": "@mystery" }] },
                "accountByline": { "runs": [{ "text": "user@gmail.com" }] },
                "pageId": "123456789"
              }
            }
            """
        )
        val channels = InnertubeParser.parseAccountChannels(json)
        assertEquals(1, channels.size)
        val channel = channels.first()
        assertEquals("@mystery", channel.name)
        assertEquals("@mystery", channel.subtitle)
        assertEquals("123456789", channel.pageId)
    }
}
