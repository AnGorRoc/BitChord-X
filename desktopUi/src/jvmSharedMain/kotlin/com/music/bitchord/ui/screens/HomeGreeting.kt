package com.music.bitchord.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.HomeGreeting
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDateTime

/** The greeting's size as the PC Home's heading: the large titles' face. */
private val GREETING_SIZE = 30.sp

/**
 * [HomeGreeting]'s line as the PC Home's heading. The PC's Home has no top bar
 * with a profile to centre it in, as the phone's has, so it leads the page.
 * A new one is drawn each time Home is opened; [name] is used by its first
 * word only.
 */
@Composable
internal fun HomeGreetingLine(name: String?, modifier: Modifier = Modifier) {
    @Suppress("NAME_SHADOWING")
    val name = name?.trim()?.substringBefore(' ')?.takeIf(String::isNotBlank)
    // The last play's artist and time, read off the computer's own history.
    // Nothing is drawn until it has been read, so the line appears once.
    val last by produceState<Pair<String?, Long?>?>(null) {
        value = runCatching { ListeningStats.recent(1).firstOrNull() }.getOrNull()
            ?.let { (song, at) -> HomeGreeting.leadArtist(song.artist) to at }
            ?: (null to null)
    }
    val read = last ?: return
    val shown = remember(read, name) {
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
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = GREETING_SIZE,
            lineHeight = GREETING_SIZE * 1.2f,
            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
        ),
        color = MaterialTheme.colorScheme.onBackground,
        // A long one wraps rather than being cut.
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
