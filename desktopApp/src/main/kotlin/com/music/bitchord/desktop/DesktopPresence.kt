package com.music.bitchord.desktop

import com.music.bitchord.data.listentogether.PartyServers
import com.music.bitchord.data.presence.Presence
import com.music.bitchord.data.presence.PresencePlatform
import com.music.bitchord.data.telemetry.TelemetryReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.prefs.Preferences

/**
 * Counts this PC toward the live open-app figure while the window is showing,
 * minimized included. Closed to the tray, it stops counting, the same as a
 * phone with BitChord in the background.
 */
internal object DesktopPresence {
    private const val KEY_INSTALL_ID = "presence_install_id"
    private val preferences = Preferences.userRoot().node("com.music.bitchord.desktop")

    fun install() {
        val id = installId()
        val base = System.getProperty("bitchord.listentogether.server")
            .orEmpty()
            .trim()
            .trimEnd('/')
            .ifEmpty { PartyServers.ORIGINAL_OFFICIAL }
        val appVersion = System.getProperty("jpackage.app-version")
            ?.takeIf { it.isNotBlank() }
            ?: "desktop"
        val presence = Presence(
            platform = PresencePlatform.Pc,
            installId = id,
            endpoint = "$base/api/presence",
            appVersion = appVersion,
        )
        TelemetryReporter.configure(
            serverBaseUrl = base,
            installId = id,
            platform = PresencePlatform.Pc,
            appVersion = appVersion,
            deviceModel = System.getProperty("os.name").orEmpty(),
            osVersion = System.getProperty("os.version").orEmpty(),
            abi = System.getProperty("os.arch").orEmpty(),
        )
        TelemetryReporter.installUncaughtHandler()
        CoroutineScope(Dispatchers.Default).launch {
            DesktopWindowVisibility.visible.collect(presence::setOpen)
        }
        // Covers both the window's close and the tray's Quit (exitProcess), so the
        // count drops now rather than when the server's window runs out.
        Runtime.getRuntime().addShutdownHook(Thread(presence::closeBlocking, "bitchord-presence-close"))
    }

    /** Random and local to this install: not the machine, and not the account. */
    private fun installId(): String {
        preferences.get(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        preferences.put(KEY_INSTALL_ID, id)
        runCatching { preferences.flush() }
        return id
    }
}
