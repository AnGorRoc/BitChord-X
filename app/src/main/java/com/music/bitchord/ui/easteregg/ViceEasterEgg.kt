package com.music.bitchord.ui.easteregg

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.AppIcon
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics

/**
 * The Vice easter egg: neon colours over the app and the sunset icon on the
 * home screen, switched on and off together.
 */
object ViceEasterEgg {
    /** Puts on the colours and the icon, remembering the icon it replaces. */
    fun turnOn(context: Context) {
        AppSettings.unlockVice()
        val current = AppIcon.current(context)
        if (current != AppIcon.Vice) {
            AppSettings.setVicePreviousIcon(current.name)
            AppIcon.apply(context, AppIcon.Vice)
        }
        AppSettings.setViceTheme(true)
    }

    /** Takes both off again: the icon goes back to the one Vice replaced. */
    fun turnOff(context: Context) {
        AppSettings.setViceTheme(false)
        if (AppIcon.current(context) == AppIcon.Vice) {
            val previous = AppIcon.entries.firstOrNull { it.name == AppSettings.vicePreviousIcon() }
                ?.takeIf { it != AppIcon.Vice }
                ?: AppIcon.Default
            AppIcon.apply(context, previous)
        }
    }
}

/**
 * Ten quick taps on the BitChord logo at the top of Home find the easter
 * egg, and the find switches it straight on. Found once, it stays found: the
 * switch in Appearance turns it off and on from then on.
 */
@Composable
fun Modifier.viceEasterEgg(): Modifier {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var taps by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }
    return this.pointerInput(Unit) {
        detectTapGestures {
            val now = System.currentTimeMillis()
            // Taps that drift apart start the count again: ten in a row, not ten ever.
            taps = if (now - lastTapAt < VICE_TAP_GAP_MS) taps + 1 else 1
            lastTapAt = now
            if (taps >= VICE_TAPS) {
                taps = 0
                haptics.play(Haptic.ToggleOn)
                val first = !AppSettings.viceUnlocked.value
                ViceEasterEgg.turnOn(context)
                Toast.makeText(
                    context,
                    context.getString(if (first) R.string.vice_welcome else R.string.vice_on_again),
                    Toast.LENGTH_LONG,
                ).show()
            } else if (taps >= VICE_TAPS - 4) {
                haptics.play(Haptic.Tick)
            }
        }
    }
}

private const val VICE_TAPS = 10
private const val VICE_TAP_GAP_MS = 700L
