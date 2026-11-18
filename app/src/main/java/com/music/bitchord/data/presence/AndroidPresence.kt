package com.music.bitchord.data.presence

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.telemetry.TelemetryReporter
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
            enabledProvider = { AppSettings.telemetryEnabled.value },
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
            enabledProvider = { AppSettings.telemetryEnabled.value },
        )
        TelemetryReporter.installUncaughtHandler()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = presence.setOpen(true)
            override fun onStop(owner: LifecycleOwner) = presence.setOpen(false)
        })
    }

    /** Random and local to this install: not the device, and not the account. */
    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }
}
