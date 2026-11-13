package com.music.bitchord.playback.cast

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.account.DeviceNotice

/** What the person is told about handing the sound around, in the language the app is set to. */
internal fun DeviceNotice.text(context: Context): String = when (this) {
    DeviceNotice.Unavailable -> context.getString(R.string.device_notice_unavailable)
    DeviceNotice.PartyOnThisDevice -> context.getString(R.string.device_notice_party_here)
    is DeviceNotice.TargetInParty -> context.getString(R.string.device_notice_target_in_party, name)
    is DeviceNotice.TargetIsRemote ->
        if (name != null) context.getString(R.string.device_notice_target_remote, name)
        else context.getString(R.string.device_notice_target_remote_generic)
    DeviceNotice.LocalFile -> context.getString(R.string.device_notice_local_file)
    DeviceNotice.NoAnswer -> context.getString(R.string.device_notice_no_answer)
    DeviceNotice.NoStart -> context.getString(R.string.device_notice_no_start)
    is DeviceNotice.Disconnected ->
        if (name != null) context.getString(R.string.device_notice_disconnected, name)
        else context.getString(R.string.device_notice_disconnected_generic)
    DeviceNotice.TooManySongs -> context.getString(R.string.device_notice_too_many)
    DeviceNotice.NotLinked -> context.getString(R.string.device_notice_not_linked)
    // A refusal this build has no wording for: the server's own words beat saying nothing.
    is DeviceNotice.Refused -> message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.device_notice_refused)
}
