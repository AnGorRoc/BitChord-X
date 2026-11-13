package com.music.bitchord.data.listentogether.account

/** What picking another device does. */
enum class Handoff {
    /** Leave the target playing what it plays; this device becomes its remote. */
    CONTROL_ONLY,

    /** Send this device's song, position and queue to the target, which starts playing it. */
    SEND_MY_MUSIC,

    /** Bring what the target is playing here, and leave the target quiet. */
    PULL_THEIR_MUSIC,

    /** This device is in a party and the target is not: it joins it as the same person and sounds. */
    SEND_PARTY,

    /** Both are in the same party: the sound moves to the target, which was following. */
    MOVE_PARTY_SOUND,
}

/** A choice the person made explicitly, from a device's menu. */
enum class HandoffForce { SEND, PULL }

/**
 * The rules of handing the sound around, with no network and no player in them.
 *
 * One principle: **whoever already has music keeps it.** Picking a device that has a song, playing or
 * paused, never replaces it with what this device had; only a device with nothing is handed music. Both directions can still be forced from the device's menu.
 */
object HandoffRules {

    /**
     * What to do when [target] is picked.
     *
     * @param localHasMusic whether this device has a song (playing or paused, with its queue) to
     *   hand over.
     */
    fun onPick(
        target: AccountDevice,
        localHasMusic: Boolean,
        force: HandoffForce? = null,
        /** The party this device is in, if any. */
        myPartyCode: String? = null,
    ): Handoff = when {
        // A party owns its player, so a device in one is never handed a queue or made to give one up:
        // what moves is the party itself. Picking a device that is in a party only controls it.
        target.partyCode != null && myPartyCode != null -> Handoff.MOVE_PARTY_SOUND
        target.partyCode != null -> Handoff.CONTROL_ONLY
        myPartyCode != null -> Handoff.SEND_PARTY
        force == HandoffForce.SEND -> Handoff.SEND_MY_MUSIC
        force == HandoffForce.PULL -> Handoff.PULL_THEIR_MUSIC
        // What a device has queued is its own even while paused: it is controlled, never overwritten.
        target.hasMusic -> Handoff.CONTROL_ONLY
        localHasMusic -> Handoff.SEND_MY_MUSIC
        else -> Handoff.CONTROL_ONLY
    }

    /**
     * Whether this device should stop its own sound now. It waits until the target confirms it has
     * taken what was handed over, so a hand-over that fails never leaves anybody listening to
     * silence.
     *
     * What counts as taking it depends on what was asked. A song handed over to play is confirmed
     * by the target *playing* it; one handed over paused ([handedPlaying] false) is confirmed by the
     * target *showing* it, since a target that is told to hold still never reports playing and
     * waiting for that was a failure after twenty seconds every time. With nothing handed over
     * ([handedVideoId] null) the person only asked to control a device, and the link is all that
     * was wanted: a device that is idle or paused is just as good a thing to control.
     */
    fun shouldSilenceLocal(target: AccountDevice?, handedVideoId: String?, handedPlaying: Boolean = true): Boolean {
        if (target == null || !target.connected) return false
        if (handedVideoId == null) return true
        val np = target.nowPlaying ?: return false
        if (np.videoId != handedVideoId) return false
        return np.isPlaying || !handedPlaying
    }

    /**
     * Whether a remote still has a reason to keep silencing its own player: the device it controls
     * is there. If that device went away, the person gets their own sound back.
     */
    fun controlStillValid(state: AccountState): Boolean = state.controlled?.connected == true

    /**
     * Why picking [target] cannot be done right now, or null when it can. The one place this is
     * decided: the Connect sheet asks it so it can stay open on a refusal instead of closing on the
     * person's tap with nothing but a passing message, and both players ask it before they start
     * anything.
     *
     * @param myPartyCode the party this device is in, if any.
     */
    fun blockedBy(target: AccountDevice?, myPartyCode: String?): DeviceNotice? = when {
        target == null || !target.connected -> DeviceNotice.Unavailable
        target.noControl -> DeviceNotice.TargetNotAllowed(target.name)
        target.unreachable -> DeviceNotice.TargetNotReachable(target.name)
        // A remote is not a speaker: what it plays comes from the device it controls.
        target.controlling != null -> DeviceNotice.TargetIsRemote(target.name)
        // Another party than the one this device is in cannot be moved onto or controlled from here.
        // The same party can (the sound moves between the person's own devices), and so can a
        // party picked from a device that is in none (it is controlled).
        myPartyCode != null && target.partyCode != null && !target.partyCode.equals(myPartyCode, ignoreCase = true) ->
            DeviceNotice.TargetInParty(target.name)
        else -> null
    }
}
