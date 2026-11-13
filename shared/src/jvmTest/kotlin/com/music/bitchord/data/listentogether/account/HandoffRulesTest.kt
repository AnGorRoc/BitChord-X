package com.music.bitchord.data.listentogether.account

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffRulesTest {

    private fun device(
        id: String = "pc",
        connected: Boolean = true,
        playing: Boolean? = null,
        videoId: String = "v1",
    ) = AccountDevice(
        deviceId = id,
        name = id,
        connected = connected,
        nowPlaying = playing?.let { AccountNowPlaying(videoId = videoId, isPlaying = it) },
    )

    // ---- the owner's rule: whoever is sounding keeps priority ------------------------------

    @Test
    fun `picking a device that is playing only controls it`() {
        assertEquals(Handoff.CONTROL_ONLY, HandoffRules.onPick(device(playing = true), localHasMusic = true))
    }

    @Test
    fun `picking a quiet device sends it what this one was playing`() {
        assertEquals(Handoff.SEND_MY_MUSIC, HandoffRules.onPick(device(playing = null), localHasMusic = true))
        assertEquals(Handoff.SEND_MY_MUSIC, HandoffRules.onPick(device(playing = false), localHasMusic = true))
    }

    @Test
    fun `picking a quiet device with nothing to send just controls it`() {
        assertEquals(Handoff.CONTROL_ONLY, HandoffRules.onPick(device(playing = null), localHasMusic = false))
    }

    @Test
    fun `the menu can force either direction whoever is playing`() {
        assertEquals(
            Handoff.SEND_MY_MUSIC,
            HandoffRules.onPick(device(playing = true), localHasMusic = true, force = HandoffForce.SEND),
        )
        assertEquals(
            Handoff.PULL_THEIR_MUSIC,
            HandoffRules.onPick(device(playing = false), localHasMusic = false, force = HandoffForce.PULL),
        )
    }

    // ---- never leave anybody in silence ----------------------------------------------------

    @Test
    fun `this device stays audible until the target confirms the song`() {
        assertFalse(HandoffRules.shouldSilenceLocal(null, "v1"))
        assertFalse(HandoffRules.shouldSilenceLocal(device(playing = null), "v1"))
        assertFalse(HandoffRules.shouldSilenceLocal(device(playing = false), "v1"))
        // Playing, but something else: the hand-over has not landed.
        assertFalse(HandoffRules.shouldSilenceLocal(device(playing = true, videoId = "other"), "v1"))
        assertTrue(HandoffRules.shouldSilenceLocal(device(playing = true, videoId = "v1"), "v1"))
    }

    @Test
    fun `controlling without handing over silences as soon as the target plays anything`() {
        assertTrue(HandoffRules.shouldSilenceLocal(device(playing = true, videoId = "whatever"), null))
    }

    @Test
    fun `a target that went away does not count`() {
        assertFalse(HandoffRules.shouldSilenceLocal(device(connected = false, playing = true), null))
    }

    @Test
    fun `a remote whose target left gets its own sound back`() {
        val state = AccountState(
            connection = AccountConnection.LIVE,
            selfId = "phone",
            devices = listOf(
                AccountDevice("phone", "Pixel", connected = true, controlling = "pc"),
                AccountDevice("pc", "IOP", connected = false),
            ),
        )
        assertFalse(HandoffRules.controlStillValid(state))
        val back = state.copy(devices = state.devices.map { if (it.deviceId == "pc") it.copy(connected = true) else it })
        assertTrue(HandoffRules.controlStillValid(back))
    }

    // ---- state helpers the Connect card relies on -----------------------------------------

    @Test
    fun `the sounding device is the one being controlled, else this one`() {
        val phone = AccountDevice("phone", "Pixel", connected = true, controlling = "pc")
        val pc = AccountDevice("pc", "IOP", connected = true, controlledBy = "phone")
        val linked = AccountState(AccountConnection.LIVE, "phone", listOf(phone, pc))
        assertEquals("pc", linked.soundingDevice?.deviceId)
        assertEquals(listOf("pc"), linked.others.map { it.deviceId })

        val free = AccountState(AccountConnection.LIVE, "phone", listOf(phone.copy(controlling = null), pc.copy(controlledBy = null)))
        assertEquals("phone", free.soundingDevice?.deviceId)
        assertNull(free.controlled)
    }

    // ---- reading the server's frames ------------------------------------------------------

    @Test
    fun `a devices frame is read, with empty links meaning none`() {
        val frame = AccountFrames.parse(
            """{"type":"devices","devices":[
                {"deviceId":"phone","name":"Pixel","type":"phone","connected":true,"lastSeenMs":5,
                 "controlledBy":"","controlling":"pc",
                 "nowPlaying":{"videoId":"v","title":"T","artist":"A","isPlaying":true,"positionMs":1000,"durationMs":9000,"anchorMs":7,"updatedAtMs":7,"extra":"ignored"}},
                {"deviceId":"pc","name":"IOP","type":"computer","connected":false}
            ]}""",
        )!!
        val list = AccountFrames.devices(frame)!!
        assertEquals(2, list.size)
        assertNull(list[0].controlledBy)
        assertEquals("pc", list[0].controlling)
        assertEquals("T", list[0].nowPlaying?.title)
        assertTrue(list[0].isPlaying)
        assertFalse(list[1].isPlaying)
    }

    @Test
    fun `frames of other kinds are not device lists`() {
        assertNull(AccountFrames.devices(AccountFrames.parse("""{"type":"pong"}""")!!))
        assertNull(AccountFrames.parse("not json"))
    }

    @Test
    fun `a broken device entry is skipped and the rest are kept`() {
        val frame = AccountFrames.parse("""{"type":"devices","devices":[{"nope":1},{"deviceId":"a","name":"A"}]}""")!!
        assertEquals(listOf("a"), AccountFrames.devices(frame)!!.map { it.deviceId })
    }

    @Test
    fun `a command frame carries who, what and its payload`() {
        val command = AccountFrames.command(
            AccountFrames.parse("""{"type":"command","from":"phone","action":"seek","payload":{"positionMs":4000},"id":"x"}""")!!,
        )!!
        assertEquals("phone", command.from)
        assertEquals(DeviceActions.SEEK, command.action)
        assertEquals(JsonPrimitive(4000), command.payload["positionMs"])
        assertEquals("x", command.id)
        assertNull(AccountFrames.command(AccountFrames.parse("""{"type":"command","action":"seek"}""")!!))
    }
}
