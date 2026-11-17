package com.music.bitchord.data.listentogether

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.PlaylistPrivacy
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

    /** Whether the given title belongs to an internal device group playlist used by the account system. */
    fun isDeviceGroupPlaylist(title: String?): Boolean {
        if (title == null) return false
        val trimmed = title.trim()
        return trimmed.startsWith("BitChord devices", ignoreCase = true)
    }

    /** The token the library already holds, or null. The smallest if there are several. */
    fun secretFrom(titles: List<String>): String? = titles.asSequence()
        .map(String::trim)
        .filter { it.startsWith(TITLE_PREFIX) }
        .map { it.removePrefix(TITLE_PREFIX).trim() }
        .filter { TOKEN.matches(it) }
        .minOrNull()

    /**
     * The same key for every server and every purpose, which is exactly the problem: a server that
     * is shown it can act as this person on any other. Kept only so old callers compile; use
     * [proofFor].
     */
    @Deprecated("Sent to every server alike. Use proofFor.")
    fun groupId(secret: String): String = MessageDigest.getInstance("SHA-256")
        .digest("bitchord-devices-v1:$secret".toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** What a proof is for. The two are never interchangeable: one cannot stand in for the other. */
    enum class ProofUse(internal val label: String) {
        /** The account channel: seeing the person's devices and commanding them. */
        ACCOUNT("account"),

        /** Being the same person in a party. */
        PARTY("party"),
    }

    /**
     * The proof a device shows one server, for one purpose.
     *
     * Derived from the secret with a message made of the purpose and the server's own address, so
     * a proof that reaches a server (a friend's, or one named by an invite link) is worthless on
     * any other server and for any other purpose: it cannot open the person's devices elsewhere
     * nor stand in for them in a party. The secret itself never leaves the device. Both platforms
     * must agree on the address, so only the host and port count, in lower case.
     */
    fun proofFor(secret: String, use: ProofUse, serverBase: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val message = "bitchord-proof-v2:${use.label}:${originOf(serverBase)}"
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** `https://Party.Example.com/x` and `party.example.com` name the same server. */
    internal fun originOf(serverBase: String): String =
        serverBase.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("wss://").removePrefix("ws://")
            .substringBefore('/').substringBefore('?').trimEnd('.')

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
            listTitles = { YtMusicRepository.userPlaylists(includeDeviceGroup = true).getOrThrow().map { it.title } },
            create = { title -> YtMusicRepository.createPlaylist(title, PlaylistPrivacy.PRIVATE).getOrThrow() },
        )
    }
}

