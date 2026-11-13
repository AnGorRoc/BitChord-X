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
    fun `picking a device with nothing sends it what this one was playing`() {
        assertEquals(Handoff.SEND_MY_MUSIC, HandoffRules.onPick(device(playing = null), localHasMusic = true))
    }

    @Test
    fun `a paused device keeps its music and is controlled, not overwritten`() {
        assertEquals(Handoff.CONTROL_ONLY, HandoffRules.onPick(device(playing = false), localHasMusic = true))
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
    fun `a paused song handed over is confirmed by the target showing it, not playing it`() {
        // Handing over a paused song never makes the target play, so waiting for that was a
        // failure after twenty seconds every time.
        assertTrue(HandoffRules.shouldSilenceLocal(device(playing = false, videoId = "v1"), "v1", handedPlaying = false))
        // But it still has to be that song, and the target still has to have something at all.
        assertFalse(HandoffRules.shouldSilenceLocal(device(playing = false, videoId = "other"), "v1", handedPlaying = false))
        assertFalse(HandoffRules.shouldSilenceLocal(device(playing = null), "v1", handedPlaying = false))
        // A song meant to play is still not confirmed by being merely shown.
        assertFalse(HandoffRules.shouldSilenceLocal(device(playing = false, videoId = "v1"), "v1", handedPlaying = true))
    }

    @Test
    fun `controlling an idle or paused device is confirmed by the link alone`() {
        assertTrue(HandoffRules.shouldSilenceLocal(device(playing = null), null))
        assertTrue(HandoffRules.shouldSilenceLocal(device(playing = false, videoId = "v1"), null))
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

    // ---- when picking a device cannot be done ---------------------------------------------

    @Test
    fun `an ordinary connected device can be picked`() {
        assertNull(HandoffRules.blockedBy(device(playing = true), myPartyCode = null))
        assertNull(HandoffRules.blockedBy(device(playing = null), myPartyCode = null))
    }

    @Test
    fun `a device that is gone or unknown cannot`() {
        assertEquals(DeviceNotice.Unavailable, HandoffRules.blockedBy(null, myPartyCode = null))
        assertEquals(DeviceNotice.Unavailable, HandoffRules.blockedBy(device(connected = false), myPartyCode = null))
    }

    @Test
    fun `a target in another party than mine, or controlling another device, is refused, naming it`() {
        val inParty = device(id = "tv").copy(partyCode = "ABC123")
        assertEquals(DeviceNotice.TargetInParty("tv"), HandoffRules.blockedBy(inParty, myPartyCode = "ZZZ999"))
        val remote = device(id = "tab").copy(controlling = "pc")
        assertEquals(DeviceNotice.TargetIsRemote("tab"), HandoffRules.blockedBy(remote, myPartyCode = null))
    }

    @Test
    fun `a device in a party can be controlled from one that is in none, and from one in the same party`() {
        val inParty = device(id = "tv", playing = true).copy(partyCode = "ABC123")
        assertNull(HandoffRules.blockedBy(inParty, myPartyCode = null))
        assertNull(HandoffRules.blockedBy(inParty, myPartyCode = "abc123"))
    }

    @Test
    fun `picking a device in a party only controls it, whatever else is asked`() {
        val inParty = device(id = "tv").copy(partyCode = "ABC123")
        assertEquals(Handoff.CONTROL_ONLY, HandoffRules.onPick(inParty, localHasMusic = true))
        assertEquals(Handoff.CONTROL_ONLY, HandoffRules.onPick(inParty, localHasMusic = true, force = HandoffForce.SEND))
    }

    @Test
    fun `from a device in a party the party moves, never a queue`() {
        val quiet = device(id = "pc")
        assertEquals(Handoff.SEND_PARTY, HandoffRules.onPick(quiet, localHasMusic = true, myPartyCode = "ABC123"))
        assertEquals(Handoff.SEND_PARTY, HandoffRules.onPick(quiet, localHasMusic = false, force = HandoffForce.PULL, myPartyCode = "ABC123"))
        val sameParty = device(id = "tab").copy(partyCode = "ABC123")
        assertEquals(Handoff.MOVE_PARTY_SOUND, HandoffRules.onPick(sameParty, localHasMusic = true, myPartyCode = "ABC123"))
    }

    @Test
    fun `a target that refuses control or has to be opened is refused, naming it`() {
        val locked = device(id = "tv").copy(noControl = true)
        assertEquals(DeviceNotice.TargetNotAllowed("tv"), HandoffRules.blockedBy(locked, myPartyCode = null))
        val asleep = device(id = "tab").copy(unreachable = true)
        assertEquals(DeviceNotice.TargetNotReachable("tab"), HandoffRules.blockedBy(asleep, myPartyCode = null))
        // Hidden is a request about the list, not about being driven.
        assertNull(HandoffRules.blockedBy(device(id = "pc").copy(hidden = true), myPartyCode = null))
    }
}
