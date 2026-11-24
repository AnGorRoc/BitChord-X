package com.music.bitchord.data.stats

import android.util.Log

/**
 * Sends the stats stores' warnings to Logcat instead of stderr. Call it before
 * `ListeningStats.init` / `ArtistFacts.init`, which are the shared members that
 * take the Android context; these cannot be extension overloads of them, since
 * a member always wins over an extension of the same signature.
 */
fun routeStatsWarningsToLogcat() {
    ListeningStats.logWarning = { msg, tr -> Log.w("ListeningStats", msg, tr) }
    ArtistFacts.logWarning = { msg, tr -> Log.w("ArtistFacts", msg, tr) }
}
