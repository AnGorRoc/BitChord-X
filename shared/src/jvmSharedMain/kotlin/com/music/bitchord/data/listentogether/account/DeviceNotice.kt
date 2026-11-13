package com.music.bitchord.data.listentogether.account

/**
 * Something the person should be told about handing the sound around, as a fact and not as a
 * sentence. Each platform puts it in the person's own language; nothing here is text, so the same
 * refusal reads the same on the phone and on the computer, in any of the app's languages.
 */
sealed interface DeviceNotice {
    /** The device they picked is gone or never was. */
    data object Unavailable : DeviceNotice

    /** This device is playing a party, which owns its player. */
    data object PartyOnThisDevice : DeviceNotice

    /** The device they picked is in a party. */
    data class TargetInParty(val name: String) : DeviceNotice

    /** The device they picked is itself another device's remote. [name] is null when not known. */
    data class TargetIsRemote(val name: String?) : DeviceNotice

    /** The song is a file that lives on this device alone. */
    data object LocalFile : DeviceNotice

    /** The other device never answered. */
    data object NoAnswer : DeviceNotice

    /** The other device was handed the song and never started it. */
    data object NoStart : DeviceNotice

    /** The device they were controlling went away. [name] is null when not known. */
    data class Disconnected(val name: String?) : DeviceNotice

    data object TooManySongs : DeviceNotice

    /** The link to that device is no longer there. */
    data object NotLinked : DeviceNotice

    /** Something the server refused that this build has no wording for; [message] is its English. */
    data class Refused(val message: String?) : DeviceNotice

    companion object {
        /** What a refusal from the account channel means, by the server's stable reason. */
        fun fromServer(reason: String?, message: String?): DeviceNotice = when (reason) {
            "not_available" -> Unavailable
            "controlling_other" -> TargetIsRemote(null)
            "too_many_songs" -> TooManySongs
            "not_linked", "self", "unknown_command" -> NotLinked
            else -> Refused(message)
        }
    }
}
