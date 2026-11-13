package com.music.bitchord.data.listentogether

/**
 * One person in a party, with all the devices they are using it from.
 *
 * A party counts and shows people. Devices of one account arrive as separate members (each holds
 * its own socket) but share a [PartyMember.personId], and are grouped back into one here.
 */
data class PartyPerson(
    val id: String,
    /** The device that joined first: the one whose name and picture stand for the person. */
    val lead: PartyMember,
    val devices: List<PartyMember>,
) {
    val isHost: Boolean get() = devices.any { it.isHost }
    val connected: Boolean get() = devices.any { it.connected }

    /** Whether any of their devices makes sound; false only when all of them are following silently. */
    val playsAudio: Boolean get() = devices.any { it.playsAudio }

    fun includes(memberId: String?): Boolean = memberId != null && devices.any { it.memberId == memberId }

    /** The member as one row should draw them: the lead, with what is true of the person. */
    val asMember: PartyMember get() = lead.copy(isHost = isHost, connected = connected, playsAudio = playsAudio)
}

/** The party's members grouped into people, in the order they joined. */
fun List<PartyMember>.byPerson(): List<PartyPerson> =
    groupBy { it.person }
        .map { (id, devices) ->
            val ordered = devices.sortedBy { it.joinedAtMs }
            PartyPerson(id, ordered.first(), ordered)
        }
        .sortedBy { it.lead.joinedAtMs }
