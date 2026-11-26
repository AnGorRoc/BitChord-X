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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.HomeGreeting
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDateTime

/** The greeting's size in the top bar: the large titles' face, scaled to fit beside the logo. */
private val GREETING_SIZE = 22.sp

/**
 * [HomeGreeting]'s line, centred in Home's top bar between the logo and the
 * profile, in the section headers' colour so it reads as part of the page,
 * not as a banner. [name] is used by its first word only: "Good morning,
 * Pedro", not the account's full name.
 */
@Composable
fun HomeGreetingLine(
    name: String?,
    modifier: Modifier = Modifier,
    /**
     * The now-playing tint's own text colour, when the app is tinted with the
     * record playing — the artwork's colour, already corrected to read on the
     * tinted page. Null keeps the theme's text colour.
     */
    color: androidx.compose.ui.graphics.Color? = null,
) {
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
    FittedGreeting(
        text = text,
        maxSize = GREETING_SIZE,
        minSize = GREETING_MIN_SIZE,
        maxLines = 2,
        color = color ?: MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
    )
}

/** The smallest a long greeting shrinks to before it would be cut. */
private val GREETING_MIN_SIZE = 14.sp

/**
 * [text] in the large titles' face at [maxSize], stepping down to [minSize]
 * and onto up to [maxLines] lines rather than being cut: "Continue with
 * Michael Jackson?" is meant to be read whole. Nothing is drawn while a size
 * is still being tried, so the steps never show.
 */
@Composable
internal fun FittedGreeting(
    text: String,
    maxSize: androidx.compose.ui.unit.TextUnit,
    minSize: androidx.compose.ui.unit.TextUnit,
    maxLines: Int,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    var size by remember(text, maxSize) { androidx.compose.runtime.mutableStateOf(maxSize) }
    var fitted by remember(text, maxSize) { androidx.compose.runtime.mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = size,
            lineHeight = size * 1.15f,
            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        ),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && size.value > minSize.value) {
                size = (size.value - 1f).coerceAtLeast(minSize.value).sp
            } else {
                fitted = true
            }
        },
        modifier = modifier.androidxDrawWhen { fitted },
    )
}

/** Draws only once [show] holds — used to hide the in-between sizes of [FittedGreeting]. */
private fun Modifier.androidxDrawWhen(show: () -> Boolean): Modifier =
    this.drawWithContent { if (show()) drawContent() }
