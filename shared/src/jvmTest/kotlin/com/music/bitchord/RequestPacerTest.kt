package com.music.bitchord

import com.music.bitchord.data.spotify.RequestPacer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RequestPacerTest {
    private var clock = 0L
    private val slept = mutableListOf<Long>()

    private fun pacer(giveUpAfter: Int = 8) = RequestPacer(
        baseGapMs = 500,
        maxGapMs = 4_000,
        cooldownsMs = listOf(10_000, 30_000),
        giveUpAfter = giveUpAfter,
        now = { clock },
        sleep = { slept += it; clock += it },
        jitter = { it },
    )

    private val ok: suspend () -> Result<Unit> = { Result.success(Unit) }
    private val timeout: suspend () -> Result<Unit> = { Result.failure(IOException("timeout")) }
    private val refused: suspend () -> Result<Unit> = { Result.failure(IllegalStateException("bad request")) }

    @Test
    fun searchesAreSpacedByTheGap() = runBlocking {
        val p = pacer()
        repeat(3) { p.run(ok) }
        assertEquals(listOf(500L, 500L), slept)
    }

    @Test
    fun youTubeNotAnsweringCoolsEverySearchDownAndWidensTheGap() = runBlocking {
        val p = pacer()
        p.run(timeout)
        assertEquals(1_000L, p.currentGapMs)
        p.run(ok)
        // The cool-down, not just the gap, before the next search.
        assertEquals(listOf(10_000L), slept)
    }

    @Test
    fun aPlainRefusalWidensTheGapWithoutACoolDown() = runBlocking {
        val p = pacer()
        p.run(refused)
        p.run(ok)
        assertEquals(listOf(1_000L), slept)
    }

    @Test
    fun giveUpAfterFailuresInARowAndRecoverOnSuccess() = runBlocking {
        val p = pacer(giveUpAfter = 3)
        repeat(2) { p.run(refused) }
        assertFalse(p.exhausted)
        p.run(refused)
        assertTrue(p.exhausted)
        p.run(ok)
        assertFalse(p.exhausted)
    }

    @Test
    fun theGapNeverPassesItsCeiling() = runBlocking {
        val p = pacer()
        repeat(6) { p.run(refused) }
        assertEquals(4_000L, p.currentGapMs)
    }
}
