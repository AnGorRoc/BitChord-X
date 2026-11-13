package com.music.bitchord.data.listentogether

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DeviceGroupTest {

    private val a = "0123456789abcdef01234567"
    private val b = "ffffffffffffffffffffffff"

    @Test
    fun `the token is read from the playlist title and nothing else matches`() {
        assertEquals(a, DeviceGroup.secretFrom(listOf("Road trip", DeviceGroup.titleFor(a))))
        assertNull(DeviceGroup.secretFrom(listOf("Road trip", "BitChord devices · nothex")))
        assertNull(DeviceGroup.secretFrom(emptyList()))
    }

    @Test
    fun `two devices that both created one settle on the smallest`() {
        val titles = listOf(DeviceGroup.titleFor(b), DeviceGroup.titleFor(a))
        assertEquals(a, DeviceGroup.secretFrom(titles))
        assertEquals(a, DeviceGroup.secretFrom(titles.reversed()))
    }

    @Test
    fun `the first device creates the playlist and later ones find it`() = runBlocking {
        val library = mutableListOf("Favourites")
        val first = DeviceGroup.resolveSecret({ library.toList() }, { library += it })
        assertTrue(library.any { it == DeviceGroup.titleFor(first) })
        val second = DeviceGroup.resolveSecret({ library.toList() }, { library += it })
        assertEquals(first, second)
        assertEquals(2, library.size)
    }

    @Test
    fun `a race is settled by listing again after creating`() = runBlocking {
        // Another device's playlist lands between this device's check and its own create.
        val library = mutableListOf<String>()
        var listed = 0
        val settled = DeviceGroup.resolveSecret(
            listTitles = { if (listed++ == 0) emptyList() else library.toList() },
            create = {
                library += DeviceGroup.titleFor(a)
                library += it
            },
        )
        assertEquals(minOf(a, DeviceGroup.secretFrom(library)!!), settled)
    }

    @Test
    fun `the group id is a hash that does not contain the token`() {
        val id = DeviceGroup.groupId(a)
        assertEquals(64, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
        assertTrue(a !in id)
        assertNotEquals(id, DeviceGroup.groupId(b))
        assertEquals(id, DeviceGroup.groupId(a))
    }

    @Test
    fun `a proof is good for one server and one purpose only`() {
        val account = DeviceGroup.ProofUse.ACCOUNT
        val party = DeviceGroup.ProofUse.PARTY
        val mine = DeviceGroup.proofFor(a, account, "https://party.example.com")
        assertEquals(64, mine.length)
        assertTrue(mine.all { it in "0123456789abcdef" })
        assertTrue(a !in mine)
        // Another server, another purpose and another secret each give something else, so what one
        // server learns opens nothing on another.
        assertNotEquals(mine, DeviceGroup.proofFor(a, account, "https://friend.example.net"))
        assertNotEquals(mine, DeviceGroup.proofFor(a, party, "https://party.example.com"))
        assertNotEquals(mine, DeviceGroup.proofFor(b, account, "https://party.example.com"))
        // And it is not the old key that every server used to be shown.
        @Suppress("DEPRECATION")
        assertNotEquals(DeviceGroup.groupId(a), mine)
    }

    @Test
    fun `the same server is the same however its address is written`() {
        val use = DeviceGroup.ProofUse.PARTY
        val plain = DeviceGroup.proofFor(a, use, "party.example.com")
        assertEquals(plain, DeviceGroup.proofFor(a, use, "https://Party.Example.com/"))
        assertEquals(plain, DeviceGroup.proofFor(a, use, "http://party.example.com/api?x=1"))
        assertNotEquals(plain, DeviceGroup.proofFor(a, use, "party.example.com:8443"))
    }

    @Test
    fun `isDeviceGroupPlaylist filters out account device playlists`() {
        assertTrue(DeviceGroup.isDeviceGroupPlaylist("BitChord devices · $a"))
        assertTrue(DeviceGroup.isDeviceGroupPlaylist("bitchord devices · $a"))
        assertTrue(DeviceGroup.isDeviceGroupPlaylist("BitChord devices"))
        assertTrue(DeviceGroup.isDeviceGroupPlaylist("bitchord devices"))
        assertTrue(DeviceGroup.isDeviceGroupPlaylist("  BitChord devices · $a  "))
        assertFalse(DeviceGroup.isDeviceGroupPlaylist("My Playlist"))
        assertFalse(DeviceGroup.isDeviceGroupPlaylist("BitChord Hits"))
        assertFalse(DeviceGroup.isDeviceGroupPlaylist(null))
        assertFalse(DeviceGroup.isDeviceGroupPlaylist(""))
    }
}
