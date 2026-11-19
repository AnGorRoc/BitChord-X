package com.music.bitchord.data.presence

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.telemetry.TelemetryReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Counts this phone toward the live open-app figure while BitChord is on
 * screen and wires opt-in error telemetry to the configured server.
 */
object AndroidPresence {
    private const val PREFS = "presence"
    private const val KEY_INSTALL_ID = "install_id"

    /** Call once from [Application.onCreate], on the main thread. */
    fun install(app: Application) {
        val id = installId(app)
        val base = BuildConfig.LISTEN_TOGETHER_SERVER.trim().trimEnd('/')
            .ifEmpty { "https://api.bitchord.kushagrasingh.in" }
        val appVersion = BuildConfig.VERSION_NAME

        val presence = Presence(
            platform = PresencePlatform.Android,
            installId = id,
            endpoint = "$base/api/presence",
            appVersion = appVersion,
            levelProvider = { AppSettings.telemetryLevel.value },
            useWebSocket = true,
        )

        val deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .trim()
        val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

        TelemetryReporter.configure(
            serverBaseUrl = base,
            installId = id,
            platform = PresencePlatform.Android,
            appVersion = appVersion,
            deviceModel = deviceModel,
            osVersion = osVersion,
            abi = abi,
            levelProvider = { AppSettings.telemetryLevel.value },
        )
        TelemetryReporter.installUncaughtHandler()

        var isForeground = false
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isForeground = true
                presence.setOpen(true)
            }
            override fun onStop(owner: LifecycleOwner) {
                isForeground = false
                presence.setOpen(false)
            }
        })
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            AppSettings.telemetryLevel.drop(1).collect { level ->
                presence.setOpen(level.sends && isForeground)
            }
        }
    }

    /**
     * Stable app-scoped UUID derived from ANDROID_ID (hashed so the raw device
     * ID never leaves the phone). On Android 8+, ANDROID_ID is scoped per
     * app-signing-key + user, so it survives uninstall/reinstall and updates
     * without requiring permissions or exposing cross-app tracking.
     */
    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim().orEmpty()
        if (androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
            val derived = UUID.nameUUIDFromBytes("bitchord-x:$androidId".toByteArray(Charsets.UTF_8)).toString()
            if (prefs.getString(KEY_INSTALL_ID, null) != derived) {
                prefs.edit().putString(KEY_INSTALL_ID, derived).apply()
            }
            return derived
        }
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }
}
