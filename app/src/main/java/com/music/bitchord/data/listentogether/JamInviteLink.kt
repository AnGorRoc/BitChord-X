package com.music.bitchord.data.listentogether

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Relays a BitChord web or scheme invite from [com.music.bitchord.MainActivity] to Compose.
 *
 * Reading and writing links is [PartyInvites]'s job, shared with the PC; this object only carries
 * one from an [Intent] to the screen that acts on it.
 */
object JamInviteLink {

    const val ORIGIN = PartyServers.ORIGINAL_WEB

    private const val EXTRA_CONSUMED = "bitchord.jamInviteConsumed"

    private val _pending = MutableStateFlow<ParsedJamInvite?>(null)
    val pending: StateFlow<ParsedJamInvite?> = _pending.asStateFlow()

    /** Reads a web invite from a cold launch or a new intent on the existing task. */
    fun consume(intent: Intent?): Boolean {
        if (
            intent == null ||
            intent.action != Intent.ACTION_VIEW ||
            intent.getBooleanExtra(EXTRA_CONSUMED, false)
        ) return false

        val invite = parseInvite(intent.dataString) ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = invite
        return true
    }

    fun handled() {
        _pending.value = null
    }

    /** The normalized party code of any invite link. */
    fun parse(value: String?): String? = parseInvite(value)?.code

    /** Any invite link: its party and the server it lives on. See [PartyInvites.parse]. */
    fun parseInvite(value: String?): ParsedJamInvite? = PartyInvites.parse(value)

    /** The link to share for a party on [server]. See [PartyInvites.url]. */
    fun url(code: String, server: String? = null): String = PartyInvites.url(code, server)

    fun schemeUrl(code: String, server: String? = null): String = PartyInvites.schemeUrl(code, server)
}
