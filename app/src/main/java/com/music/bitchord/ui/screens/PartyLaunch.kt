package com.music.bitchord.ui.screens

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the person asked for from the Connect sheet, left for the Listen Together screen to pick up
 * as it opens: "Start a party" and "Join" should land on the right sheet, not on a page of choices.
 */
object PartyLaunch {
    enum class Action { START, JOIN }

    /** Set by the sheet, taken (and cleared) by the screen. */
    val pending = MutableStateFlow<Action?>(null)
}
