package com.music.bitchord.data

import com.music.bitchord.data.LocalCopies.Quality
import com.music.bitchord.data.LocalCopies.Streaming
import com.music.bitchord.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the listener's own files stand in for a catalogue track, and when. */
class LocalCopiesTest {

    @After
    fun tearDown() = LocalCopies.update(emptyList())

    private fun file(title: String, artist: String, duration: String, path: String = "/x/$title.flac", id: Int = 1) =
        Song(
            videoId = "content://media/external/audio/media/$id",
            title = title,
            artist = artist,
            thumbnailUrl = null,
            durationText = duration,
            localUri = "content://media/external/audio/media/$id",
            localPath = path,
        )

    private fun row(title: String, artist: String, duration: String?) =
        Song(videoId = "yt$title", title = title, artist = artist, thumbnailUrl = null, durationText = duration)

    // ---- Finding the file ----------------------------------------------------

    @Test
    fun `a search row finds the same recording on the phone`() {
        LocalCopies.update(listOf(file("Blood on the Dance Floor", "Michael Jackson", "4:13")))

        val copy = LocalCopies.find(row("Blood On The Dance Floor", "Michael Jackson", "4:12"))

        assertEquals("content://media/external/audio/media/1", copy?.uri)
    }

    @Test
    fun `a runtime too far off is another take`() {
        LocalCopies.update(listOf(file("Bad", "Michael Jackson", "4:07")))

        assertNull(LocalCopies.find(row("Bad", "Michael Jackson", "4:20")))
    }

    @Test
    fun `a remix is not the album cut`() {
        LocalCopies.update(listOf(file("Bad (Remix)", "Michael Jackson", "4:07")))

        assertNull(LocalCopies.find(row("Bad", "Michael Jackson", "4:07")))
    }

    @Test
    fun `another artist with the same title and length is not used`() {
        LocalCopies.update(listOf(file("Bad", "Someone Else", "4:07")))

        assertNull(LocalCopies.find(row("Bad", "Michael Jackson", "4:07")))
    }

    @Test
    fun `a search card without a runtime still finds the one recording`() {
        LocalCopies.update(listOf(file("Bad", "Michael Jackson", "4:07")))

        assertEquals("content://media/external/audio/media/1", LocalCopies.find(row("Bad", "Michael Jackson", null))?.uri)
    }

    @Test
    fun `without a runtime, two lengths of one title are too unsure to pick from`() {
        LocalCopies.update(
            listOf(
                file("Bad", "Michael Jackson", "4:07", id = 1),
                file("Bad", "Michael Jackson", "8:21", path = "/x/Bad (extended).flac", id = 2),
            ),
        )

        assertNull(LocalCopies.find(row("Bad", "Michael Jackson", null)))
    }

    @Test
    fun `without a runtime, a remix is still not the album cut`() {
        LocalCopies.update(listOf(file("Bad (Remix)", "Michael Jackson", "4:07")))

        assertNull(LocalCopies.find(row("Bad", "Michael Jackson", null)))
    }

    @Test
    fun `a row that is itself a local file plays itself`() {
        val own = file("Bad", "Michael Jackson", "4:07")
        LocalCopies.update(listOf(own))

        assertNull(LocalCopies.find(own))
    }

    // ---- The desktop's scan, which reads no runtime ---------------------------

    private fun desktopFile(title: String, artist: String, path: String = "C:/Music/$title.flac") = Song(
        videoId = "local:$path",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        localUri = "file:///$path",
        localPath = path,
    )

    @Test
    fun `a desktop file with no runtime is found by title and artist`() {
        LocalCopies.update(listOf(desktopFile("Blood on the Dance Floor", "Michael Jackson")))

        assertEquals("file:///C:/Music/Blood on the Dance Floor.flac", LocalCopies.find(row("Blood on the Dance Floor", "Michael Jackson", "4:13"))?.uri)
    }

    @Test
    fun `two desktop files of one title and no runtime are too unsure to pick from`() {
        LocalCopies.update(
            listOf(
                desktopFile("Bad", "Michael Jackson", "C:/Music/Bad.flac"),
                desktopFile("Bad", "Michael Jackson", "C:/Music/Bad (album).flac"),
            ),
        )

        assertNull(LocalCopies.find(row("Bad", "Michael Jackson", "4:07")))
    }

    @Test
    fun `a desktop file of another take is still refused by its title`() {
        LocalCopies.update(listOf(desktopFile("Bad (Live)", "Michael Jackson")))

        assertNull(LocalCopies.find(row("Bad", "Michael Jackson", "4:07")))
    }

    // ---- What the file is --------------------------------------------------

    @Test
    fun `lossless containers read as lossless`() {
        assertEquals(Quality.LOSSLESS, LocalCopies.qualityOf(LocalCopies.formatOf("/a.flac", 30_000_000, 240_000)))
        assertEquals(Quality.LOSSLESS, LocalCopies.qualityOf(LocalCopies.formatOf("/a.wav", 40_000_000, 240_000)))
        assertEquals(Quality.LOSSLESS, LocalCopies.qualityOf(LocalCopies.formatOf("/a.dsf", 100_000_000, 240_000)))
    }

    @Test
    fun `an m4a is ALAC or AAC by how rich it runs`() {
        // 30 MB over 4 minutes is 1000 kbps: ALAC. 7.5 MB is 250 kbps: AAC.
        assertEquals("alac", LocalCopies.formatOf("/a.m4a", 30_000_000, 240_000).codec)
        assertEquals("aac", LocalCopies.formatOf("/a.m4a", 7_500_000, 240_000).codec)
    }

    @Test
    fun `a lossy file is high, good or low by its bitrate`() {
        // 320 kbps over 4 minutes is 9.6 MB; 256 kbps is 7.68 MB; 128 kbps is 3.84 MB.
        assertEquals(Quality.HIGH_LOSSY, LocalCopies.qualityOf(LocalCopies.formatOf("/a.mp3", 9_600_000, 240_000)))
        assertEquals(Quality.GOOD_LOSSY, LocalCopies.qualityOf(LocalCopies.formatOf("/a.ogg", 7_680_000, 240_000)))
        assertEquals(Quality.LOW, LocalCopies.qualityOf(LocalCopies.formatOf("/a.mp3", 3_840_000, 240_000)))
    }

    @Test
    fun `a download's label says what it is`() {
        assertEquals(Quality.LOSSLESS, LocalCopies.qualityOfDownload("FLAC", null, null))
        assertEquals(Quality.LOSSLESS, LocalCopies.qualityOfDownload("DOLBY", null, null))
        assertEquals(Quality.LOW, LocalCopies.qualityOfDownload("OPUS · 160 kbps", null, null))
        assertEquals(Quality.GOOD_LOSSY, LocalCopies.qualityOfDownload("AAC · 256 kbps", null, null))
        assertEquals(Quality.HIGH_LOSSY, LocalCopies.qualityOfDownload("MP3 · 320 kbps", null, null))
    }

    // ---- Which copy plays ---------------------------------------------------

    @Test
    fun `a lossless file plays even with a lossless stream on offer`() {
        assertTrue(LocalCopies.shouldPlay(Quality.LOSSLESS, online = true) { Streaming.LOSSLESS })
    }

    @Test
    fun `a 320 kbps file beats YouTube and matches JioSaavn, not a lossless stream`() {
        assertTrue(LocalCopies.shouldPlay(Quality.HIGH_LOSSY, online = true) { Streaming.STANDARD })
        assertTrue(LocalCopies.shouldPlay(Quality.HIGH_LOSSY, online = true) { Streaming.HIGH })
        assertFalse(LocalCopies.shouldPlay(Quality.HIGH_LOSSY, online = true) { Streaming.LOSSLESS })
    }

    @Test
    fun `a 256 kbps file beats YouTube only`() {
        assertTrue(LocalCopies.shouldPlay(Quality.GOOD_LOSSY, online = true) { Streaming.STANDARD })
        assertFalse(LocalCopies.shouldPlay(Quality.GOOD_LOSSY, online = true) { Streaming.HIGH })
    }

    @Test
    fun `a poor file is only for offline`() {
        assertFalse(LocalCopies.shouldPlay(Quality.LOW, online = true) { Streaming.STANDARD })
        assertTrue(LocalCopies.shouldPlay(Quality.LOW, online = false) { Streaming.LOSSLESS })
    }
}
