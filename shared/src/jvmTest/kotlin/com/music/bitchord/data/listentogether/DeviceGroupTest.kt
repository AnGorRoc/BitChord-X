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
