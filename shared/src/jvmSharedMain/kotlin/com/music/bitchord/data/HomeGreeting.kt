package com.music.bitchord.data

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Home's greeting — which one to say: a line that changes every time the app
 * is come back to, worked out on the device from what it already knows — the
 * hour, the day, how long since the last play, what was playing, the
 * listener's name. Nothing leaves the device for it, and no location is read.
 *
 * Shared by the phone's and the PC's Home; each draws the line itself.
 */
object HomeGreeting {

    enum class Kind { MORNING, AFTERNOON, EVENING, LATE_NIGHT, FRIDAY, SUNDAY, WELCOME_BACK, CONTINUE_ARTIST, HELLO }

    data class Pick(val kind: Kind, val named: Boolean)

    /** Away this long, and the greeting is a welcome back. */
    const val AWAY_DAYS = 3

    /** Within this, the last artist is still "what you were listening to". */
    private const val CONTINUE_WINDOW_MS = 2L * 24 * 3_600_000

    /**
     * Which greeting to show. Every greeting that fits the moment is a
     * candidate; a long absence wins outright, and otherwise one is drawn at
     * random, never the [previous] one.
     */
    fun pick(
        now: LocalDateTime,
        name: String?,
        lastPlayedAtMs: Long?,
        lastArtist: String?,
        nowMs: Long,
        previous: Kind?,
        random: Random = Random.Default,
    ): Pick {
        val named = !name.isNullOrBlank()
        val away = lastPlayedAtMs?.let { nowMs - it >= AWAY_DAYS * 24L * 3_600_000 } == true
        if (away && previous != Kind.WELCOME_BACK) return Pick(Kind.WELCOME_BACK, named)
        val candidates = buildList {
            add(
                when (now.hour) {
                    in 0..4 -> Kind.LATE_NIGHT
                    in 5..11 -> Kind.MORNING
                    in 12..18 -> Kind.AFTERNOON
                    else -> Kind.EVENING
                },
            )
            if (now.dayOfWeek == DayOfWeek.FRIDAY && now.hour >= 6) add(Kind.FRIDAY)
            if (now.dayOfWeek == DayOfWeek.SUNDAY && now.hour >= 6) add(Kind.SUNDAY)
            if (!lastArtist.isNullOrBlank() && lastPlayedAtMs != null && nowMs - lastPlayedAtMs <= CONTINUE_WINDOW_MS) {
                add(Kind.CONTINUE_ARTIST)
            }
            if (named) add(Kind.HELLO)
        }
        val fresh = candidates.filter { it != previous }.ifEmpty { candidates }
        return Pick(fresh[random.nextInt(fresh.size)], named)
    }

    /** The last one shown in this process, so the next return draws another. */
    @Volatile
    var lastShown: Kind? = null

    /** The artist the greeting names: the lead credit only, not "A, B & C". */
    fun leadArtist(credit: String): String = credit.substringBefore(",").substringBefore(" & ").trim()
}
