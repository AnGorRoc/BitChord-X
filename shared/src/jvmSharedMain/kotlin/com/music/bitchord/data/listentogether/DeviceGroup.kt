package com.music.bitchord.data.listentogether

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.PlaylistPrivacy
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * How one person's devices prove they are the same person, without the server ever being able to
 * check a Google account.
 *
 * The proof is a secret that only the account can show: a private playlist in the person's own
 * YouTube Music library whose title carries a random token. Any device signed in to the account can
 * read it, nobody else can, and it needs no server, no pairing step and no new permission. The
 * server only ever sees a hash of it ([groupId]), used as a bearer key to a private room; the token
 * itself never leaves the device.
 *
 * Two devices that set it up at the same moment can each create one. They converge by both using
 * the smallest token listed, so whichever playlist is left over is simply unused.
 */
object DeviceGroup {

    const val TITLE_PREFIX = "BitChord devices · "
    private val TOKEN = Regex("^[0-9a-f]{24}$")

    fun newSecret(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun titleFor(secret: String): String = TITLE_PREFIX + secret

    /** The token the library already holds, or null. The smallest if there are several. */
    fun secretFrom(titles: List<String>): String? = titles.asSequence()
        .map(String::trim)
        .filter { it.startsWith(TITLE_PREFIX) }
        .map { it.removePrefix(TITLE_PREFIX).trim() }
        .filter { TOKEN.matches(it) }
        .minOrNull()

    /** The key the server knows this group by: a hash, so the token itself stays on the devices. */
    fun groupId(secret: String): String = MessageDigest.getInstance("SHA-256")
        .digest("bitchord-devices-v1:$secret".toByteArray())
        .joinToString("") { "%02x".format(it) }

    /**
     * The group's token, made if this is the first device to ask. Listing again after creating is
     * what settles a race with another device doing the same.
     */
    suspend fun resolveSecret(
        listTitles: suspend () -> List<String>,
        create: suspend (title: String) -> Unit,
    ): String {
        secretFrom(listTitles())?.let { return it }
        val mine = newSecret()
        create(titleFor(mine))
        return secretFrom(listTitles()) ?: mine
    }

    /** [resolveSecret] against the signed-in account's own library. */
    suspend fun ensureSecret(): Result<String> = runCatching {
        resolveSecret(
            listTitles = { YtMusicRepository.userPlaylists().getOrThrow().map { it.title } },
            create = { title -> YtMusicRepository.createPlaylist(title, PlaylistPrivacy.PRIVATE).getOrThrow() },
        )
    }
}

@kotlinx.serialization.Serializable
data class DevicesJoinRequest(
    val userId: String,
    val deviceId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val groupId: String,
)
