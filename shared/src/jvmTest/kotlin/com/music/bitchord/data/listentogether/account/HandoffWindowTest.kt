package com.music.bitchord.data.listentogether.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffWindowTest {
    private fun ids(n: Int) = (0 until n).map { "v$it" }

    @Test
    fun `a short queue goes whole with the current song in its place`() {
        val s = HandoffWindow.slice(ids(10), 4)!!
        assertEquals((0 until 10).toList(), s.indices)
        assertEquals(4, s.index)
        assertFalse(s.truncated)
    }

    @Test
    fun `songs already played go along, a limited number of them`() {
        val s = HandoffWindow.slice(ids(100), 60)!!
        assertEquals(40, s.indices.first())
        assertEquals(HandoffWindow.HISTORY, s.index)
    }

    @Test
    fun `a long queue is bounded after the current song, and says so`() {
        val s = HandoffWindow.slice(ids(1000), 0)!!
        assertEquals(1 + HandoffWindow.UPCOMING, s.indices.size)
        assertTrue(s.truncated)
        assertTrue(s.indices.size <= 500)
    }

    @Test
    fun `files of this device stay behind and the index follows`() {
        val queue = listOf("v0", "content://a", "v2", "file://b", "v4")
        val s = HandoffWindow.slice(queue, 4, HandoffWindow::isDeviceFile.let { f -> { id -> !f(id) } })!!
        assertEquals(listOf(0, 2, 4), s.indices)
        assertEquals(2, s.index)
    }

    @Test
    fun `a current song that cannot be handed over means nothing is`() {
        assertNull(HandoffWindow.slice(listOf("content://a", "v1"), 0) { !HandoffWindow.isDeviceFile(it) })
        assertNull(HandoffWindow.slice(emptyList(), 0))
        assertNull(HandoffWindow.slice(ids(3), -1))
    }
}
