package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricLine

/** Keep every unfinished vocal visible, including overlaps spanning more than two rows. */
internal fun activeLyricRows(lines: List<LyricLine>, positionMs: Long): List<Int> {
    if (lines.isEmpty()) return emptyList()
    var low = 0
    var high = lines.size - 1
    var latest = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            latest = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    if (latest < 0) return emptyList()

    val result = ArrayList<Int>(4)
    for (index in 0..latest) {
        val line = lines[index]
        if (index == latest || (!line.isGap &&
            (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
            line.timeMs <= positionMs && positionMs < line.endMs)
        ) {
            result.add(index)
        }
    }
    return result
}
