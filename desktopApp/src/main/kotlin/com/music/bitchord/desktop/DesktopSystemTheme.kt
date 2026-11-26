package com.music.bitchord.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.util.concurrent.TimeUnit

/**
 * Whether the operating system is set to dark mode, for the theme setting's
 * "System". Read again every few seconds while the app is open, so switching
 * Windows (or macOS) between light and dark carries over without a restart.
 */
internal object DesktopSystemTheme {

    /** How often the system's mode is read again. */
    const val POLL_MS = 2_000L

    private const val PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

    /** True when the system asks apps to be dark; dark too where it cannot be told. */
    fun isDark(): Boolean = when {
        DesktopPlatform.isWindows -> windowsIsDark()
        DesktopPlatform.isMac -> macIsDark()
        else -> true
    }

    private fun windowsIsDark(): Boolean = runCatching {
        // 0 means apps are dark; the value is missing on systems that predate the choice.
        if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "AppsUseLightTheme")) return true
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "AppsUseLightTheme") == 0
    }.getOrDefault(true)

    private fun macIsDark(): Boolean = runCatching {
        // Only set, to "Dark", while dark mode is on.
        val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(2, TimeUnit.SECONDS)
        out.trim().equals("Dark", ignoreCase = true)
    }.getOrDefault(true)
}
