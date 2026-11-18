package com.music.bitchord.data.telemetry

import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.Http
import com.music.bitchord.data.presence.PresencePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Sends opt-in, privacy-preserving error reports to the BitChord-X server
 * (`POST /api/telemetry/error`) when telemetry is enabled in settings.
 *
 * Only technical error metadata (error type, sanitized message/stacktrace, app
 * version, and basic device model/OS) plus the random per-install UUID are sent.
 * Accounts, user names, IP addresses, and listening history are never included.
 */
object TelemetryReporter {
    private const val TAG = "TelemetryReporter"
    private const val DEDUPE_WINDOW_MS = 5 * 60 * 1000L
    private const val MAX_MESSAGE_CHARS = 1000
    private const val MAX_STACK_CHARS = 8000
    private val JSON = "application/json".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentErrors = ConcurrentHashMap<String, Long>()

    @Volatile private var config: Config? = null
    @Volatile private var uncaughtInstalled = false

    private data class Config(
        val endpoint: String,
        val installId: String,
        val platform: PresencePlatform,
        val appVersion: String,
        val deviceModel: String,
        val osVersion: String,
        val abi: String,
        val enabledProvider: () -> Boolean,
    )

    fun configure(
        serverBaseUrl: String,
        installId: String,
        platform: PresencePlatform,
        appVersion: String,
        deviceModel: String = "",
        osVersion: String = "",
        abi: String = "",
        enabledProvider: () -> Boolean = { true },
    ) {
        val base = serverBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return
        config = Config(
            endpoint = "$base/api/telemetry/error",
            installId = installId,
            platform = platform,
            appVersion = appVersion,
            deviceModel = deviceModel,
            osVersion = osVersion,
            abi = abi,
            enabledProvider = enabledProvider,
        )
    }

    /**
     * Wraps the JVM default uncaught exception handler so fatal crashes are
     * reported best-effort before delegating to the original handler.
     */
    fun installUncaughtHandler() {
        if (uncaughtInstalled) return
        synchronized(this) {
            if (uncaughtInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val msg = throwable.message?.takeIf { it.isNotBlank() }
                        ?: throwable::class.java.simpleName
                        ?: "UncaughtException"
                    reportError(
                        errorType = "uncaught_crash",
                        message = "[${thread.name}] $msg",
                        throwable = throwable,
                        blocking = true,
                    )
                }
                previous?.uncaughtException(thread, throwable)
            }
            uncaughtInstalled = true
        }
    }

    fun reportError(
        errorType: String,
        message: String,
        throwable: Throwable? = null,
        blocking: Boolean = false,
    ) {
        val cfg = config ?: return
        if (!cfg.enabledProvider()) return
        val cleanType = errorType.trim().ifEmpty { "runtime_error" }.take(64)
        val cleanMessage = message.trim().ifEmpty { throwable?.javaClass?.simpleName ?: "Unknown error" }.take(MAX_MESSAGE_CHARS)

        val dedupeKey = "$cleanType|$cleanMessage"
        val now = System.currentTimeMillis()
        val prev = recentErrors[dedupeKey]
        if (!blocking && prev != null && now - prev < DEDUPE_WINDOW_MS) {
            return
        }
        recentErrors[dedupeKey] = now
        if (recentErrors.size > 200) {
            recentErrors.entries.removeIf { now - it.value >= DEDUPE_WINDOW_MS }
        }

        val stack = throwable?.let { formatStacktrace(it) }.orEmpty()
        if (blocking) {
            sendSync(cfg, cleanType, cleanMessage, stack, timeoutMs = 1500L)
        } else {
            scope.launch {
                sendSync(cfg, cleanType, cleanMessage, stack, timeoutMs = 8000L)
            }
        }
    }

    private fun sendSync(
        cfg: Config,
        errorType: String,
        errorMessage: String,
        stacktrace: String,
        timeoutMs: Long,
    ) {
        val payload = buildJsonObject {
            put("id", cfg.installId)
            put("platform", cfg.platform.wire)
            put("appVersion", cfg.appVersion)
            put("errorType", errorType)
            put("errorMessage", errorMessage)
            if (stacktrace.isNotEmpty()) put("stacktrace", stacktrace)
            if (cfg.deviceModel.isNotBlank()) put("deviceModel", cfg.deviceModel)
            if (cfg.osVersion.isNotBlank()) put("osVersion", cfg.osVersion)
            if (cfg.abi.isNotBlank()) put("abi", cfg.abi)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url(cfg.endpoint)
            .post(payload)
            .build()

        runCatching {
            val call = Http.client.newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            call.execute().use { res ->
                if (!res.isSuccessful) {
                    DebugLog.d(TAG, "telemetry error report rejected: HTTP ${res.code}")
                }
            }
        }.onFailure { e ->
            DebugLog.d(TAG, "telemetry error report failed: ${e.message}")
        }
    }

    private fun formatStacktrace(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
        return sw.toString().take(MAX_STACK_CHARS)
    }
}
