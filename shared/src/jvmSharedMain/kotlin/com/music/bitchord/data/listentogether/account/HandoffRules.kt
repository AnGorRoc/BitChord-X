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
     * Whether this device should stop its own sound now. It waits until the target confirms it is
     * playing the song that was handed over, so a hand-over that fails never leaves anybody
     * listening to silence.
     */
    fun shouldSilenceLocal(target: AccountDevice?, handedVideoId: String?): Boolean {
        val np = target?.nowPlaying ?: return false
        if (!target.connected || !np.isPlaying) return false
        return handedVideoId == null || np.videoId == handedVideoId
    }

    /**
     * Whether a remote still has a reason to keep silencing its own player: the device it controls
     * is there. If that device went away, the person gets their own sound back.
     */
    fun controlStillValid(state: AccountState): Boolean = state.controlled?.connected == true
}
