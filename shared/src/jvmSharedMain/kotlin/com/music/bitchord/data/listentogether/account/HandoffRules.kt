package com.music.bitchord.data.listentogether.account

/** What picking another device does. */
enum class Handoff {
    /** Leave the target playing what it plays; this device becomes its remote. */
    CONTROL_ONLY,

    /** Send this device's song, position and queue to the target, which starts playing it. */
    SEND_MY_MUSIC,

    /** Bring what the target is playing here, and leave the target quiet. */
    PULL_THEIR_MUSIC,
}

/** A choice the person made explicitly, from a device's menu. */
enum class HandoffForce { SEND, PULL }

/**
 * The rules of handing the sound around, with no network and no player in them.
 *
 * One principle: **whoever is already sounding keeps priority.** Picking a device that is playing
 * never replaces what it plays with what this device was playing; only a device that is quiet is
 * handed music. Both directions can still be forced from the device's menu.
 */
object HandoffRules {

    /**
     * What to do when [target] is picked.
     *
     * @param localHasMusic whether this device has a song (playing or paused, with its queue) to
     *   hand over.
     */
    fun onPick(target: AccountDevice, localHasMusic: Boolean, force: HandoffForce? = null): Handoff = when {
        force == HandoffForce.SEND -> Handoff.SEND_MY_MUSIC
        force == HandoffForce.PULL -> Handoff.PULL_THEIR_MUSIC
        target.isPlaying -> Handoff.CONTROL_ONLY
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
     * @param partyOwnsPlayer this device is playing a party, which keeps its player where the
     *   party says (see PartySync); a hand-over would be undone within a second.
     */
    fun blockedBy(target: AccountDevice?, partyOwnsPlayer: Boolean): DeviceNotice? = when {
        target == null || !target.connected -> DeviceNotice.Unavailable
        partyOwnsPlayer -> DeviceNotice.PartyOnThisDevice
        target.partyCode != null -> DeviceNotice.TargetInParty(target.name)
        // A remote is not a speaker: what it plays comes from the device it controls.
        target.controlling != null -> DeviceNotice.TargetIsRemote(target.name)
        else -> null
    }
}
