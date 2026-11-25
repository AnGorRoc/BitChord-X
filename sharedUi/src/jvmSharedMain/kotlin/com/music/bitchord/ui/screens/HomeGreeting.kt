package com.music.bitchord.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * The line under Home's wordmark: a greeting that changes every time the app is
 * come back to, worked out on the device from what it already knows — the
 * hour, the day, how long since the last play, what was playing, the
 * listener's name. Nothing leaves the device for it, and no location is read.
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
    internal var lastShown: Kind? = null
}

/** The greeting's size in the top bar: the large titles' face, scaled to fit beside the logo. */
private val GREETING_SIZE = 22.sp

/**
 * [HomeGreeting]'s line, centred in Home's top bar between the logo and the
 * profile, in the section headers' colour so it reads as part of the page,
 * not as a banner. [name] is used by its first word only: "Good morning,
 * Pedro", not the account's full name.
 */
@Composable
fun HomeGreetingLine(name: String?, modifier: Modifier = Modifier) {
    @Suppress("NAME_SHADOWING")
    val name = name?.trim()?.substringBefore(' ')?.takeIf(String::isNotBlank)
    // A new draw on every return to the app, not on every recomposition.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visit by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) visit++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // The last play's artist and time, read off the device's own history.
    // Null until read, and nothing is drawn till then, so the line appears
    // once, already knowing whether this is a welcome back.
    val last by produceState<Pair<String?, Long?>?>(null, visit) {
        value = runCatching { ListeningStats.recent(1).firstOrNull() }.getOrNull()
            ?.let { (song, at) -> song.artist.substringBefore(",").substringBefore(" & ").trim() to at }
            ?: (null to null)
    }
    val read = last ?: return
    val shown = remember(visit, read, name) {
        HomeGreeting.pick(
            now = LocalDateTime.now(),
            name = name,
            lastPlayedAtMs = read.second,
            lastArtist = read.first,
            nowMs = System.currentTimeMillis(),
            previous = HomeGreeting.lastShown,
        ).also { HomeGreeting.lastShown = it.kind }
    }
    val text = when (shown.kind) {
        HomeGreeting.Kind.MORNING -> if (shown.named) stringResource(Res.string.greeting_morning_named, name.orEmpty()) else stringResource(Res.string.greeting_morning)
        HomeGreeting.Kind.AFTERNOON -> if (shown.named) stringResource(Res.string.greeting_afternoon_named, name.orEmpty()) else stringResource(Res.string.greeting_afternoon)
        HomeGreeting.Kind.EVENING -> if (shown.named) stringResource(Res.string.greeting_evening_named, name.orEmpty()) else stringResource(Res.string.greeting_evening)
        HomeGreeting.Kind.LATE_NIGHT -> if (shown.named) stringResource(Res.string.greeting_late_night_named, name.orEmpty()) else stringResource(Res.string.greeting_late_night)
        HomeGreeting.Kind.FRIDAY -> stringResource(Res.string.greeting_friday)
        HomeGreeting.Kind.SUNDAY -> stringResource(Res.string.greeting_sunday)
        HomeGreeting.Kind.WELCOME_BACK -> if (shown.named) stringResource(Res.string.greeting_welcome_back_named, name.orEmpty()) else stringResource(Res.string.greeting_welcome_back)
        HomeGreeting.Kind.CONTINUE_ARTIST -> stringResource(Res.string.greeting_continue_artist, read.first.orEmpty())
        HomeGreeting.Kind.HELLO -> stringResource(Res.string.greeting_hello_named, name.orEmpty())
    }
    Text(
        text = text,
        // The face of Explore's and Library's large titles, at a size the
        // top bar holds.
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = GREETING_SIZE,
            lineHeight = GREETING_SIZE * 1.2f,
            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
        ),
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
