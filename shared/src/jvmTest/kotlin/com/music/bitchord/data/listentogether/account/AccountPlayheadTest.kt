package com.music.bitchord.data.listentogether.account

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountPlayheadTest {
    private fun frame(playing: Boolean, position: Long, anchor: Long, serverMs: Long, duration: Long = 200_000) = """
        {"type":"devices","serverMs":$serverMs,"devices":[{"deviceId":"b","name":"B","connected":true,
        "nowPlaying":{"videoId":"v","title":"T","durationMs":$duration,"isPlaying":$playing,
        "positionMs":$position,"anchorMs":$anchor,"updatedAtMs":$anchor}}]}
    """.trimIndent()

    private fun devicesOf(text: String) = AccountFrames.devices(AccountFrames.parse(text)!!)!!

    @Test
    fun `a playing song is brought up to the moment of the frame`() {
        val np = devicesOf(frame(playing = true, position = 1_000, anchor = 10_000, serverMs = 70_000)).single().nowPlaying!!
        assertEquals(61_000, np.positionMs)
        assertEquals(70_000, np.anchorMs)
    }

    @Test
    fun `a paused song stays where it was`() {
        val np = devicesOf(frame(playing = false, position = 1_000, anchor = 10_000, serverMs = 70_000)).single().nowPlaying!!
        assertEquals(1_000, np.positionMs)
    }

    @Test
    fun `the playhead never passes the end of the song`() {
        val np = devicesOf(frame(playing = true, position = 150_000, anchor = 0 + 1, serverMs = 900_000)).single().nowPlaying!!
        assertEquals(200_000, np.positionMs)
    }

    @Test
    fun `a frame without the server clock leaves the report as sent`() {
        val text = frame(playing = true, position = 1_000, anchor = 10_000, serverMs = 70_000).replace("\"serverMs\":70000,", "")
        assertEquals(1_000, devicesOf(text).single().nowPlaying!!.positionMs)
    }

    @Test
    fun `the playhead keeps running after the frame arrived`() {
        val device = devicesOf(frame(playing = true, position = 5_000, anchor = 10_000, serverMs = 10_000))
            .single().copy(receivedAtMs = System.currentTimeMillis() - 20_000)
        val aged = device.playhead()!!.positionMs
        assertEquals(true, aged in 24_900..26_500, "was $aged")
    }
}
