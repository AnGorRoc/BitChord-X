package com.music.bitchord.desktop

import com.music.bitchord.data.listentogether.PartyServers
import com.music.bitchord.data.presence.Presence
import com.music.bitchord.data.presence.PresencePlatform
import com.music.bitchord.data.telemetry.TelemetryLevel
import com.music.bitchord.data.telemetry.TelemetryReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
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
    private const val KEY_TELEMETRY = "telemetry_enabled"
    private const val KEY_TELEMETRY_LEVEL = "telemetry_level"
    private const val KEY_NOTICE_SEEN = "telemetry_notice_seen"
    private val preferences = Preferences.userRoot().node("com.music.bitchord.desktop")

    /** How much anonymous telemetry is sent: full by default, minimal or off by choice. */
    val telemetryLevel = MutableStateFlow(
        preferences.get(KEY_TELEMETRY_LEVEL, null)?.let(TelemetryLevel::fromWire)
            // Before there were levels there was one switch.
            ?: if (preferences.getBoolean(KEY_TELEMETRY, true)) TelemetryLevel.FULL else TelemetryLevel.OFF,
    )

    /** The telemetry dialog on screen, if any. */
    val prompt = MutableStateFlow<DesktopTelemetryPrompt?>(null)

    private val noticeSeen = MutableStateFlow(preferences.getBoolean(KEY_NOTICE_SEEN, false))

    fun setTelemetryLevel(value: TelemetryLevel) {
        telemetryLevel.value = value
        preferences.put(KEY_TELEMETRY_LEVEL, value.wire)
        runCatching { preferences.flush() }
    }

    fun openPicker() {
        prompt.value = DesktopTelemetryPrompt.Picker
    }

    fun markNoticeSeen() {
        noticeSeen.value = true
        preferences.putBoolean(KEY_NOTICE_SEEN, true)
        runCatching { preferences.flush() }
    }

    /** Shows the notice once, to anyone who has not seen it yet. */
    fun showNoticeIfNew() {
        // Seen once shown: closing the window with it open must not bring it back.
        if (!noticeSeen.value && prompt.value == null) {
            markNoticeSeen()
            prompt.value = DesktopTelemetryPrompt.Notice
        }
    }

    fun install() {
        val id = installId()
        val base = System.getProperty("bitchord.listentogether.server")
            .orEmpty()
            .trim()
            .trimEnd('/')
            .ifEmpty { PartyServers.ORIGINAL_OFFICIAL }
        val appVersion = System.getProperty("jpackage.app-version")
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("bitchord.version")?.takeIf { it.isNotBlank() }
            ?: "desktop"
        val packageFormat = DesktopPackageFormat.current
        val presence = Presence(
            platform = PresencePlatform.Pc,
            installId = id,
            endpoint = "$base/api/presence",
            appVersion = appVersion,
            levelProvider = { telemetryLevel.value },
            useWebSocket = true,
            packageFormat = packageFormat,
        )
        TelemetryReporter.configure(
            serverBaseUrl = base,
            installId = id,
            platform = PresencePlatform.Pc,
            appVersion = appVersion,
            deviceModel = System.getProperty("os.name").orEmpty(),
            osVersion = System.getProperty("os.version").orEmpty(),
            abi = System.getProperty("os.arch").orEmpty(),
            levelProvider = { telemetryLevel.value },
            packageFormat = packageFormat,
        )
        TelemetryReporter.installUncaughtHandler()
        CoroutineScope(Dispatchers.Default).launch {
            DesktopWindowVisibility.visible.collect(presence::setOpen)
        }
        CoroutineScope(Dispatchers.Default).launch {
            // Turning it off in Settings takes the install off the live count straight away.
            telemetryLevel.drop(1).collect { presence.setOpen(it.sends && DesktopWindowVisibility.visible.value) }
        }
        // Covers both the window's close and the tray's Quit (exitProcess), so the
        // count drops now rather than when the server's window runs out.
        Runtime.getRuntime().addShutdownHook(Thread(presence::closeBlocking, "bitchord-presence-close"))
    }

    /** Random and local to this install: not the machine, and not the account. */
    internal fun installId(): String {
        preferences.get(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        preferences.put(KEY_INSTALL_ID, id)
        runCatching { preferences.flush() }
        return id
    }
}
