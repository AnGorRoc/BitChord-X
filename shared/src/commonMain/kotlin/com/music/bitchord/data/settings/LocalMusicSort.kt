package com.music.bitchord.data.settings

/** Stable persisted ordering for each on-device music library. */
enum class LocalMusicSort {
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED,
    DATE_MODIFIED,
}

/**
 * Ordering for the track list on an album or playlist page.
 */
enum class SongSort {
    DEFAULT,

    /**
     * By the file's own track-number tag — only offered on a page whose rows
     * carry one, which in practice means an album off the device's files.
     */
    TRACK_NUMBER,
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED_ASC,
    DATE_ADDED_DESC,
}
