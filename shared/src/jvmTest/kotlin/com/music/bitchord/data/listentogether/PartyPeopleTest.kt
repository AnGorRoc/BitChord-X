package com.music.bitchord.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyPeopleTest {

    private fun member(
        id: String,
        person: String?,
        joined: Long,
        name: String = id,
        host: Boolean = false,
        connected: Boolean = true,
        audio: Boolean = true,
    ) = PartyMember(
        memberId = id,
        personId = person,
        displayName = name,
        isHost = host,
        connected = connected,
        playsAudio = audio,
        joinedAtMs = joined,
    )

    @Test
    fun `devices sharing a person id are one person`() {
        val people = listOf(
            member("a1", "ana", 1, "Ana", host = true),
            member("b1", "beto", 2, "Beto"),
            member("a2", "ana", 3, "Ana"),
        ).byPerson()
        assertEquals(listOf("ana", "beto"), people.map { it.id })
        assertEquals(2, people[0].devices.size)
        assertEquals("a1", people[0].lead.memberId)
    }

    @Test
    fun `an older server without person ids shows every device as a person`() {
        val people = listOf(member("a1", null, 1), member("a2", null, 2)).byPerson()
        assertEquals(2, people.size)
    }

    @Test
    fun `a person is host, connected and audible if any device is`() {
        val ana = listOf(
            member("a1", "ana", 1, host = true, connected = false, audio = false),
            member("a2", "ana", 2, host = true, connected = true, audio = true),
        ).byPerson().single()
        assertTrue(ana.isHost)
        assertTrue(ana.connected)
        assertTrue(ana.playsAudio)
        val quiet = listOf(member("a1", "ana", 1, audio = false), member("a2", "ana", 2, audio = false)).byPerson().single()
        assertFalse(quiet.playsAudio)
    }

    @Test
    fun `a person includes their own devices only`() {
        val people = listOf(member("a1", "ana", 1), member("b1", "beto", 2)).byPerson()
        assertTrue(people[0].includes("a1"))
        assertFalse(people[0].includes("b1"))
        assertFalse(people[0].includes(null))
    }

    @Test
    fun `the row draws the lead with the person's state`() {
        val ana = listOf(
            member("a1", "ana", 1, "Ana", audio = false),
            member("a2", "ana", 2, "Ana", host = true),
        ).byPerson().single().asMember
        assertEquals("a1", ana.memberId)
        assertTrue(ana.isHost)
    }

    @Test
    fun `an empty party has nobody`() {
        assertEquals(emptyList(), emptyList<PartyMember>().byPerson())
    }
}
