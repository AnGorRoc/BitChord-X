package com.music.bitchord.data.offline

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineOrderTest {
    private fun song(id: String, localUri: String? = null) = Song(id, id.uppercase(), "Artist", null, localUri = localUri)

    private val saved = setOf("b", "d")
    private val isPlayable = { s: Song -> s.videoId in saved }
    private val list = listOf(song("a"), song("b"), song("c"), song("d"), song("e"))

    @Test
    fun playableSongsComeFirstAndEachGroupKeepsItsOrder() {
        assertEquals(listOf("b", "d", "a", "c", "e"), list.offlineFirst(isPlayable).map { it.videoId })
    }

    @Test
    fun aListWithNothingToReorderIsReturnedAsItIs() {
        assertSame(list, list.offlineFirst { true })
        assertSame(list, list.offlineFirst { false })
        val ordered = listOf(song("b"), song("d"), song("a"))
        assertEquals(ordered.map { it.videoId }, ordered.offlineFirst(isPlayable).map { it.videoId })
    }

    @Test
    fun theOriginalListIsNeverChanged() {
        val before = list.map { it.videoId }
        list.offlineFirst(isPlayable)
        assertEquals(before, list.map { it.videoId })
    }

    @Test
    fun tappingAPlayableSongQueuesOnlyPlayableOnesAndFindsItsPlace() {
        val queue = list.offlineQueue(3, isPlayable)!!
        assertEquals(listOf("b", "d"), queue.songs.map { it.videoId })
        assertEquals(1, queue.index)
    }

    @Test
    fun tappingASongThatCannotBePlayedDoesNothing() {
        assertNull(list.offlineQueue(0, isPlayable))
        assertNull(list.offlineQueue(9, isPlayable))
    }

    @Test
    fun theSameTrackTwiceKeepsTheTappedCopy() {
        val twice = listOf(song("b"), song("x"), song("b"))
        val queue = twice.offlineQueue(2, isPlayable)!!
        assertEquals(1, queue.index)
        assertSame(twice[2], queue.songs[1])
    }

    @Test
    fun aDownloadAndItsCachedCopyAreOneEntryAndDownloadsLead() {
        val merged = mergeOffline(listOf(song("b"), song("d")), listOf(song("z"), song("b")))
        assertEquals(listOf("b", "d", "z"), merged.map { it.videoId })
    }

    @Test
    fun onlyAFileOnThePhoneIsOnDevice() {
        assertTrue(isOnDeviceUri("content://media/1"))
        assertTrue(isOnDeviceUri("file:///music/a.mp3"))
        assertTrue(isOnDeviceUri("/storage/emulated/0/a.mp3"))
        assertFalse(isOnDeviceUri("https://server/a.mp3"))
        assertFalse(isOnDeviceUri(null))
    }
}
