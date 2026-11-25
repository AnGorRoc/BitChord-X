package com.music.bitchord.data.spotify

import com.music.bitchord.data.MonotonicClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Spaces out the searches a bulk import sends to YouTube Music, however many
 * lookups run at once.
 *
 * An import of a whole library is hundreds of searches, and YouTube answers a
 * burst of them by refusing — or by going quiet for everyone on the device,
 * playback included. So every search waits its turn behind a gap ([baseGapMs]
 * with jitter, so many devices don't fall into step). A failure doubles the
 * gap; one that reads as YouTube pushing back (a 429, a timeout) also holds
 * every search for a cool-down that grows while the refusals go on. A run of
 * clean answers earns the pace back a little at a time.
 *
 * After [giveUpAfter] failures in a row [exhausted] turns true: YouTube has
 * stopped answering, and the import should stop rather than keep asking.
 */
class RequestPacer(
    private val baseGapMs: Long = BASE_GAP_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val cooldownsMs: List<Long> = COOLDOWNS_MS,
    private val giveUpAfter: Int = GIVE_UP_AFTER,
    private val now: () -> Long = { MonotonicClock.nowMs() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { (it * Random.nextDouble(0.8, 1.25)).toLong() },
) {
    private val turn = Mutex()
    private val lock = Any()

    private var gapMs = baseGapMs
    private var nextAt = 0L
    private var cleanStreak = 0
    private var failuresInARow = 0
    private var pushBacksInARow = 0

    /** True once YouTube has failed [giveUpAfter] times in a row. */
    val exhausted: Boolean get() = synchronized(lock) { failuresInARow >= giveUpAfter }

    /** The gap between two searches right now, for tests and logs. */
    val currentGapMs: Long get() = synchronized(lock) { gapMs }

    /**
     * Waits for this request's turn, runs it and learns from how it went.
     * A failed [Result] is returned as it came; cancellation is thrown.
     */
    suspend fun <T> run(request: suspend () -> Result<T>): Result<T> {
        turn.withLock {
            val wait = synchronized(lock) { nextAt } - now()
            if (wait > 0) sleep(wait)
            synchronized(lock) { nextAt = now() + jitter(gapMs) }
        }
        val result = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (result.isSuccess) onSuccess() else onFailure(result.exceptionOrNull()!!)
        return result
    }

    private fun onSuccess() = synchronized(lock) {
        failuresInARow = 0
        pushBacksInARow = 0
        if (++cleanStreak >= CLEAN_BEFORE_EASING) {
            cleanStreak = 0
            gapMs = maxOf(baseGapMs, gapMs * 3 / 4)
        }
    }

    private fun onFailure(e: Throwable) = synchronized(lock) {
        cleanStreak = 0
        failuresInARow++
        gapMs = minOf(maxGapMs, gapMs * 2)
        // The next turn was booked at the old pace; it waits the new one.
        nextAt = maxOf(nextAt, now() + gapMs)
        val kind = exportErrorKind(e)
        if (kind == ExportErrorKind.RATE_LIMITED || kind == ExportErrorKind.NETWORK) {
            val cooldown = cooldownsMs[minOf(pushBacksInARow, cooldownsMs.lastIndex)]
            pushBacksInARow++
            nextAt = maxOf(nextAt, now() + cooldown)
        }
    }

    companion object {
        /** Under two searches a second at full pace. */
        const val BASE_GAP_MS = 650L
        const val MAX_GAP_MS = 8_000L
        val COOLDOWNS_MS = listOf(10_000L, 30_000L, 60_000L)
        const val CLEAN_BEFORE_EASING = 15
        const val GIVE_UP_AFTER = 8
    }
}
